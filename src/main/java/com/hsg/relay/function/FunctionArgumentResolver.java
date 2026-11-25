package com.hsg.relay.function;

import com.hsg.relay.function.RuleExecutionContext.Path;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 函数参数解析：把条件节点的 arguments / 字段的 argumentBindings 解析成实参。
 * 取值说明只有两种——受控路径（$candidate.providerCode）或字面量（按参数类型校验）；
 * 这里不做表达式求值，也不能借参数调用任何 Java 方法、SQL 或命令。
 */
@Component
public class FunctionArgumentResolver {
    /** 一个参数的绑定：path 为受控取值路径（规范名，不带 $），或 literal 为字面量，二者必居其一。 */
    public record Binding(String parameter, String path, String literal) {
        public String text() {
            return path != null ? "$" + path : literal;
        }
    }

    /**
     * 校验并整理绑定表（按 Handler 的参数顺序）。缺少必填参数、未知参数、不受控的路径、类型或数据来源不匹配都报参数错误。
     * 没有绑定、但声明了默认路径的可选参数补上默认路径。owner 用于报错时指明是哪个函数节点 / 字段。
     */
    public Map<String, Binding> toBindings(String owner, BusinessFunction handler, Map<String, Object> arguments, String ruleCode) {
        Map<String, Object> supplied = arguments == null ? Map.of() : arguments;
        Map<String, Path> known = new LinkedHashMap<>();
        for (Path path : RuleExecutionContext.paths(ruleCode)) known.put(path.name(), path);
        for (String key : supplied.keySet())
            if (handler.parameters().stream().noneMatch(p -> p.code().equals(key)))
                throw new IllegalArgumentException(owner + " 不支持参数：" + key);
        Map<String, Binding> bindings = new LinkedHashMap<>();
        for (BusinessFunction.Parameter parameter : handler.parameters()) {
            Object raw = supplied.get(parameter.code());
            if (raw == null || (raw instanceof String text && text.isBlank())) {
                if (parameter.defaultPath() != null) {
                    if (!known.containsKey(parameter.defaultPath()))
                        throw new IllegalStateException(owner + " 的默认取值路径未登记：" + parameter.defaultPath());
                    bindings.put(parameter.code(), new Binding(parameter.code(), parameter.defaultPath(), null));
                } else if (parameter.required())
                    throw new IllegalArgumentException(owner + " 缺少必填参数：" + parameter.code());
                continue;
            }
            bindings.put(parameter.code(), binding(owner, parameter, raw, known));
        }
        return bindings;
    }

    private Binding binding(String owner, BusinessFunction.Parameter parameter, Object raw, Map<String, Path> known) {
        String code = parameter.code();
        if (raw instanceof String text) {
            String name = text.trim();
            // 以 $ 开头的一律按路径处理（写错也不当成字面量）；不带 $ 但等于已登记路径的同样按路径处理
            String path = name.startsWith("$") ? name.substring(1) : known.containsKey(name) ? name : null;
            if (path != null) {
                Path target = known.get(path);
                if (target == null)
                    throw new IllegalArgumentException(owner + " 的参数 " + code + " 引用了不受控的取值路径：" + path + "，只能使用已登记的 candidate.* / transaction.* / context.* 路径");
                if (!target.type().equals(parameter.type()))
                    throw new IllegalArgumentException(owner + " 的参数 " + code + " 类型应为 " + parameter.type() + "，不能绑定 " + path + "（" + target.type() + "）");
                if (!target.group().name().equals(parameter.source()))
                    throw new IllegalArgumentException(owner + " 的参数 " + code + " 数据来源应为 " + parameter.source() + "，不能绑定 " + path + "（" + target.group() + "）");
                return new Binding(code, path, null);
            }
            checkLiteral(owner, parameter, text);
            return new Binding(code, null, text);
        }
        // 非字符串只可能是数字 / 布尔字面量
        if (raw instanceof Boolean || raw instanceof Number) {
            checkLiteral(owner, parameter, raw.toString());
            return new Binding(code, null, raw.toString());
        }
        throw new IllegalArgumentException(owner + " 的参数 " + code + " 取值说明无效：" + raw);
    }

    private void checkLiteral(String owner, BusinessFunction.Parameter parameter, String text) {
        String type = parameter.type();
        try {
            switch (type) {
                case "STRING" -> {
                    if (text.isBlank()) throw new NumberFormatException();
                }
                case "INTEGER" -> new BigDecimal(text).longValueExact();
                case "DECIMAL" -> new BigDecimal(text);
                case "BOOLEAN" -> {
                    if (!"true".equals(text) && !"false".equals(text)) throw new NumberFormatException();
                }
                case "DATE" -> LocalDateTime.parse(text);
                default -> throw new NumberFormatException();
            }
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException ex) {
            throw new IllegalArgumentException(owner + " 的参数 " + parameter.code() + " 类型应为 " + type + "，字面量无效：" + text);
        }
    }

    /** 运行时按绑定表取值并做类型检查；路径取值为空时报错，不使用默认值顶替。 */
    public Map<String, Object> resolve(String owner, BusinessFunction handler, Map<String, Binding> bindings, RuleExecutionContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (BusinessFunction.Parameter parameter : handler.parameters()) {
            Binding binding = bindings.get(parameter.code());
            if (binding == null) continue;
            Object value = binding.path() != null ? context.value(binding.path()) : binding.literal();
            arguments.put(parameter.code(), convert(owner, parameter, value, binding.path()));
        }
        return arguments;
    }

    private Object convert(String owner, BusinessFunction.Parameter parameter, Object value, String path) {
        String code = parameter.code();
        if (value == null || (value instanceof String text && text.isBlank()))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 取值为空" + (path != null ? "（" + path + "）" : ""));
        Object typed;
        try {
            typed = switch (parameter.type()) {
                case "STRING" -> value instanceof String text ? text : null;
                case "INTEGER" -> value instanceof BigDecimal decimal ? decimal.longValueExact()
                        : value instanceof Integer integer ? integer.longValue()
                        : value instanceof Long whole ? whole : null;
                case "DECIMAL" -> value instanceof BigDecimal decimal ? decimal
                        : value instanceof Integer integer ? BigDecimal.valueOf(integer)
                        : value instanceof Long whole ? BigDecimal.valueOf(whole) : null;
                case "BOOLEAN" -> value instanceof Boolean flag ? flag : null;
                case "DATE" -> value instanceof LocalDateTime time ? time
                        : value instanceof LocalDate date ? date.atStartOfDay() : null;
                default -> null;
            };
        } catch (ArithmeticException ex) {
            typed = null;
        }
        if (typed == null)
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 取值类型应为 " + parameter.type() + "：" + value);
        return typed;
    }
}
