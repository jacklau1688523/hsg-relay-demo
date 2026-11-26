package com.hsg.relay.routing;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.Reach;

/**
 * C5 起息日计算：根据覆盖行的截单时间、工作日偏移和节假日日历，算出最早可实现起息日、候选起息日和延迟工作日数。
 * <p>只做日期计算，不给分数：C5 分数来自已发布的评分规则（见 {@link C5ScoreService}）；C5 是打分项，从不剔除候选。
 * 日期计算由 {@link CalculateValueDateFunction}（calculateValueDate）包装成业务函数，每个候选路由在评分前只执行一次。
 */
public final class C5Calculator {
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");

    private C5Calculator() {
    }

    public record Result(ZonedDateTime evaluatedAt, boolean currentBusinessDay, boolean beforeCutoff,
                         String rollReason, LocalDate baseBusinessDate, LocalDate earliestValueDate,
                         LocalDate requestedValueDate,
                         LocalDate candidateValueDate, long delayBusinessDays) {

        /** 是否「今日为工作日且早于截单」：是则最早起息日 = 今日 + 偏移，否则 = 下一工作日 + 偏移。 */
        public boolean beforeCutoffToday() {
            return currentBusinessDay && beforeCutoff;
        }
    }

    public static void validate(String zone, LocalTime cutoff, Integer offset, List<LocalDate> holidays) {
        if (zone == null || zone.isBlank() || cutoff == null || offset == null || holidays == null)
            throw new IllegalArgumentException("时区、截单时间、起息偏移及节假日列表必填");
        try {
            ZoneId.of(zone);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("截单时区无效");
        }
        if (offset < 0 || offset > 5) throw new IllegalArgumentException("起息日偏移必须为0–5个工作日");
        if (holidays.stream().anyMatch(Objects::isNull) || new HashSet<>(holidays).size() != holidays.size())
            throw new IllegalArgumentException("节假日不可为空或重复");
    }

    private static boolean business(LocalDate date, Set<LocalDate> holidays) {
        return date.getDayOfWeek().getValue() <= 5 && !holidays.contains(date);
    }

    private static LocalDate addBusiness(LocalDate date, int days, Set<LocalDate> holidays) {
        while (days > 0) {
            date = date.plusDays(1);
            if (business(date, holidays)) days--;
        }
        return date;
    }

    /**
     * start 之后到 end（含）之间的工作日数，周末和节假日不计。start 本身不计入：
     * 请求起息日落在非工作日时，从它之后的第一个工作日起数（例：周六请求、周二是最早起息日 → 延迟 2 个工作日：周一、周二）。
     */
    static long businessDaysBetween(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        if (!end.isAfter(start)) return 0;
        long days = ChronoUnit.DAYS.between(start, end), total = (days / 7) * 5;
        for (int i = 1; i <= days % 7; i++) if (start.plusDays(i).getDayOfWeek().getValue() <= 5) total++;
        return total - holidays.stream().filter(d -> d.isAfter(start) && !d.isAfter(end) && d.getDayOfWeek().getValue() <= 5).count();
    }

    public static Result calculate(Reach reach, ZonedDateTime at, LocalDate requested) {
        validate(reach.zone(), reach.cutoff(), reach.offsetDays(), reach.holidays());
        if (at == null) at = ZonedDateTime.now(HK);
        if (requested == null) requested = at.withZoneSameInstant(HK).toLocalDate();
        ZonedDateTime local = at.withZoneSameInstant(ZoneId.of(reach.zone()));
        Set<LocalDate> holidays = new HashSet<>(reach.holidays());
        boolean working = business(local.toLocalDate(), holidays), before = local.toLocalTime().isBefore(reach.cutoff());
        LocalDate base = local.toLocalDate();
        String reason = "BEFORE_CUTOFF";
        if (!working || !before) {
            base = addBusiness(base, 1, holidays);
            reason = !working ? "NOT_BUSINESS_DAY" : "AT_OR_AFTER_CUTOFF";
        }
        LocalDate earliest = addBusiness(base, reach.offsetDays(), holidays);
        // 候选起息日 = max(请求起息日, 最早可实现起息日)；延迟按适用日历里的工作日数计（请求日早于最早日时才有延迟）
        long delay = businessDaysBetween(requested, earliest, holidays);
        return new Result(local, working, before, reason, base, earliest, requested, earliest.isAfter(requested) ? earliest : requested, delay);
    }
}
