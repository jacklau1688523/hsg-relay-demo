package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionCountMatch（满足条件的记录数）：满足谓词的记录个数；空集合或没有记录满足时返回 0。
 */
@Component
public class CollectionCountMatchFunction extends CollectionFunction {
    public static final String CODE = "collectionCountMatch";

    public CollectionCountMatchFunction() {
        super(CODE, "INTEGER", null, p("collection", COLLECTION, true, "记录集合"), p("predicate", PREDICATE, true, "匹配条件"));
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
