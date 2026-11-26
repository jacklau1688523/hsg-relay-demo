package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.RuleAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 多级排序、主备路由角色、评分异常处理和审计内容：固定的候选数据，不依赖 Spring。 */
class RouteRankingServiceTest {
    static final ScoreWeightMatcher.Weights QUARTERS = new ScoreWeightMatcher.Weights(25, 25, 25, 25);
    static final ScoreWeightMatcher.Match MATCH = new ScoreWeightMatcher.Match(7, 3, "TRADE", "ALL", "P2", QUARTERS,
            LocalDateTime.of(2000, 1, 1, 0, 0), null);
    static final LocalDateTime DECIDED = LocalDateTime.of(2026, 11, 26, 10, 0);

    final ScoreWeightMatcher matcher = mock(ScoreWeightMatcher.class);
    final RuleAuditRepository audits = mock(RuleAuditRepository.class);
    RouteRankingService ranking;

    @BeforeEach
    void setUp() {
        when(matcher.require(any(), any(), any())).thenReturn(MATCH);
        ranking = new RouteRankingService(matcher, new RouteTotalScoreCalculator(), audits, new ObjectMapper());
    }

    static RoutingService.Candidate cand(String provider, String venue, int priority, String cost, String valueDate, int[] s) {
        var c = new RoutingService.Candidate();
        c.providerCode = provider;
        c.fxVenue = venue;
        c.priority = priority;
        c.passed = true;
        c.estimatedCostHkd = new BigDecimal(cost);
        c.valueDate = LocalDate.parse(valueDate);
        c.c5Score = s[1];
        c.scores.put("C7", BigDecimal.valueOf(s[0]));
        c.scores.put("C5", BigDecimal.valueOf(s[1]));
        c.scores.put("C8", BigDecimal.valueOf(s[2]));
        c.scores.put("C9", BigDecimal.valueOf(s[3]));
        return c;
    }

    /** 四项得分合计 200 → 在等权重下总分恒为 50.00（得分的排列不同，总分相同）。 */
    static RoutingService.Candidate tied(String provider, String venue, int priority, String cost, String valueDate, int[] s) {
        assertThat(Arrays.stream(s).sum()).isEqualTo(200);
        return cand(provider, venue, priority, cost, valueDate, s);
    }

    List<String> order(List<RoutingService.Candidate> valid) {
        return ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", valid, DECIDED).ranked().stream()
                .map(s -> s.candidate().providerCode + "/" + s.candidate().fxVenue).toList();
    }

    @Test
    void higherTotalComesFirst() {
        var low = cand("PROV-A", "NONE", 1, "10.00", "2026-11-27", new int[]{40, 40, 40, 40});
        var high = cand("PROV-B", "NONE", 99, "90.00", "2026-12-30", new int[]{90, 90, 90, 90});
        assertThat(order(List.of(low, high))).containsExactly("PROV-B/NONE", "PROV-A/NONE");
        assertThat(high.totalScore).isEqualByComparingTo("90.00");
        assertThat(low.totalScore).isEqualByComparingTo("40.00");
    }

    @Test
    void equalTotalLowerCostWins() {
        // 题目示例：权重 40/30/20/10，(90×40 + 100×30 + 80×20 + 50×10) / 100 = 87.00；成本 80 与 65 → 成本低的第一
        when(matcher.require(any(), any(), any())).thenReturn(new ScoreWeightMatcher.Match(7, 1, "TRADE", "ALL", "P2",
                new ScoreWeightMatcher.Weights(40, 30, 20, 10), MATCH.effectiveFrom(), null));
        var a = cand("PROV-A", "NONE", 1, "80.00", "2026-11-27", new int[]{90, 100, 80, 50});
        var b = cand("PROV-B", "NONE", 99, "65.00", "2026-12-30", new int[]{90, 100, 80, 50});
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "ALL", List.of(a, b), DECIDED);
        assertThat(out.ranked().stream().map(s -> s.candidate().providerCode).toList()).containsExactly("PROV-B", "PROV-A");
        assertThat(a.totalScore).isEqualByComparingTo("87.00");
        assertThat(b.totalScore).isEqualByComparingTo("87.00");
    }

    @Test
    void equalTotalAndCostEarlierValueDateWins() {
        var a = tied("PROV-A", "NONE", 1, "65.00", "2026-11-30", new int[]{80, 60, 40, 20});
        var b = tied("PROV-B", "NONE", 99, "65.00", "2026-11-27", new int[]{60, 80, 40, 20});
        assertThat(order(List.of(a, b))).containsExactly("PROV-B/NONE", "PROV-A/NONE");
    }

    @Test
    void equalTotalCostAndDateSmallerProviderPriorityWins() {
        var a = tied("PROV-A", "NONE", 5, "65.00", "2026-11-27", new int[]{80, 60, 40, 20});
        var b = tied("PROV-B", "NONE", 2, "65.00", "2026-11-27", new int[]{60, 80, 40, 20});
        var d = tied("PROV-D", "NONE", 99, "65.00", "2026-11-27", new int[]{80, 60, 20, 40});
        assertThat(order(List.of(a, b, d))).containsExactly("PROV-B/NONE", "PROV-A/NONE", "PROV-D/NONE");
    }

    @Test
    void equalEverythingUpToPriorityThenProviderCodeAscending() {
        var b = tied("PROV-B", "NONE", 3, "65.00", "2026-11-27", new int[]{80, 60, 40, 20});
        var a = tied("PROV-A", "NONE", 3, "65.00", "2026-11-27", new int[]{60, 80, 40, 20});
        var c = tied("PROV-C", "NONE", 3, "65.00", "2026-11-27", new int[]{80, 60, 20, 40});
        assertThat(order(List.of(c, b, a))).containsExactly("PROV-A/NONE", "PROV-B/NONE", "PROV-C/NONE");
    }

    @Test
    void sameProviderWithSeveralVenuesKeepsAFixedOrderRegardlessOfInputOrder() {
        List<RoutingService.Candidate> candidates = new ArrayList<>(List.of(
                tied("PROV-A", "TREASURY", 1, "65.00", "2026-11-27", new int[]{80, 60, 40, 20}),
                tied("PROV-A", "PROVIDER", 1, "65.00", "2026-11-27", new int[]{60, 80, 40, 20}),
                tied("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{80, 60, 20, 40})));
        for (int seed = 0; seed < 20; seed++) {
            Collections.shuffle(candidates, new Random(seed));
            assertThat(order(candidates)).as("seed %s", seed).containsExactly("PROV-A/NONE", "PROV-A/PROVIDER", "PROV-A/TREASURY");
        }
        // 完全打乱后的 6 个同分候选：结果只由比较键决定
        List<RoutingService.Candidate> many = new ArrayList<>(List.of(
                tied("PROV-B", "NONE", 2, "65.00", "2026-11-27", new int[]{80, 60, 40, 20}),
                tied("PROV-A", "PROVIDER", 2, "65.00", "2026-11-27", new int[]{60, 80, 40, 20}),
                tied("PROV-C", "NONE", 1, "65.00", "2026-11-28", new int[]{80, 60, 20, 40}),
                tied("PROV-A", "TREASURY", 2, "66.00", "2026-11-27", new int[]{40, 80, 60, 20}),
                tied("PROV-C", "NONE", 1, "65.00", "2026-11-28", new int[]{60, 60, 60, 20}),
                tied("PROV-A", "NONE", 2, "65.00", "2026-11-27", new int[]{80, 40, 60, 20})));
        List<String> expected = List.of("PROV-A/NONE", "PROV-A/PROVIDER", "PROV-B/NONE", "PROV-C/NONE", "PROV-C/NONE", "PROV-A/TREASURY");
        for (int seed = 0; seed < 20; seed++) {
            Collections.shuffle(many, new Random(seed));
            assertThat(order(many)).as("seed %s", seed).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void rankingUsesTheRoundedTotalNotTheUnroundedOne() {
        // A 未舍入 50.0025、B 未舍入 50.0000：两位小数后同为 50.00，由成本决定 → B 在前
        var a = cand("PROV-A", "NONE", 1, "80.00", "2026-11-27", new int[]{80, 60, 40, 20});
        a.scores.put("C8", new BigDecimal("40.01"));
        var b = tied("PROV-B", "NONE", 99, "65.00", "2026-11-27", new int[]{60, 80, 40, 20});
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(a, b), DECIDED);
        assertThat(a.rawScore).isEqualByComparingTo("50.0025");
        assertThat(a.totalScore).isEqualByComparingTo("50.00");
        assertThat(out.ranked().get(0).candidate()).isSameAs(b);
        assertThat(out.ranked().get(0).total().unrounded()).isEqualByComparingTo("50");
    }

    @Test
    void singleCandidateIsPrimaryOnly() {
        var only = cand("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50});
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(only), DECIDED);
        assertThat(out.ranked()).hasSize(1);
        assertThat(out.ranked().get(0).role()).isEqualTo("PRIMARY");
        assertThat(only.rank).isEqualTo(1);
        assertThat(only.role).isEqualTo("PRIMARY");
    }

    @Test
    void twoCandidatesGivePrimaryAndAlternate1() {
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(
                tied("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{80, 60, 40, 20}),
                tied("PROV-B", "NONE", 2, "65.00", "2026-11-27", new int[]{60, 80, 40, 20})), DECIDED);
        assertThat(out.ranked()).extracting(RouteRankingService.Scored::role).containsExactly("PRIMARY", "ALTERNATE_1");
    }

    @Test
    void fourCandidatesGiveThreeRoutesAndTheRestIsNotSelected() {
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(
                cand("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50}),
                cand("PROV-B", "NONE", 1, "65.00", "2026-11-27", new int[]{80, 100, 80, 50}),
                cand("PROV-C", "NONE", 1, "65.00", "2026-11-27", new int[]{70, 100, 80, 50}),
                cand("PROV-D", "NONE", 1, "65.00", "2026-11-27", new int[]{60, 100, 80, 50}),
                cand("PROV-E", "NONE", 1, "65.00", "2026-11-27", new int[]{50, 100, 80, 50})), DECIDED);
        assertThat(out.ranked()).extracting(s -> s.candidate().providerCode).containsExactly("PROV-A", "PROV-B", "PROV-C", "PROV-D", "PROV-E");
        assertThat(out.ranked()).extracting(RouteRankingService.Scored::role)
                .containsExactly("PRIMARY", "ALTERNATE_1", "ALTERNATE_2", "NOT_SELECTED", "NOT_SELECTED");
        assertThat(out.ranked()).extracting(RouteRankingService.Scored::rank).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void noQualifiedCandidateReturnsNothingWithoutMatchingWeights() {
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(), DECIDED);
        assertThat(out.ranked()).isEmpty();
        assertThat(out.match()).isNull();
        verifyNoInteractions(matcher);
        verifyNoInteractions(audits);
    }

    @Test
    void everyCandidateOfOneTransactionUsesTheSameWeightVersion() {
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(
                cand("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50}),
                cand("PROV-B", "NONE", 1, "65.00", "2026-11-27", new int[]{80, 90, 70, 60}),
                cand("PROV-C", "TREASURY", 1, "65.00", "2026-11-27", new int[]{70, 80, 60, 50})), DECIDED);
        // 整笔交易只匹配一次权重
        verify(matcher, times(1)).require("TRADE", "VIP", DECIDED);
        assertThat(out.match().weightConfigId()).isEqualTo(7);
        assertThat(out.match().weightConfigVersion()).isEqualTo(3);
        assertThat(out.match().weightMatchLevel()).isEqualTo("P2");
        assertThat(out.match().matchedTransactionType()).isEqualTo("TRADE");
        assertThat(out.match().matchedClientTier()).isEqualTo("ALL");
        assertThat(out.match().weights()).isEqualTo(QUARTERS);
    }

    @Test
    void missingConfigPropagatesTheConfigurationException() {
        when(matcher.require(any(), any(), any())).thenThrow(new IllegalStateException("没有可用的评分权重配置"));
        assertThatThrownBy(() -> ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(
                cand("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50})), DECIDED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有可用的评分权重配置");
        verifyNoInteractions(audits);
    }

    @Test
    void missingOrInvalidScoreStopsRankingInsteadOfScoringZero() {
        var missing = cand("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50});
        missing.scores.remove("C7");
        var bad = cand("PROV-B", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50});
        bad.scores.put("C9", new BigDecimal("120"));
        var good = cand("PROV-C", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50});
        assertThatThrownBy(() -> ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(good, missing), DECIDED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROV-A/NONE").hasMessageContaining("C7");
        assertThatThrownBy(() -> ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(good, bad), DECIDED))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROV-B/NONE").hasMessageContaining("超出 0–100");
        assertThat(good.rank).isNull();
        assertThat(good.totalScore).isNull();
        verifyNoInteractions(audits);
    }

    @Test
    void zeroSpeedScoreIsARankedCandidateNotAnError() {
        var slow = cand("PROV-A", "NONE", 1, "65.00", "2026-11-30", new int[]{90, 0, 80, 50});
        var fast = cand("PROV-B", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 50, 80, 50});
        var out = ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(slow, fast), DECIDED);
        assertThat(out.ranked()).extracting(s -> s.candidate().providerCode).containsExactly("PROV-B", "PROV-A");
        // (90 + 0 + 80 + 50) × 25 / 100 = 55.00
        assertThat(slow.totalScore).isEqualByComparingTo("55.00");
        assertThat(slow.role).isEqualTo("ALTERNATE_1");
    }

    @Test
    void eachScoredCandidateWritesAnAuditWithWeightsScoresAndRank() throws Exception {
        var a = cand("PROV-A", "NONE", 3, "65.00", "2026-11-27", new int[]{90, 100, 80, 50});
        var b = cand("PROV-B", "PROVIDER", 1, "70.00", "2026-11-30", new int[]{80, 90, 80, 60});
        ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", List.of(a, b), DECIDED);
        var captor = ArgumentCaptor.forClass(RuleAudit.class);
        verify(audits, times(2)).save(captor.capture());
        List<RuleAudit> rows = captor.getAllValues();
        assertThat(rows).extracting(x -> x.objectType, x -> x.objectId, x -> x.operation, x -> x.operatorName)
                .containsOnly(tuple("ROUTE_SCORING", "DEC-1", "SCORE", "system"));
        JsonNode first = new ObjectMapper().readTree(rows.get(0).afterJson);
        JsonNode second = new ObjectMapper().readTree(rows.get(1).afterJson);
        assertThat(rows.get(0).beforeJson).isNull();
        assertThat(first.path("transactionId").asText()).isEqualTo("TX-1");
        assertThat(first.path("providerCode").asText()).isEqualTo("PROV-A");
        assertThat(first.path("fxVenue").asText()).isEqualTo("NONE");
        assertThat(first.path("transactionType").asText()).isEqualTo("TRADE");
        assertThat(first.path("clientTier").asText()).isEqualTo("VIP");
        assertThat(first.path("weightMatchLevel").asText()).isEqualTo("P2");
        assertThat(first.path("weightConfigId").asLong()).isEqualTo(7);
        assertThat(first.path("weightConfigVersion").asInt()).isEqualTo(3);
        assertThat(first.path("c7CostScore").decimalValue()).isEqualByComparingTo("90");
        assertThat(first.path("c5SpeedScore").asInt()).isEqualTo(100);
        assertThat(first.path("c8PreferenceScore").asInt()).isEqualTo(80);
        assertThat(first.path("c9CommercialScore").asInt()).isEqualTo(50);
        assertThat(first.path("w1").asInt()).isEqualTo(25);
        assertThat(first.path("w2").asInt()).isEqualTo(25);
        assertThat(first.path("w3").asInt()).isEqualTo(25);
        assertThat(first.path("w4").asInt()).isEqualTo(25);
        // (90 + 100 + 80 + 50) × 25 / 100 = 80.00
        assertThat(first.path("totalScore").decimalValue()).isEqualByComparingTo("80.00");
        assertThat(first.path("estimatedCostHkd").decimalValue()).isEqualByComparingTo("65.00");
        assertThat(first.path("candidateValueDate").asText()).isEqualTo("2026-11-27");
        assertThat(first.path("providerPriority").asInt()).isEqualTo(3);
        assertThat(first.path("finalRank").asInt()).isEqualTo(1);
        assertThat(first.path("routeRole").asText()).isEqualTo("PRIMARY");
        assertThat(first.path("decisionTime").asText()).isNotBlank();
        // 全部字段齐全，且不含客户档案内容
        List<String> names = new ArrayList<>();
        first.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("transactionId", "decisionId", "providerCode", "fxVenue", "transactionType", "clientTier",
                "weightMatchLevel", "weightConfigId", "weightConfigVersion", "c7CostScore", "c5SpeedScore", "c8PreferenceScore", "c9CommercialScore",
                "w1", "w2", "w3", "w4", "totalScore", "totalScoreUnrounded", "estimatedCostHkd", "candidateValueDate", "providerPriority",
                "finalRank", "routeRole", "decisionTime");
        assertThat(second.path("providerCode").asText()).isEqualTo("PROV-B");
        assertThat(second.path("fxVenue").asText()).isEqualTo("PROVIDER");
        assertThat(second.path("finalRank").asInt()).isEqualTo(2);
        assertThat(second.path("routeRole").asText()).isEqualTo("ALTERNATE_1");
        assertThat(second.path("weightConfigVersion").asInt()).isEqualTo(first.path("weightConfigVersion").asInt());
    }

    @Test
    void rankingDoesNotReorderTheCallersList() {
        var a = cand("PROV-A", "NONE", 1, "65.00", "2026-11-27", new int[]{90, 100, 80, 50});
        var b = cand("PROV-B", "NONE", 1, "65.00", "2026-11-27", new int[]{80, 90, 70, 60});
        List<RoutingService.Candidate> valid = new ArrayList<>(List.of(b, a));
        ranking.rank("TX-1", "DEC-1", "TRADE", "VIP", valid, DECIDED);
        assertThat(valid).containsExactly(b, a);
    }
}
