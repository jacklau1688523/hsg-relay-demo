package com.hsg.relay.routing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static com.hsg.relay.routing.RoutingConfig.Reach;

class C3MatcherTest {
    static Reach reach(String country, String region, List<String> bics, boolean enabled) {
        return new Reach("PROV-GD", "USD", country, region, bics, List.of("SHA"), "Asia/Hong_Kong", LocalTime.of(17, 0), 0, new BigDecimal("100000"), new BigDecimal("1000000"), List.of(), enabled);
    }

    static C3Matcher.Input input(String country, String region, String bic, String charge) {
        return new C3Matcher.Input("PROV-GD", "USD", country, region, bic, charge);
    }

    @Test
    void guangdongRequiresAllFiveConditions() {
        var rows = List.of(reach("CN", "CN-GD", List.of(), true));
        assertThat(C3Matcher.select(rows, input("CN", "CN-GD", "ICBKCNBJXXX", "SHA"))).isPresent();
        for (var in : List.of(input("CN", null, "ICBKCNBJXXX", "SHA"), input("CN", "CN-SH", "ICBKCNBJXXX", "SHA"), input("HK", "CN-GD", "ICBKCNBJXXX", "SHA"), input("CN", "CN-GD", "ICBKCNBJXXX", "OUR"), new C3Matcher.Input("PROV-GD", "CNY", "CN", "CN-GD", "ICBKCNBJXXX", "SHA"))) {
            assertThat(C3Matcher.select(rows, in)).isEmpty();
        }
    }

    @Test
    void missingRegionMatchesOnlyAll() {
        var rows = List.of(reach("CN", "CN-GD", List.of(), true), reach("CN", "ALL", List.of(), true));
        assertThat(C3Matcher.select(rows, input("CN", null, "ICBKCNBJXXX", "SHA")).orElseThrow().index()).isEqualTo(1);
        assertThat(C3Matcher.select(rows, input("CN", "", "ICBKCNBJXXX", "SHA")).orElseThrow().index()).isEqualTo(1);
    }

    @Test
    void bankListMatchesBic8Prefix() {
        var rows = List.of(reach("CN", "ALL", List.of("ICBKCNBJ"), true));
        assertThat(C3Matcher.select(rows, input("CN", null, "ICBKCNBJXXX", "SHA"))).isPresent();
        assertThat(C3Matcher.select(rows, input("CN", null, "ICBKCNBJ", "SHA"))).isPresent();
        assertThat(C3Matcher.select(rows, input("CN", null, "BKCHCNBJXXX", "SHA"))).isEmpty();
    }

    @Test
    void regionOutranksCountryAndBankList() {
        var rows = List.of(reach("CN", "ALL", List.of("ICBKCNBJ"), true), reach("ALL", "CN-GD", List.of(), true));
        assertThat(C3Matcher.select(rows, input("CN", "CN-GD", "ICBKCNBJXXX", "SHA")).orElseThrow().index()).isEqualTo(1);
    }

    @Test
    void countryThenBankListBreakTies() {
        var in = input("CN", "CN-GD", "ICBKCNBJXXX", "SHA");
        assertThat(C3Matcher.select(List.of(reach("ALL", "ALL", List.of("ICBKCNBJ"), true), reach("CN", "ALL", List.of(), true)), in).orElseThrow().index()).isEqualTo(1);
        assertThat(C3Matcher.select(List.of(reach("CN", "ALL", List.of(), true), reach("CN", "ALL", List.of("ICBKCNBJ"), true)), in).orElseThrow().index()).isEqualTo(1);
    }

    @Test
    void disabledRowCannotMatchAndTiesUseFirstRow() {
        var in = input("CN", "CN-GD", "ICBKCNBJXXX", "SHA");
        var specific = reach("CN", "CN-GD", List.of(), false);
        assertThat(C3Matcher.select(List.of(specific, reach("ALL", "ALL", List.of(), true)), in).orElseThrow().index()).isEqualTo(1);
        var r = reach("CN", "ALL", List.of(), true);
        assertThat(C3Matcher.select(List.of(r, r), in).orElseThrow().index()).isZero();
    }

    @Test
    void reportsAllFiveConditionsOnFailure() {
        var rows = C3Matcher.rows(List.of(reach("CN", "CN-GD", List.of("ICBKCNBJ"), true)), input("HK", null, "BKCHCNBJXXX", "OUR"));
        assertThat(rows.get(0).criteria()).hasSize(5);
        assertThat(rows.get(0).criteria()).filteredOn(x -> !x.passed()).extracting(C3Matcher.Criterion::field).containsExactly("country", "region", "bankScope", "chargeOption");
    }

    @Test
    void rejectsMalformedCountryRegionAndBic() {
        assertThatThrownBy(() -> C3Matcher.validate(reach("HK", "CN-GD", List.of(), true))).hasMessageContaining("不一致");
        assertThatThrownBy(() -> C3Matcher.validate(reach("CN", "ALL", List.of("ICBKCNBJXXX"), true))).hasMessageContaining("BIC8");
        assertThatThrownBy(() -> C3Matcher.validateInput(input("CN", "ALL", "BAD", "SHA"))).isInstanceOf(IllegalArgumentException.class);
    }
}
