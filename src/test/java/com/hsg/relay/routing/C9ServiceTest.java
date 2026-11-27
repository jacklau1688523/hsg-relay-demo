package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import com.hsg.relay.service.C1PathConfigService;
import com.hsg.relay.service.C1PathFixtures;

@SpringBootTest
@Transactional
class C9ServiceTest {
    @Autowired
    C9Service c9;
    @Autowired
    RoutingService routing;
    @Autowired
    ObjectMapper json;

    @Autowired
    C1PathConfigService pathConfig;

    @BeforeEach
    void publishBaselinePaths() {
        // C1 只执行已发布的路径配置：先发布基线（只有 DEFAULT 路径，条件恒成立），结果由必检和准入规则决定
        C1PathFixtures.publishDefaultOnly(pathConfig, json);
    }

    private static final ZonedDateTime AT = ZonedDateTime.parse("2026-11-20T10:00:00+08:00"); // 第20/30天，金额基准3,333,333.33

    private C9Service.RowView row(String provider) {
        return c9.list().rows().stream().filter(r -> r.provider().equals(provider)).findFirst().orElseThrow();
    }

    @Test
    void listingCoversProvidersWithAgreementsMtdAndScore() {
        var listing = c9.list();
        assertThat(listing.rows()).isNotEmpty();
        var t1 = row("PROV-T1");
        assertThat(t1.agreements()).hasSize(1);
        assertThat(t1.agreements().get(0).metric()).isEqualTo("AMOUNT");
        assertThat(t1.monthUsd()).isEqualByComparingTo("0");
        assertThat(t1.calculation()).isNotNull();
        assertThat(listing.rows()).allSatisfy(r -> {
            assertThat(r.calculation()).isNotNull();
            assertThat(r.calculation().dayOfMonth()).isPositive();
        });
    }

    @Test
    void upsertAgreementSharesConfigWithRoutingAndDoesNotDuplicate() {
        var before = routing.config().config().agreements().size();
        c9.save(new C9Service.Save(c9.list().revision(), "PROV-T1", "AMOUNT", new BigDecimal("6000000")));
        var t1 = row("PROV-T1");
        assertThat(t1.agreements()).hasSize(1);
        assertThat(t1.agreements().get(0).monthlyTarget()).isEqualByComparingTo("6000000");
        assertThat(routing.config().config().agreements()).hasSize(before);
        c9.save(new C9Service.Save(c9.list().revision(), "PROV-T1", "COUNT", new BigDecimal("120")));
        assertThat(row("PROV-T1").agreements()).extracting(C9Service.AgreementView::metric).containsExactly("AMOUNT", "COUNT");
        assertThat(routing.config().config().agreements()).hasSize(before + 1);
    }

    @Test
    void staleRevisionCannotOverwrite() {
        var revision = c9.list().revision();
        c9.save(new C9Service.Save(revision, "PROV-T1", "AMOUNT", new BigDecimal("1")));
        assertThatThrownBy(() -> c9.save(new C9Service.Save(revision, "PROV-T1", "AMOUNT", new BigDecimal("2")))).hasMessageContaining("更新");
    }

    @Test
    void deleteRemovesAgreementAndFallsBackToNoTarget() {
        var revision = c9.list().revision();
        c9.delete(new C9Service.Delete(revision, "PROV-GD", "AMOUNT"));
        var sim = c9.simulate(new C9Service.Input("PROV-GD", null, null, null, null, AT));
        assertThat(sim.calculation().score()).isZero();
        assertThat(sim.calculation().outcome()).isEqualTo("NO_TARGET");
        assertThatThrownBy(() -> c9.delete(new C9Service.Delete(c9.list().revision(), "PROV-GD", "AMOUNT"))).hasMessageContaining("不存在");
    }

    @Test
    void mtdUpdatesMonthUsdAndCountForRouting() {
        c9.mtd(new C9Service.Mtd(c9.list().revision(), "PROV-T1", new BigDecimal("4000000"), 42L));
        var t1 = row("PROV-T1");
        assertThat(t1.monthUsd()).isEqualByComparingTo("4000000");
        assertThat(t1.monthCount()).isEqualTo(42L);
        assertThat(routing.config().config().monthUsd().get("PROV-T1")).isEqualByComparingTo("4000000");
        assertThatThrownBy(() -> c9.mtd(new C9Service.Mtd(c9.list().revision(), "PROV-T1", new BigDecimal("-1"), null))).hasMessageContaining("非负");
    }

    @Test
    void deleteMtdClearsBothProgressMeasuresAndKeepsAgreements() {
        var before = c9.list();
        var agreements = row("PROV-T1").agreements();
        var saved = c9.mtd(new C9Service.Mtd(before.revision(), "PROV-T1", new BigDecimal("123"), 4L));
        assertThatThrownBy(() -> c9.deleteMtd(new C9Service.DeleteMtd(before.revision(), "PROV-T1"))).hasMessageContaining("更新");
        var deleted = c9.deleteMtd(new C9Service.DeleteMtd(saved.revision(), "PROV-T1"));
        assertThat(deleted.rows().stream().filter(r -> r.provider().equals("PROV-T1")).findFirst().orElseThrow().monthUsd()).isEqualByComparingTo("0");
        assertThat(routing.config().config().monthUsd()).doesNotContainKey("PROV-T1");
        assertThat(routing.config().config().monthCount()).doesNotContainKey("PROV-T1");
        assertThat(row("PROV-T1").agreements()).isEqualTo(agreements);
    }

    @Test
    void simulateUsesSavedAgreementAndMtd() {
        var sim = c9.simulate(new C9Service.Input("PROV-T1", null, null, null, null, AT));
        assertThat(sim.calculation().score()).isEqualTo(100);
        assertThat(sim.calculation().outcome()).isEqualTo("BEHIND_PACE");
        assertThat(sim.calculation().items().get(0).expectedPace()).isEqualByComparingTo("3333333.33");
    }

    @Test
    void simulateOverridesApplyWithoutSaving() {
        var sim = c9.simulate(new C9Service.Input("PROV-T1", "AMOUNT", new BigDecimal("3000000"), new BigDecimal("2000000"), 5L, AT));
        assertThat(sim.calculation().score()).isEqualTo(50);
        assertThat(sim.calculation().outcome()).isEqualTo("ON_PACE");
        assertThat(sim.monthCount()).isEqualTo(5L);
        assertThat(routing.config().config().agreements().stream().filter(a -> a.provider().equals("PROV-T1")).findFirst().orElseThrow().monthlyTarget()).isEqualByComparingTo("5000000");
        assertThat(routing.config().config().monthUsd().get("PROV-T1")).isEqualByComparingTo("0");
    }

    @Test
    void simulateCountWithoutSavedTargetNeedsTarget() {
        assertThatThrownBy(() -> c9.simulate(new C9Service.Input("PROV-T1", "COUNT", null, null, null, AT))).hasMessageContaining("月度目标");
    }

    @Test
    void unknownProviderIsRejected() {
        assertThatThrownBy(() -> c9.simulate(new C9Service.Input("PROV-XX", null, null, null, null, AT))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> c9.save(new C9Service.Save(c9.list().revision(), "PROV-XX", "AMOUNT", BigDecimal.TEN))).hasMessageContaining("不存在");
    }

    @Test
    void routingCandidateUsesSameC9Calculation() throws Exception {
        c9.mtd(new C9Service.Mtd(c9.list().revision(), "PROV-T1", new BigDecimal("4000000"), 42L));
        var standalone = c9.simulate(new C9Service.Input("PROV-T1", null, null, null, null, AT)).calculation();
        ObjectNode req = (ObjectNode) json.readTree("""
                 {"paymentId":"C9-TEST","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                 "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                 "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                 "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        var result = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        var candidate = result.candidates().stream().filter(c -> c.providerCode.equals("PROV-T1")).findFirst().orElseThrow();
        assertThat(candidate.passed).isTrue();
        assertThat(candidate.rank).isNotNull();
        assertThat(candidate.checks).extracting(RoutingService.Check::code).doesNotContain("C9");
        assertThat(candidate.scores.get("C9")).isEqualByComparingTo(String.valueOf(standalone.score()));
        assertThat(standalone.score()).isEqualTo(50); // 4,000,000 ≥ 3,333,333.33 且 ＜ 5,000,000
    }
}
