package com.hsg.relay.function;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 字段取值：普通字段取请求提交的取值并按字段类型转换；绑定了函数的字段（valueSource = FUNCTION）由函数计算
 * （见 ExpressionService / FunctionExecutionService），数据解析字段（valueSource = DATA_RESOLVER）由字段解析器取数
 * （见 FieldValueService）；后两种不采用请求里提交的同名字段取值。
 */
@Component
public class FieldResolver {
    private static final TypeReference<Map<String, Object>> BINDINGS = new TypeReference<>() {
    };

    private final ObjectMapper json;

    public FieldResolver(ObjectMapper json) {
        this.json = json;
    }

    /** valueSource 为空（升级前的旧数据）按请求输入处理。 */
    public static String valueSource(FieldDefinition field) {
        return field != null && "FUNCTION".equals(field.valueSource) ? "FUNCTION" : "REQUEST";
    }

    public static boolean isFunctionBound(FieldDefinition field) {
        return "FUNCTION".equals(valueSource(field)) && field.functionCode != null && !field.functionCode.isBlank();
    }

    /** 取值来源为数据解析（DATA_RESOLVER）：由字段元数据的 resolverCode 对应的解析器在规则执行中取数，请求里提交的同名取值同样不采用。 */
    public static boolean isDataResolved(FieldDefinition field) {
        return field != null && "DATA_RESOLVER".equals(field.valueSource);
    }

    /** 字段上配置的参数绑定（参数编码 → 受控路径 "$candidate.providerCode" 或字面量）。 */
    public Map<String, Object> bindings(FieldDefinition field) {
        if (field.argumentBindings == null || field.argumentBindings.isBlank()) return Map.of();
        try {
            return json.readValue(field.argumentBindings, BINDINGS);
        } catch (Exception ex) {
            throw new IllegalArgumentException("字段 " + field.fieldCode + " 的参数绑定无法解析，请在元数据中心重新配置");
        }
    }

    /** 页面控件提交的取值都是字符串（"7"、"true"）：数字和布尔字段先按字段类型转换，否则 Aviator 比较会报错，或 "true" == true 静默不命中；空白按未提供处理。 */
    public Object variable(FieldDefinition field, Object raw) {
        Object value = raw;
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) return null;
        if ("DECIMAL".equals(field.dataType) || "INTEGER".equals(field.dataType)) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("字段 " + field.fieldCode + " 的取值不是有效数字：" + text);
            }
        }
        if ("BOOLEAN".equals(field.dataType)) {
            if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text))
                throw new IllegalArgumentException("字段 " + field.fieldCode + " 的取值只能是 true 或 false：" + text);
            return "true".equalsIgnoreCase(text);
        }
        return value;
    }
}
