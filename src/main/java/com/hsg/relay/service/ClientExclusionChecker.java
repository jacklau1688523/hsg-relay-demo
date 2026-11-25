package com.hsg.relay.service;

import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.ClientExclusionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 客户排除查询：合作方详情「客户排除管理」里当前有效的「客户 + 合作方」记录。
 * 既是 C1 执行器的系统必检（CLIENT_EXCLUDED），也是函数库 clientExcluded 的后端实现。
 */
@Service
public class ClientExclusionChecker implements BusinessFunction {
    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("providerCode", "STRING", "CANDIDATE", true, null),
            new Parameter("clientId", "STRING", "TRANSACTION", true, null),
            new Parameter("decisionTime", "DATE", "CONTEXT", false, "context.decisionTime"));

    private final ClientExclusionRepository exclusions;

    public ClientExclusionChecker(ClientExclusionRepository exclusions) {
        this.exclusions = exclusions;
    }

    public boolean clientExcluded(String providerCode, String clientId, LocalDateTime now) {
        return valid(providerCode, clientId, now) != null;
    }

    /**
     * 有效记录：已到生效时间，且未到失效时间。已过期或已解除的记录 effectiveTo 不晚于决策时间，不算有效。
     * 合作方、客户、决策时间全部来自入参；查询失败时异常向上抛出，不会被当成「未排除」。
     */
    private ClientExclusion valid(String providerCode, String clientId, LocalDateTime now) {
        if (providerCode == null || clientId == null || now == null)
            throw new IllegalArgumentException("clientExcluded 需要合作方、客户和决策时间");
        return exclusions.findAll().stream()
                .filter(e -> providerCode.equals(e.providerCode) && clientId.equals(e.clientId)
                        && e.effectiveFrom != null && !now.isBefore(e.effectiveFrom) && (e.effectiveTo == null || now.isBefore(e.effectiveTo)))
                .min(Comparator.comparing(e -> e.id))
                .orElse(null);
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
        Object decisionTime = arguments.get("decisionTime");
        ClientExclusion hit = valid((String) arguments.get("providerCode"), (String) arguments.get("clientId"),
                decisionTime instanceof LocalDateTime time ? time : context.decisionTime());
        return hit == null ? Outcome.of(false) : new Outcome(true, hit.id.toString());
    }
}
