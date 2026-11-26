package com.hsg.relay.service;

import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.repo.ClientExclusionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 客户排除查询：合作方详情「客户排除管理」里当前有效的「客户 + 合作方」记录。
 * 是函数库 clientExcluded 的后端实现（C1 通过条件树里的函数节点或函数绑定字段使用它，执行器不单独强制检查），
 * 同时为元数据字段 providerExcludedClientIds 提供同一个 {@link ExclusionSet}（见 ProviderExclusionListResolver）。
 * <p>有效记录：已到生效时间，且未到失效时间。已过期或已解除的记录 effectiveTo 不晚于决策时间，不算有效。
 * 合作方、客户、决策时间全部来自入参；查询失败时异常向上抛出，不会被当成「未排除」。
 */
@Service
public class ClientExclusionChecker implements BusinessFunction {
    /**
     * 合作方有效排除记录不超过这个条数时全量加载到内存（一次查询）；超过则改为成员查询——每次按「合作方 + 客户 + 决策时间」
     * 查一条，不把整份名单加载进内存。两种形态对外的 CONTAINS 语义相同，见 {@link ExclusionSet}。
     * 默认 1000，可用 relay.exclusion.materialize-limit 调整。
     */
    public static final int MATERIALIZE_LIMIT = 1000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("providerCode", "STRING", "CANDIDATE", true, null),
            new Parameter("clientId", "STRING", "TRANSACTION", true, null),
            new Parameter("decisionTime", "DATE", "CONTEXT", false, "context.decisionTime"));

    private final ClientExclusionRepository exclusions;
    private final int materializeLimit;

    public ClientExclusionChecker(ClientExclusionRepository exclusions,
                                  @Value("${relay.exclusion.materialize-limit:" + MATERIALIZE_LIMIT + "}") int materializeLimit) {
        this.exclusions = exclusions;
        this.materializeLimit = Math.max(0, materializeLimit);
    }

    /** 单次查询（不经过请求级缓存）：接口自检等不在规则执行里的调用使用。 */
    public boolean clientExcluded(String providerCode, String clientId, LocalDateTime now) {
        if (providerCode == null || clientId == null || now == null)
            throw new IllegalArgumentException("clientExcluded 需要合作方、客户和决策时间");
        return exclusionSet(providerCode, now, null).lookup(clientId).hit();
    }

    /** 当前候选合作方在决策时间的有效排除客户集合；有执行上下文时在本次求值内只构建一次。 */
    public RuleCollection exclusionSet(RuleExecutionContext context) {
        Object providerCode = context.value("candidate.providerCode");
        LocalDateTime decisionTime = context.decisionTime();
        if (providerCode == null || decisionTime == null)
            throw new IllegalArgumentException("客户排除集合需要候选合作方和决策时间");
        return exclusionSet((String) providerCode, decisionTime, context);
    }

    /**
     * 合作方在决策时间的有效排除客户集合。cacheKey 为「来源 + 合作方 + 决策时间」，存进请求级缓存后，
     * clientExcluded 函数节点、绑定它的字段、元数据字段 providerExcludedClientIds 拿到的是同一份，只查询一次数据库。
     */
    public RuleCollection exclusionSet(String providerCode, LocalDateTime decisionTime, RuleExecutionContext context) {
        if (providerCode == null || providerCode.isBlank() || decisionTime == null)
            throw new IllegalArgumentException("客户排除集合需要合作方和决策时间");
        if (context == null) return new ExclusionSet(exclusions, providerCode, decisionTime, materializeLimit);
        // 集合在 loader 里才构造（构造时才查库）：本次求值里已经加载过就直接复用，不再访问数据库
        return (RuleCollection) context.data(cacheKey(providerCode, decisionTime),
                () -> new ExclusionSet(exclusions, providerCode, decisionTime, materializeLimit));
    }

    /** 排除集合在请求级缓存里的键：来源 + 合作方 + 决策时间（决策时间不同的集合不会串用）。 */
    static String cacheKey(String providerCode, LocalDateTime decisionTime) {
        return "CLIENT_EXCLUSION|" + providerCode + "|" + decisionTime.format(TIME);
    }

    @Override
    public String handlerCode() {
        return "clientExclusionChecker";
    }

    @Override
    public String returnType() {
        return "BOOLEAN";
    }

    @Override
    public String dataSource() {
        return "CLIENT_EXCLUSION";
    }

    @Override
    public List<Parameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        String providerCode = (String) arguments.get("providerCode");
        String clientId = (String) arguments.get("clientId");
        Object decisionTime = arguments.get("decisionTime");
        // 显式传入的决策时间可能与上下文的不同：按传入的时间取集合（缓存键含决策时间，不会与上下文的那份混用）
        RuleCollection set = decisionTime instanceof LocalDateTime time && context != null
                ? exclusionSet(providerCode, time, context) : exclusionSet(context);
        RuleCollection.Lookup lookup = set.lookup(clientId);
        return lookup.hit() ? new Outcome(true, lookup.recordId()) : Outcome.of(false);
    }

    /**
     * 合作方的有效排除客户集合。构造时先数条数：不超过 materializeLimit（默认 {@link #MATERIALIZE_LIMIT}）就一次加载（可枚举），
     * 否则进入成员查询模式——lookup 按「合作方 + 客户 + 决策时间」查询并在本次求值内记住结果，elements() 报错而不是全量加载。
     * 任何一次查询失败、或查回的数据与查询条件不符（结果不完整）都抛异常，不会退化成空集合。
     */
    public static final class ExclusionSet implements RuleCollection {
        private final ClientExclusionRepository exclusions;
        private final String providerCode;
        private final LocalDateTime decisionTime;
        private final String cacheKey;
        private final int materializeLimit;
        private final Map<Object, String> members = new LinkedHashMap<>();
        private final Map<Object, Lookup> lookups = new HashMap<>();
        private boolean materialized;

        ExclusionSet(ClientExclusionRepository exclusions, String providerCode, LocalDateTime decisionTime, int materializeLimit) {
            this.exclusions = exclusions;
            this.providerCode = providerCode;
            this.decisionTime = decisionTime;
            this.materializeLimit = materializeLimit;
            this.cacheKey = ClientExclusionChecker.cacheKey(providerCode, decisionTime);
            long total = exclusions.countValidForProvider(providerCode, decisionTime);
            if (total > materializeLimit) {
                materialized = false;
                return;
            }
            List<ClientExclusion> rows = valid(exclusions.findValidForProvider(providerCode, decisionTime));
            for (ClientExclusion row : rows)
                members.putIfAbsent(row.clientId, row.id.toString());
            materialized = true;
        }

        @Override
        public String source() {
            return "CLIENT_EXCLUSION";
        }

        @Override
        public Lookup lookup(Object element) {
            if (element == null) return Lookup.MISS;
            if (materialized) {
                String recordId = members.get(element);
                return recordId == null ? Lookup.MISS : Lookup.hit(recordId);
            }
            // 成员查询模式：同一客户在本次求值内只查一次
            return lookups.computeIfAbsent(element, client -> {
                List<ClientExclusion> rows = valid(exclusions.findValidForMember(providerCode, String.valueOf(client), decisionTime));
                return rows.isEmpty() ? Lookup.MISS : Lookup.hit(rows.get(0).id.toString());
            });
        }

        @Override
        public boolean materialized() {
            return materialized;
        }

        @Override
        public Set<Object> elements() {
            if (!materialized)
                throw new IllegalStateException("合作方 " + providerCode + " 的有效排除记录超过 " + materializeLimit
                        + " 条，未全量加载：只能做成员查询（collectionContains），不能枚举或求交集");
            return Collections.unmodifiableSet(members.keySet());
        }

        @Override
        public String cacheKey() {
            return cacheKey;
        }

        /** 查回的每一行都必须属于该合作方且在决策时间有效，否则说明结果不完整，按读取失败处理。 */
        private List<ClientExclusion> valid(List<ClientExclusion> rows) {
            if (rows == null)
                throw new IllegalStateException("客户排除记录查询没有返回结果（合作方 " + providerCode + "）");
            for (ClientExclusion row : rows)
                if (row == null || row.id == null || row.clientId == null || !providerCode.equals(row.providerCode)
                        || row.effectiveFrom == null || decisionTime.isBefore(row.effectiveFrom)
                        || (row.effectiveTo != null && !decisionTime.isBefore(row.effectiveTo)))
                    throw new IllegalStateException("客户排除记录查询结果不完整或与查询条件不符（合作方 " + providerCode + "）");
            return rows;
        }
    }
}
