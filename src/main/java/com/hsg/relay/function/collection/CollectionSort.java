package com.hsg.relay.function.collection;

import com.hsg.relay.function.SortField;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.RuleList;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 对象集合的排序比较：多字段、升降序、STRING / INTEGER / DECIMAL / BOOLEAN / DATE，稳定（排序键相等的记录保持原有先后），
 * 属性为空的记录一律排在末尾（升降序都一样，空值之间保持原有先后）。排序在副本上进行，不修改输入集合。
 */
final class CollectionSort {
    /** 排序字段上限。 */
    static final int MAX_FIELDS = 5;

    private CollectionSort() {
    }

    /** 排序字段清单：OBJECT_LIST 须 1~5 项（属性名与类型在编译期已按元素结构校验）。 */
    static List<SortField> fields(Object argument, String owner, String parameter) {
        if (!(argument instanceof List<?> list))
            throw new IllegalArgumentException(owner + " 的参数 " + parameter + " 缺少排序字段");
        List<SortField> fields = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof SortField field))
                throw new IllegalArgumentException(owner + " 的参数 " + parameter + " 含无效的排序字段");
            fields.add(field);
        }
        if (fields.isEmpty() || fields.size() > MAX_FIELDS)
            throw new IllegalArgumentException(owner + " 的参数 " + parameter + " 须为 1~" + MAX_FIELDS + " 项");
        return fields;
    }

    /** 按 sortFields 排序后的记录清单（副本）；fields 为空时保持原有顺序。 */
    static List<Map<String, Object>> sorted(CollectionInput input, List<SortField> fields) {
        List<Map<String, Object>> rows = CollectionInput.rows(input);
        if (fields == null || fields.isEmpty()) return rows;
        Comparator<Map<String, Object>> comparator = null;
        for (SortField field : fields) comparator = comparator == null ? order(input, field) : comparator.thenComparing(order(input, field));
        List<Map<String, Object>> copy = new ArrayList<>(rows);
        copy.sort(comparator);   // List.sort 稳定：排序键相等的记录保持原有先后
        return copy;
    }

    /** 单个排序字段：先按取值比较，再按方向取反；空值恒排在末尾。 */
    private static Comparator<Map<String, Object>> order(CollectionInput input, SortField field) {
        ElementSchema.Attribute attribute;
        try {
            attribute = input.schema.attribute(field.field());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(input.owner + " 的排序字段 " + field.field() + " 不在元素结构 " + input.schema.code() + " 里");
        }
        if (RuleList.STRING_SET.equals(attribute.type()))
            throw new IllegalArgumentException(input.owner + " 的排序字段 " + field.field() + " 是集合类型，不能排序");
        Comparator<Object> value = switch (attribute.type()) {
            case ElementSchema.INTEGER -> Comparator.comparingLong(v -> (Long) v);
            case ElementSchema.DECIMAL -> Comparator.comparing(v -> (BigDecimal) v);
            case ElementSchema.DATE -> Comparator.comparing(v -> (LocalDateTime) v);
            case ElementSchema.BOOLEAN -> Comparator.comparing(v -> (Boolean) v);
            default -> Comparator.comparing(v -> (String) v);
        };
        return (x, y) -> {
            Object a = x.get(field.field()), b = y.get(field.field());
            // 空值排在末尾：升降序都一样；两个空值返回 0，交给稳定排序保持原有先后
            if (a == null || b == null) return a == b ? 0 : a == null ? 1 : -1;
            int result = value.compare(a, b);
            return field.descending() ? -result : result;
        };
    }
}
