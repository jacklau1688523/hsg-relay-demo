package com.hsg.relay.routing;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static com.hsg.relay.routing.RoutingConfig.Reach;

class C5CalculatorTest {
    private Reach row(int offset, LocalDate... holidays) {
        return new Reach("PROV-T1", "USD", "ALL", "ALL", List.of(), List.of("SHA"), "Asia/Hong_Kong", LocalTime.of(17, 0), offset, BigDecimal.TEN, BigDecimal.TEN, List.of(holidays), true);
    }

    private C5Calculator.Result calculate(Reach r, String at, String requested) {
        return C5Calculator.calculate(r, ZonedDateTime.parse(at), requested == null ? null : LocalDate.parse(requested));
    }

    @Test
    void beforeCutoffWithZeroOffsetScores100() {
        var r = calculate(row(0), "2026-11-20T16:59:59+08:00", "2026-11-20");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-20");
        assertThat(r.speedScore()).isEqualTo(100);
        assertThat(r.delayBusinessDays()).isZero();
    }

    @Test
    void cutoffEqualityRollsToNextWorkday() {
        var r = calculate(row(0), "2026-11-20T17:00:00+08:00", "2026-11-20");
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-23");
        assertThat(r.rollReason()).isEqualTo("AT_OR_AFTER_CUTOFF");
        assertThat(r.delayBusinessDays()).isEqualTo(1);
        assertThat(r.speedScore()).isEqualTo(50);
    }

    @Test
    void afterCutoffThenOffsetMakesTwoDayDelay() {
        var r = calculate(row(1), "2026-11-20T18:00:00+08:00", "2026-11-20");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-24");
        assertThat(r.delayBusinessDays()).isEqualTo(2);
        assertThat(r.speedScore()).isZero();
    }

    @Test
    void weekendSkipsToMondayThenAddsOffset() {
        var r = calculate(row(1), "2026-11-21T10:00:00+08:00", "2026-11-23");
        assertThat(r.currentBusinessDay()).isFalse();
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-23");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-24");
        assertThat(r.speedScore()).isEqualTo(50);
    }

    @Test
    void holidayCurrentDayRollsAndFutureHolidaysAreSkipped() {
        var r = calculate(row(1, LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-23")), "2026-11-20T10:00:00+08:00", "2026-11-20");
        assertThat(r.rollReason()).isEqualTo("NOT_BUSINESS_DAY");
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-24");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-25");
        assertThat(r.delayBusinessDays()).isEqualTo(2);
    }

    @Test
    void futureRequestGets100AndCandidateUsesRequestedDate() {
        var r = calculate(row(5), "2026-11-20T10:00:00+08:00", "2026-12-10");
        assertThat(r.candidateValueDate()).isEqualTo("2026-12-10");
        assertThat(r.speedScore()).isEqualTo(100);
        assertThat(r.delayBusinessDays()).isZero();
    }

    @Test
    void delayCountIsNotCappedAtTwo() {
        var r = calculate(row(5), "2026-11-20T17:00:00+08:00", "2026-11-20");
        assertThat(r.delayBusinessDays()).isEqualTo(6);
        assertThat(r.speedScore()).isZero();
        assertThat(r.candidateValueDate()).isEqualTo("2026-11-30");
    }

    @Test
    void defaultRequestUsesHongKongDayEvenWhenTimestampIsUtc() {
        var r = calculate(row(0), "2026-11-19T23:00:00Z", null);
        assertThat(r.requestedValueDate()).isEqualTo("2026-11-20");
        assertThat(r.speedScore()).isEqualTo(100);
    }

    @Test
    void repeatedHolidayOnWeekendIsNotDoubleDeductedFromDelay() {
        long n = C5Calculator.businessDaysBetween(LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-24"), Set.of(LocalDate.parse("2026-11-21"), LocalDate.parse("2026-11-23")));
        assertThat(n).isEqualTo(1);
    }

    @Test
    void businessDayCountWorksAcrossYears() {
        assertThat(C5Calculator.businessDaysBetween(LocalDate.parse("2026-01-01"), LocalDate.parse("2027-01-01"), Set.of())).isEqualTo(261);
    }

    @Test
    void validatesOffsetAndDuplicateHoliday() {
        assertThatThrownBy(() -> C5Calculator.validate("Asia/Hong_Kong", LocalTime.NOON, 6, List.of())).hasMessageContaining("0–5");
        assertThatThrownBy(() -> C5Calculator.validate("Asia/Hong_Kong", LocalTime.NOON, 1, List.of(LocalDate.parse("2026-12-25"), LocalDate.parse("2026-12-25")))).hasMessageContaining("重复");
    }
}
