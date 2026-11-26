package com.hsg.relay.function.field;

import com.fasterxml.jackson.annotation.JsonValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则计算用的有序集合（LIST / SET），由字段解析器或集合函数产生，作为 STRING_LIST / NUMBER_LIST / DATE_LIST / OBJECT_LIST
 * 类型的字段取值、函数实参和函数返回值。与 {@link RuleCollection}（STRING_SET，可按成员查询）并列：
 * 本类型一律已全量加载，元素保持加载顺序，允许重复元素（LIST 语义）。
 * <p>构造时逐个校验元素并做类型规整，任何一项不满足都直接报错，不会丢掉元素或退化成空集合：
 * <ul>
 *   <li>不允许 null 元素（数据不完整按读取失败处理，不静默忽略）；</li>
 *   <li>STRING：非空字符串；DECIMAL：BigDecimal / 整数（不接受 double、float）；DATE：LocalDate（规整为当天零点）、LocalDateTime 或 ISO 文本；
 *       OBJECT：属性与 {@link ElementSchema} 完全一致的 Map；</li>
 *   <li>元素个数不超过 {@link #MAX_ELEMENTS}（执行保护，过大集合需要先在解析器一侧收敛）。</li>
 * </ul>
 * 本类型只读，不产生任何业务副作用。序列化（接口返回、试算展示）只输出摘要 {@link #view}：来源、类型、元素个数和少量样例，
 * 不带完整名单或整份记录。
 */
public final class RuleList {
    /** 单个集合允许的最大元素个数。 */
    public static final int MAX_ELEMENTS = 10_000;

    public static final String STRING_SET = "STRING_SET", STRING_LIST = "STRING_LIST", NUMBER_LIST = "NUMBER_LIST",
            DATE_LIST = "DATE_LIST", OBJECT_LIST = "OBJECT_LIST";

    private final String source;
    private final String dataType;
    private final String elementType;
    private final ElementSchema schema;
    private final String elementSchema;
    private final List<Object> elements;
    private final String cacheKey;
    // 个人数据清单（如客户董事 / UBO 国籍）：调用记录、试算和日志只给个数，不列出元素
    private final boolean sensitive;

    private RuleList(String source, String dataType, String elementType, ElementSchema schema, List<Object> elements) {
        this(source, dataType, elementType, schema, elements, false);
    }

    private RuleList(String source, String dataType, String elementType, ElementSchema schema, List<Object> elements, boolean sensitive) {
        this.source = source;
        this.dataType = dataType;
        this.elementType = elementType;
        this.schema = schema;
        this.elementSchema = schema == null ? null : schema.code();
        this.elements = Collections.unmodifiableList(elements);
        this.sensitive = sensitive;
        this.cacheKey = "list:" + source + ":" + dataType + ":" + elements.size() + ":" + elements.hashCode();
    }

    /** 是否为个人数据清单：是则展示形态只给元素个数，不列样例（函数计算不受影响）。 */
    public boolean sensitive() {
        return sensitive;
    }

    /** 个人数据清单（标量集合）：与 {@link #of} 相同，只是调用记录 / 日志 / 试算展示里不列出元素。 */
    public static RuleList ofPrivate(String source, String dataType, Collection<?> elements) {
        RuleList list = of(source, dataType, elements);
        return new RuleList(list.source, list.dataType, list.elementType, null, list.elements, true);
    }

    /** 集合数据的来源编码（如 ROUTING_CONFIG），供试算展示与审计。 */
    public String source() {
        return source;
    }

    /** 数据类型：STRING_SET / STRING_LIST / NUMBER_LIST / DATE_LIST / OBJECT_LIST。 */
    public String dataType() {
        return dataType;
    }

    /** 元素类型：STRING / DECIMAL / DATE / OBJECT。 */
    public String elementType() {
        return elementType;
    }

    /** 元素结构（仅 OBJECT_LIST 有）。 */
    public ElementSchema schema() {
        return schema;
    }

    /** OBJECT_LIST 的元素结构编码（如 REACH_ROW）；其余类型为 null。 */
    public String elementSchema() {
        return elementSchema;
    }

    /** 全部元素（只读，保持加载顺序，可含重复）。 */
    public List<Object> elements() {
        return elements;
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    /** 本次求值内的身份标识（含来源、类型、元素个数和元素哈希），用作函数结果复用的缓存键的一部分。 */
    public String cacheKey() {
        return cacheKey;
    }

    /** 摘要（日志、调用记录）：来源、类型、元素个数；不输出完整内容。 */
    public String summary() {
        return source + "（" + dataType + (elementSchema == null ? "" : "·" + elementSchema) + "，" + elements.size() + " 个元素）";
    }

    /**
     * 展示形态（接口返回、试算、日志用）：来源、类型、元素个数，以及最多 5 个样例。
     * 标量集合列出样例元素；对象集合只列记录 ID（元素结构的 ID 属性，没有 ID 属性时为行号），不带整份记录。
     */
    @JsonValue
    public Map<String, Object> view() {
        return view(5);
    }

    public Map<String, Object> view(int limit) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("source", source);
        shown.put("dataType", dataType);
        shown.put("elementType", elementType);
        if (elementSchema != null) shown.put("elementSchema", elementSchema);
        shown.put("size", elements.size());
        shown.put("sample", sensitive ? List.of() : sample(limit));
        if (sensitive) shown.put("masked", "个人数据清单，不列明细");
        return Collections.unmodifiableMap(shown);
    }

    /** 最多 limit 个样例：对象记录给出记录 ID，其余给出元素本身。 */
    public List<Object> sample(int limit) {
        List<Object> sample = new ArrayList<>();
        for (int i = 0; i < elements.size() && sample.size() < limit; i++) {
            Object element = elements.get(i);
            sample.add(schema == null ? element : schema.recordId(element, i));
        }
        return List.copyOf(sample);
    }

    @Override
    public String toString() {
        return summary();
    }

    // ---------- 构造 ----------

    /**
     * 标量集合：STRING_SET / STRING_LIST / NUMBER_LIST / DATE_LIST。元素逐个校验并规整为 String / BigDecimal / LocalDateTime。
     */
    public static RuleList of(String source, String dataType, Collection<?> elements) {
        String elementType = scalarElementType(dataType);
        if (elements == null) throw new IllegalArgumentException("集合 " + source + " 未加载（元素为 null）");
        List<Object> normalized = normalize(source, dataType, elements, null);
        return new RuleList(source, dataType, elementType, null, normalized);
    }

    /**
     * 对象集合的派生结果（过滤 / 排序之后）：元素已按结构校验过，这里再核对一遍属性与结构是否一致。
     */
    public static RuleList derivedRecords(String source, ElementSchema schema, List<?> elements) {
        if (schema == null) throw new IllegalArgumentException("集合 " + source + " 缺少元素结构");
        for (Object element : elements)
            if (!(element instanceof Map<?, ?>) || !schema.matches((Map<?, ?>) element))
                throw new IllegalArgumentException("集合 " + source + " 含不符合结构 " + schema.code() + " 的记录");
        return new RuleList(source, OBJECT_LIST, "OBJECT", schema, new ArrayList<>(elements));
    }

    /** 标量集合的派生结果（并集 / 交集 / 差集 / 去重 / 过滤）：元素已校验过，沿用原集合的数据类型。 */
    public static RuleList derived(String source, String dataType, List<?> elements) {
        return new RuleList(source, dataType, scalarElementType(dataType), null, new ArrayList<>(elements));
    }

    /** 同上；sensitive 为 true 时派生结果仍是个人数据清单（展示不列明细）。 */
    public static RuleList derived(String source, String dataType, List<?> elements, boolean sensitive) {
        return sensitive ? new RuleList(source, dataType, scalarElementType(dataType), null, new ArrayList<>(elements), true)
                : derived(source, dataType, elements);
    }

    /**
     * 对象集合：每一行的属性须与 schema 完全一致（不缺、不多），取值类型与属性声明一致；声明为可空的属性允许为 null。
     */
    public static RuleList records(String source, ElementSchema schema, Collection<Map<String, Object>> rows) {
        if (rows == null) throw new IllegalArgumentException("集合 " + source + " 未加载（记录为 null）");
        if (schema == null) throw new IllegalArgumentException("集合 " + source + " 缺少元素结构");
        List<Object> normalized = normalize(source, OBJECT_LIST, rows, schema);
        return new RuleList(source, OBJECT_LIST, "OBJECT", schema, normalized);
    }

    /** 是否为集合类型。 */
    public static boolean isCollection(String dataType) {
        try {
            elementTypeOf(dataType);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /** 标量集合的元素类型；OBJECT_LIST 返回 OBJECT。 */
    public static String elementTypeOf(String dataType) {
        return switch (dataType == null ? "" : dataType) {
            case STRING_SET, STRING_LIST -> "STRING";
            case NUMBER_LIST -> "DECIMAL";
            case DATE_LIST -> "DATE";
            case OBJECT_LIST -> "OBJECT";
            default -> throw new IllegalArgumentException("不是集合类型：" + dataType);
        };
    }

    private static String scalarElementType(String dataType) {
        String type = elementTypeOf(dataType);
        if ("OBJECT".equals(type)) throw new IllegalArgumentException(dataType + " 须通过 records 构造");
        return type;
    }

    private static List<Object> normalize(String source, String dataType, Collection<?> elements, ElementSchema schema) {
        if (elements.size() > MAX_ELEMENTS)
            throw new IllegalArgumentException("集合 " + source + " 的元素个数 " + elements.size() + " 超过上限 " + MAX_ELEMENTS + "，无法载入计算");
        List<Object> normalized = new ArrayList<>(elements.size());
        int index = 0;
        for (Object element : elements)
            normalized.add(normalizeElement(source, dataType, element, schema, index++));
        return normalized;
    }

    /** 单个元素的校验与规整；不满足时给出带位置和类型的错误。 */
    static Object normalizeElement(String source, String dataType, Object element, ElementSchema schema, int index) {
        String where = "集合 " + source + " 第 " + (index + 1) + " 个元素";
        if (element == null) throw new IllegalArgumentException(where + " 为 null（数据不完整），不能忽略");
        if (OBJECT_LIST.equals(dataType)) {
            if (!(element instanceof Map<?, ?> row)) throw new IllegalArgumentException(where + " 应为对象记录，实际为 " + element.getClass().getSimpleName());
            return schema.normalize(row, where);
        }
        Object value;
        try {
            value = switch (dataType) {
                case STRING_SET, STRING_LIST -> element instanceof String text && !text.isBlank() ? text : null;
                case NUMBER_LIST -> decimal(element);
                case DATE_LIST -> date(element);
                default -> null;
            };
        } catch (DateTimeParseException | NumberFormatException | ArithmeticException ex) {
            throw new IllegalArgumentException(where + " 应为 " + dataType + "，实际为 " + element);
        }
        if (value == null)
            throw new IllegalArgumentException(where + " 应为 " + dataType + "，实际为 " + element.getClass().getSimpleName() + " " + element);
        return value;
    }

    /** 数值元素：BigDecimal / 整数；double、float 不参与金额与数值计算；其它类型返回 null。 */
    static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Integer whole) return BigDecimal.valueOf(whole);
        if (value instanceof Long whole) return BigDecimal.valueOf(whole);
        if (value instanceof BigInteger whole) return new BigDecimal(whole);
        if (value instanceof Double || value instanceof Float)
            throw new NumberFormatException("浮点数不能作为数值集合元素");
        return null;
    }

    /** 日期元素：纯日期规整为当天零点；ISO 文本原样解析；其它类型返回 null。 */
    static LocalDateTime date(Object value) {
        if (value instanceof LocalDateTime time) return time;
        if (value instanceof LocalDate day) return day.atStartOfDay();
        if (value instanceof String text) return text.trim().length() == 10 ? LocalDate.parse(text.trim()).atStartOfDay() : LocalDateTime.parse(text.trim());
        return null;
    }
}
