package com.hsg.relay.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.RuleAuditRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 路由排序服务：对通过全部硬过滤、且 C5 已给出分数的候选（单位：providerCode + fxVenue）计算综合评分并排序。
 * <p>
 * 排序顺序固定为：① 综合评分（两位小数）降序 → ② 预估成本 HKD 升序 → ③ 候选起息日（C5 的 candidateValueDate）升序
 * → ④ 合作方优先级（数字小者优先）→ ⑤ 合作方编码升序 → ⑥ FX Venue 升序。
 * 得分与成本都用 {@link BigDecimal#compareTo} 比较，不使用 double/float；比较键全部显式，不依赖输入或数据库返回顺序，
 * 所以同一合作方多个 FX Venue 且其余条件都相同时，按 Venue 名称固定排序，结果稳定可重现。
 * <p>
 * 前三名依次为 PRIMARY / ALTERNATE_1 / ALTERNATE_2，其余候选保留排名但不进入推荐路由；候选不足三个时有多少取多少，
 * 一个都没有时返回空列表，由 {@link RoutingService} 按现有 REFER 流程处理，不会生成虚假的主路由。
 * 四项评分缺失或越界时抛出 {@link IllegalStateException}，不按 0 分处理（C5 得 0 分是合法得分，照常参与）。
 * 每个参与评分的候选写一条 {@code ROUTE_SCORING} 审计：权重版本、四项得分、四项权重、总分、排序依据和最终角色。
 */
@Service
public class RouteRankingService {
    public static final String AUDIT_TYPE = "ROUTE_SCORING";
    public static final String PRIMARY = "PRIMARY", ALTERNATE_1 = "ALTERNATE_1", ALTERNATE_2 = "ALTERNATE_2", NOT_SELECTED = "NOT_SELECTED";
    /** 推荐路由最多 3 个：主路由 + 两个备选。 */
    public static final int SELECTED = 3;
    static final List<String> ROLES = List.of(PRIMARY, ALTERNATE_1, ALTERNATE_2);

    /** 本笔交易实际使用的权重：交易维度一份，全部候选共用。 */
    public record MatchInfo(String transactionType, String clientTier, String weightMatchLevel, long weightConfigId, int weightConfigVersion,
                            String matchedTransactionType, String matchedClientTier, ScoreWeightMatcher.Weights weights) {
        static MatchInfo of(String transactionType, String clientTier, ScoreWeightMatcher.Match match) {
            return new MatchInfo(transactionType, clientTier, match.level(), match.configId(), match.versionNo(),
                    match.transactionType(), match.clientTier(), match.weights());
        }
    }

    /** 一个候选的评分结果：排名、角色、未舍入与舍入后的总分。 */
    public record Scored(RoutingService.Candidate candidate, int rank, String role, RouteTotalScoreCalculator.Total total) {
    }

    public record Outcome(MatchInfo match, List<Scored> ranked) {
    }

    /** 多级排序比较器：见类注释。 */
    static final Comparator<RoutingService.Candidate> ORDER = Comparator
            .comparing((RoutingService.Candidate c) -> c.totalScore, Comparator.reverseOrder())
            .thenComparing(c -> c.estimatedCostHkd)
            .thenComparing(c -> c.valueDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(c -> c.priority)
            .thenComparing(c -> c.providerCode)
            .thenComparing(c -> c.fxVenue);

    private final ScoreWeightMatcher matcher;
    private final RouteTotalScoreCalculator calculator;
    private final RuleAuditRepository audit;
    private final ObjectMapper json;

    public RouteRankingService(ScoreWeightMatcher matcher, RouteTotalScoreCalculator calculator, RuleAuditRepository audit, ObjectMapper json) {
        this.matcher = matcher;
        this.calculator = calculator;
        this.audit = audit;
        this.json = json;
    }

    /**
     * 没有合格候选时直接返回空结果：不匹配权重（权重配置缺失不会改变现有 REFER 的原因码）。
     * 否则整笔交易只匹配一次权重，全部候选用同一个 {@link ScoreWeightMatcher.Match} 计算后排序，并逐个写审计。
     */
    public Outcome rank(String paymentId, String decisionId, String transactionType, String clientTier,
                        List<RoutingService.Candidate> valid, LocalDateTime decisionTime) {
        if (valid == null || valid.isEmpty()) return new Outcome(null, List.of());
        ScoreWeightMatcher.Match match = matcher.require(transactionType, clientTier, decisionTime);
        ScoreWeightMatcher.Weights w = match.weights();
        // 先对全部候选算分并校验，再统一写回：任何一项评分缺失或越界都整笔失败，候选上不会留下算了一半的总分
        Map<RoutingService.Candidate, RouteTotalScoreCalculator.Total> totals = new LinkedHashMap<>();
        for (RoutingService.Candidate c : valid) {
            String key = c.providerCode + "/" + c.fxVenue;
            totals.put(c, calculator.calculateChecked(new RouteTotalScoreCalculator.Scores(
                    c.scores.get("C7"), c.scores.get("C5"), c.scores.get("C8"), c.scores.get("C9")), w, key));
            if (c.estimatedCostHkd == null || c.valueDate == null)
                throw new IllegalStateException("评分数据异常：" + key + " 缺少预估成本或候选起息日，无法参与排序");
        }
        for (RoutingService.Candidate c : valid) {
            c.rawScore = totals.get(c).unrounded();
            c.totalScore = totals.get(c).rounded();
        }
        List<RoutingService.Candidate> ordered = new ArrayList<>(valid);
        ordered.sort(ORDER);
        List<Scored> ranked = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            RoutingService.Candidate c = ordered.get(i);
            c.rank = i + 1;
            c.role = i < SELECTED ? ROLES.get(i) : NOT_SELECTED;
            ranked.add(new Scored(c, c.rank, c.role, new RouteTotalScoreCalculator.Total(c.rawScore, c.totalScore)));
        }
        for (Scored s : ranked)
            audit.save(new RuleAudit(AUDIT_TYPE, decisionId, "SCORE", "system", null, scoringAudit(paymentId, decisionId, transactionType, clientTier, match, s, decisionTime)));
        return new Outcome(MatchInfo.of(transactionType, clientTier, match), List.copyOf(ranked));
    }

    /** 审计内容：权重版本、四项得分、四项权重、总分、排序依据、最终角色；不含客户敏感信息。 */
    private String scoringAudit(String paymentId, String decisionId, String transactionType, String clientTier, ScoreWeightMatcher.Match match,
                                Scored s, LocalDateTime decisionTime) {
        RoutingService.Candidate c = s.candidate();
        ScoreWeightMatcher.Weights w = match.weights();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transactionId", paymentId);
        row.put("decisionId", decisionId);
        row.put("providerCode", c.providerCode);
        row.put("fxVenue", c.fxVenue);
        row.put("transactionType", transactionType);
        row.put("clientTier", clientTier);
        row.put("weightMatchLevel", match.level());
        row.put("weightConfigId", match.configId());
        row.put("weightConfigVersion", match.versionNo());
        row.put("c7CostScore", c.scores.get("C7"));
        row.put("c5SpeedScore", c.scores.get("C5"));
        row.put("c8PreferenceScore", c.scores.get("C8"));
        row.put("c9CommercialScore", c.scores.get("C9"));
        row.put("w1", w.cost());
        row.put("w2", w.speed());
        row.put("w3", w.preference());
        row.put("w4", w.commercial());
        row.put("totalScore", c.totalScore);
        row.put("totalScoreUnrounded", c.rawScore);
        row.put("estimatedCostHkd", c.estimatedCostHkd);
        // 日期与时间直接写成 ISO 字符串：审计 JSON 不依赖 ObjectMapper 是否注册了日期模块
        row.put("candidateValueDate", String.valueOf(c.valueDate));
        row.put("providerPriority", c.priority);
        row.put("finalRank", s.rank());
        row.put("routeRole", s.role());
        row.put("decisionTime", String.valueOf(decisionTime));
        try {
            return json.writeValueAsString(row);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("评分审计序列化失败", ex);
        }
    }

    /** 未参与推荐的候选数量超过零时给结果消息用的说明。 */
    public static String describe(MatchInfo match) {
        if (match == null) return "";
        return "权重 " + match.matchedTransactionType() + " + " + match.matchedClientTier() + "（v" + match.weightConfigVersion() + "，匹配级别 "
                + match.weightMatchLevel() + "）：" + match.weights().cost() + "/" + match.weights().speed() + "/"
                + match.weights().preference() + "/" + match.weights().commercial();
    }
}
