package com.hsg.relay.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.domain.RoutingScoreWeight;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.repo.RoutingScoreWeightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.hsg.relay.domain.RoutingScoreWeight.*;

/**
 * 综合评分权重配置（全局）的管理：草稿保存 / 删除、发布、停用、查询、版本与审计。
 * <ul>
 *   <li>权重校验：W1–W4 为 0–100 的整数且合计 100；页面、保存、发布三处都校验，数据库另有 CHECK 约束。</li>
 *   <li>版本：修改已发布配置不覆盖历史，而是为同一「交易类型 + 客户层级」生成下一版本号的草稿，发布后成为新版本；
 *       每组同一时间最多一个草稿。</li>
 *   <li>发布：可指定生效时间（留空 = 发布当时）。当前未结束的版本在新的生效时间结束；该组已有发布过的版本时，生效时间不能早于当前时间
 *       （否则已发生决策回放时会换成新权重），不能与任何已发布版本的有效时间段重叠，已有待生效版本时须先停用它——否则一律拒绝，
 *       不会出现两条已发布配置同时生效。全新的「交易类型 + 客户层级」没有历史版本，可以追溯生效。</li>
 *   <li>停用：把版本的有效时间段截止到当前时间（待生效版本停用后从未生效，并在它的生效时间结束的前一版本恢复为长期有效），历史版本不删除。
 *       OTHER + ALL 是兜底配置，不能停用。</li>
 *   <li>并发：保存 / 发布 / 停用都带行版本号，被他人改过时拒绝。</li>
 * </ul>
 * 发布、停用在事务提交后刷新 {@link ScoreWeightMatcher} 的内存快照（事务回滚时快照不变）。
 * 项目没有登录与角色体系，操作人由页面填写并记入审计。
 */
@Service
public class RoutingScoreWeightService {
    public static final String AUDIT_TYPE = "SCORE_WEIGHT";
    /** 客户层级在字段库里的编码（C8「客户分层」）；层级的可选值从它的字典取值读取。 */
    public static final String TIER_FIELD = "clientSegment";
    static final String SEED_OPERATOR = "system";
    /** 系统默认权重自始生效（与评分规则默认版本同一口径）。 */
    public static final LocalDateTime SEED_EFFECTIVE_FROM = LocalDateTime.of(2000, 1, 1, 0, 0);

    /** 一条默认权重。 */
    public record DefaultWeight(String transactionType, int w1Cost, int w2Speed, int w3Preference, int w4Commercial) {
    }

    /** 系统首次初始化写入的 7 条默认权重（客户层级全部为 ALL）。 */
    public static final List<DefaultWeight> DEFAULTS = List.of(
            new DefaultWeight("TRADE", 40, 30, 20, 10),
            new DefaultWeight("TREASURY", 25, 50, 20, 5),
            new DefaultWeight("INTRAGROUP", 30, 30, 35, 5),
            new DefaultWeight("PAYROLL", 55, 25, 10, 10),
            new DefaultWeight("FEE", 60, 20, 10, 10),
            new DefaultWeight("REFUND", 60, 20, 10, 10),
            new DefaultWeight(FALLBACK_TYPE, 50, 30, 10, 10));

    /** 保存草稿：id 为空是新增，否则更新已有草稿；expectedVersion 是读取时的行版本号。 */
    public record DraftRequest(Long id, Long expectedVersion, String transactionType, String clientTier,
                               Integer w1Cost, Integer w2Speed, Integer w3Preference, Integer w4Commercial, String note, String operator) {
    }

    /** 发布 / 停用请求。effectiveFrom 只用于发布，留空 = 发布当时生效。 */
    public record PublishRequest(Long expectedVersion, String operator, LocalDateTime effectiveFrom, String note) {
    }

    public record View(Long id, String transactionType, String clientTier, int w1Cost, int w2Speed, int w3Preference, int w4Commercial,
                       int weightSum, String status, boolean enabled, int versionNo, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
                       boolean effectiveNow, LocalDateTime publishedAt, String publishedBy, LocalDateTime createdAt, String createdBy,
                       LocalDateTime updatedAt, String updatedBy, long rowVersion, String note) {
    }

    /** 页面下拉与提示用的选项：交易类型、客户层级字典、兜底配置是否生效。 */
    public record Options(List<String> transactionTypes, List<String> clientTiers, String tierField, boolean fallbackEffective) {
    }

    public record AuditView(long id, String operation, String operator, LocalDateTime operatedAt, String transactionType,
                            String clientTier, Integer versionNo) {
    }

    private final RoutingScoreWeightRepository repo;
    private final FieldDefinitionRepository fields;
    private final RuleAuditRepository audit;
    private final ScoreWeightMatcher matcher;
    private final ObjectMapper json;
    private final Clock clock;

    public RoutingScoreWeightService(RoutingScoreWeightRepository repo, FieldDefinitionRepository fields, RuleAuditRepository audit,
                                     ScoreWeightMatcher matcher, ObjectMapper json, Clock clock) {
        this.repo = repo;
        this.fields = fields;
        this.audit = audit;
        this.matcher = matcher;
        this.json = json;
        this.clock = clock;
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public List<View> list(String transactionType, String clientTier, String status) {
        return repo.findAllByOrderByTransactionTypeAscClientTierAscVersionNoDesc().stream()
                .filter(r -> transactionType == null || transactionType.isBlank() || r.transactionType.equals(transactionType))
                .filter(r -> clientTier == null || clientTier.isBlank() || r.clientTier.equals(clientTier))
                .filter(r -> status == null || status.isBlank() || r.status.equals(status))
                .map(this::view).toList();
    }

    /** 配置版本记录：已发布和已停用的版本（不含草稿），按时间倒序。 */
    public List<View> versions() {
        return repo.findAllByOrderByTransactionTypeAscClientTierAscVersionNoDesc().stream()
                .filter(r -> !DRAFT.equals(r.status))
                .sorted(Comparator.comparing((RoutingScoreWeight r) -> r.effectiveFrom).reversed()
                        .thenComparing(r -> r.transactionType).thenComparing(r -> r.clientTier).thenComparingInt(r -> -r.versionNo))
                .map(this::view).toList();
    }

    public Options options() {
        TreeSet<String> tiers = new TreeSet<>();
        tiers.add(ALL_TIERS);
        // 客户层级字典：字段库 clientSegment 的可选值；已配置过的层级也算在内
        fields.findById(TIER_FIELD).ifPresent(f -> {
            if (f.valueOptions != null)
                Arrays.stream(f.valueOptions.split("[,，]")).map(String::trim).filter(s -> !s.isEmpty()).forEach(tiers::add);
        });
        repo.findAll().forEach(r -> tiers.add(r.clientTier));
        List<String> ordered = new ArrayList<>();
        ordered.add(ALL_TIERS);
        tiers.stream().filter(t -> !ALL_TIERS.equals(t)).sorted().forEach(ordered::add);
        return new Options(RoutingService.TRANSACTION_TYPES.stream().sorted().toList(), List.copyOf(ordered), TIER_FIELD, matcher.fallbackEffective());
    }

    @Transactional
    public View saveDraft(DraftRequest request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        String who = requireText(request.operator(), "操作人不能为空");
        int[] w = checkWeights(request.w1Cost(), request.w2Speed(), request.w3Preference(), request.w4Commercial());
        String note = optionalText(request.note(), "说明最多500位");
        RoutingScoreWeight row;
        String before = null;
        if (request.id() == null) {
            String type = requireType(request.transactionType()), tier = requireTier(request.clientTier());
            repo.findByTransactionTypeAndClientTierAndStatus(type, tier, DRAFT).ifPresent(d -> {
                throw new IllegalArgumentException(type + " + " + tier + " 已有待发布的草稿（版本 v" + d.versionNo + "），请先编辑该草稿");
            });
            row = new RoutingScoreWeight(type, tier, nextVersion(type, tier), w[0], w[1], w[2], w[3]);
            row.createdAt = now();
            row.createdBy = who;
        } else {
            row = repo.findById(request.id()).orElseThrow(() -> new IllegalArgumentException("权重配置不存在"));
            if (!DRAFT.equals(row.status)) throw new IllegalArgumentException("已发布的版本不能修改：请为 " + row.transactionType + " + " + row.clientTier + " 新建草稿，发布后成为新版本");
            checkStale(row, request.expectedVersion());
            if ((request.transactionType() != null && !request.transactionType().isBlank() && !request.transactionType().equals(row.transactionType))
                    || (request.clientTier() != null && !request.clientTier().isBlank() && !request.clientTier().equals(row.clientTier)))
                throw new IllegalArgumentException("草稿的交易类型和客户层级不能修改：请删除草稿后重新新增");
            before = write(view(row));
            row.w1Cost = w[0];
            row.w2Speed = w[1];
            row.w3Preference = w[2];
            row.w4Commercial = w[3];
        }
        row.note = note;
        row.updatedAt = now();
        row.updatedBy = who;
        RoutingScoreWeight saved = repo.saveAndFlush(row);
        audit.save(new RuleAudit(AUDIT_TYPE, String.valueOf(saved.id), request.id() == null ? "CREATE_DRAFT" : "UPDATE_DRAFT", who, before, write(view(saved))));
        return view(saved);
    }

    @Transactional
    public void deleteDraft(long id, String operator) {
        String who = requireText(operator, "操作人不能为空");
        RoutingScoreWeight row = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("权重配置不存在"));
        if (!DRAFT.equals(row.status)) throw new IllegalArgumentException("只有草稿可以删除；已发布的版本是历史记录，不能删除（可停用）");
        audit.save(new RuleAudit(AUDIT_TYPE, String.valueOf(id), "DELETE_DRAFT", who, write(view(row)), null));
        repo.delete(row);
    }

    @Transactional
    public View publish(long id, PublishRequest request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        String who = requireText(request.operator(), "发布必须提供操作人");
        String note = optionalText(request.note(), "说明最多500位");
        RoutingScoreWeight row = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("权重配置不存在"));
        if (!DRAFT.equals(row.status)) throw new IllegalArgumentException("只有草稿可以发布（当前状态 " + row.status + "）");
        checkStale(row, request.expectedVersion());
        checkWeights(row.w1Cost, row.w2Speed, row.w3Preference, row.w4Commercial);
        LocalDateTime now = now(), from = request.effectiveFrom() == null ? now : request.effectiveFrom();
        String key = row.transactionType + " + " + row.clientTier;
        List<RoutingScoreWeight> history = repo.findByTransactionTypeAndClientTierOrderByVersionNoDesc(row.transactionType, row.clientTier);
        RoutingScoreWeight pending = history.stream().filter(p -> !DRAFT.equals(p.status) && p.effectiveTo == null && p.effectiveFrom.isAfter(now))
                .findFirst().orElse(null);
        if (pending != null)
            throw new IllegalArgumentException(key + " 已有待生效版本 v" + pending.versionNo + "（生效时间 " + pending.effectiveFrom + "），请先停用它再发布新版本");
        // 已有发布过的版本时，新版本不能早于当前时间生效（否则已发生的决策回放时会换成新权重）；
        // 全新的「交易类型 + 客户层级」没有任何历史版本，允许追溯生效
        if (from.isBefore(now) && history.stream().anyMatch(p -> !DRAFT.equals(p.status)))
            throw new IllegalArgumentException("生效时间不能早于当前时间：" + key + " 已有发布过的版本，新版本回溯生效会改变已发生决策所用的权重（全新的交易类型 + 客户层级才允许追溯生效）");
        // 与同一组的每个已发布版本都不能重叠；当前生效的版本在新的生效时间结束
        for (RoutingScoreWeight p : history) {
            if (DRAFT.equals(p.status)) continue;
            if (p.effectiveTo == null) {
                // 内容不变，只记截止时间（单独写一条审计，便于版本记录里看到交接）
                String old = write(view(p));
                p.effectiveTo = from;
                audit.save(new RuleAudit(AUDIT_TYPE, String.valueOf(p.id), "SUPERSEDED", who, old, write(view(p))));
            } else if (p.effectiveTo.isAfter(p.effectiveFrom) && p.effectiveTo.isAfter(from)) {
                // 结束时间 = 生效时间的是「从未生效」的空区间（待生效后被停用），不与任何版本重叠
                throw new IllegalArgumentException("生效时间与已发布版本 v" + p.versionNo + "（" + p.effectiveFrom + " ~ " + p.effectiveTo + "）的有效时间段重叠");
            }
        }
        String before = write(view(row));
        row.status = PUBLISHED;
        row.effectiveFrom = from;
        row.publishedAt = now;
        row.publishedBy = who;
        row.updatedAt = now;
        row.updatedBy = who;
        row.note = note == null ? row.note : note;
        RoutingScoreWeight saved = repo.saveAndFlush(row);
        audit.save(new RuleAudit(AUDIT_TYPE, String.valueOf(id), "PUBLISH", who, before, write(view(saved))));
        refreshAfterCommit();
        return view(saved);
    }

    @Transactional
    public View disable(long id, PublishRequest request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        String who = requireText(request.operator(), "停用必须提供操作人");
        RoutingScoreWeight row = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("权重配置不存在"));
        if (!PUBLISHED.equals(row.status)) throw new IllegalArgumentException("只有已发布的版本可以停用（当前状态 " + row.status + "）");
        if (row.effectiveTo != null) throw new IllegalArgumentException("版本 v" + row.versionNo + " 已结束，不再是生效中的版本，无需停用");
        if (FALLBACK_TYPE.equals(row.transactionType) && ALL_TIERS.equals(row.clientTier))
            throw new IllegalArgumentException("OTHER + ALL 是兜底权重配置，必须保留，不能停用");
        checkStale(row, request.expectedVersion());
        LocalDateTime now = now();
        String before = write(view(row));
        boolean scheduled = now.isBefore(row.effectiveFrom);
        if (scheduled) reopenPredecessor(row, who);
        // 待生效版本（生效时间还没到）停用后从未生效：结束时间 = 生效时间
        row.effectiveTo = scheduled ? row.effectiveFrom : now;
        row.status = DISABLED;
        row.updatedAt = now;
        row.updatedBy = who;
        RoutingScoreWeight saved = repo.saveAndFlush(row);
        audit.save(new RuleAudit(AUDIT_TYPE, String.valueOf(id), "DISABLE", who, before, write(view(saved))));
        refreshAfterCommit();
        return view(saved);
    }

    /**
     * 默认权重初始化（幂等）：某个交易类型在 ALL 层级下一条记录都没有时才写入 v1。
     * 记录从不删除，所以用户修改（发布新版本 / 停用）后不会再次写入，也不会覆盖已发布的用户配置。
     */
    @Transactional
    public int seedDefaults() {
        int created = 0;
        for (DefaultWeight d : DEFAULTS) {
            if (repo.existsByTransactionTypeAndClientTier(d.transactionType(), ALL_TIERS)) continue;
            RoutingScoreWeight row = new RoutingScoreWeight(d.transactionType(), ALL_TIERS, 1, d.w1Cost(), d.w2Speed(), d.w3Preference(), d.w4Commercial());
            row.status = PUBLISHED;
            row.effectiveFrom = SEED_EFFECTIVE_FROM;
            LocalDateTime now = now();
            row.createdAt = now;
            row.createdBy = SEED_OPERATOR;
            row.updatedAt = now;
            row.updatedBy = SEED_OPERATOR;
            row.publishedAt = now;
            row.publishedBy = SEED_OPERATOR;
            row.note = "系统初始化：默认权重";
            RoutingScoreWeight saved = repo.saveAndFlush(row);
            audit.save(new RuleAudit(AUDIT_TYPE, String.valueOf(saved.id), "SEED", SEED_OPERATOR, null, write(view(saved))));
            created++;
        }
        if (created > 0) refreshAfterCommit();
        return created;
    }

    /** 权重匹配模拟（只读）：P1 / P2 / P3 的逐步判断与实际命中的配置。 */
    public ScoreWeightMatcher.Trace match(String transactionType, String clientTier, LocalDateTime at) {
        return matcher.trace(transactionType, clientTier, at);
    }

    public List<AuditView> audits() {
        return audit.findTop500ByObjectTypeOrderByIdDesc(AUDIT_TYPE).stream().map(a -> {
            String type = null, tier = null;
            Integer version = null;
            try {
                var node = json.readTree(a.afterJson != null ? a.afterJson : a.beforeJson);
                type = node.path("transactionType").asText(null);
                tier = node.path("clientTier").asText(null);
                version = node.path("versionNo").isNumber() ? node.path("versionNo").asInt() : null;
            } catch (Exception ignored) {
                // 旧审计内容不可读时只展示基本信息
            }
            return new AuditView(a.id, a.operation, a.operatorName, a.operatedAt, type, tier, version);
        }).toList();
    }

    /**
     * 停用待生效版本等于撤销这次交接：在它的生效时间结束的那个版本恢复为长期有效。
     * 已发生的决策用的一直是那个版本，所以历史不变；不恢复的话该交易类型从生效时间起会没有任何权重。
     */
    private void reopenPredecessor(RoutingScoreWeight scheduled, String who) {
        LocalDateTime at = scheduled.effectiveFrom.truncatedTo(ChronoUnit.MICROS);
        for (RoutingScoreWeight p : repo.findByTransactionTypeAndClientTierOrderByVersionNoDesc(scheduled.transactionType, scheduled.clientTier)) {
            if (p.id.equals(scheduled.id) || !PUBLISHED.equals(p.status) || p.effectiveTo == null) continue;
            if (!p.effectiveTo.truncatedTo(ChronoUnit.MICROS).equals(at)) continue;
            String old = write(view(p));
            p.effectiveTo = null;
            audit.save(new RuleAudit(AUDIT_TYPE, String.valueOf(p.id), "REOPEN", who, old, write(view(p))));
        }
    }

    private void checkStale(RoutingScoreWeight row, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion != row.version)
            throw new IllegalArgumentException("配置已被他人修改（当前行版本 " + row.version + "），请刷新后重试");
    }

    private int nextVersion(String type, String tier) {
        return repo.findByTransactionTypeAndClientTierOrderByVersionNoDesc(type, tier).stream().mapToInt(r -> r.versionNo).max().orElse(0) + 1;
    }

    /** 权重业务校验：必填、0–100 的整数、合计 100。通过时返回归一化后的四项权重。 */
    static int[] checkWeights(Integer w1, Integer w2, Integer w3, Integer w4) {
        Integer[] values = {w1, w2, w3, w4};
        String[] names = {"W1 成本", "W2 速度", "W3 偏好", "W4 履约"};
        int sum = 0;
        for (int i = 0; i < 4; i++) {
            if (values[i] == null) throw new IllegalArgumentException("权重 " + names[i] + " 未填写：W1–W4 均为必填");
            if (values[i] < 0) throw new IllegalArgumentException("权重不能为负数：" + names[i] + " = " + values[i]);
            if (values[i] > 100) throw new IllegalArgumentException("权重不能大于 100：" + names[i] + " = " + values[i]);
            sum += values[i];
        }
        if (sum != 100) throw new IllegalArgumentException("权重合计必须等于 100，当前为 " + sum);
        return new int[]{w1, w2, w3, w4};
    }

    private static String requireType(String value) {
        String type = value == null ? "" : value.trim();
        if (!RoutingService.TRANSACTION_TYPES.contains(type)) throw new IllegalArgumentException("交易类型无效，应为 " + RoutingService.TRANSACTION_TYPES);
        return type;
    }

    private static String requireTier(String value) {
        String tier = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!tier.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) throw new IllegalArgumentException("客户层级编码须为 1–64 位大写字母、数字、_ 或 -");
        return tier;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank() || value.trim().length() > 64) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String optionalText(String value, String message) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > 500) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private View view(RoutingScoreWeight r) {
        LocalDateTime now = now();
        boolean effective = PUBLISHED.equals(r.status) && r.effectiveFrom != null && !r.effectiveFrom.isAfter(now) && (r.effectiveTo == null || now.isBefore(r.effectiveTo));
        return new View(r.id, r.transactionType, r.clientTier, r.w1Cost, r.w2Speed, r.w3Preference, r.w4Commercial, r.weightSum(),
                r.status, effective, r.versionNo, r.effectiveFrom, r.effectiveTo, effective, r.publishedAt, r.publishedBy,
                r.createdAt, r.createdBy, r.updatedAt, r.updatedBy, r.version, r.note);
    }

    private String write(View view) {
        try {
            return json.writeValueAsString(view);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("权重配置序列化失败", ex);
        }
    }

    /** 事务提交后再刷新权重快照：回滚时快照保持不变；没有事务时直接刷新。 */
    private void refreshAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            matcher.refresh();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                matcher.refresh();
            }
        });
    }
}
