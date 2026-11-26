package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * collectionFilter（按条件筛选）：保留满足谓词条件的元素，保持原有先后顺序（重复元素保留）。
 * 空集合结果仍为空集合。谓词逐个元素判断（标量清单用 value，对象集合用属性名），结果类型跟随输入。
 */
@Component
public class CollectionFilterFunction extends CollectionFunction {
    public static final String CODE = "collectionFilter";

    public CollectionFilterFunction() {
        super(CODE, "COLLECTION", "collection", p("collection", COLLECTION, true, "集合"), p("predicate", PREDICATE, true, "筛选条件"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        ElementPredicate predicate = predicate("predicate", arguments);
        List<Object> result = new ArrayList<>();
        for (Object element : collection.elements()) if (predicate.test(element, context)) result.add(element);
        return Outcome.of(collection.derived(CODE, result));
    }
}
