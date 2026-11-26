package com.hsg.relay.function.collection;

import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.RuleExecutionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记录匹配函数共用的逐个判断：谓词只看「当前这一个元素」，条件不会跨记录拼接
 * （第 1 条记录满足一半、第 2 条满足另一半不算命中）。
 */
final class CollectionMatchSupport {
    private CollectionMatchSupport() {
    }

    /** 是否至少有一个元素满足条件。 */
    static boolean any(CollectionInput collection, ElementPredicate predicate, RuleExecutionContext context) {
        for (Object element : collection.elements()) if (predicate.test(element, context)) return true;
        return false;
    }

    /** 满足条件的对象记录（保持原有先后顺序）。 */
    static List<Map<String, Object>> matched(CollectionInput collection, ElementPredicate predicate, RuleExecutionContext context) {
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> row : CollectionInput.rows(collection)) if (predicate.test(row, context)) matched.add(row);
        return matched;
    }
}
