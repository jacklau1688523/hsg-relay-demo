package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.function.RuleExecutionContext;
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
        if (node.isFunction()) return functionText(node);
        if (node.children() != null && !node.children().isEmpty())
            return "(" + String.join("OR".equals(node.logic()) ? " 或 " : " 且 ", node.children().stream().map(RegulatoryTrigger::text).toList()) + ")";
        String operator = "IN".equals(node.operator()) ? "属于" : SYMBOL.getOrDefault(node.operator(), String.valueOf(node.operator()));
        return node.field() + " " + operator + " " + node.value();
    }

    /** 函数节点的摘要：函数名（参数=取值来源，…），带运算符和期望值时一并给出。只写来源，不写运行期取值。 */
    private static String functionText(ExpressionService.Node node) {
        Map<String, Object> arguments = node.arguments() == null ? Map.of() : node.arguments();
        String call = arguments.entrySet().stream().map(e -> e.getKey() + "=" + sourceText(e.getValue()))
                .collect(java.util.stream.Collectors.joining(", "));
        call = node.functionCode() + "(" + call + ")";
        if (node.operator() == null && node.value() == null) return call;
        return call + " " + SYMBOL.getOrDefault(node.operator(), String.valueOf(node.operator())) + " " + node.value();
    }

    /** 参数的取值来源摘要：来源 + 绑定的字段 / 节点 / 路径；常量写值；谓词写「条件」。 */
    private static String sourceText(Object argument) {
        if (!(argument instanceof Map<?, ?> map) || map.get("source") == null) return String.valueOf(argument);
        String source = map.get("source").toString();
        if ("PREDICATE".equals(source)) return "条件";
        Object target = "CONSTANT".equals(source) ? map.get("value")
                : map.containsKey("nodeId") ? map.get("nodeId")
                : map.containsKey("fieldCode") ? map.get("fieldCode") : map.get("path");
        return target == null ? source : source + ":" + target;
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
     * 用本次交易的取值求附加条件（不带执行上下文：条件树里不能有函数节点和数据解析字段）。
     * 缺值或失效时既不报错也不静默放过，都在 note 里说明：
     * 缺值按未触发处理（与 C1 附加条件、C2「未填写的可选字段按未命中处理」一致）；
     * 条件失效是已发布配置出错，按触发处理——不因配置失效放宽监管要求，退化成只按范围触发。
     */
    static Outcome evaluate(ExpressionService.Node tree, Map<String, Object> variables, ExpressionService expressions) {
        return evaluate(tree, variables, expressions, null);
    }

    /**
     * 同上，带本次判定的执行上下文。
     * <p>条件树里没有函数节点、数据解析字段时，走原来的求值路径，结果与说明不变。
     * 引用了函数节点或数据解析字段（如合作方限制集合、监管规则要求的能力标签）时按执行上下文求值：
     * 取值没有加载、解析器读取失败、函数执行失败都「按触发处理」并在 note 里说明——数据不完整不能放宽监管要求
     * （与「条件失效按触发处理」一致；与普通字段「缺值按未触发」不同）。C2 全局附加条件共用这段逻辑：带 note 的结果不算通过。
     */
    static Outcome evaluate(ExpressionService.Node tree, Map<String, Object> variables, ExpressionService expressions,
                            RuleExecutionContext context) {
        if (tree == null) return new Outcome(true, null, null);
        ExpressionService.Compiled compiled;
        try {
            compiled = expressions.compileDetailed(tree, "C2");
        } catch (IllegalArgumentException ex) {
            return new Outcome(true, null, "附加触发条件已失效（" + ex.getMessage() + "），按触发处理；请在「监管路由规则」页核对后重新保存");
        }
        if (compiled.needsContext()) return evaluateWithContext(compiled, variables, expressions, context);
        List<String> missing = expressions.referencedFields(tree).stream()
                .filter(field -> variables.get(field) == null || variables.get(field).toString().isBlank()).toList();
        if (!missing.isEmpty())
            return new Outcome(false, null, "附加触发条件引用的字段 " + String.join("、", missing) + " 本次没有提供取值，按未触发处理");
        ExpressionService.PreviewResult result = expressions.preview(new ExpressionService.Preview(tree, variables, "C2"));
        return new Outcome(result.matched(), result.expression(), null);
    }

    /** 含函数节点 / 数据解析字段的条件树：按执行上下文求值，失败按触发处理并说明原因。 */
    private static Outcome evaluateWithContext(ExpressionService.Compiled compiled, Map<String, Object> variables,
                                               ExpressionService expressions, RuleExecutionContext context) {
        if (context == null)
            return new Outcome(true, null, "附加触发条件引用了业务函数或数据解析字段，本次求值没有执行上下文，按触发处理");
        try {
            return new Outcome(expressions.evaluate(compiled, variables, context).matched(), compiled.readable(), null);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Outcome(true, null, "附加触发条件执行失败（" + reason + "），按触发处理；请核对函数参数引用的数据是否可用");
        }
    }
}
