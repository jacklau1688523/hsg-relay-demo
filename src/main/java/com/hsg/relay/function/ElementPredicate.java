package com.hsg.relay.function;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.function.field.RuleList;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 元素谓词：集合函数（过滤 / 记录匹配 / 条件汇总）用来逐个判断集合元素的受控条件树，编译期校验、运行期只做比较。
 * <p>结构：
 * <ul>
 *   <li>条件组 {type:"GROUP", logic:"AND"|"OR", children:[...]}，可以嵌套（最多 {@link #MAX_DEPTH} 层）；</li>
 *   <li>叶子 {type:"ELEMENT", field, operator, value}：field 是元素结构的属性名；标量集合（STRING_LIST / NUMBER_LIST / DATE_LIST）
 *       固定写 "value"，表示元素本身。</li>
 * </ul>
 * value（比较对象）有两种写法：字面量（字符串 / 数字 / 布尔，按属性类型校验），或来源映射：
 * <pre>
 *   {"source":"CONSTANT","value":"HK"}                                      常量
 *   {"source":"TRANSACTION","fieldCode":"paymentCurrency"}                  受控路径 transaction.paymentCurrency
 *   {"source":"FIELD","fieldCode":"requiredCapabilityTags"}                 元数据字段（由字段解析器取数）
 *   {"source":"CURRENT_ELEMENT","fieldCode":"minUsd"}                       当前元素的另一个属性（仅 OBJECT_LIST）
 *   {"source":"RULE_ROW","fieldCode":"currency"}                            C3 当前正在判断的覆盖行属性
 * </pre>
 * 每个叶子只看「当前这一个元素」：条件不会跨记录拼接（第 1 条记录满足一半、第 2 条满足另一半不算命中）。
 * 属性值为空时该叶子为 false；比较对象（路径 / 字段）没有取值时报错，不按 false 放行。谓词里不支持函数节点和函数输出。
 */
public final class ElementPredicate {
    /** 标量集合的元素属性名。 */
    public static final String VALUE_FIELD = "value";
    /** 条件树最大嵌套层数。 */
    public static final int MAX_DEPTH = 6;
    /** 单个条件组的最大子条件数。 */
    public static final int MAX_GROUP = 20;
    /** 整棵谓词的叶子总数上限（执行保护）。 */
    public static final int MAX_LEAVES = 60;

    private static final Map<String, String> SYMBOL = Map.of("EQ", "=", "NE", "≠", "GT", ">", "GTE", "≥", "LT", "<", "LTE", "≤",
            "IN", "属于", "NOT_IN", "不属于", "CONTAINS", "包含", "NOT_CONTAINS", "不包含");

    /** 条件树节点：GROUP（logic + children）或 ELEMENT（field + operator + value）。 */
    public record Condition(String type, String logic, List<Condition> children, String field, String operator, Object value) {
        public Condition {
            if (type == null) type = children != null && !children.isEmpty() ? "GROUP" : "ELEMENT";
        }
    }

    /** 比较对象的来源（编译期已校验）。 */
    enum OperandKind { LITERAL, PATH, FIELD, ELEMENT }

    /** 比较对象：literal 为已按属性类型规整的常量；path 为受控路径名；fieldCode 为数据字段（FIELD）或当前元素的属性名（ELEMENT）。 */
    record Operand(OperandKind kind, Object literal, String path, String fieldCode) {

        /** 比较对象由来源映射校验后构造（见 FunctionArgumentResolver.predicateOperand）。 */
        static Operand of(OperandKind kind, Object literal, String path, String fieldCode) {
            return new Operand(kind, literal, path, fieldCode);
        }

        String text() {
            return switch (kind) {
                case LITERAL -> String.valueOf(literal);
                case PATH -> "$" + path;
                case FIELD -> fieldCode;
                case ELEMENT -> "当前元素." + fieldCode;
            };
        }
    }

    /** 叶子：属性名、属性类型、比较运算符、比较对象。 */
    private record Leaf(String field, String type, String operator, Operand operand) implements Node {
    }

    private record Group(String logic, List<Node> children) implements Node {
    }

    private interface Node {
    }

    private final JsonNode tree;
    private final Node root;
    private final String text;
    private final FunctionArgumentResolver sources;

    private ElementPredicate(JsonNode tree, Node root, String text, FunctionArgumentResolver sources) {
        this.tree = tree;
        this.root = root;
        this.text = text;
        this.sources = sources;
    }

    /** 可读文本，如「(currency = 'USD' 且 countryMatched = true)」。 */
    public String text() {
        return text;
    }

    /** 条件树原文（JSON），供接口回显；相等性按它判断（同一棵树的谓词相等）。 */
    public JsonNode tree() {
        return tree;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ElementPredicate that && tree.equals(that.tree);
    }

    @Override
    public int hashCode() {
        return tree.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }

    /**
     * 编译并校验谓词。elementType 为集合的元素类型（STRING / DECIMAL / DATE / OBJECT）；OBJECT 须给出元素结构。
     * sources 负责校验来源映射（路径是否登记、字段是否可用、类型是否一致），运行期也经它读取数据字段。
     */
    public static ElementPredicate compile(String owner, Object condition, String elementType, ElementSchema schema,
                                           String ruleCode, FunctionArgumentResolver sources) {
        if (condition == null) throw new IllegalArgumentException(owner + " 的谓词缺少 condition（条件树）");
        JsonNode tree = sources.json().valueToTree(condition);
        Condition parsed;
        try {
            parsed = sources.json().treeToValue(tree, Condition.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(owner + " 的谓词条件树不合法：" + ex.getMessage());
        }
        int[] leaves = {0};
        Node root = build(owner, parsed, 0, elementType, schema, ruleCode, sources, leaves);
        return new ElementPredicate(tree, root, describe(root), sources);
    }

    private static Node build(String owner, Condition node, int depth, String elementType, ElementSchema schema,
                              String ruleCode, FunctionArgumentResolver sources, int[] leaves) {
        if (node == null) throw new IllegalArgumentException(owner + " 的谓词含空条件");
        boolean group = "GROUP".equals(node.type()) || node.children() != null;
        if (group) {
            if (depth >= MAX_DEPTH) throw new IllegalArgumentException(owner + " 的谓词最多允许 " + MAX_DEPTH + " 层条件组");
            if (!"AND".equals(node.logic()) && !"OR".equals(node.logic())) throw new IllegalArgumentException(owner + " 的谓词条件组只允许 AND/OR");
            if (node.children() == null || node.children().isEmpty()) throw new IllegalArgumentException(owner + " 的谓词条件组没有子条件");
            if (node.children().size() > MAX_GROUP) throw new IllegalArgumentException(owner + " 的谓词条件组不可超过 " + MAX_GROUP + " 个子条件");
            List<Node> children = new ArrayList<>();
            for (Condition child : node.children()) children.add(build(owner, child, depth + 1, elementType, schema, ruleCode, sources, leaves));
            return new Group(node.logic(), List.copyOf(children));
        }
        if (node.type() != null && !"ELEMENT".equals(node.type()))
            throw new IllegalArgumentException(owner + " 的谓词只支持条件组（GROUP）和元素条件（ELEMENT），不支持 " + node.type());
        if (++leaves[0] > MAX_LEAVES) throw new IllegalArgumentException(owner + " 的谓词子条件不可超过 " + MAX_LEAVES + " 个");
        return leaf(owner, node, elementType, schema, ruleCode, sources);
    }

    private static Leaf leaf(String owner, Condition node, String elementType, ElementSchema schema, String ruleCode,
                             FunctionArgumentResolver sources) {
        String field = node.field() == null ? "" : node.field().trim();
        String type;
        if (VALUE_FIELD.equals(field)) {
            if ("OBJECT".equals(elementType)) throw new IllegalArgumentException(owner + " 的谓词须写元素结构的属性名，不能用 " + VALUE_FIELD);
            type = elementType;
        } else if ("OBJECT".equals(elementType)) {
            if (field.isEmpty()) throw new IllegalArgumentException(owner + " 的谓词缺少属性名 field");
            if (schema == null) throw new IllegalArgumentException(owner + " 的集合没有元素结构，无法按属性判断");
            type = schema.attribute(field).type();
        } else {
            throw new IllegalArgumentException(owner + " 的谓词属性名应为 " + VALUE_FIELD + "（标量集合按元素本身判断），实际为 " + field);
        }
        String operator = node.operator() == null ? "" : node.operator().trim().toUpperCase(Locale.ROOT);
        List<String> allowed = ElementSchema.Attribute.defaultOperators(type);
        if (!allowed.contains(operator))
            throw new IllegalArgumentException(owner + " 的谓词属性 " + field + "（" + type + "）不支持运算符 " + operator + "，可用：" + String.join("、", allowed));
        Operand operand = operand(owner, field, type, operator, node.value(), elementType, schema, ruleCode, sources);
        return new Leaf(field, type, operator, operand);
    }

    private static Operand operand(String owner, String field, String type, String operator, Object raw, String elementType,
                                   ElementSchema schema, String ruleCode, FunctionArgumentResolver sources) {
        String where = owner + " 的谓词属性 " + field;
        if (raw == null || raw instanceof JsonNode node && node.isNull())
            throw new IllegalArgumentException(where + " 缺少比较对象 value");
        if (raw instanceof JsonNode node) {
            if (node.isObject()) throw new IllegalArgumentException(where + " 的来源映射无效");
            raw = plain(node);
        }
        if (raw instanceof Map<?, ?> mapping)
            return sources.predicateOperand(where, type, operator, mapping, elementType, schema, ruleCode);
        return new Operand(OperandKind.LITERAL, literal(where, type, operator, raw), null, null);
    }

    /** Jackson 树里的字面量转成普通对象：数字一律 BigDecimal（不用 double）。 */
    static Object plain(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            node.forEach(item -> items.add(plain(item)));
            return items;
        }
        return node.asText();
    }

    /** 字面量按属性类型规整：数字转 Long / BigDecimal、日期转 LocalDateTime、IN 的多值支持逗号分隔或数组。 */
    static Object literal(String where, String type, String operator, Object raw) {
        if (raw == null || raw instanceof String text && text.isBlank())
            throw new IllegalArgumentException(where + " 缺少比较对象 value");
        try {
            return switch (type) {
                case ElementSchema.INTEGER -> new BigDecimal(raw.toString().trim()).longValueExact();
                case ElementSchema.DECIMAL -> new BigDecimal(raw.toString().trim());
                case ElementSchema.DATE -> date(raw);
                case ElementSchema.BOOLEAN -> raw instanceof Boolean flag ? flag
                        : "true".equals(raw.toString()) ? Boolean.TRUE : "false".equals(raw.toString()) ? Boolean.FALSE : reject(where, type, raw);
                case ElementSchema.STRING_SET -> strings(where, raw);
                // STRING：IN / NOT_IN 可给数组或逗号分隔的多个值；其余为单个非空字符串
                default -> multi(operator) && raw instanceof Collection<?> items
                        ? items.stream().map(item -> text(where, item)).toList() : text(where, raw);
            };
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException ex) {
            throw new IllegalArgumentException(where + " 的比较对象应为 " + type + "，实际为 " + raw);
        }
    }

    private static boolean multi(String operator) {
        return "IN".equals(operator) || "NOT_IN".equals(operator);
    }

    private static Object reject(String where, String type, Object raw) {
        throw new IllegalArgumentException(where + " 的比较对象应为 " + type + "，实际为 " + raw);
    }

    private static String text(String where, Object raw) {
        if (!(raw instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(where + " 的比较对象应为非空字符串，实际为 " + raw);
        return text.trim();
    }

    private static List<String> strings(String where, Object raw) {
        Collection<?> items = raw instanceof Collection<?> list ? list : Arrays.asList(raw.toString().split(","));
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (Object item : items) values.add(text(where, item));
        if (values.isEmpty()) throw new IllegalArgumentException(where + " 的比较对象不能为空");
        return List.copyOf(values);
    }

    private static LocalDateTime date(Object raw) {
        if (raw instanceof LocalDateTime time) return time;
        if (raw instanceof LocalDate day) return day.atStartOfDay();
        if (raw instanceof String text) {
            String trimmed = text.trim();
            return trimmed.length() == 10 ? LocalDate.parse(trimmed).atStartOfDay() : LocalDateTime.parse(trimmed);
        }
        throw new IllegalArgumentException("不是日期");
    }

    /** 把运行期取到的比较对象按属性类型规整；类型不符或没有取值时报错。 */
    static Object runtime(String where, String type, Object value) {
        if (value == null || value instanceof String text && text.isBlank())
            throw new IllegalStateException(where + " 的比较对象没有取值，不能参与比较");
        if (ElementSchema.STRING_SET.equals(type)) {
            // 集合字段：已加载的 STRING_SET 或各种字符串清单
            if (value instanceof RuleCollection collection) {
                if (!collection.materialized()) throw new IllegalStateException(where + " 的比较对象是未全量加载的集合，不能按成员展开比较");
                return new LinkedHashSet<>(collection.elements());
            }
            if (value instanceof RuleList list) return new LinkedHashSet<>(list.elements());
        }
        try {
            return ElementSchema.normalizeValue(type, value);
        } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException ex) {
            throw new IllegalStateException(where + " 的比较对象取值类型应为 " + type + "，实际为 " + value.getClass().getSimpleName() + " " + value);
        }
    }

    /** 字符串 IN / 集合相等的多个值：逗号分隔或数组。 */
    private static List<String> options(Object value) {
        Collection<?> items = value instanceof Collection<?> list ? list : Arrays.asList(value.toString().split(","));
        List<String> options = new ArrayList<>();
        for (Object item : items) options.add(item.toString().trim());
        return options;
    }

    private static String describe(Node node) {
        if (node instanceof Group group) {
            String delimiter = "AND".equals(group.logic()) ? " 且 " : " 或 ";
            return "(" + String.join(delimiter, group.children().stream().map(ElementPredicate::describe).toList()) + ")";
        }
        Leaf leaf = (Leaf) node;
        return leaf.field() + " " + SYMBOL.getOrDefault(leaf.operator(), leaf.operator()) + " " + leaf.operand().text();
    }

    /** 判断一个元素是否满足谓词。element 为 OBJECT_LIST 的记录（Map）或标量集合的元素值。 */
    public boolean test(Object element, RuleExecutionContext context) {
        if (root == null) return true;
        Map<String, Object> record = element instanceof Map<?, ?> map ? cast(map) : Map.of(VALUE_FIELD, element);
        return match(root, record, context);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private boolean match(Node node, Map<String, Object> record, RuleExecutionContext context) {
        if (node instanceof Group group) {
            boolean and = "AND".equals(group.logic());
            for (Node child : group.children()) {
                boolean hit = match(child, record, context);
                // AND 遇到 false 即 false，OR 遇到 true 即 true
                if (and != hit) return hit;
            }
            return and;
        }
        return compare((Leaf) node, record, context);
    }

    private boolean compare(Leaf leaf, Map<String, Object> record, RuleExecutionContext context) {
        String where = "谓词 " + leaf.field() + " " + leaf.operator();
        Operand operand = leaf.operand();
        Object right = switch (operand.kind()) {
            case LITERAL -> operand.literal();
            case PATH -> runtime(where, leaf.type(), context.value(operand.path()));
            case FIELD -> runtime(where, leaf.type(), sources.fieldValue(operand.fieldCode(), context));
            case ELEMENT -> record.get(operand.fieldCode());
        };
        // 属性值或对照的属性值为空：该叶子不成立（不抛错，也不当作相等）
        if (right == null) return false;
        Object left = record.get(leaf.field());
        if (left == null) return false;
        return switch (leaf.type()) {
            case ElementSchema.INTEGER, ElementSchema.DECIMAL -> order(number(left).compareTo(number(right)), leaf.operator());
            case ElementSchema.DATE -> order(((LocalDateTime) ElementSchema.normalizeValue(ElementSchema.DATE, left))
                    .compareTo((LocalDateTime) ElementSchema.normalizeValue(ElementSchema.DATE, right)), leaf.operator());
            case ElementSchema.BOOLEAN -> "EQ".equals(leaf.operator()) == (ElementSchema.normalizeValue(ElementSchema.BOOLEAN, left)
                    .equals(ElementSchema.normalizeValue(ElementSchema.BOOLEAN, right)));
            case ElementSchema.STRING_SET -> set((Set<?>) left, right, leaf.operator(), where);
            default -> string(String.valueOf(left), right, leaf.operator());
        };
    }

    /** 数值统一成 BigDecimal 再比较（整数属性、小数属性、常量都是 BigDecimal / Long）。 */
    private static BigDecimal number(Object value) {
        return value instanceof Long whole ? BigDecimal.valueOf(whole) : (BigDecimal) value;
    }

    /** 数值 / 日期的六种比较；sign 为左值与右值比较结果的符号。 */
    private static boolean order(int sign, String operator) {
        return switch (operator) {
            case "EQ" -> sign == 0;
            case "NE" -> sign != 0;
            case "GT" -> sign > 0;
            case "GTE" -> sign >= 0;
            case "LT" -> sign < 0;
            default -> sign <= 0;
        };
    }

    @SuppressWarnings("unchecked")
    private static boolean set(Set<?> left, Object right, String operator, String where) {
        Set<String> a = new LinkedHashSet<>();
        for (Object item : left) a.add(String.valueOf(item));
        if ("CONTAINS".equals(operator) || "NOT_CONTAINS".equals(operator)) {
            boolean hit = a.contains(text(where, right));
            return "CONTAINS".equals(operator) == hit;
        }
        boolean same = a.equals(new LinkedHashSet<>(options(right)));
        return "EQ".equals(operator) == same;
    }

    /** 字符串比较：区分大小写、整串相等；IN / NOT_IN 按多个值；CONTAINS 为子串。 */
    private static boolean string(String left, Object right, String operator) {
        if ("CONTAINS".equals(operator) || "NOT_CONTAINS".equals(operator)) {
            boolean hit = left.contains(String.valueOf(right));
            return "CONTAINS".equals(operator) == hit;
        }
        if (multi(operator)) {
            boolean hit = options(right).contains(left);
            return "IN".equals(operator) == hit;
        }
        boolean same = left.equals(String.valueOf(right));
        return "EQ".equals(operator) == same;
    }
}
