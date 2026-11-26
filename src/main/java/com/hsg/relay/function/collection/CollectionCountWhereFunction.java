package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionCountWhere（按条件计数）：满足谓词条件的元素个数。空集合或没有元素满足时返回 0。
 * 谓词逐个元素判断，比较对象没有取值时报错，不会按不满足处理。
 */
@Component
public class CollectionCountWhereFunction extends CollectionFunction {
    public static final String CODE = "collectionCountWhere";

    public CollectionCountWhereFunction() {
        super(CODE, "INTEGER", null, p("collection", COLLECTION, true, "集合"), p("predicate", PREDICATE, true, "条件"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        ElementPredicate predicate = predicate("predicate", arguments);
        long count = 0;
        for (Object element : collection.elements()) if (predicate.test(element, context)) count++;
        return Outcome.of(count);
    }
}
