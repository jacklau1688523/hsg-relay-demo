package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 端到端：交易输入 → C1–C10 → 四项评分 → 全局权重匹配 → 综合得分 → 排序 → Primary + Alternates。
 * 只读取已初始化的默认权重（不修改权重配置），合作方准入、头寸、请求都在事务内改动，结束后回滚。
 */
@SpringBootTest
@Transactional
class ScoreWeightRoutingTest {
    @Autowired
    RoutingService service;
    @Autowired
    ObjectMapper json;
    @Autowired
    AppetiteRepository appetites;
    @Autowired
    RuleAuditRepository audits;
    @Autowired
    ProviderRepository providers;
    ObjectNode req;

    @BeforeEach
    void setup() throws Exception {
        req = (ObjectNode) json.readTree("""
                {"paymentId":"SW-1","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
    }

    RoutingService.Result run() {
        return service.simulate(json.convertValue(req, RoutingService.Request.class));
    }

    static RoutingService.Candidate candidate(RoutingService.Result r, String provider) {
        return r.candidates().stream().filter(c -> c.providerCode.equals(provider)).findFirst().orElseThrow();
    }

    /** 给合作方补一条 STANDARD 准入（照抄 PROV-T1 的），让它在 C1 通过。 */
    void acceptStandard(String provider) {
        Appetite template = appetites.findAll().stream().filter(x -> x.providerCode.equals("PROV-T1") && x.flowCategory.equals("STANDARD")).findFirst().orElseThrow();
        Appetite copy = json.convertValue(json.valueToTree(template), Appetite.class);
        copy.id = null;
        copy.providerCode = provider;
        appetites.save(copy);
    }

    @Test
    void defaultTransactionUsesTypePlusAllWeightsAndRanksTheCandidates() {
        var r = run();
        assertThat(r.result()).isEqualTo("ROUTE");
        // 默认权重只有「交易类型 + ALL」，TRADE + STANDARD 精确层级没有配置 → P2
        assertThat(r.ranking().weightMatchLevel()).isEqualTo("P2");
        assertThat(r.ranking().transactionType()).isEqualTo("TRADE");
        assertThat(r.ranking().clientTier()).isEqualTo("STANDARD");
        assertThat(r.ranking().matchedTransactionType()).isEqualTo("TRADE");
        assertThat(r.ranking().matchedClientTier()).isEqualTo("ALL");
        assertThat(r.ranking().weightConfigVersion()).isEqualTo(1);
        assertThat(r.ranking().weights()).isEqualTo(new ScoreWeightMatcher.Weights(40, 30, 20, 10));
        // PROV-W：C7 100、C5 100、C8 0、C9 100 → (100×40 + 100×30 + 0×20 + 100×10) / 100 = 80.00
        var route = r.route();
        assertThat(route.providerCode).isEqualTo("PROV-W");
        assertThat(route.fxVenue).isEqualTo("NONE");
        assertThat(route.role).isEqualTo("PRIMARY");
        assertThat(route.rank).isEqualTo(1);
        assertThat(route.totalScore).isEqualByComparingTo("80.00").hasScaleOf(2);
        assertThat(route.scores.get("C7")).isEqualByComparingTo("100");
        // PROV-T1：C7 76.92、C5 50、C8 0、C9 100 → (3076.80 + 1500 + 0 + 1000) / 100 = 55.768 → 55.77
        assertThat(r.alternatives()).hasSize(1);
        var alternate = r.alternatives().get(0);
        assertThat(alternate.providerCode).isEqualTo("PROV-T1");
        assertThat(alternate.role).isEqualTo("ALTERNATE_1");
        assertThat(alternate.rank).isEqualTo(2);
        assertThat(alternate.totalScore).isEqualByComparingTo("55.77");
        assertThat(alternate.rawScore).isEqualByComparingTo("55.768");
        // 原有输出字段照旧
        assertThat(route.estimatedCostHkd).isEqualByComparingTo("100.00");
        assertThat(route.valueDate.toString()).isEqualTo("2026-11-20");
        assertThat(r.opsCheckRequired()).isTrue();
        assertThat(r.candidates()).hasSize(4);
        assertThat(r.messages()).anySatisfy(m -> assertThat(m).contains("TRADE + ALL（v1，匹配级别 P2）"));
    }

    @Test
    void twoQualifiedCandidatesGivePrimaryAndOneAlternate() {
        var r = run();
        assertThat(r.ranking()).isNotNull();
        assertThat(r.route().role).isEqualTo("PRIMARY");
        assertThat(r.alternatives()).extracting(c -> c.role).containsExactly("ALTERNATE_1");
    }

    @Test
    void singleQualifiedCandidateGivesPrimaryOnly() {
        // PROV-W 多一条 AUD 准入后，USD→AUD 只有它自己的 FX Venue 可用：只有 1 个候选
        Appetite wise = appetites.findAll().stream().filter(x -> x.providerCode.equals("PROV-W")).findFirst().orElseThrow();
        Appetite copy = json.convertValue(json.valueToTree(wise), Appetite.class);
        copy.id = null;
        copy.currency = "AUD";
        appetites.save(copy);
        req.put("paymentCurrency", "AUD");
        var r = run();
        assertThat(r.result()).isEqualTo("ROUTE");
        assertThat(r.candidates()).hasSize(1);
        assertThat(r.route().providerCode).isEqualTo("PROV-W");
        assertThat(r.route().fxVenue).isEqualTo("PROVIDER");
        assertThat(r.route().role).isEqualTo("PRIMARY");
        assertThat(r.route().rank).isEqualTo(1);
        assertThat(r.alternatives()).isEmpty();
    }

    @Test
    void threeQualifiedCandidatesGivePrimaryAndTwoAlternates() {
        acceptStandard("PROV-HO");
        var r = run();
        assertThat(r.result()).isEqualTo("ROUTE");
        assertThat(r.candidates()).filteredOn(c -> c.passed && c.c5Score != null).hasSize(3);
        assertThat(r.route().role).isEqualTo("PRIMARY");
        assertThat(r.alternatives()).extracting(c -> c.role).containsExactly("ALTERNATE_1", "ALTERNATE_2");
        assertThat(r.route().rank).isEqualTo(1);
        assertThat(r.alternatives().get(0).rank).isEqualTo(2);
        assertThat(r.alternatives().get(1).rank).isEqualTo(3);
        // PROV-HO 的 C5 为满分，总分最高 → 主路由；PROV-W（80.00）、PROV-T1（55.77）依次为两个备选
        assertThat(r.route().providerCode).isEqualTo("PROV-HO");
        assertThat(r.alternatives()).extracting(c -> c.providerCode, c -> c.role)
                .containsExactly(tuple("PROV-W", "ALTERNATE_1"), tuple("PROV-T1", "ALTERNATE_2"));
        assertRankedInOrder(r);
        // 第四个候选（PROV-GD：C1 + C3 不过）没有排名和角色
        assertThat(r.candidates()).filteredOn(c -> c.rank != null).hasSize(3).allSatisfy(c -> assertThat(c.role).isNotBlank());
        assertThat(candidate(r, "PROV-GD").role).isNull();
    }

    /** 排序规则：总分（两位小数）降序；总分相同时预估成本 HKD 升序。 */
    static void assertRankedInOrder(RoutingService.Result r) {
        List<RoutingService.Candidate> ranked = new ArrayList<>();
        ranked.add(r.route());
        ranked.addAll(r.alternatives());
        for (int i = 0; i + 1 < ranked.size(); i++) {
            RoutingService.Candidate a = ranked.get(i), b = ranked.get(i + 1);
            assertThat(a.totalScore).isGreaterThanOrEqualTo(b.totalScore);
            if (a.totalScore.compareTo(b.totalScore) == 0) assertThat(a.estimatedCostHkd).isLessThanOrEqualTo(b.estimatedCostHkd);
            assertThat(a.rank).isEqualTo(i + 1);
        }
    }

    @Test
    void hardFilteredCandidatesNeverTakePartInTheRanking() {
        acceptStandard("PROV-GD");
        var r = run();
        // PROV-GD：C1 通过（补了准入）但 C3 无覆盖行、PROV-HO：C1 不通过——两者 C5 可能有分，也绝不参与排序
        RoutingService.Candidate gd = candidate(r, "PROV-GD");
        RoutingService.Candidate ho = candidate(r, "PROV-HO");
        assertThat(gd.passed).isFalse();
        assertThat(ho.passed).isFalse();
        assertThat(ho.c5Score).isEqualTo(100);
        for (RoutingService.Candidate c : List.of(gd, ho)) {
            assertThat(c.rank).isNull();
            assertThat(c.role).isNull();
            assertThat(c.totalScore).isNull();
            assertThat(c.scores).doesNotContainKeys("C7", "C8", "C9");
        }
        assertThat(r.route().providerCode).isNotEqualTo("PROV-GD");
        assertThat(r.alternatives()).extracting(c -> c.providerCode).doesNotContain("PROV-GD", "PROV-HO");
        assertThat(r.ranking()).isNotNull();
    }

    @Test
    void noQualifiedCandidateRefersWithoutAnyRouteOrRanking() {
        // 头寸清零 → PROV-T1 仅因 C4 不过；PROV-W / PROV-GD 的头寸开关是关的，所以再把 PROV-W 下线。
        // 与 RoutingServiceTest.fundingOnlyFailureRefers 同一构造：没有任何合格候选
        var cfg = json.valueToTree(service.config().config());
        cfg.withArray("balances").forEach(b -> ((ObjectNode) b).put("balance", 0));
        service.saveConfig(new RoutingService.ConfigView(service.config().revision(), json.convertValue(cfg, RoutingConfig.class)));
        var wise = providers.findById("PROV-W").orElseThrow();
        wise.status = "NOT_LIVE";
        providers.save(wise);
        var r = run();
        assertThat(r.result()).isEqualTo("REFER");
        assertThat(r.reasonCode()).isEqualTo("FUNDING_SHORTFALL");
        assertThat(r.route()).isNull();
        assertThat(r.alternatives()).isEmpty();
        assertThat(r.ranking()).isNull();
        assertThat(r.candidates()).allSatisfy(c -> {
            assertThat(c.rank).isNull();
            assertThat(c.role).isNull();
        });
    }

    @Test
    void zeroSpeedScoreStillTakesPartInTheScoring() {
        // 请求起息日远早于可实现日期：C5 对全部候选给 0 分（不是「不可评分」），照常计算总分和排序
        req.put("requestedValueDate", "2026-11-10");
        var r = run();
        assertThat(r.result()).isEqualTo("ROUTE");
        assertThat(r.route().c5Status).isEqualTo("SCORED");
        assertThat(r.route().c5Score).isZero();
        assertThat(r.route().scores.get("C5")).isEqualByComparingTo("0");
        // (100×40 + 0×30 + 0×20 + 100×10) / 100 = 50.00
        assertThat(r.route().totalScore).isEqualByComparingTo("50.00");
        assertThat(r.alternatives()).hasSize(1);
        assertThat(r.alternatives().get(0).c5Score).isZero();
    }

    @Test
    void everyCandidateOfOneTransactionUsesTheSameWeightVersionAndIsAudited() throws Exception {
        acceptStandard("PROV-HO");
        var r = run();
        List<RuleAudit> rows = audits.findTop500ByObjectTypeOrderByIdDesc(RouteRankingService.AUDIT_TYPE).stream()
                .filter(a -> r.decisionId().equals(a.objectId)).toList();
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(a -> json.readTree(a.afterJson).path("weightConfigId").asLong()).containsOnly(r.ranking().weightConfigId());
        assertThat(rows).extracting(a -> json.readTree(a.afterJson).path("weightConfigVersion").asInt()).containsOnly(1);
        assertThat(rows).extracting(a -> json.readTree(a.afterJson).path("weightMatchLevel").asText()).containsOnly("P2");
        for (RuleAudit a : rows) {
            var node = json.readTree(a.afterJson);
            assertThat(node.path("transactionId").asText()).isEqualTo("SW-1");
            assertThat(node.path("transactionType").asText()).isEqualTo("TRADE");
            assertThat(node.path("clientTier").asText()).isEqualTo("STANDARD");
            assertThat(node.path("w1").asInt()).isEqualTo(40);
            assertThat(node.path("w2").asInt()).isEqualTo(30);
            assertThat(node.path("w3").asInt()).isEqualTo(20);
            assertThat(node.path("w4").asInt()).isEqualTo(10);
            for (String field : List.of("providerCode", "fxVenue", "c7CostScore", "c5SpeedScore", "c8PreferenceScore", "c9CommercialScore",
                    "totalScore", "estimatedCostHkd", "candidateValueDate", "providerPriority", "finalRank", "routeRole", "decisionTime"))
                assertThat(node.hasNonNull(field)).as(field).isTrue();
            var candidate = r.candidates().stream().filter(c -> c.providerCode.equals(node.path("providerCode").asText())).findFirst().orElseThrow();
            assertThat(node.path("totalScore").decimalValue()).isEqualByComparingTo(candidate.totalScore);
            assertThat(node.path("routeRole").asText()).isEqualTo(candidate.role);
            assertThat(node.path("finalRank").asInt()).isEqualTo(candidate.rank);
        }
        assertThat(rows).extracting(a -> a.operatorName).containsOnly("system");
        // 决策记录里保存了实际使用的权重版本，可回放
        var saved = json.readTree(service.decision(r.decisionId()).toString()).path("response").path("ranking");
        assertThat(saved.path("weightMatchLevel").asText()).isEqualTo("P2");
        assertThat(saved.path("weightConfigVersion").asInt()).isEqualTo(1);
    }

    @Test
    void c1ToC10ChecksAreUnchangedByTheScoringStep() {
        acceptStandard("PROV-HO");
        var r = run();
        for (RoutingService.Candidate c : r.candidates())
            assertThat(c.checks).extracting(RoutingService.Check::code).containsExactly("C1", "C2", "C3", "C4", "C6", "C10");
        assertThat(candidate(r, "PROV-T1").checks).filteredOn(x -> x.code().equals("C1")).allSatisfy(x -> assertThat(x.passed()).isTrue());
        assertThat(candidate(r, "PROV-GD").checks).filteredOn(x -> !x.passed()).extracting(RoutingService.Check::code).containsExactly("C1", "C3");
    }
}
