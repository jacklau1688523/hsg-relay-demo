package com.hsg.relay.function;

import com.hsg.relay.function.RuleExecutionContext.Path;
import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.function.field.RuleCollection;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 函数参数解析：把条件节点的 arguments / 字段的 argumentBindings 解析成实参。
 * 取值说明有两种写法：
 * <ul>
 *   <li>字符串（旧版）：受控路径（$candidate.providerCode）或字面量（按参数类型校验）；</li>
 *   <li>对象（显式来源映射）：{"source":"FIELD","fieldCode":"providerExcludedClientIds"} 取元数据字段的解析结果，
 *       {"source":"TRANSACTION","fieldCode":"clientId"} 取 transaction.clientId——来源和字段都写明，不靠参数名恰好等于路径名。</li>
 * </ul>
 * 这里不做表达式求值，也不能借参数调用任何 Java 方法、SQL 或命令。
 */
@Component
public class FunctionArgumentResolver {
    /** 显式来源映射里可以用的来源；FUNCTION_OUTPUT（前置函数的返回值）要等 AST 支持函数嵌套，暂不开放。 */
    private static final Set<String> GROUPS = Set.of("CANDIDATE", "TRANSACTION", "CONTEXT");
    private static final Set<String> ANY = Set.of("CANDIDATE", "TRANSACTION", "CONTEXT", "FIELD");

    /** 一个参数的绑定：path 为受控取值路径（规范名，不带 $），literal 为字面量，fieldCode 为数据解析字段——三者必居其一。 */
    public record Binding(String parameter, String path, String literal, String fieldCode) {
        public Binding(String parameter, String path, String literal) {
            this(parameter, path, literal, null);
        }

        /** 绑定的文本形式：路径带 $，字段为字段编码。 */
        public String text() {
            return path != null ? "$" + path : fieldCode != null ? fieldCode : literal;
        }
    }

    private final FieldValueService fieldValues;

    public FunctionArgumentResolver(FieldValueService fieldValues) {
        this.fieldValues = fieldValues;
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
            bindings.put(parameter.code(), raw instanceof Map<?, ?> mapping ? mapped(owner, parameter, mapping, known, ruleCode)
                    : binding(owner, parameter, raw, known));
        }
        return bindings;
    }

    /** 参数允许的数据来源：声明 ANY 的四种来源都可以，其余只认声明的那一种。 */
    private static boolean accepts(BusinessFunction.Parameter parameter, String source) {
        return CollectionArguments.ANY.equals(parameter.source()) ? ANY.contains(source) : parameter.source().equals(source);
    }

    private Binding binding(String owner, BusinessFunction.Parameter parameter, Object raw, Map<String, Path> known) {
        String code = parameter.code();
        if (raw instanceof String text) {
            String name = text.trim();
            // 以 $ 开头的一律按路径处理（写错也不当成字面量）；不带 $ 但等于已登记路径的同样按路径处理
            String path = name.startsWith("$") ? name.substring(1) : known.containsKey(name) ? name : null;
            if (path != null) return path(owner, parameter, path, known);
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

    /** 显式来源映射：{"source":"FIELD","fieldCode":"..."} 或 {"source":"TRANSACTION","fieldCode":"clientId"}。 */
    private Binding mapped(String owner, BusinessFunction.Parameter parameter, Map<?, ?> mapping, Map<String, Path> known, String ruleCode) {
        String code = parameter.code();
        String source = text(mapping.get("source")).toUpperCase(Locale.ROOT);
        if (source.isEmpty())
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 的来源映射缺少 source（FIELD / CANDIDATE / TRANSACTION / CONTEXT）");
        if ("FUNCTION_OUTPUT".equals(source))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 的来源 FUNCTION_OUTPUT（前置函数的返回值）暂不支持，目前可用 FIELD / CANDIDATE / TRANSACTION / CONTEXT");
        if (!accepts(parameter, source))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 数据来源应为 " + parameter.source() + "，不能绑定 " + source);
        String target = text(mapping.get("fieldCode")).isEmpty() ? text(mapping.get("path")) : text(mapping.get("fieldCode"));
        if (target.isEmpty())
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 的来源映射缺少 fieldCode（来源 " + source + "）");
        if ("FIELD".equals(source)) {
            // 字段须是可用于该规则的数据解析字段，类型与参数一致（不一致时这里直接说明原因）
            fieldValues.check(target, parameter.type(), ruleCode);
            return new Binding(code, null, null, target);
        }
        return path(owner, parameter, source.toLowerCase(Locale.ROOT) + "." + target, known);
    }

    /** 受控路径的校验：路径已登记，类型和数据来源都与参数一致。 */
    private Binding path(String owner, BusinessFunction.Parameter parameter, String path, Map<String, Path> known) {
        String code = parameter.code();
        Path target = known.get(path);
        if (target == null)
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 引用了不受控的取值路径：" + path + "，只能使用已登记的 candidate.* / transaction.* / context.* 路径");
        if (!target.type().equals(parameter.type()))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 类型应为 " + parameter.type() + "，不能绑定 " + path + "（" + target.type() + "）");
        if (!accepts(parameter, target.group().name()))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 数据来源应为 " + parameter.source() + "，不能绑定 " + path + "（" + target.group() + "）");
        return new Binding(code, path, null);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
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
                // 集合只能来自字段解析器：不接受手写的集合字面量
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
            Object value;
            if (binding.fieldCode() != null) value = fieldValue(owner, parameter, binding, context);
            else value = binding.path() != null ? context.value(binding.path()) : binding.literal();
            arguments.put(parameter.code(), convert(owner, parameter, value, binding));
        }
        return arguments;
    }

    /** FIELD 实参取数：解析器读失败时按「执行失败」报出（带函数、参数、字段和原因），不返回 null 或空集合。 */
    private Object fieldValue(String owner, BusinessFunction.Parameter parameter, Binding binding, RuleExecutionContext context) {
        try {
            return fieldValues.value(binding.fieldCode(), context);
        } catch (FunctionExecutionService.FunctionExecutionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            throw new FunctionExecutionService.FunctionExecutionException(owner + " 执行失败：参数 " + parameter.code() + " 的字段 "
                    + binding.fieldCode() + " 取数失败：" + reason, ex);
        }
    }

    private Object convert(String owner, BusinessFunction.Parameter parameter, Object value, Binding binding) {
        String code = parameter.code();
        if (value == null || (value instanceof String text && text.isBlank()))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 取值为空" + where(binding));
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
                case "STRING_SET" -> value instanceof RuleCollection collection ? collection : null;
                default -> null;
            };
        } catch (ArithmeticException ex) {
            typed = null;
        }
        if (typed == null)
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 取值类型应为 " + parameter.type() + "：" + value + where(binding));
        return typed;
    }

    private static String where(Binding binding) {
        return binding.path() != null ? "（" + binding.path() + "）" : binding.fieldCode() != null ? "（字段 " + binding.fieldCode() + "）" : "";
    }
}
