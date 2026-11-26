package com.hsg.relay.routing;

/**
 * C3 最终选中的覆盖行，原样交给 C5、C6 等后续规则：行号（路由配置里的下标）、覆盖行本身、C3 条件版本、路由配置版本。
 * <p>C5 只能使用这份匹配结果，不重新挑选覆盖行；C6 用同一份，保证截单时间与限额出自同一条覆盖行。
 */
public record ReachMatch(int rowIndex, RoutingConfig.Reach reach, int c3ConditionVersion, long configRevision) {
    /** 匹配结果在求值上下文（请求级数据缓存）里的键，calculateValueDate 从这里读取覆盖行及其日历。 */
    public static final String CONTEXT_KEY = "C5_REACH_MATCH";

    public ReachMatch {
        if (rowIndex < 0 || reach == null) throw new IllegalArgumentException("覆盖行匹配结果无效");
    }

    public static ReachMatch of(C3Matcher.Row row, int c3ConditionVersion, long configRevision) {
        return new ReachMatch(row.index(), row.reach(), c3ConditionVersion, configRevision);
    }
}
