package com.hsg.relay.routing;

import java.util.List;
import java.util.Map;

/**
 * 一次评分的结果。status 说明是否真的得到了分数：
 * SCORED 命中评分组；NO_REACH 没有可用的 C3 覆盖行；NOT_PUBLISHED 没有已发布（或在决策时间生效）的规则版本；
 * NOT_MATCHED 没有任何评分组命中（配置异常）；RULE_DISABLED 规则已停用；ERROR 日期计算或条件求值出错。
 * 不是 SCORED 时 score 为 null——不伪造 0 分或 100 分，由调用方按「评分不可计算」处理。
 */
public record ScoreResult(String ruleCode, String status, Integer score, String matchedGroupCode, Integer ruleVersion,
                          List<GroupOutcome> groups, String message, Map<String, Object> details) {

    /** 每个评分组的判断结果（按优先级顺序）：matched 为 true 的是命中组；命中之后的组不再判断（note 说明）。 */
    public record GroupOutcome(String groupCode, String name, int priority, Integer score, Boolean matched,
                               String expression, String note) {
    }

    public boolean scored() {
        return "SCORED".equals(status) && score != null;
    }
}
