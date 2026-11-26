package com.hsg.relay.routing;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.hsg.relay.service.ExpressionService;

import java.util.List;

/**
 * 评分规则配置（ruleType = SCORE）。持久化 JSON 就是这个结构，字段名与接口一致。
 * <p>评分组的条件 condition 直接复用条件树的 {@link ExpressionService.Node}（FIELD / FUNCTION / GROUP + AND/OR），不另建一套 AST。
 * group.operator 沿用字段库里已有的运算符编码（EQ / NE / GT / GTE / LT / LTE），不另造 GE / LE。
 */
public record ScoreRule(String ruleCode, String ruleType, String matchMode, ScoreRange scoreRange,
                        List<Group> groups, Boolean enabled) {
    @JsonCreator
    public ScoreRule {
        if (ruleCode == null) ruleCode = "C5";
        if (ruleType == null) ruleType = "SCORE";
        if (matchMode == null) matchMode = "FIRST_MATCH";
        if (scoreRange == null) scoreRange = new ScoreRange(0, 100);
        if (enabled == null) enabled = Boolean.TRUE;
        groups = groups == null ? List.of() : groups;
    }

    /** 分数合法范围（含两端）；C5 固定 0–100。 */
    public record ScoreRange(Integer min, Integer max) {
    }

    /**
     * 一个根评分组：按 priority 升序逐个判断 condition，首个命中的组给出 score。
     * version 是该组所在发布版本的版本号（发布时写入；草稿里为空）。
     */
    public record Group(String groupCode, String name, Integer priority, ExpressionService.Node condition,
                        Result result, Boolean enabled, Integer version) {
        @JsonCreator
        public Group {
            if (enabled == null) enabled = Boolean.TRUE;
        }
    }

    /** 命中后的返回。目前只有 SCORE（给出分数）。 */
    public record Result(String type, Integer score) {
    }
}
