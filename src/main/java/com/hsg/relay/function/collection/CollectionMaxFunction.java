package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionMax（最大值）：NUMBER_LIST 取元素本身；OBJECT_LIST 须用取值属性（valueSelector）指定数值属性。BigDecimal 比较。
 * 空集合没有最大值，报「没有可计算的最大值」，不返回 0 或 null。
 */
@Component
public class CollectionMaxFunction extends CollectionFunction {
    public static final String CODE = "collectionMax";

    public CollectionMaxFunction() {
        super(CODE, "DECIMAL", null, p("collection", COLLECTION, true, "集合"), p("valueSelector", STRING, false, "取值属性"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        return Outcome.of(CollectionNumbers.pick(collection.owner, CollectionNumbers.values(collection,
                CollectionSumFunction.optionalText(arguments, "valueSelector"), "valueSelector"), false, "最大值"));
    }
}
