package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * collectionDistinct（去重）：每个元素只保留第一次出现的位置，其余重复元素去掉；数值按数值相等（1.0 与 1.00 是同一个元素），
 * 字符串区分大小写。空集合结果仍为空集合。结果类型跟随输入。
 */
@Component
public class CollectionDistinctFunction extends CollectionFunction {
    public static final String CODE = "collectionDistinct";

    public CollectionDistinctFunction() {
        super(CODE, "COLLECTION", "collection", p("collection", COLLECTION, true, "集合"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        List<Object> result = CollectionFunctionSupport.distinct(collection.elements());
        return Outcome.of(collection.derived(CODE, result));
    }
}
