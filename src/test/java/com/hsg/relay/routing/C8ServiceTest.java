package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.Preference;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class C8ServiceTest {
    @Autowired
    C8Service c8;
    @Autowired
    RoutingService routing;
    @Autowired
    ObjectMapper json;

    @Test
    void listingHasDefaultRules() {
        var listing = c8.rules();
        assertThat(listing.rows()).hasSize(3);
        assertThat(listing.rows()).extracting(r -> r.rule().provider()).contains("PROV-T1", "PROV-HO", "PROV-GD");
    }

    @Test
    void saveAddEditDeleteSharesConfigWithRouting() {
        int before = routing.config().config().preferences().size();
        Preference rule = new Preference("PROV-W", "EUR", null, null, null, null, "TRADE", "SME", 30);
        c8.save(new C8Service.Save(c8.rules().revision(), null, rule));
        assertThat(c8.rules().rows()).hasSize(before + 1);
        assertThat(routing.config().config().preferences()).hasSize(before + 1);
        int added = c8.rules().rows().stream().filter(r -> r.rule().equals(rule)).findFirst().orElseThrow().index();
        c8.save(new C8Service.Save(c8.rules().revision(), added, new Preference("PROV-W", "EUR", null, null, null, null, "TRADE", "SME", 45)));
        assertThat(c8.rules().rows().get(added).rule().points()).isEqualTo(45);
        c8.delete(new C8Service.Delete(c8.rules().revision(), added));
        assertThat(routing.config().config().preferences()).hasSize(before);
        assertThatThrownBy(() -> c8.delete(new C8Service.Delete(c8.rules().revision(), added))).hasMessageContaining("不存在");
    }

    @Test
    void saveValidatesPointsAndFormats() {
        long revision = c8.rules().revision();
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", null, null, null, null, null, null, null, 0)))).hasMessageContaining("1–100");
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", null, null, null, null, null, null, null, 101)))).hasMessageContaining("1–100");
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-XX", null, null, null, null, null, null, null, 10)))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", null, "USA", null, null, null, null, null, 10)))).hasMessageContaining("受益行国家");
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", null, null, "GD", null, null, null, null, 10)))).hasMessageContaining("受益人地区");
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", null, null, null, new BigDecimal("1000"), new BigDecimal("100"), null, null, 10)))).hasMessageContaining("上限");
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", null, null, null, null, null, "BAD_TYPE", null, 10)))).hasMessageContaining("交易类型");
        c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", "EUR", null, null, null, null, null, null, 30)));
        assertThatThrownBy(() -> c8.save(new C8Service.Save(revision, null, new Preference("PROV-W", "SGD", null, null, null, null, null, null, 30)))).hasMessageContaining("已更新");
    }

    @Test
    void simulateMatchesDefaultRules() {
        var kg = c8.simulate(new C8Service.Input("USD", "KG", null, new BigDecimal("30000"), "TRADE", "STANDARD"));
        assertThat(score(kg, "PROV-HO")).isEqualTo(100);
        assertThat(score(kg, "PROV-T1")).isZero();
        var rich = c8.simulate(new C8Service.Input("USD", "US", null, new BigDecimal("1000000"), "TRADE", "STANDARD"));
        assertThat(score(rich, "PROV-T1")).isEqualTo(100);
        var poor = c8.simulate(new C8Service.Input("USD", "US", null, new BigDecimal("999999.99"), "TRADE", "STANDARD"));
        assertThat(score(poor, "PROV-T1")).isZero();
        var gd = c8.simulate(new C8Service.Input("USD", "CN", "CN-GD", new BigDecimal("1000"), "TRADE", "STANDARD"));
        assertThat(score(gd, "PROV-GD")).isEqualTo(100);
        var zj = c8.simulate(new C8Service.Input("USD", "CN", "CN-ZJ", new BigDecimal("1000"), "TRADE", "STANDARD"));
        assertThat(score(zj, "PROV-GD")).isZero();
        assertThat(zj.calculation().providers()).allSatisfy(p -> assertThat(p.score()).isZero());
    }

    @Test
    void simulateValidatesInput() {
        assertThatThrownBy(() -> c8.simulate(new C8Service.Input("USD", "US", null, BigDecimal.TEN, "TRADE", null))).hasMessageContaining("客户分层");
        assertThatThrownBy(() -> c8.simulate(new C8Service.Input("USD", "US", null, BigDecimal.ZERO, "TRADE", "SME"))).hasMessageContaining("金额");
        assertThatThrownBy(() -> c8.simulate(new C8Service.Input("XXX", "US", null, BigDecimal.TEN, "TRADE", "SME"))).hasMessageContaining("汇率");
    }

    @Test
    void routingUsesSamePreferenceScore() throws Exception {
        ObjectNode req = (ObjectNode) json.readTree("""
                {"paymentId":"C8-TEST","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":1000000,
                "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        var result = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        var standalone = c8.simulate(new C8Service.Input("USD", "US", null, new BigDecimal("1000000"), "TRADE", "STANDARD"));
        for (String provider : List.of("PROV-T1", "PROV-W")) {
            var candidate = result.candidates().stream().filter(c -> c.providerCode.equals(provider)).findFirst().orElseThrow();
            assertThat(candidate.scores.get("C8")).isEqualByComparingTo(String.valueOf(score(standalone, provider)));
        }
    }

    private int score(C8Service.Simulation sim, String provider) {
        return sim.calculation().providers().stream().filter(p -> p.provider().equals(provider)).map(C8Calculator.ProviderScore::score).findFirst().orElse(0);
    }
}
