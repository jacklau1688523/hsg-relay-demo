package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionIsEmpty（集合是否为空）：已加载且元素个数为 0 返回 true，否则 false。
 * 集合未加载、或处于成员查询模式（未全量加载、无法得知个数）时报错——「不知道有多少元素」不等于「空集合」。
 */
@Component
public class CollectionIsEmptyFunction extends CollectionFunction {
    public static final String CODE = "collectionIsEmpty";

    public CollectionIsEmptyFunction() {
        super(CODE, "BOOLEAN", null, p("collection", COLLECTION, true, "集合"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        return Outcome.of(collection("collection", arguments).size() == 0);
    }
}
