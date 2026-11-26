package com.hsg.relay.function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.ElementPredicate.Operand;
import com.hsg.relay.function.ElementPredicate.OperandKind;
import com.hsg.relay.function.RuleExecutionContext.Path;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.function.field.RuleList;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 函数参数解析：把条件节点的 arguments / 字段的 argumentBindings 解析成实参。
 * 取值说明有两种写法：
 * <ul>
 *   <li>字符串（旧版）：受控路径（$candidate.providerCode）或字面量（按参数类型校验）；</li>
 *   <li>对象（显式来源映射）：来源 source 与取值目标都写明，不靠参数名恰好等于路径名。
 *       <pre>
 *         {"source":"FIELD","fieldCode":"providerExcludedClientIds"}                       元数据字段（数据解析器取数）
 *         {"source":"TRANSACTION","fieldCode":"clientId"}                                   受控路径 transaction.clientId（CANDIDATE / CONTEXT / RULE_ROW 同理）
 *         {"source":"CONSTANT","value":"HK"}                                                常量（按参数类型校验）
 *         {"source":"FUNCTION_OUTPUT","nodeId":"node-001"}                                  前置函数节点的输出（只能在条件树里用）
 *         {"source":"PREDICATE","condition":{...}}                                           元素谓词条件树（见 ElementPredicate）
 *       </pre>
 *   </li>
 * </ul>
 * 这里不做表达式求值，也不能借参数调用任何 Java 方法、SQL 或命令。
 */
@Component
public class FunctionArgumentResolver {
    /** 来源映射里代表受控路径的分组。 */
    private static final Set<String> GROUPS = Set.of("CANDIDATE", "TRANSACTION", "CONTEXT", "RULE_ROW");
    /** 声明 ANY 的参数可以绑定的来源：四类受控路径、数据字段、常量；FUNCTION_OUTPUT 只能在条件树里（有前置节点时）使用。 */
    private static final Set<String> ANY = Set.of("CANDIDATE", "TRANSACTION", "CONTEXT", "FIELD", "CONSTANT");
    /** 依赖前置节点的来源：单独校验一个函数时没有前置节点，只有条件树编译会提供。 */
    private static final Set<String> CHAINED = Set.of("FUNCTION_OUTPUT");

    public static final String CONSTANT = "CONSTANT", FUNCTION_OUTPUT = "FUNCTION_OUTPUT", PREDICATE = "PREDICATE",
            CURRENT_ELEMENT = "CURRENT_ELEMENT", RULE_ROW = "RULE_ROW";

    /** 条件树里全部函数节点的视图：按节点 id 找到前置函数（校验、循环引用在这里报错），由 ExpressionService 编译时提供。 */
    public interface NodeContext {
        /** 前置函数节点的校验结果；节点不存在或形成循环时抛参数错误。 */
        FunctionExecutionService.Validated producer(String nodeId);
    }

    /**
     * 一个参数的绑定：path 为受控取值路径（规范名，不带 $），literal 为旧版字面量，fieldCode 为数据解析字段；
     * source 为显式来源（FIELD / CANDIDATE / TRANSACTION / CONTEXT / RULE_ROW / CONSTANT / FUNCTION_OUTPUT / PREDICATE），
     * constant 为已校验的常量，outputNodeId 为前置函数节点 id，predicate 为已编译的元素谓词。旧版绑定 source 为空。
     */
    public record Binding(String parameter, String path, String literal, String fieldCode, String source, String outputNodeId,
                          Object constant, ElementPredicate predicate) {
        public Binding(String parameter, String path, String literal, String fieldCode) {
            this(parameter, path, literal, fieldCode, null, null, null, null);
        }

        public Binding(String parameter, String path, String literal) {
            this(parameter, path, literal, null);
        }

        /** 引用的是前置函数节点的输出。 */
        public boolean isOutput() {
            return outputNodeId != null;
        }

        /** 引用的是数据解析字段。 */
        public boolean isField() {
            return fieldCode != null && outputNodeId == null;
        }

        /** 绑定的文本形式：路径带 $，字段为字段编码，常量为取值本身，前置函数为「← 节点」。 */
        public String text() {
            if (outputNodeId != null) return "←" + outputNodeId;
            if (predicate != null) return predicate.text();
            if (constant != null) return String.valueOf(constant);
            return path != null ? "$" + path : fieldCode != null ? fieldCode : literal;
        }
    }

    private final FieldValueService fieldValues;
    private final ObjectMapper json;

    public FunctionArgumentResolver(FieldValueService fieldValues, ObjectMapper json) {
        this.fieldValues = fieldValues;
        this.json = json;
    }

    ObjectMapper json() {
        return json;
    }

    /**
     * 校验并整理绑定表（按 Handler 的参数顺序）。缺少必填参数、未知参数、不受控的路径、类型或数据来源不匹配都报参数错误。
     * 没有绑定、但声明了默认路径的可选参数补上默认路径。owner 用于报错时指明是哪个函数节点 / 字段。
     */
    public Map<String, Binding> toBindings(String owner, BusinessFunction handler, Map<String, Object> arguments, String ruleCode) {
        return toBindings(owner, handler, arguments, ruleCode, null);
    }

    /**
     * 同上。nodes 不为空时允许参数引用条件树里其它函数节点的输出（FUNCTION_OUTPUT）；谓词参数按目标集合参数
     * （第一个集合类型的参数）的元素类型 / 元素结构校验，所以集合参数先绑定。
     */
    public Map<String, Binding> toBindings(String owner, BusinessFunction handler, Map<String, Object> arguments, String ruleCode,
                                           NodeContext nodes) {
        Map<String, Object> supplied = arguments == null ? Map.of() : arguments;
        Map<String, Path> known = new LinkedHashMap<>();
        for (Path path : RuleExecutionContext.paths(ruleCode)) known.put(path.name(), path);
        for (String key : supplied.keySet())
            if (handler.parameters().stream().noneMatch(p -> p.code().equals(key)))
                throw new IllegalArgumentException(owner + " 不支持参数：" + key);
        Map<String, Binding> bindings = new LinkedHashMap<>();
        String target = predicateTarget(handler);
        // 第一遍：普通参数（含集合参数）；谓词和排序字段放到第二遍，按目标集合的静态类型校验
        List<BusinessFunction.Parameter> deferred = new ArrayList<>();
        for (BusinessFunction.Parameter parameter : handler.parameters()) {
            if (FunctionTypes.PREDICATE.equals(parameter.type()) || FunctionTypes.SORT_FIELDS.equals(parameter.type())) {
                deferred.add(parameter);
                continue;
            }
            bind(owner, parameter, supplied, known, ruleCode, nodes, bindings, target);
        }
        // 第二遍
        for (BusinessFunction.Parameter parameter : deferred)
            bindStructured(owner, parameter, supplied, target, bindings, ruleCode, nodes, known);
        return bindings;
    }

    /** 谓词 / 排序字段针对的集合：Handler 声明的第一个集合类型参数；Handler 没有集合参数时为 null。 */
    static String predicateTarget(BusinessFunction handler) {
        for (BusinessFunction.Parameter parameter : handler.parameters())
            if (FunctionTypes.isCollection(parameter.type()) || FunctionTypes.COLLECTION.equals(parameter.type()))
                return parameter.code();
        return null;
    }

    private void bind(String owner, BusinessFunction.Parameter parameter, Map<String, Object> supplied, Map<String, Path> known,
                      String ruleCode, NodeContext nodes, Map<String, Binding> bindings, String target) {
        // 元素参数：类型跟随集合参数的元素类型，换成具体类型后按普通参数绑定
        if (FunctionTypes.ELEMENT.equals(parameter.type())) {
            StaticType type = staticType(owner, parameter.code(), target, bindings, nodes, ruleCode, known);
            if (type == null || "OBJECT".equals(type.elementType()))
                throw new IllegalArgumentException(owner + " 的参数 " + parameter.code() + " 的类型跟随集合参数 " + target
                        + " 的元素类型：集合须为字符串 / 数值 / 日期清单，不能是对象集合，也不能缺省");
            parameter = new BusinessFunction.Parameter(parameter.code(), type.elementType(), parameter.source(),
                    parameter.required(), parameter.defaultPath(), parameter.label());
        }
        Object raw = supplied.get(parameter.code());
        if (raw == null || (raw instanceof String text && text.isBlank())) {
            if (parameter.defaultPath() != null) {
                if (!known.containsKey(parameter.defaultPath()))
                    throw new IllegalStateException(owner + " 的默认取值路径未登记：" + parameter.defaultPath());
                bindings.put(parameter.code(), new Binding(parameter.code(), parameter.defaultPath(), null));
            } else if (parameter.required())
                throw new IllegalArgumentException(owner + " 缺少必填参数：" + parameter.code());
            return;
        }
        bindings.put(parameter.code(), raw instanceof Map<?, ?> mapping ? mapped(owner, parameter, mapping, known, ruleCode, nodes)
                : binding(owner, parameter, raw, known));
    }

    private void bindStructured(String owner, BusinessFunction.Parameter parameter, Map<String, Object> supplied, String target,
                                Map<String, Binding> bindings, String ruleCode, NodeContext nodes, Map<String, Path> known) {
        Object raw = supplied.get(parameter.code());
        if (raw == null || (raw instanceof String text && text.isBlank()) || raw instanceof Map<?, ?> m && m.isEmpty()) {
            if (parameter.required()) throw new IllegalArgumentException(owner + " 缺少必填参数：" + parameter.code());
            return;
        }
        if (!(raw instanceof Map<?, ?> mapping))
            throw new IllegalArgumentException(owner + " 的参数 " + parameter.code() + " 须写成对象（{source, condition / value}）");
        String source = text(mapping.get("source")).toUpperCase(Locale.ROOT);
        if (source.isEmpty() && mapping.containsKey("condition")) source = PREDICATE;
        if (!parameter.type().equals(source) && !(FunctionTypes.SORT_FIELDS.equals(parameter.type()) && CONSTANT.equals(source)))
            throw new IllegalArgumentException(owner + " 的参数 " + parameter.code() + " 的来源应为 " + parameter.type() + "，实际为 "
                    + (source.isEmpty() ? "（缺少 source）" : source));
        StaticType type = staticType(owner, parameter.code(), target, bindings, nodes, ruleCode, known);
        if (FunctionTypes.PREDICATE.equals(parameter.type())) {
            if (type == null) throw new IllegalArgumentException(owner + " 没有集合参数可供谓词判断");
            bindings.put(parameter.code(), new Binding(parameter.code(), null, null, null, PREDICATE, null, null,
                    ElementPredicate.compile(owner, mapping.get("condition"), type.elementType(), type.schema(), ruleCode, this)));
            return;
        }
        bindings.put(parameter.code(), new Binding(parameter.code(), null, null, null, CONSTANT, null,
                sortFields(owner, mapping.get("value"), type), null));
    }

    /** 集合参数的静态类型（数据类型 + 元素结构），谓词、排序字段、元素参数按它校验。 */
    private StaticType staticType(String owner, String parameter, String target, Map<String, Binding> bindings, NodeContext nodes,
                                  String ruleCode, Map<String, Path> known) {
        Binding binding = bindings.get(target);
        if (binding == null) return null;
        if (binding.path() != null) {
            Path path = known.get(binding.path());
            if (path == null || !RuleList.isCollection(path.type()))
                throw new IllegalArgumentException(owner + " 的参数 " + parameter + " 须绑定集合类型，不能绑定 " + binding.path() + "（"
                        + (path == null ? "未登记" : path.type()) + "）");
            return new StaticType(path.type(), null);
        }
if (binding.isField()) {
            FieldDefinition field = fieldValues.check(binding.fieldCode(), FunctionTypes.COLLECTION, ruleCode);
            return new StaticType(field.dataType, fieldValues.schema(field));
        }
        if (binding.isOutput()) return new StaticType(nodes.producer(binding.outputNodeId()).staticType(),
                nodes.producer(binding.outputNodeId()).staticSchema());
        throw new IllegalArgumentException(owner + " 的参数 " + parameter + " 要求集合来自数据字段或前置函数，不能是 "
                + (binding.path() != null ? "受控路径 " + binding.path() : "常量 / 字面量"));
    }

    /** 集合的静态类型：数据类型与（OBJECT_LIST 的）元素结构。 */
    private record StaticType(String dataType, ElementSchema schema) {
        String elementType() {
            return RuleList.elementTypeOf(dataType);
        }
    }

    /** 参数允许的数据来源：声明 ANY 的来源都可以，其余只认声明的那一种。 */
    private static boolean accepts(BusinessFunction.Parameter parameter, String source) {
        if (CHAINED.contains(source)) return CollectionArguments.ANY.equals(parameter.source());
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

    /** 显式来源映射。 */
    private Binding mapped(String owner, BusinessFunction.Parameter parameter, Map<?, ?> mapping, Map<String, Path> known,
                           String ruleCode, NodeContext nodes) {
        String code = parameter.code();
        String source = text(mapping.get("source")).toUpperCase(Locale.ROOT);
        if (source.isEmpty())
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 的来源映射缺少 source（FIELD / CANDIDATE / TRANSACTION / CONTEXT / RULE_ROW / CONSTANT / FUNCTION_OUTPUT）");
        if (!accepts(parameter, source))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 数据来源应为 " + parameter.source() + "，不能绑定 " + source);
        if (CONSTANT.equals(source)) {
            if (!mapping.containsKey("value") || mapping.get("value") == null)
                throw new IllegalArgumentException(owner + " 的参数 " + code + " 的常量缺少 value");
            return new Binding(code, null, null, null, CONSTANT, null, constant(owner, parameter, mapping.get("value")), null);
        }
        if (FUNCTION_OUTPUT.equals(source)) {
            if (nodes == null)
                throw new IllegalArgumentException(owner + " 的参数 " + code + " 引用前置函数的输出，只能在条件树里与前置函数节点一起使用");
            String nodeId = text(mapping.get("nodeId"));
            if (nodeId.isEmpty())
                throw new IllegalArgumentException(owner + " 的参数 " + code + " 的来源 FUNCTION_OUTPUT 缺少 nodeId");
            FunctionExecutionService.Validated producer = nodes.producer(nodeId);
            String actual = producer.staticType();
            if (!compatible(parameter.type(), actual))
                throw new IllegalArgumentException(owner + " 的参数 " + code + " 类型应为 " + parameter.type() + "，不能引用前置函数 " + nodeId + "（" + actual + "）");
            return new Binding(code, null, null, null, FUNCTION_OUTPUT, nodeId, null, null);
        }
        String target = text(mapping.get("fieldCode")).isEmpty() ? text(mapping.get("path")) : text(mapping.get("fieldCode"));
        if (target.isEmpty())
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 的来源映射缺少 fieldCode（来源 " + source + "）");
        if ("FIELD".equals(source)) {
            // 字段须是可用于该规则的数据解析字段，类型与参数一致（不一致时这里直接说明原因）
            fieldValues.check(target, parameter.type(), ruleCode);
            return new Binding(code, null, null, target, "FIELD", null, null, null);
        }
        return path(owner, parameter, source.equals(RULE_ROW) ? "ruleRow." + target : source.toLowerCase(Locale.ROOT) + "." + target, known);
    }

    /** 参数类型与实际类型是否匹配：COLLECTION 接受任意集合；STRING_SET 接受字符串清单；INTEGER 可以给 DECIMAL；其余须完全一致。 */
    public static boolean compatible(String parameterType, String actualType) {
        if (parameterType == null || actualType == null) return false;
        if (FunctionTypes.COLLECTION.equals(parameterType)) return FunctionTypes.isCollection(actualType);
        if (FunctionTypes.STRING_SET.equals(parameterType)) return FunctionTypes.STRING_SET.equals(actualType) || FunctionTypes.STRING_LIST.equals(actualType);
        if (FunctionTypes.DECIMAL.equals(parameterType) && FunctionTypes.INTEGER.equals(actualType)) return true;
        return parameterType.equals(actualType);
    }

    /** 受控路径的校验：路径已登记，类型和数据来源都与参数一致。 */
    private Binding path(String owner, BusinessFunction.Parameter parameter, String path, Map<String, Path> known) {
        String code = parameter.code();
        Path target = known.get(path);
        if (target == null)
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 引用了不受控的取值路径：" + path + "，只能使用已登记的 candidate.* / transaction.* / context.* / ruleRow.* 路径");
        if (!compatible(parameter.type(), target.type()))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 类型应为 " + parameter.type() + "，不能绑定 " + path + "（" + target.type() + "）");
        if (!accepts(parameter, target.group().name()))
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 数据来源应为 " + parameter.source() + "，不能绑定 " + path + "（" + target.group() + "）");
        return new Binding(code, path, null);
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    /** 常量按参数类型校验并规整：数字转 Long / BigDecimal（不用 double），日期转 LocalDateTime。集合不能写成常量。 */
    private Object constant(String owner, BusinessFunction.Parameter parameter, Object raw) {
        String type = parameter.type();
        String code = parameter.code();
        if (raw instanceof com.fasterxml.jackson.databind.JsonNode node) raw = ElementPredicate.plain(node);
        try {
            return switch (type) {
                case FunctionTypes.STRING -> raw instanceof String text && !text.isBlank() ? text : reject(owner, code, type, raw);
                case FunctionTypes.INTEGER -> raw instanceof Boolean ? reject(owner, code, type, raw) : new BigDecimal(raw.toString().trim()).longValueExact();
                case FunctionTypes.DECIMAL -> raw instanceof Boolean ? reject(owner, code, type, raw) : new BigDecimal(raw.toString().trim());
                case FunctionTypes.BOOLEAN -> raw instanceof Boolean flag ? flag : reject(owner, code, type, raw);
                case FunctionTypes.DATE -> raw instanceof LocalDateTime time ? time : raw instanceof LocalDate day ? day.atStartOfDay()
                        : raw instanceof String s ? (s.trim().length() == 10 ? LocalDate.parse(s.trim()).atStartOfDay() : LocalDateTime.parse(s.trim()))
                        : reject(owner, code, type, raw);
                default -> throw new IllegalArgumentException(owner + " 的参数 " + code + " 不能使用常量（类型 " + type + " 只能来自数据字段或前置函数）");
            };
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException ex) {
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 类型应为 " + type + "，常量无效：" + raw);
        }
    }

    private static Object reject(String owner, String code, String type, Object raw) {
        throw new IllegalArgumentException(owner + " 的参数 " + code + " 类型应为 " + type + "，常量无效：" + raw);
    }

    /** 排序字段清单：1~5 项，属性须在元素结构内且可排序，不可重复；标量集合不需要排序字段（可给空清单）。 */
    private static List<SortField> sortFields(String owner, Object raw, StaticType type) {
        List<?> items;
        if (raw == null) items = List.of();
        else if (raw instanceof com.fasterxml.jackson.databind.JsonNode node && node.isArray()) items = (List<?>) ElementPredicate.plain(node);
        else if (raw instanceof List<?> list) items = list;
        else throw new IllegalArgumentException(owner + " 的排序字段须写成数组 [{field, direction}]");
        if (type == null) throw new IllegalArgumentException(owner + " 没有集合参数可供排序");
        if (!RuleList.OBJECT_LIST.equals(type.dataType())) {
            if (!items.isEmpty())
                throw new IllegalArgumentException(owner + " 的集合是标量清单（" + type.dataType() + "），按元素本身排序，不需要排序字段");
            return List.of();
        }
        if (items.isEmpty() || items.size() > 5) throw new IllegalArgumentException(owner + " 的排序字段须为 1~5 项");
        ElementSchema schema = type.schema();
        if (schema == null) throw new IllegalArgumentException(owner + " 的集合没有元素结构，无法按属性排序");
        List<SortField> fields = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> entry)) throw new IllegalArgumentException(owner + " 的排序字段须写成 {field, direction}");
            String field = text(entry.get("field"));
            ElementSchema.Attribute attribute = schema.attribute(field);
            if (ElementSchema.STRING_SET.equals(attribute.type()))
                throw new IllegalArgumentException(owner + " 的排序字段 " + field + " 是集合类型，不能排序");
            String direction = text(entry.get("direction")).toUpperCase(Locale.ROOT);
            if (!direction.isEmpty() && !SortField.ASC.equals(direction) && !SortField.DESC.equals(direction))
                throw new IllegalArgumentException(owner + " 的排序字段 " + field + " 方向只能为 ASC / DESC");
            SortField sortField = new SortField(field, direction);
            if (fields.stream().anyMatch(f -> f.field().equals(field)))
                throw new IllegalArgumentException(owner + " 的排序字段重复：" + field);
            fields.add(sortField);
        }
        return List.copyOf(fields);
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
                // 日期时间（2026-11-25T10:15:30）或纯日期（2026-11-30，起息日这类按日历日的取值）
                case "DATE" -> {
                    if (text.length() == 10) LocalDate.parse(text);
                    else LocalDateTime.parse(text);
                }
                // 集合只能来自字段解析器或前置函数：不接受手写的集合字面量
                default -> throw new NumberFormatException();
            }
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException ex) {
            throw new IllegalArgumentException(owner + " 的参数 " + parameter.code() + " 类型应为 " + type + "，字面量无效：" + text);
        }
    }

    /**
     * 谓词里比较对象的来源映射（编译期校验）：CONSTANT 常量；CANDIDATE / TRANSACTION / CONTEXT / RULE_ROW 受控路径；
     * FIELD 数据字段；CURRENT_ELEMENT 当前元素的另一个属性（仅 OBJECT_LIST）。
     */
    Operand predicateOperand(String where, String type, String operator, Map<?, ?> mapping, String elementType, ElementSchema schema,
                             String ruleCode) {
        String source = text(mapping.get("source")).toUpperCase(Locale.ROOT);
        if (source.isEmpty())
            throw new IllegalArgumentException(where + " 的来源映射缺少 source（CONSTANT / CANDIDATE / TRANSACTION / CONTEXT / FIELD / CURRENT_ELEMENT / RULE_ROW）");
        if (CONSTANT.equals(source))
            return Operand.of(OperandKind.LITERAL, ElementPredicate.literal(where, type, operator, mapping.get("value")), null, null);
        if (CURRENT_ELEMENT.equals(source)) {
            String attribute = text(mapping.get("fieldCode"));
            if (!"OBJECT".equals(elementType)) throw new IllegalArgumentException(where + " 的来源 CURRENT_ELEMENT 只能用于对象集合");
            if (schema == null || attribute.isEmpty()) throw new IllegalArgumentException(where + " 的来源 CURRENT_ELEMENT 缺少属性名 fieldCode");
            if (!schema.attribute(attribute).type().equals(type))
                throw new IllegalArgumentException(where + " 与当前元素属性 " + attribute + "（" + schema.attribute(attribute).type() + "）类型不一致（" + type + "）");
            return Operand.of(OperandKind.ELEMENT, null, null, attribute);
        }
        if (FUNCTION_OUTPUT.equals(source) || PREDICATE.equals(source))
            throw new IllegalArgumentException(where + " 的来源 " + source + " 不能用于谓词（谓词里暂不支持函数节点）");
        String target = text(mapping.get("fieldCode"));
        if (target.isEmpty()) throw new IllegalArgumentException(where + " 的来源 " + source + " 缺少 fieldCode");
        if ("FIELD".equals(source)) {
            fieldValues.check(target, type, ruleCode);
            return Operand.of(OperandKind.FIELD, null, null, target);
        }
        if (!GROUPS.contains(source)) throw new IllegalArgumentException(where + " 的来源 " + source + " 不能用于谓词");
        String name = RULE_ROW.equals(source) ? "ruleRow." + target : source.toLowerCase(Locale.ROOT) + "." + target;
        Path path = RuleExecutionContext.paths(ruleCode).stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(where + " 引用了不受控的取值路径：" + name));
        if (!compatible(type, path.type()))
            throw new IllegalArgumentException(where + " 类型应为 " + type + "，不能绑定 " + name + "（" + path.type() + "）");
        return Operand.of(OperandKind.PATH, null, name, null);
    }

    /** 谓词运行期读取数据字段（走字段解析器与请求级缓存）。 */
    Object fieldValue(String fieldCode, RuleExecutionContext context) {
        return fieldValues.value(fieldCode, context);
    }

    /** 数据字段的定义：须可用于该规则的集合参数（已启用、适用该规则、取值来源为数据解析、解析器已登记、类型为集合）。 */
    FieldDefinition fieldType(String fieldCode, String ruleCode) {
        return fieldValues.check(fieldCode, FunctionTypes.COLLECTION, ruleCode);
    }

    /** 数据字段的元素结构（仅 OBJECT_LIST 有）。 */
    ElementSchema fieldSchema(FieldDefinition field) {
        return fieldValues.schema(field);
    }

    /** 运行时按绑定表取值并做类型检查；路径取值为空时报错，不使用默认值顶替。 */
    public Map<String, Object> resolve(String owner, BusinessFunction handler, Map<String, Binding> bindings, RuleExecutionContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (BusinessFunction.Parameter parameter : handler.parameters()) {
            Binding binding = bindings.get(parameter.code());
            if (binding == null) continue;
            Object value;
            if (binding.predicate() != null) value = binding.predicate();
            else if (binding.outputNodeId() != null) value = output(owner, parameter, binding, context);
            else if (binding.constant() != null) value = binding.constant();
            else if (binding.isField()) value = fieldValue(owner, parameter, binding, context);
            else value = binding.path() != null ? context.value(binding.path()) : binding.literal();
            arguments.put(parameter.code(), convert(owner, parameter, value, binding));
        }
        return arguments;
    }

    /** FUNCTION_OUTPUT 实参：前置节点没有输出（未执行 / 执行失败）时报错，不返回 null 或空集合。 */
    private Object output(String owner, BusinessFunction.Parameter parameter, Binding binding, RuleExecutionContext context) {
        try {
            return context.output(binding.outputNodeId());
        } catch (RuntimeException ex) {
            throw new FunctionExecutionService.FunctionExecutionException(owner + " 执行失败：参数 " + parameter.code() + " 引用的前置函数节点 "
                    + binding.outputNodeId() + " 没有输出：" + ex.getMessage(), ex);
        }
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
                // 元素参数：按取值的实际形态判断（字符串 / 数值 / 日期），与集合的元素类型是否一致由函数自己核对
                case FunctionTypes.ELEMENT -> value instanceof String || value instanceof BigDecimal || value instanceof LocalDateTime ? value : null;
                case "STRING" -> value instanceof String text ? text : null;
                case "INTEGER" -> value instanceof BigDecimal decimal ? decimal.longValueExact()
                        : value instanceof Integer integer ? integer.longValue()
                        : value instanceof Long whole ? whole
                        : value instanceof java.math.BigInteger big && big.bitLength() < 64 ? big.longValue() : null;
                case "DECIMAL" -> value instanceof BigDecimal decimal ? decimal
                        : value instanceof Integer integer ? BigDecimal.valueOf(integer)
                        : value instanceof Long whole ? BigDecimal.valueOf(whole) : null;
                case "BOOLEAN" -> value instanceof Boolean flag ? flag : null;
                case "DATE" -> value instanceof LocalDateTime time ? time
                        : value instanceof LocalDate date ? date.atStartOfDay()
                        // 字面量：日期时间原样使用；纯日期（起息日）保持为当天零点
                        : value instanceof String text ? (text.trim().length() == 10 ? LocalDate.parse(text.trim()).atStartOfDay()
                        : LocalDateTime.parse(text.trim())) : null;
                // 字符串集合：STRING_SET（可按成员查询）或已加载的字符串清单
                case "STRING_SET" -> value instanceof RuleCollection collection ? collection
                        : value instanceof RuleList list && list.elementType().equals("STRING") ? RuleCollection.of(list.source(), list.elements()) : null;
                case FunctionTypes.COLLECTION -> value instanceof RuleCollection || value instanceof RuleList ? value : null;
                case FunctionTypes.STRING_LIST, FunctionTypes.NUMBER_LIST, FunctionTypes.DATE_LIST, FunctionTypes.OBJECT_LIST ->
                        value instanceof RuleList list && list.dataType().equals(parameter.type()) ? list : null;
                case FunctionTypes.MAP -> value instanceof Map<?, ?> map ? Collections.unmodifiableMap(new LinkedHashMap<>(map)) : null;
                case FunctionTypes.OBJECT -> value instanceof Map<?, ?> || value != null && value.getClass().isRecord() ? value : null;
                case FunctionTypes.PREDICATE -> value instanceof ElementPredicate predicate ? predicate : null;
                case FunctionTypes.SORT_FIELDS -> value instanceof List<?> fields ? List.copyOf(fields) : null;
                default -> null;
            };
        } catch (ArithmeticException | DateTimeParseException ex) {
            typed = null;
        }
        if (typed == null)
            throw new IllegalArgumentException(owner + " 的参数 " + code + " 取值类型应为 " + parameter.type() + "：" + value + where(binding));
        return typed;
    }

    private static String where(Binding binding) {
        return binding.outputNodeId() != null ? "（前置函数 " + binding.outputNodeId() + "）"
                : binding.path() != null ? "（" + binding.path() + "）" : binding.fieldCode() != null ? "（字段 " + binding.fieldCode() + "）" : "";
    }
}
