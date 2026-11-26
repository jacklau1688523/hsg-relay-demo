package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionAllMatch（是否每条记录都满足条件）：全部记录满足谓词返回 true，有一条不满足就返回 false；已确认的空集合返回 true。
 */
@Component
public class CollectionAllMatchFunction extends CollectionFunction {
    public static final String CODE = "collectionAllMatch";

    public CollectionAllMatchFunction() {
        super(CODE, "BOOLEAN", null, p("collection", COLLECTION, true, "记录集合"), p("predicate", PREDICATE, true, "条件"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        ElementPredicate predicate = predicate("predicate", arguments);
        for (Object element : collection.elements()) if (!predicate.test(element, context)) return Outcome.of(false);
        return Outcome.of(true);
    }
}
