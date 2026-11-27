package com.hsg.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * C1 路径配置的测试基线。全新数据库启动后只有一份空白草稿、没有已发布版本，C1 对每个候选合作方都返回 C1_CONFIG_MISSING；
 * 只关心准入规则、函数或路由的测试先调用 {@link #publishDefaultOnly}：发布一份只有 DEFAULT 路径的配置，
 * 路径条件（flowCategory != NONE）对任何客户都成立，C1 的结果完全由必检和准入规则决定。
 */
public final class C1PathFixtures {
    public static final String OPERATOR = "test.fixture";

    private C1PathFixtures() {
    }

    /** 从未发布过时发布基线（已有任何已发布版本则不动）。 */
    public static void publishDefaultOnly(C1PathConfigService pathConfig, ObjectMapper json) {
        if (pathConfig.latestPublishedRule() != null) return;
        pathConfig.saveDraft(json.valueToTree(defaultOnly()), null, OPERATOR);
        pathConfig.publish(new C1PathConfigService.PublishRequest(null, OPERATOR, "测试基线：仅 DEFAULT 路径"));
    }

    /** 只有 DEFAULT 路径、条件恒成立的配置。 */
    public static C1PathRule defaultOnly() {
        return new C1PathRule(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE, List.of(
                path(C1PathRule.DEFAULT_PATH, "默认路径", List.of(), 0, true, group("AND", field("flowCategory", "NE", "NONE")))));
    }

    /** 一条路径；失败原因码为空时由规范化补成 C1_PATH_编码_NOT_MATCHED。 */
    public static C1PathRule.Path path(String code, String name, List<String> providers, int priority, boolean enabled,
                                       ExpressionService.Node tree) {
        return new C1PathRule.Path(code, name, providers, priority, enabled, tree, null, null);
    }

    public static ExpressionService.Node field(String code, String operator, Object value) {
        return new ExpressionService.Node(null, null, code, operator, value);
    }

    public static ExpressionService.Node group(String logic, ExpressionService.Node... children) {
        return new ExpressionService.Node(logic, List.of(children), null, null, null);
    }

    public static ExpressionService.Node function(String code, Map<String, Object> arguments, String operator, Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, operator, value, code, arguments);
    }

    /** 客户排除函数节点的入参：与执行器必检使用的入参相同。 */
    public static final Map<String, Object> EXCLUSION_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");
}
