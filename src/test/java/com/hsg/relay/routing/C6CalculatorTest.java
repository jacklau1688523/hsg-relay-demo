package com.hsg.relay.routing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.hsg.relay.routing.RoutingConfig.Reach;
import static org.assertj.core.api.Assertions.*;

class C6CalculatorTest {
    private static final Reach REACH = new Reach("PROV-T1", "USD", "ALL", "ALL", List.of(), List.of("OUR", "SHA", "BEN"),
            "Asia/Hong_Kong", java.time.LocalTime.of(17, 0), 0, new BigDecimal("2000000"), new BigDecimal("10000000"), List.of(), true);

    @Test
    void withinLimitsPassesAndReportsTodayAfter() {
        var r = C6Calculator.check(REACH, new BigDecimal("500000"), new BigDecimal("3000000"));
        assertThat(r.passed()).isTrue();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.todayAfterUsd()).isEqualByComparingTo("3500000");
        assertThat(r.perPaymentPassed()).isTrue();
        assertThat(r.dailyPassed()).isTrue();
    }

    @Test
    void bothLimitsFailInFixedOrder() {
        var r = C6Calculator.check(REACH, new BigDecimal("3000000"), new BigDecimal("8000000"));
        assertThat(r.passed()).isFalse();
        assertThat(r.reasons()).containsExactly("LIMIT_PER_PAYMENT", "LIMIT_DAILY");
        assertThat(r.todayAfterUsd()).isEqualByComparingTo("11000000");
    }

    @Test
    void dailyAloneFailsWhenPerPaymentWithin() {
        var r = C6Calculator.check(REACH, new BigDecimal("1000000"), new BigDecimal("9500000"));
        assertThat(r.reasons()).containsExactly("LIMIT_DAILY");
    }

    @Test
    void boundaryEqualsPass() {
        var r = C6Calculator.check(REACH, new BigDecimal("2000000"), new BigDecimal("8000000"));
        assertThat(r.passed()).isTrue();
    }

    @Test
    void validateRejectsNegative() {
        assertThatThrownBy(() -> C6Calculator.validate(new BigDecimal("-1"), new BigDecimal("10"))).hasMessageContaining("非负");
        assertThatThrownBy(() -> C6Calculator.validate(new BigDecimal("1"), null)).hasMessageContaining("非负");
    }
}
