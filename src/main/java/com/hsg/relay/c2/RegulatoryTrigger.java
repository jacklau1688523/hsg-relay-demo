package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.service.ExpressionService;

import java.util.*;

/**
 * 监管规则的附加触发条件：按字段库构建的条件树，形态与 C1 附加条件相同（条件组 {logic, children}，叶子 {field, operator, value}）。
 * 交易落在规则范围（币种 + 受益行国家）内且满足附加条件时，规则才触发。
 */
final class RegulatoryTrigger {
    private static final Map<String, String> SYMBOL = Map.of("EQ", "=", "NE", "≠", "GT", ">", "GTE", "≥", "LT", "<", "LTE", "≤");

    /** matched：附加条件是否命中；expression：编译后的表达式（未能求值时为 null）；note：未能正常求值时的处理说明。 */
    record Outcome(boolean matched, String expression, String note) {
    }

    private RegulatoryTrigger() {
    }

    static ExpressionService.Node parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, ExpressionService.Node.class);
        } catch (Exception e) {
            throw new IllegalStateException("附加触发条件 JSON 不合法", e);
        }
    }

    /** 条件树摘要，如「(purpose = INVESTMENT 且 debitCurrency ≠ USD)」。 */
    static String text(ExpressionService.Node node) {
        if (node == null) return null;
        if (node.children() != null && !node.children().isEmpty())
            return "(" + String.join("OR".equals(node.logic()) ? " 或 " : " 且 ", node.children().stream().map(RegulatoryTrigger::text).toList()) + ")";
        String operator = "IN".equals(node.operator()) ? "属于" : SYMBOL.getOrDefault(node.operator(), String.valueOf(node.operator()));
        return node.field() + " " + operator + " " + node.value();
    }

    /** 按当前字段库校验（字段已启用、适用规则含 C2、运算符与条件值合法）。可执行返回 null，否则返回不能执行的原因。 */
    static String problem(ExpressionService.Node tree, ExpressionService expressions) {
        if (tree == null) return null;
        try {
            expressions.compile(tree, "C2");
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    /**
     * 用本次交易的取值求附加条件。缺值或失效时既不报错也不静默放过，都在 note 里说明：
     * 缺值按未触发处理（与 C1 附加条件、C2「未填写的可选字段按未命中处理」一致）；
     * 条件失效是已发布配置出错，按触发处理——不因配置失效放宽监管要求，退化成只按范围触发。
     */
    static Outcome evaluate(ExpressionService.Node tree, Map<String, Object> variables, ExpressionService expressions) {
        if (tree == null) return new Outcome(true, null, null);
        String problem = problem(tree, expressions);
        if (problem != null)
            return new Outcome(true, null, "附加触发条件已失效（" + problem + "），按触发处理；请在「监管路由规则」页核对后重新保存");
        List<String> missing = expressions.referencedFields(tree).stream()
                .filter(field -> variables.get(field) == null || variables.get(field).toString().isBlank()).toList();
        if (!missing.isEmpty())
            return new Outcome(false, null, "附加触发条件引用的字段 " + String.join("、", missing) + " 本次没有提供取值，按未触发处理");
        ExpressionService.PreviewResult result = expressions.preview(new ExpressionService.Preview(tree, variables, "C2"));
        return new Outcome(result.matched(), result.expression(), null);
    }
}
