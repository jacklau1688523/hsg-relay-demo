package com.hsg.relay.function.collection;

import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.function.field.RuleList;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集合函数的实参视图：把两种集合取值（可按成员查询的 {@link RuleCollection}、已全量加载的 {@link RuleList}）
 * 统一成「数据类型 + 元素类型 + 元素结构 + 元素」供函数计算。只做视图，不复制、不修改原集合。
 * <ul>
 *   <li>null 不是集合：表示数据没有加载或前置函数没有输出，直接报错，不会当成空集合；</li>
 *   <li>成员查询模式（未全量加载）的集合只能做成员判断（{@link #contains}），需要逐个元素时 {@link #elements()} 报错，
 *       不会为了计算而隐式全量加载。</li>
 * </ul>
 */
final class CollectionInput {
    final String owner;
    final String code;
    /** 数据类型：STRING_SET / STRING_LIST / NUMBER_LIST / DATE_LIST / OBJECT_LIST。 */
    final String dataType;
    /** 元素类型：STRING / DECIMAL / DATE / OBJECT。 */
    final String elementType;
    /** 数据来源编码（供报错与试算说明）。 */
    final String source;
    /** OBJECT_LIST 的元素结构；其余为 null。 */
    final ElementSchema schema;
    /** 全部元素；成员查询模式为 null。 */
    private final List<Object> elements;
    /** 成员判断用的去重键（已加载的集合才有）。 */
    private final Set<Object> keys;
    private final RuleCollection lookup;
    /** 个人数据清单：派生结果沿用「展示不列明细」。 */
    private final boolean sensitive;

    private CollectionInput(String owner, String code, String dataType, String elementType, String source, ElementSchema schema,
                            List<Object> elements, RuleCollection lookup) {
        this(owner, code, dataType, elementType, source, schema, elements, lookup, false);
    }

    private CollectionInput(String owner, String code, String dataType, String elementType, String source, ElementSchema schema,
                            List<Object> elements, RuleCollection lookup, boolean sensitive) {
        this.sensitive = sensitive;
        this.owner = owner;
        this.code = code;
        this.dataType = dataType;
        this.elementType = elementType;
        this.source = source;
        this.schema = schema;
        this.elements = elements;
        this.lookup = lookup;
        Set<Object> distinct = new LinkedHashSet<>();
        if (elements != null) for (Object element : elements) distinct.add(key(element));
        this.keys = elements == null ? null : distinct;
    }

    static CollectionInput of(String owner, String code, Object value) {
        if (value instanceof RuleCollection collection)
            return new CollectionInput(owner, code, RuleList.STRING_SET, "STRING", collection.source(), null,
                    collection.materialized() ? new ArrayList<>(collection.elements()) : null, collection);
        if (value instanceof RuleList list)
            return new CollectionInput(owner, code, list.dataType(), list.elementType(), list.source(), list.schema(), list.elements(), null,
                    list.sensitive());
        throw new IllegalArgumentException(owner + " 的参数 " + code + " 应为集合（数据字段或前置函数的集合输出），实际为"
                + (value == null ? "空（数据未加载或前置函数没有输出）" : value.getClass().getSimpleName() + " " + value));
    }

    /** 是否已全量加载。 */
    boolean materialized() {
        return elements != null;
    }

    /** 是否为对象集合。 */
    boolean records() {
        return RuleList.OBJECT_LIST.equals(dataType);
    }

    /** 元素个数；成员查询模式的集合在这里报错，不会隐式全量加载。 */
    int size() {
        return elements().size();
    }

    /** 全部元素（保持顺序，可含重复）；成员查询模式的集合在这里报错，不会隐式全量加载。 */
    List<Object> elements() {
        if (elements == null)
            throw new IllegalArgumentException(owner + " 的参数 " + code + "（" + source + "）为成员查询模式、未全量加载，不能逐个元素计算；"
                    + "这类集合只能做成员判断（collectionContains / collectionNotContains / collectionContainsAny / collectionContainsAll）");
        return elements;
    }

    /** 成员判断：字符串整串相等（区分大小写），数值按数值相等（1.0 与 1.00 视为相同）。 */
    boolean contains(Object element) {
        if (element == null) return false;
        if (lookup != null) return lookup.contains(element);
        return keys.contains(key(element));
    }

    /** 同类型、同元素结构的子集视图（按条件筛选后的元素，元素本身不变）。 */
    CollectionInput subset(List<Object> part) {
        return new CollectionInput(owner, code, dataType, elementType, source, schema, part, null, sensitive);
    }

    /** 是否为个人数据清单。 */
    boolean sensitive() {
        return sensitive;
    }

    /** 去重用的键：BigDecimal 规整成不带多余零的数值，其余就是元素本身。 */
    static Object key(Object element) {
        if (element instanceof BigDecimal decimal) return decimal.stripTrailingZeros();
        return element;
    }

    /**
     * 元素实参的类型核对：须与集合的元素类型一致（字符串 / 数值 / 日期），类型不符时报错，不做隐式转换。
     */
    static Object elementOf(CollectionInput collection, String code, Object value) {
        boolean ok;
        if (value == null) ok = false;
        else if (collection.records()) ok = false;
        else ok = switch (collection.elementType) {
            case "DECIMAL" -> value instanceof BigDecimal;
            case "DATE" -> value instanceof LocalDateTime;
            default -> value instanceof String text && !text.isBlank();
        };
        if (!ok)
            throw new IllegalArgumentException(collection.owner + " 的参数 " + code + " 的类型应为 " + collection.elementType
                    + "（与集合 " + collection.describe() + " 的元素一致）" + (value == null ? "，实际缺少取值" : "，实际为 " + value));
        return value;
    }

    /**
     * 两个集合做并 / 交 / 差或交叉成员判断前的类型核对：元素类型须一致，且都是字符串 / 数值 / 日期清单
     * （对象集合的记录不做相等比较，不能参与这类运算）。
     */
    static void sameElements(CollectionInput a, CollectionInput b) {
        if (a.records() || b.records() || !a.elementType.equals(b.elementType))
            throw new IllegalArgumentException(a.owner + " 的参数 " + a.code + "（" + a.describe() + "）与参数 " + b.code + "（"
                    + b.describe() + "）的元素类型不一致或为对象集合，不能一起做集合运算");
    }

    /** 计算结果：与输入同类型的集合（元素已校验过）。来源标记为产生它的函数。 */
    RuleList derived(String function, List<?> result) {
        return derived(function, result, sensitive);
    }

    /** 同上；并集等合并了另一个集合的结果按两个输入综合判断是否为个人数据清单。 */
    RuleList derived(String function, List<?> result, boolean sensitiveResult) {
        if (schema != null) return RuleList.derivedRecords("FUNCTION:" + function, schema, result);
        return RuleList.derived("FUNCTION:" + function, dataType, result, sensitiveResult);
    }

    /** 形态说明，供报错。 */
    String describe() {
        return dataType + (schema == null ? "" : "·" + schema.code()) + "（" + source + "）";
    }

    /** 集合里的对象记录（OBJECT_LIST 专用）；不是对象集合时报错。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(CollectionInput input) {
        if (!input.records())
            throw new IllegalArgumentException(input.owner + " 的参数 " + input.code + " 应为对象集合 OBJECT_LIST，实际为 " + input.describe());
        return input.elements().stream().map(element -> (Map<String, Object>) element).toList();
    }

    /** 按元素结构校验过的对象记录清单转回集合（过滤 / 排序之后的结果）。 */
    static RuleList recordsOf(String function, ElementSchema schema, List<Map<String, Object>> rows) {
        return RuleList.derivedRecords("FUNCTION:" + function, schema, rows);
    }

    static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal number ? number : BigDecimal.valueOf((Long) value);
    }
}
