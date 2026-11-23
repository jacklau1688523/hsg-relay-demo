package com.hsg.relay.routing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static com.hsg.relay.routing.RoutingConfig.Agreement;

class C9CalculatorTest {
    private static final ZonedDateTime AT = ZonedDateTime.parse("2026-11-20T10:00:00+08:00"); // 第20/30天
    private final Agreement amount = new Agreement("P", "AMOUNT", new BigDecimal("5000000"));

    private C9Calculator.Result calc(Agreement a, BigDecimal monthUsd, long monthCount, ZonedDateTime at) {
        return C9Calculator.calculate(a == null ? List.of() : List.of(a), monthUsd, monthCount, at);
    }

    @Test
    void noAgreementScoresZeroWithoutTarget() {
        var r = calc(null, new BigDecimal("100"), 5, AT);
        assertThat(r.score()).isZero();
        assertThat(r.outcome()).isEqualTo("NO_TARGET");
        assertThat(r.hasTarget()).isFalse();
        assertThat(r.items()).isEmpty();
    }

    @Test
    void zeroTargetMeansNoTargetAndScoresZero() {
        var r = calc(new Agreement("P", "AMOUNT", BigDecimal.ZERO), new BigDecimal("100"), 5, AT);
        assertThat(r.score()).isZero();
        assertThat(r.outcome()).isEqualTo("NO_TARGET");
        assertThat(r.hasTarget()).isFalse();
        assertThat(r.items().get(0).expectedPace()).isNull();
    }

    @Test
    void mtdAtOrAboveTargetScoresZero() {
        assertThat(calc(amount, new BigDecimal("5000000"), 0, AT).score()).isZero();
        assertThat(calc(amount, new BigDecimal("9000000"), 0, AT).outcome()).isEqualTo("TARGET_MET");
    }

    @Test
    void strictlyBelowPaceScores100() {
        var r = calc(amount, new BigDecimal("3333333.32"), 0, AT);
        assertThat(r.outcome()).isEqualTo("BEHIND_PACE");
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.items().get(0).expectedPace()).isEqualByComparingTo("3333333.33");
    }

    @Test
    void atExactPaceScores50() {
        var r = calc(new Agreement("P", "AMOUNT", new BigDecimal("3000000")), new BigDecimal("2000000"), 0, AT); // 3000000×20÷30=2000000
        assertThat(r.outcome()).isEqualTo("ON_PACE");
        assertThat(r.score()).isEqualTo(50);
    }

    @Test
    void abovePaceBelowTargetScores50() {
        var r = calc(amount, new BigDecimal("3333333.34"), 0, AT);
        assertThat(r.outcome()).isEqualTo("ON_PACE");
        assertThat(r.score()).isEqualTo(50);
        assertThat(r.items().get(0).targetPercent()).isEqualByComparingTo("66.7");
    }

    @Test
    void countMetricUsesMonthCountNotAmount() {
        var count = new Agreement("P", "COUNT", new BigDecimal("100"));
        assertThat(calc(count, new BigDecimal("999999"), 60, AT).score()).isEqualTo(100); // 基准66.67笔
        assertThat(calc(count, BigDecimal.ZERO, 70, AT).score()).isEqualTo(50);
        assertThat(calc(count, BigDecimal.ZERO, 100, AT).score()).isZero();
    }

    @Test
    void multipleAgreementsTakeHighestScore() {
        var met = new Agreement("P", "AMOUNT", new BigDecimal("100"));
        var behind = new Agreement("P", "COUNT", new BigDecimal("100"));
        var r = C9Calculator.calculate(List.of(met, behind), new BigDecimal("500"), 60, AT);
        assertThat(r.items()).hasSize(2);
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.outcome()).isEqualTo("BEHIND_PACE");
    }

    @Test
    void endOfMonthTreatsAnythingBelowTargetAsBehind() {
        var r = calc(amount, new BigDecimal("4999999.99"), 0, ZonedDateTime.parse("2026-11-30T23:00:00+08:00"));
        assertThat(r.outcome()).isEqualTo("BEHIND_PACE");
        assertThat(r.score()).isEqualTo(100);
    }

    @Test
    void firstDayOfMonthHasSmallPace() {
        var r = calc(amount, BigDecimal.ONE, 0, ZonedDateTime.parse("2026-11-01T08:00:00+08:00"));
        assertThat(r.items().get(0).expectedPace()).isEqualByComparingTo("166666.67");
        assertThat(r.score()).isEqualTo(100);
    }

    @Test
    void evaluationDateUsesHongKongCalendar() {
        var r = calc(amount, BigDecimal.ZERO, 0, ZonedDateTime.parse("2026-11-19T18:00:00-05:00")); // 纽约19日＝香港20日
        assertThat(r.dayOfMonth()).isEqualTo(20);
        assertThat(r.evaluatedAt().getDayOfMonth()).isEqualTo(20);
    }

    @Test
    void nullTimeDefaultsToNowHk() {
        assertThat(calc(amount, BigDecimal.ZERO, 0, null).dayOfMonth()).isPositive();
    }

    @Test
    void validatesMetricAndTarget() {
        assertThatThrownBy(() -> C9Calculator.validate("VOLUME", BigDecimal.TEN)).hasMessageContaining("AMOUNT 或 COUNT");
        assertThatThrownBy(() -> C9Calculator.validate("AMOUNT", new BigDecimal("-1"))).hasMessageContaining("非负");
        assertThatThrownBy(() -> C9Calculator.calculate(null, BigDecimal.ZERO, 0, AT)).hasMessageContaining("必填");
    }
}
