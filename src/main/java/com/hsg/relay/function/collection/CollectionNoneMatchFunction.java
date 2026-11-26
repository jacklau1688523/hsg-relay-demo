package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionNoneMatch（是否没有记录满足条件）：没有任何记录满足谓词返回 true，有一条满足就返回 false；已确认的空集合返回 true。
 */
@Component
public class CollectionNoneMatchFunction extends CollectionFunction {
    public static final String CODE = "collectionNoneMatch";

    public CollectionNoneMatchFunction() {
        super(CODE, "BOOLEAN", null, p("collection", COLLECTION, true, "记录集合"), p("predicate", PREDICATE, true, "排除条件"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        return Outcome.of(!CollectionMatchSupport.any(collection("collection", arguments), predicate("predicate", arguments), context));
    }
}
