package com.hsg.relay.function;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 受控的通用封顶函数 numberMin（取较小值）：返回 value 与 cap 中的较小者，全程 BigDecimal，不做其它运算。
 * 用于把函数输出限制在封顶值以内（如 C8 偏好加分合计封顶 100），替代在规则里写 Aviator 表达式。只做计算，不查询业务表。
 */
@Component
public class NumberMinFunction implements BusinessFunction {
    public static final String CODE = "numberMin";

    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("value", "DECIMAL", CollectionArguments.ANY, true, null, "数值"),
            new Parameter("cap", "DECIMAL", CollectionArguments.ANY, true, null, "上限"));

    @Override
    public String handlerCode() {
        return CODE;
    }

    @Override
    public String returnType() {
        return "DECIMAL";
    }

    @Override
    public String dataSource() {
        return "ARGUMENT";
    }

    @Override
    public List<Parameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        String owner = "函数 " + CODE;
        BigDecimal value = decimal(owner, "value", arguments.get("value")), cap = decimal(owner, "cap", arguments.get("cap"));
        return Outcome.of(value.compareTo(cap) <= 0 ? value : cap);
    }

    private static BigDecimal decimal(String owner, String code, Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Long whole) return BigDecimal.valueOf(whole);
        if (value instanceof Integer whole) return BigDecimal.valueOf(whole);
        throw new IllegalArgumentException(owner + " 的参数 " + code + " 应为数值（DECIMAL），实际为"
                + (value == null ? "空（没有取值或前置函数没有输出）" : value.getClass().getSimpleName() + " " + value));
    }
}
