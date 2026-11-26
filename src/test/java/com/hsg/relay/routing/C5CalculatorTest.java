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
    void beforeCutoffWithZeroOffsetStartsToday() {
        var r = calculate(row(0), "2026-11-20T16:59:59+08:00", "2026-11-20");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-20");
        assertThat(r.delayBusinessDays()).isZero();
    }

    @Test
    void cutoffEqualityRollsToNextWorkday() {
        var r = calculate(row(0), "2026-11-20T17:00:00+08:00", "2026-11-20");
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-23");
        assertThat(r.rollReason()).isEqualTo("AT_OR_AFTER_CUTOFF");
        assertThat(r.delayBusinessDays()).isEqualTo(1);
    }

    @Test
    void afterCutoffThenOffsetMakesTwoDayDelay() {
        var r = calculate(row(1), "2026-11-20T18:00:00+08:00", "2026-11-20");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-24");
        assertThat(r.delayBusinessDays()).isEqualTo(2);
    }

    @Test
    void weekendSkipsToMondayThenAddsOffset() {
        var r = calculate(row(1), "2026-11-21T10:00:00+08:00", "2026-11-23");
        assertThat(r.currentBusinessDay()).isFalse();
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-23");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-24");
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
    void futureRequestKeepsRequestedDateAndHasNoDelay() {
        var r = calculate(row(5), "2026-11-20T10:00:00+08:00", "2026-12-10");
        assertThat(r.candidateValueDate()).isEqualTo("2026-12-10");
        assertThat(r.delayBusinessDays()).isZero();
    }

    @Test
    void delayCountIsNotCappedAtTwo() {
        var r = calculate(row(5), "2026-11-20T17:00:00+08:00", "2026-11-20");
        assertThat(r.delayBusinessDays()).isEqualTo(6);
        assertThat(r.candidateValueDate()).isEqualTo("2026-11-30");
    }

    @Test
    void defaultRequestUsesHongKongDayEvenWhenTimestampIsUtc() {
        var r = calculate(row(0), "2026-11-19T23:00:00Z", null);
        assertThat(r.requestedValueDate()).isEqualTo("2026-11-20");
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

    // ---------- C5 动态评分：日期计算补充用例（固定时间，不依赖系统时钟） ----------

    private Reach row(String zone, int offset, LocalDate... holidays) {
        return new Reach("PROV-T1", "USD", "ALL", "ALL", List.of(), List.of("SHA"), zone, LocalTime.of(17, 0), offset, BigDecimal.TEN, BigDecimal.TEN, List.of(holidays), true);
    }

    private C5Calculator.Result calculate(Reach r, String at) {
        return C5Calculator.calculate(r, ZonedDateTime.parse(at), null);
    }

    @Test
    void sundayIsNotABusinessDayAndStartsFromMonday() {
        var r = calculate(row(0), "2026-11-22T10:00:00+08:00");            // 周日
        assertThat(r.currentBusinessDay()).isFalse();
        assertThat(r.rollReason()).isEqualTo("NOT_BUSINESS_DAY");
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-23");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-23");
        assertThat(r.requestedValueDate()).isEqualTo("2026-11-22");         // 未上送请求起息日：默认取决策日的香港日期
        assertThat(r.delayBusinessDays()).isEqualTo(1);                      // 周六请求 → 从之后的第一个工作日起数：周一
    }

    @Test
    void consecutiveNonBusinessDaysAreAllSkipped() {
        // 周五 18:00 已过截单；周一、二、三都是节假日 → 基准日跳到周四
        var r = calculate(row(0, LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-24"), LocalDate.parse("2026-11-25")), "2026-11-20T18:00:00+08:00", "2026-11-20");
        assertThat(r.rollReason()).isEqualTo("AT_OR_AFTER_CUTOFF");
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-26");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-26");
        assertThat(r.delayBusinessDays()).isEqualTo(1);                      // 周六请求、周四是最早起息日：周一至周三是节假日，不计
    }

    @Test
    void yearEndSkipsNewYearHolidayAndCountsAcrossYears() {
        // 2026-12-31 周四 18:00 已过截单；2027-01-01 周五是节假日；偏移 1 个工作日
        var r = calculate(row(1, LocalDate.parse("2027-01-01")), "2026-12-31T18:00:00+08:00", "2026-12-31");
        assertThat(r.baseBusinessDate()).isEqualTo("2027-01-04");
        assertThat(r.earliestValueDate()).isEqualTo("2027-01-05");
        assertThat(r.candidateValueDate()).isEqualTo("2027-01-05");
        assertThat(r.delayBusinessDays()).isEqualTo(2);                      // 2027-01-04、01-05
    }

    @Test
    void monthEndMondayBeforeCutoffWithOffsetLandsOnNextBusinessDay() {
        // 周一早于截单 + 偏移 1 → 基准日周一，最早起息日周二；请求日周一 → 延迟 1 个工作日（偏移本身造成的延迟同样计入）
        var r = calculate(row(1), "2026-11-30T10:00:00+08:00", "2026-11-30");
        assertThat(r.beforeCutoffToday()).isTrue();
        assertThat(r.baseBusinessDate()).isEqualTo("2026-11-30");
        assertThat(r.earliestValueDate()).isEqualTo("2026-12-01");
        assertThat(r.delayBusinessDays()).isEqualTo(1);
    }

    @Test
    void cutoffIsJudgedInTheReachRowTimeZoneNotHongKong() {
        Reach newYork = row("America/New_York", 0);
        // 香港周五 20:00 = 纽约周五 07:00，早于纽约截单 17:00：今日起息
        var morning = calculate(newYork, "2026-11-20T20:00:00+08:00");
        assertThat(morning.beforeCutoff()).isTrue();
        assertThat(morning.earliestValueDate()).isEqualTo("2026-11-20");
        assertThat(morning.delayBusinessDays()).isZero();
        // 香港周五 08:00 = 纽约周四 19:00，已过截单：按纽约日历取下一工作日（周五）
        var evening = calculate(newYork, "2026-11-20T08:00:00+08:00");
        assertThat(evening.currentBusinessDay()).isTrue();
        assertThat(evening.beforeCutoff()).isFalse();
        assertThat(evening.rollReason()).isEqualTo("AT_OR_AFTER_CUTOFF");
        assertThat(evening.baseBusinessDate()).isEqualTo("2026-11-20");
        assertThat(evening.earliestValueDate()).isEqualTo("2026-11-20");
        // 香港周一 02:00 = 纽约周日 13:00：纽约是非工作日，跳到纽约的周一
        var sunday = calculate(newYork, "2026-11-23T02:00:00+08:00");
        assertThat(sunday.currentBusinessDay()).isFalse();
        assertThat(sunday.earliestValueDate()).isEqualTo("2026-11-23");
    }

    @Test
    void defaultRequestDateIsHongKongDateOfDecisionTime() {
        // 纽约周四 19:00 已过截单、偏移 1：最早起息日为纽约周五之后的下一个工作日（周一）；默认请求日起香港日期 11-20 → 延迟 1 个工作日
        var r = calculate(row("America/New_York", 1), "2026-11-20T08:00:00+08:00");
        assertThat(r.requestedValueDate()).isEqualTo("2026-11-20");
        assertThat(r.earliestValueDate()).isEqualTo("2026-11-23");
        assertThat(r.delayBusinessDays()).isEqualTo(1);
        assertThat(r.candidateValueDate()).isEqualTo("2026-11-23");
    }

    @Test
    void delayOnNonBusinessRequestDayCountsFromTheFollowingBusinessDay() {
        // 周六请求、周二是最早起息日：延迟 2（周一、周二），周六本身不计
        assertThat(calculate(row(2), "2026-11-20T10:00:00+08:00", "2026-11-21").delayBusinessDays()).isEqualTo(2);
        // 请求日不早于最早起息日 → 延迟 0，候选起息日 = 请求起息日
        var satisfied = calculate(row(0), "2026-11-20T10:00:00+08:00", "2026-12-10");
        assertThat(satisfied.delayBusinessDays()).isZero();
        assertThat(satisfied.candidateValueDate()).isEqualTo("2026-12-10");
    }

    @Test
    void exactlyAtCutoffIsNotBeforeCutoff() {
        var r = calculate(row(0), "2026-11-20T17:00:00+08:00", "2026-11-20");
        assertThat(r.currentBusinessDay()).isTrue();
        assertThat(r.beforeCutoff()).isFalse();
        assertThat(r.beforeCutoffToday()).isFalse();
        var justBefore = calculate(row(0), "2026-11-20T16:59:59+08:00", "2026-11-20");
        assertThat(justBefore.beforeCutoff()).isTrue();
        assertThat(justBefore.earliestValueDate()).isEqualTo("2026-11-20");
    }
}
