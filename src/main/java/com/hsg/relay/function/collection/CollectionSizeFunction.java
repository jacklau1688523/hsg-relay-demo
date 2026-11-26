package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionSize（集合的元素个数）：LIST 按元素个数计（重复元素各算一个）；STRING_SET 已去重，按去重后的个数计。
 * 空集合返回 0。集合未加载、或处于成员查询模式（未全量加载、无法得知个数）时报错，不会按 0 处理。
 */
@Component
public class CollectionSizeFunction extends CollectionFunction {
    public static final String CODE = "collectionSize";

    public CollectionSizeFunction() {
        super(CODE, "INTEGER", null, p("collection", COLLECTION, true, "集合"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        return Outcome.of((long) collection("collection", arguments).size());
    }
}
