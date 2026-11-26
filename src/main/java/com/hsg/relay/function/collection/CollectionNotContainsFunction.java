package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionNotContains（集合是否不包含指定值）：集合里没有这个元素返回 true，有则 false；已确认的空集合返回 true。
 * 集合未加载、元素类型不符时报错，不会按 true 处理。只做计算，不查询业务表。
 */
@Component
public class CollectionNotContainsFunction extends CollectionFunction {
    public static final String CODE = "collectionNotContains";

    public CollectionNotContainsFunction() {
        super(CODE, "BOOLEAN", null, p("collection", COLLECTION, true, "集合"), e("element", true, "元素"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        Object element = CollectionInput.elementOf(collection, "element", arguments.get("element"));
        return Outcome.of(!collection.contains(element));
    }
}
