package com.hsg.relay.function.collection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** 集合运算的小工具：按首次出现的顺序去重、按条件筛选。 */
final class CollectionFunctionSupport {
    private CollectionFunctionSupport() {
    }

    /** 去重：每个元素只保留第一次出现的位置；数值按数值相等（1.0 与 1.00 是同一个元素）。 */
    static List<Object> distinct(List<?> elements) {
        List<Object> result = new ArrayList<>();
        LinkedHashSet<Object> seen = new LinkedHashSet<>();
        for (Object element : elements) if (seen.add(CollectionInput.key(element))) result.add(element);
        return result;
    }

    /** 左边集合中满足条件的元素（已去重，保持左边集合的先后顺序）。 */
    static List<Object> kept(CollectionInput left, java.util.function.Predicate<Object> keep) {
        List<Object> result = new ArrayList<>();
        for (Object element : left.elements()) if (keep.test(element)) result.add(element);
        return distinct(result);
    }
}
