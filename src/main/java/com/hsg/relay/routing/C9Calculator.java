package com.hsg.relay.routing;

import java.math.*;
import java.time.*;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.Agreement;

/**
 * C9 is a score, never a filtering decision. 100 = behind pace (needs volume), 0 = target met or no target.
 */
public final class C9Calculator {
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");
    private static final MathContext MC = MathContext.DECIMAL128;

    private C9Calculator() {
    }

    /**
     * outcome: NO_TARGET 无月度目标；TARGET_MET 已达标；BEHIND_PACE 进度落后；ON_PACE 进度正常。
     */
    public record Item(String metric, BigDecimal monthlyTarget, BigDecimal mtd, BigDecimal expectedPace,
                       BigDecimal targetPercent, String outcome, int score) {
    }

    public record Result(ZonedDateTime evaluatedAt, int dayOfMonth, int daysInMonth, BigDecimal timePercent,
                         boolean hasTarget, List<Item> items, int score, String outcome) {
    }

    public static void validate(String metric, BigDecimal target) {
        if (metric == null || !Set.of("AMOUNT", "COUNT").contains(metric))
            throw new IllegalArgumentException("履约指标应为 AMOUNT 或 COUNT");
        if (target == null || target.signum() < 0) throw new IllegalArgumentException("月度目标必须非负");
    }

    public static BigDecimal expectedPace(BigDecimal target, LocalDate date) {
        return target.multiply(BigDecimal.valueOf(date.getDayOfMonth()), MC).divide(BigDecimal.valueOf(date.lengthOfMonth()), MC);
    }

    private static Item item(Agreement a, BigDecimal monthUsd, long monthCount, LocalDate date) {
        BigDecimal mtd = "COUNT".equals(a.metric()) ? BigDecimal.valueOf(monthCount) : monthUsd;
        if (a.monthlyTarget().signum() == 0)
            return new Item(a.metric(), a.monthlyTarget(), mtd, null, null, "NO_TARGET", 0);
        if (mtd.compareTo(a.monthlyTarget()) >= 0) return new Item(a.metric(), a.monthlyTarget(), mtd,
                expectedPace(a.monthlyTarget(), date).setScale(2, RoundingMode.HALF_UP), mtd.multiply(BigDecimal.valueOf(100), MC).divide(a.monthlyTarget(), MC).setScale(1, RoundingMode.HALF_UP), "TARGET_MET", 0);
        BigDecimal pace = expectedPace(a.monthlyTarget(), date);
        boolean behind = mtd.compareTo(pace) < 0;
        return new Item(a.metric(), a.monthlyTarget(), mtd, pace.setScale(2, RoundingMode.HALF_UP),
                mtd.multiply(BigDecimal.valueOf(100), MC).divide(a.monthlyTarget(), MC).setScale(1, RoundingMode.HALF_UP), behind ? "BEHIND_PACE" : "ON_PACE", behind ? 100 : 50);
    }

    public static Result calculate(List<Agreement> agreements, BigDecimal monthUsd, long monthCount, ZonedDateTime at) {
        if (agreements == null || monthUsd == null) throw new IllegalArgumentException("协议列表及月累计金额必填");
        if (monthCount < 0) throw new IllegalArgumentException("月累计笔数不可为负");
        LocalDate date = (at == null ? ZonedDateTime.now(HK) : at).withZoneSameInstant(HK).toLocalDate();
        List<Item> items = agreements.stream().map(a -> item(a, monthUsd == null ? BigDecimal.ZERO : monthUsd, monthCount, date)).toList();
        boolean hasTarget = agreements.stream().anyMatch(a -> a.monthlyTarget().signum() > 0);
        int score = items.stream().mapToInt(Item::score).max().orElse(0);
        String outcome = items.stream().max(Comparator.comparingInt(Item::score)).map(Item::outcome).orElse("NO_TARGET");
        return new Result(at == null ? ZonedDateTime.now(HK) : at.withZoneSameInstant(HK), date.getDayOfMonth(), date.lengthOfMonth(),
                BigDecimal.valueOf(date.getDayOfMonth()).multiply(BigDecimal.valueOf(100), MC).divide(BigDecimal.valueOf(date.lengthOfMonth()), MC).setScale(1, RoundingMode.HALF_UP),
                hasTarget, items, score, outcome);
    }

    public static int score(List<Agreement> agreements, BigDecimal monthUsd, long monthCount, ZonedDateTime at) {
        return calculate(agreements, monthUsd, monthCount, at).score();
    }
}
