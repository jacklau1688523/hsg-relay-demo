package com.hsg.relay.routing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.hsg.relay.routing.RoutingConfig.Preference;
import static org.assertj.core.api.Assertions.*;

class C8CalculatorTest {
    private static final C8Calculator.Txn TXN = new C8Calculator.Txn("USD", "CN", null, new BigDecimal("50000"), "TRADE", "STANDARD");

    @Test
    void emptyConditionsMatchEverything() {
        Preference rule = new Preference("PROV-T1", null, null, null, null, null, null, null, 60);
        var eval = C8Calculator.evaluate(List.of(rule), TXN);
        assertThat(eval.evaluations().get(0).matched()).isTrue();
        assertThat(eval.providers().get(0).score()).isEqualTo(60);
        assertThat(eval.evaluations().get(0).conditions()).allSatisfy(c -> {
            assertThat(c.wildcard()).isTrue();
            assertThat(c.matched()).isTrue();
        });
    }

    @Test
    void specificRegionRequiresExactValue() {
        Preference rule = new Preference("PROV-GD", null, null, "CN-GD", null, null, null, null, 100);
        assertThat(C8Calculator.matches(rule, TXN)).isFalse(); // 交易未填写地区，仅 region=ALL 可匹配
        assertThat(C8Calculator.matches(rule, new C8Calculator.Txn("USD", "CN", "CN-ZJ", new BigDecimal("1"), "TRADE", "STANDARD"))).isFalse();
        assertThat(C8Calculator.matches(rule, new C8Calculator.Txn("USD", "CN", "CN-GD", new BigDecimal("1"), "TRADE", "STANDARD"))).isTrue();
    }

    @Test
    void amountBoundsAreInclusive() {
        Preference rule = new Preference("PROV-T1", "USD", null, null, new BigDecimal("1000000"), null, null, null, 100);
        assertThat(C8Calculator.matches(rule, new C8Calculator.Txn("USD", "US", null, new BigDecimal("1000000"), "TRADE", "STANDARD"))).isTrue();
        assertThat(C8Calculator.matches(rule, new C8Calculator.Txn("USD", "US", null, new BigDecimal("999999.99"), "TRADE", "STANDARD"))).isFalse();
        Preference capped = new Preference("PROV-T1", "USD", null, null, null, new BigDecimal("1000000"), null, null, 100);
        assertThat(C8Calculator.matches(capped, new C8Calculator.Txn("USD", "US", null, new BigDecimal("1000000"), "TRADE", "STANDARD"))).isTrue();
        assertThat(C8Calculator.matches(capped, new C8Calculator.Txn("USD", "US", null, new BigDecimal("1000001"), "TRADE", "STANDARD"))).isFalse();
    }

    @Test
    void nonMatchingCurrencyOrTypeOrSegmentBlocks() {
        Preference rule = new Preference("PROV-T1", "EUR", "KG", "CN-GD", null, null, "PAYROLL", "SME", 100);
        assertThat(C8Calculator.matches(rule, TXN)).isFalse();
        var cs = C8Calculator.conditions(rule, TXN);
        assertThat(cs.stream().filter(c -> c.field().equals("currency")).findFirst().orElseThrow().matched()).isFalse();
        assertThat(cs.stream().filter(c -> c.field().equals("segment")).findFirst().orElseThrow().matched()).isFalse();
    }

    @Test
    void scoreForSumsPerProviderAndCapsAt100() {
        Preference a1 = new Preference("PROV-A", null, null, null, null, null, null, null, 60);
        Preference a2 = new Preference("PROV-A", "USD", null, null, null, null, null, null, 60);
        Preference b = new Preference("PROV-B", null, null, null, null, null, null, null, 80);
        assertThat(C8Calculator.scoreFor(List.of(a1, a2, b), "PROV-A", TXN)).isEqualTo(100);
        assertThat(C8Calculator.scoreFor(List.of(a1, a2, b), "PROV-B", TXN)).isEqualTo(80);
        assertThat(C8Calculator.scoreFor(List.of(a1, a2, b), "PROV-C", TXN)).isZero();
    }

    @Test
    void evaluateGroupsHitsWithRuleIndexes() {
        Preference a1 = new Preference("PROV-A", "EUR", null, null, null, null, null, null, 30); // 不命中
        Preference a2 = new Preference("PROV-A", null, null, null, null, null, null, null, 70); // 命中
        var result = C8Calculator.evaluate(List.of(a1, a2), TXN);
        assertThat(result.evaluations()).hasSize(2);
        assertThat(result.evaluations().get(0).matched()).isFalse();
        assertThat(result.evaluations().get(1).matched()).isTrue();
        assertThat(result.providers()).hasSize(1);
        assertThat(result.providers().get(0).provider()).isEqualTo("PROV-A");
        assertThat(result.providers().get(0).rawPoints()).isEqualTo(70);
        assertThat(result.providers().get(0).score()).isEqualTo(70);
        assertThat(result.providers().get(0).hitIndexes()).containsExactly(1);
    }
}
