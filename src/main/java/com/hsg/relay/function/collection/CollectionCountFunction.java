package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionCount（计数）：集合的元素个数。空集合返回 0；集合未加载或处于成员查询模式（无法得知个数）时报错，不会按 0 处理。
 */
@Component
public class CollectionCountFunction extends CollectionFunction {
    public static final String CODE = "collectionCount";

    public CollectionCountFunction() {
        super(CODE, "INTEGER", null, p("collection", COLLECTION, true, "集合"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        return Outcome.of((long) collection("collection", arguments).size());
    }
}
