package com.hsg.relay.routing;

import com.hsg.relay.function.field.RuleList;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 一次路由决策的执行上下文（决策级，一次路由新建一个，不跨交易复用，没有任何全局 / 静态缓存）。
 * <p>承载 C7 跨候选聚合需要的全部状态：
 * <ul>
 *   <li>决策身份：决策编号、路由配置版本、C7 评分配置版本、汇率快照标识，合成执行标识 executionId——
 *       决策级结果都带上它，不同交易、不同配置版本的决策结果不可能串用；</li>
 *   <li>一次决策共用的一份配置快照与交易要素：全部候选按同一份手续费配置、同一个 HSG 中间汇率快照计算成本；</li>
 *   <li>候选集合：候选身份 = providerCode + fxVenue。硬过滤（C1/C2/C3/C4/C6/C10）结束后冻结；</li>
 *   <li>三个阶段严格顺序：CANDIDATE_CALC（逐个候选算成本）→ AGGREGATE（聚合屏障之后才算集合与最小值）→
 *       CANDIDATE_SCORE（全部候选按同一个最小值算分）。</li>
 * </ul>
 * 作用域约定：候选成本 / 得分属于 CANDIDATE，只能由该候选自己发布；候选成本集合与最低成本属于 CANDIDATE_SET / DECISION，
 * 在聚合阶段发布一次、全部候选共用，单个候选不能改写；聚合完成之前任何人都读不到决策级结果。
 * <p>全部状态在同一把锁（this）下修改；成本计算可以并行，{@link #awaitAllCosts()} 阻塞等待全部可路由候选的成本发布
 * （不用固定等待，有候选失败时立即唤醒并报错），{@link #beginAggregation()} 不等待、直接核对屏障，
 * 任何一项未完成或失败都抛 {@link C7ExecutionException}。成本、得分为空或为负数一律拒绝，失败不会变成 0。
 */
public final class RoutingDecisionContext {
    /** 执行器通过 {@code RuleExecutionContext#supply} 把本上下文放进求值上下文时使用的键。 */
    public static final String CONTEXT_KEY = "routingDecision";
    /** 三个元数据字段的编码：候选成本（CANDIDATE）、可路由候选成本集合（CANDIDATE_SET）、最低成本（DECISION）。 */
    public static final String COST_FIELD = "estimatedTotalCostHkd";
    public static final String COSTS_FIELD = "eligibleEstimatedCostsHkd";
    public static final String MIN_COST_FIELD = "minEligibleCostHkd";
    /** 候选得分在候选结果里的键。 */
    public static final String SCORE_RESULT = "c7CostScore";
    /** 候选成本构成（来自成本函数输出的其余属性）在候选结果里的键。 */
    public static final String BREAKDOWN_RESULT = "costBreakdown";
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Comparator<CostRef> BY_IDENTITY = Comparator.comparing(CostRef::providerCode).thenComparing(CostRef::fxVenue);

    public enum Phase { CANDIDATE_CALC, AGGREGATE, CANDIDATE_SCORE, FINISHED }

    /** 候选成本状态：PENDING 未计算 / PUBLISHED 已发布（可路由候选为正式成本，被剔除候选为参考成本）/ FAILED 失败（可路由候选）/ UNAVAILABLE 不可用（被剔除候选，仅供排查）。 */
    public enum CostStatus { PENDING, PUBLISHED, FAILED, UNAVAILABLE }

    /**
     * 本次决策的交易要素：全部候选共用。amountHkd / amountUsd 由路由引擎按配置快照折算，
     * 成本函数会核对它们与快照汇率一致，不接受各候选自行折算。
     */
    public record Transaction(String transactionType, String paymentCurrency, String debitCurrency, BigDecimal amount,
                              BigDecimal amountHkd, BigDecimal amountUsd, String chargeOption) {
    }

    /** 决策级结果：取值、类型、写入它的那次决策执行（executionId），读取时核对。 */
    public record DecisionResult(String key, Object value, String type, String executionId) {
    }

    /** 聚合输入的一项：候选身份 ↔ 成本（港币，满精度），供审计与展示。 */
    public record CostRef(String providerCode, String fxVenue, BigDecimal costHkd) {
    }

    /** 一个候选在本次决策里的 C7 状态。身份 = providerCode + fxVenue。属性的读写都在候选自己的锁下完成（写入时同时持有决策锁）。 */
    public static final class CandidateContext {
        private final String providerCode;
        private final String fxVenue;
        private final boolean eligible;
        private final List<String> failedChecks;
        private final BigDecimal fxSpreadBps;
        private CostStatus costStatus = CostStatus.PENDING;
        private String costError;
        private BigDecimal cost;
        private final Map<String, Object> results = new LinkedHashMap<>();
        private String scoreNote;

        private CandidateContext(String providerCode, String fxVenue, boolean eligible, List<String> failedChecks, BigDecimal fxSpreadBps) {
            this.providerCode = providerCode;
            this.fxVenue = fxVenue;
            this.eligible = eligible;
            this.failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
            this.fxSpreadBps = fxSpreadBps;
        }

        public synchronized String getProviderCode() {
            return providerCode;
        }

        public synchronized String getFxVenue() {
            return fxVenue;
        }

        /** 是否通过全部硬过滤（进入可路由集合，参与聚合）。 */
        public synchronized boolean isEligible() {
            return eligible;
        }

        /** 未通过的硬过滤规则编码（通过全部时为空）。 */
        public synchronized List<String> getFailedChecks() {
            return failedChecks;
        }

        /** C10 选出的开放窗口点差（含加点，bps）；窗口未开放或无需换汇时为空。 */
        public synchronized BigDecimal getFxSpreadBps() {
            return fxSpreadBps;
        }

        public synchronized CostStatus getCostStatus() {
            return costStatus;
        }

        /** 成本不可用 / 失败的原因；正常时为空。 */
        public synchronized String getCostError() {
            return costError;
        }

        /** 预估总成本（港币，满精度）；未发布时为空。被剔除候选的成本仅为参考值，不进入聚合。 */
        public synchronized BigDecimal getCost() {
            return cost;
        }

        /** C7 得分（两位小数）；未计算时为空。 */
        public synchronized BigDecimal getScore() {
            return (BigDecimal) results.get(SCORE_RESULT);
        }

        /** 得分未计算的原因；正常时为空。 */
        public synchronized String getScoreNote() {
            return scoreNote;
        }

        /** 候选级结果（c7CostScore、costBreakdown），只读副本。 */
        public synchronized Map<String, Object> getResults() {
            return Map.copyOf(results);
        }

        /** 身份文本。 */
        public String identity() {
            return providerCode + "/" + fxVenue;
        }

        private synchronized void published(BigDecimal value, Map<String, Object> breakdown) {
            cost = value;
            costStatus = CostStatus.PUBLISHED;
            results.put(BREAKDOWN_RESULT, Map.copyOf(breakdown));
        }

        private synchronized void failed(String reason) {
            costStatus = eligible ? CostStatus.FAILED : CostStatus.UNAVAILABLE;
            costError = reason == null || reason.isBlank() ? "成本计算失败" : reason;
        }

        private synchronized void scored(BigDecimal value) {
            results.put(SCORE_RESULT, value);
        }

        private synchronized void unscored(String reason) {
            scoreNote = reason == null || reason.isBlank() ? "未计算得分" : reason;
        }
    }

    private final String decisionId;
    private final long configRevision;
    private final long c7ConfigRevision;
    private final String fxSnapshotId;
    private final String executionId;
    private final LocalDateTime decisionTime;
    private final RoutingConfig config;
    private final Transaction transaction;
    private final List<CandidateContext> candidates = new ArrayList<>();
    private final Map<String, CandidateContext> byKey = new LinkedHashMap<>();
    private final Map<String, DecisionResult> decisionResults = new LinkedHashMap<>();
    private List<CostRef> aggregationInputs = List.of();
    private boolean frozen;
    private Phase phase = Phase.CANDIDATE_CALC;
    private boolean aggregationSkipped;
    private String aggregationNote;
    private String failure;

    public RoutingDecisionContext(String decisionId, long configRevision, long c7ConfigRevision, RoutingConfig config,
                                  Transaction transaction, LocalDateTime decisionTime) {
        if (decisionId == null || decisionId.isBlank()) throw new IllegalArgumentException("决策编号不能为空");
        if (config == null || config.hkdRates() == null || config.fees() == null)
            throw new IllegalArgumentException("路由决策上下文缺少路由配置");
        if (transaction == null || decisionTime == null) throw new IllegalArgumentException("路由决策上下文缺少交易要素或决策时间");
        this.decisionId = decisionId;
        this.configRevision = configRevision;
        this.c7ConfigRevision = c7ConfigRevision;
        this.config = config;
        this.transaction = transaction;
        this.decisionTime = decisionTime;
        this.fxSnapshotId = snapshotId(configRevision, config.hkdRates());
        this.executionId = decisionId + ":" + configRevision + ":" + c7ConfigRevision + ":" + fxSnapshotId;
    }

    /** 汇率快照标识：路由配置版本 + 按币种排序的中间汇率摘要（前 12 位十六进制）；同一份汇率配置得到同一个标识。 */
    static String snapshotId(long revision, Map<String, BigDecimal> rates) {
        String canonical = new TreeMap<>(rates).entrySet().stream()
                .map(e -> e.getKey() + "=" + (e.getValue() == null ? "null" : e.getValue().toPlainString()))
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", digest[i]));
            return "FX-" + revision + "-" + hex;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String getDecisionId() {
        return decisionId;
    }

    public long getConfigRevision() {
        return configRevision;
    }

    public long getC7ConfigRevision() {
        return c7ConfigRevision;
    }

    public String getFxSnapshotId() {
        return fxSnapshotId;
    }

    /** 本次决策执行的标识：决策编号 + 路由配置版本 + C7 配置版本 + 汇率快照。 */
    public String getExecutionId() {
        return executionId;
    }

    public LocalDateTime getDecisionTime() {
        return decisionTime;
    }

    /** 本次决策共用的配置快照（只读引用，调用方不得修改）。 */
    public RoutingConfig getConfig() {
        return config;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    /** 快照里的中间汇率（每 1 单位币种折合港币）；缺少或非正数时报错，不会按 1 处理。 */
    public BigDecimal rate(String currency) {
        BigDecimal rate = currency == null ? null : config.hkdRates().get(currency);
        if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("缺少汇率：" + currency);
        return rate;
    }

    /** 按快照汇率把交易币种金额折成港币（与路由引擎同一口径、同一精度）。 */
    public BigDecimal toHkd(BigDecimal amount, String currency) {
        return amount.multiply(rate(currency), MC);
    }

    // ---------- 候选集合 ----------

    /**
     * 登记一个候选（硬过滤过程中调用）。身份重复、集合已冻结时报错；可路由候选不能带未通过的过滤项。
     */
    public synchronized CandidateContext register(String providerCode, String fxVenue, boolean eligible, List<String> failedChecks,
                                                  BigDecimal fxSpreadBps) {
        if (frozen) throw new C7ExecutionException("候选集合已冻结，不能再登记候选：" + providerCode + "/" + fxVenue);
        if (providerCode == null || providerCode.isBlank() || fxVenue == null || fxVenue.isBlank())
            throw new IllegalArgumentException("候选身份（providerCode + fxVenue）不能为空");
        if (eligible && failedChecks != null && !failedChecks.isEmpty())
            throw new IllegalArgumentException("通过全部硬过滤的候选不能带未通过的过滤项：" + providerCode + "/" + fxVenue);
        if (fxSpreadBps != null && fxSpreadBps.signum() < 0)
            throw new IllegalArgumentException("外汇点差不能为负数：" + providerCode + "/" + fxVenue);
        String key = providerCode + "/" + fxVenue;
        if (byKey.containsKey(key)) throw new C7ExecutionException("候选重复登记（身份 = providerCode + fxVenue）：" + key);
        CandidateContext candidate = new CandidateContext(providerCode, fxVenue, eligible, failedChecks, fxSpreadBps);
        byKey.put(key, candidate);
        candidates.add(candidate);
        return candidate;
    }

    /** 硬过滤结束：冻结候选集合。之后只能发布成本 / 结果，不能再登记候选。重复冻结报错。 */
    public synchronized void freeze() {
        if (frozen) throw new C7ExecutionException("候选集合已经冻结");
        frozen = true;
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    public synchronized Phase getPhase() {
        return phase;
    }

    /** 全部候选（登记顺序，只读）。 */
    public synchronized List<CandidateContext> getCandidates() {
        return List.copyOf(candidates);
    }

    /** 通过全部硬过滤、进入聚合的候选个数。 */
    public synchronized int getEligibleCount() {
        return (int) candidates.stream().filter(CandidateContext::isEligible).count();
    }

    /** 按身份取候选；不在本次决策里时抛参数错误（不会返回 null 让调用方自行兜底）。 */
    public synchronized CandidateContext candidate(String providerCode, String fxVenue) {
        CandidateContext candidate = providerCode == null || fxVenue == null ? null : byKey.get(providerCode + "/" + fxVenue);
        if (candidate == null) throw new IllegalArgumentException("候选不在本次决策内：" + providerCode + "/" + fxVenue);
        return candidate;
    }

    // ---------- 阶段一：CANDIDATE_CALC ----------

    /** 发布可路由候选的正式成本（进入聚合）。只能在 CANDIDATE_CALC 阶段、每个候选一次、非空且非负。 */
    public void publishCost(String providerCode, String fxVenue, BigDecimal cost) {
        publish(providerCode, fxVenue, cost, Map.of(), false);
    }

    /** 同上，并带上成本构成（来自成本函数输出的其余属性，仅供展示与审计）。 */
    public void publishCost(String providerCode, String fxVenue, BigDecimal cost, Map<String, Object> breakdown) {
        publish(providerCode, fxVenue, cost, breakdown, false);
    }

    /**
     * 发布被剔除候选的参考成本：仅供排查展示，不进入聚合、不参与得分基准。
     * 被剔除候选不是可路由集合的一员，所以不能走 {@link #publishCost}。
     */
    public void publishReferenceCost(String providerCode, String fxVenue, BigDecimal cost, Map<String, Object> breakdown) {
        publish(providerCode, fxVenue, cost, breakdown, true);
    }

    public void publishReferenceCost(String providerCode, String fxVenue, BigDecimal cost) {
        publish(providerCode, fxVenue, cost, Map.of(), true);
    }

    private void publish(String providerCode, String fxVenue, BigDecimal cost, Map<String, Object> breakdown, boolean reference) {
        synchronized (this) {
            CandidateContext c = candidate(providerCode, fxVenue);
            requireFrozen();
            if (phase != Phase.CANDIDATE_CALC)
                throw new C7ExecutionException("当前阶段 " + phase + " 不能发布候选成本（只能在 CANDIDATE_CALC 阶段）：" + c.identity());
            if (reference == c.isEligible())
                throw new C7ExecutionException(c.isEligible()
                        ? "通过硬过滤的候选必须发布正式成本，不能按参考值发布：" + c.identity()
                        : "未通过硬过滤的候选不进入聚合，成本只能按参考值发布：" + c.identity());
            if (cost == null) throw new C7ExecutionException("候选成本不能为空（成本计算失败不能按 0 处理）：" + c.identity());
            if (cost.signum() < 0) throw new C7ExecutionException("候选成本不能为负数：" + c.identity() + " = " + cost.toPlainString());
            if (c.getCostStatus() != CostStatus.PENDING) throw new C7ExecutionException("候选成本只能发布一次：" + c.identity());
            c.published(cost, breakdown == null ? Map.of() : breakdown);
            notifyAll();
        }
    }

    /** 记录候选成本不可用：可路由候选 → FAILED（阻断聚合）；被剔除候选 → UNAVAILABLE（仅不展示成本与得分）。 */
    public void failCost(String providerCode, String fxVenue, String error) {
        synchronized (this) {
            CandidateContext c = candidate(providerCode, fxVenue);
            requireFrozen();
            if (phase != Phase.CANDIDATE_CALC)
                throw new C7ExecutionException("当前阶段 " + phase + " 不能记录候选成本失败：" + c.identity());
            if (c.getCostStatus() != CostStatus.PENDING) throw new C7ExecutionException("候选成本已发布，不能再记为失败：" + c.identity());
            c.failed(error);
            notifyAll();
        }
    }

    // ---------- 聚合屏障 ----------

    /**
     * 阻塞等待全部可路由候选的成本发布（成本并行计算时使用）。任何一个可路由候选失败都立即唤醒并抛异常；
     * 不使用固定等待，被中断时恢复中断标志并报错。
     */
    public synchronized void awaitAllCosts() {
        requireFrozen();
        while (pending() && !failed()) {
            try {
                wait();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new C7ExecutionException("等待候选成本计算被中断");
            }
        }
        assertNoFailure();
    }

    /** 进入聚合阶段（不等待）：仍有可路由候选的成本未完成或失败时立即报错；只能在 CANDIDATE_CALC 之后调用一次。 */
    public synchronized void beginAggregation() {
        requireFrozen();
        if (phase != Phase.CANDIDATE_CALC)
            throw new C7ExecutionException("聚合只能在 CANDIDATE_CALC 阶段之后开始，当前阶段 " + phase);
        if (pending())
            throw new C7ExecutionException("仍有可路由候选的成本未计算完成，不能进入聚合："
                    + candidates.stream().filter(c -> c.isEligible() && c.getCostStatus() == CostStatus.PENDING).map(CandidateContext::identity).collect(Collectors.joining("、")));
        assertNoFailure();
        phase = Phase.AGGREGATE;
    }

    private boolean pending() {
        return candidates.stream().anyMatch(c -> c.isEligible() && c.getCostStatus() == CostStatus.PENDING);
    }

    private boolean failed() {
        return candidates.stream().anyMatch(c -> c.isEligible() && c.getCostStatus() == CostStatus.FAILED);
    }

    private void assertNoFailure() {
        for (CandidateContext c : candidates)
            if (c.isEligible() && c.getCostStatus() == CostStatus.FAILED)
                throw new C7ExecutionException("可路由候选的成本计算失败，不能进入聚合（失败不按 0 处理）：" + c.identity() + " — " + c.getCostError());
    }

    // ---------- 阶段二：AGGREGATE ----------

    /**
     * 聚合输入：全部可路由候选的成本（候选身份 ↔ 成本），按 providerCode、fxVenue 排序，不依赖任何数据库顺序。
     * 只能在聚合阶段读取；要求集合已冻结、全部可路由候选的成本已正式发布且非负——任何一项为空或失败都报错，不会跳过。
     * 没有可路由候选时返回空清单（由调用方决定不聚合）。
     */
    public synchronized List<CostRef> eligibleCosts() {
        requireFrozen();
        if (phase != Phase.AGGREGATE)
            throw new C7ExecutionException("候选成本集合只能在聚合阶段读取，当前阶段 " + phase);
        if (pending())
            throw new C7ExecutionException("仍有可路由候选的成本未计算完成，不能读取候选成本集合");
        assertNoFailure();
        List<CostRef> refs = new ArrayList<>();
        for (CandidateContext c : candidates) {
            if (!c.isEligible()) continue;
            if (c.getCostStatus() != CostStatus.PUBLISHED || c.getCost() == null)
                throw new C7ExecutionException("可路由候选没有有效的成本，不能进入聚合：" + c.identity() + "（" + c.getCostStatus() + "）");
            if (c.getCost().signum() < 0) throw new C7ExecutionException("候选成本不能为负数：" + c.identity());
            refs.add(new CostRef(c.getProviderCode(), c.getFxVenue(), c.getCost()));
        }
        refs.sort(BY_IDENTITY);
        return List.copyOf(refs);
    }

    /** 记录聚合输入（候选身份 ↔ 成本），供审计与展示；只能在聚合阶段、记录一次。 */
    public synchronized void recordAggregationInputs(List<CostRef> refs) {
        if (phase != Phase.AGGREGATE)
            throw new C7ExecutionException("聚合输入只能在聚合阶段记录，当前阶段 " + phase);
        if (!aggregationInputs.isEmpty()) throw new C7ExecutionException("聚合输入只能记录一次");
        aggregationInputs = List.copyOf(refs);
    }

    /** 聚合输入（候选身份 ↔ 成本）；尚未聚合时为空清单。 */
    public synchronized List<CostRef> getAggregationInputs() {
        return aggregationInputs;
    }

    /**
     * 发布一个决策级结果（聚合阶段、每个键一次、全部候选共用）。按类型校验：DECIMAL 须为非负 BigDecimal；
     * NUMBER_LIST 须为元素非负的数值清单。为空、类型不符、负数一律拒绝（失败不等于 0）。结果带上本次执行的标识。
     */
    public synchronized void publishDecisionResult(String key, Object value, String type) {
        requireFrozen();
        if (key == null || key.isBlank()) throw new IllegalArgumentException("决策结果键不能为空");
        if (phase != Phase.AGGREGATE)
            throw new C7ExecutionException("决策结果只能在聚合阶段发布，当前阶段 " + phase + "：" + key);
        if (decisionResults.containsKey(key)) throw new C7ExecutionException("决策结果只能发布一次：" + key);
        decisionResults.put(key, new DecisionResult(key, validate(key, value, type), type, executionId));
    }

    private static Object validate(String key, Object value, String type) {
        String owner = "决策结果 " + key;
        if (value == null) throw new C7ExecutionException(owner + " 不能为空（聚合失败不能按 0 处理）");
        switch (type == null ? "" : type) {
            case "DECIMAL" -> {
                if (!(value instanceof BigDecimal decimal))
                    throw new C7ExecutionException(owner + " 的类型应为 DECIMAL（BigDecimal），实际为 " + value.getClass().getSimpleName());
                if (decimal.signum() < 0) throw new C7ExecutionException(owner + " 不能为负数：" + decimal.toPlainString());
            }
            case "NUMBER_LIST" -> {
                if (!(value instanceof RuleList list) || !RuleList.NUMBER_LIST.equals(list.dataType()))
                    throw new C7ExecutionException(owner + " 的类型应为 NUMBER_LIST，实际为 " + value.getClass().getSimpleName());
                for (Object element : list.elements())
                    if (!(element instanceof BigDecimal decimal) || decimal.signum() < 0)
                        throw new C7ExecutionException(owner + " 含有为空或为负数的元素");
            }
            default -> throw new IllegalArgumentException("不支持的决策结果类型：" + type);
        }
        return value;
    }

    /** 聚合完成：决策级结果定稿，进入评分阶段。 */
    public synchronized void completeAggregation() {
        requirePhase(Phase.AGGREGATE, "完成聚合");
        phase = Phase.CANDIDATE_SCORE;
        notifyAll();
    }

    /** 没有可路由候选：不做聚合（没有最低成本，任何候选都不会有 C7 得分），直接进入评分阶段。 */
    public synchronized void skipAggregation(String reason) {
        requirePhase(Phase.AGGREGATE, "跳过聚合");
        if (getEligibleCount() > 0) throw new C7ExecutionException("有可路由候选时不能跳过聚合");
        aggregationSkipped = true;
        aggregationNote = reason;
        phase = Phase.CANDIDATE_SCORE;
        notifyAll();
    }

    /** 读取决策级结果：聚合完成之前不可读（抛异常，不返回 null）；结果不属于本次执行时同样报错。 */
    public synchronized DecisionResult decisionResult(String key) {
        if (phase != Phase.CANDIDATE_SCORE && phase != Phase.FINISHED)
            throw new C7ExecutionException("决策结果 " + key + " 尚未完成聚合，不能读取（当前阶段 " + phase + "）");
        DecisionResult result = decisionResults.get(key);
        if (result == null)
            throw new C7ExecutionException("本次决策没有决策结果 " + key + (aggregationSkipped ? "（" + aggregationNote + "）" : ""));
        if (!executionId.equals(result.executionId()))
            throw new C7ExecutionException("决策结果 " + key + " 不属于本次决策执行");
        return result;
    }

    public synchronized boolean hasDecisionResult(String key) {
        return (phase == Phase.CANDIDATE_SCORE || phase == Phase.FINISHED) && decisionResults.containsKey(key);
    }

    public synchronized boolean isAggregationSkipped() {
        return aggregationSkipped;
    }

    public synchronized String getAggregationNote() {
        return aggregationNote;
    }

    // ---------- 阶段三：CANDIDATE_SCORE ----------

    /** 发布候选得分（CANDIDATE_SCORE 阶段、每个候选一次、成本已发布、非空且非负）。 */
    public synchronized void publishScore(String providerCode, String fxVenue, BigDecimal score) {
        CandidateContext c = candidate(providerCode, fxVenue);
        if (phase != Phase.CANDIDATE_SCORE)
            throw new C7ExecutionException("候选得分只能在 CANDIDATE_SCORE 阶段发布，当前阶段 " + phase + "：" + c.identity());
        if (c.getCostStatus() != CostStatus.PUBLISHED) throw new C7ExecutionException("候选没有成本，不能发布得分：" + c.identity());
        if (score == null) throw new C7ExecutionException("候选得分不能为空：" + c.identity());
        if (score.signum() < 0) throw new C7ExecutionException("候选得分不能为负数：" + c.identity());
        if (c.getScore() != null) throw new C7ExecutionException("候选得分只能发布一次：" + c.identity());
        c.scored(score);
    }

    /** 记录候选不计算得分的原因（成本不可用，或没有可路由候选所以没有最低成本）。 */
    public synchronized void skipScore(String providerCode, String fxVenue, String reason) {
        CandidateContext c = candidate(providerCode, fxVenue);
        if (phase != Phase.CANDIDATE_SCORE)
            throw new C7ExecutionException("候选得分只能在 CANDIDATE_SCORE 阶段处理，当前阶段 " + phase + "：" + c.identity());
        if (c.getScore() != null) throw new C7ExecutionException("候选已有得分，不能再记为未计算：" + c.identity());
        c.unscored(reason);
    }

    /** 三个阶段全部结束。 */
    public synchronized void finish() {
        requirePhase(Phase.CANDIDATE_SCORE, "结束");
        phase = Phase.FINISHED;
        notifyAll();
    }

    /** 执行失败：结束本次决策的 C7 执行并唤醒全部等待方，之后的发布 / 读取一律拒绝。 */
    public synchronized void fail(String reason) {
        if (phase == Phase.FINISHED) return;
        failure = reason;
        phase = Phase.FINISHED;
        notifyAll();
    }

    public synchronized String getFailure() {
        return failure;
    }

    // ---------- 内部 ----------

    private void requireFrozen() {
        if (!frozen) throw new C7ExecutionException("候选集合尚未冻结（硬过滤未结束），不能发布或读取成本与决策结果");
    }

    private void requirePhase(Phase expected, String action) {
        requireFrozen();
        if (phase != expected) throw new C7ExecutionException("不能在阶段 " + phase + " " + action + "（需要 " + expected + "）");
    }
}
