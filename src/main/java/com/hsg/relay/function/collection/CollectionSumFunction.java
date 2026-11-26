package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * collectionSum（合计）：NUMBER_LIST 取元素本身；OBJECT_LIST 须用取值属性（valueSelector）指定参与合计的数值属性。
 * 全程 BigDecimal。空集合返回 0；任何一条记录取不到数值（属性为空）都报错，不会静默跳过。
 */
@Component
public class CollectionSumFunction extends CollectionFunction {
    public static final String CODE = "collectionSum";

    public CollectionSumFunction() {
        super(CODE, "DECIMAL", null, p("collection", COLLECTION, true, "集合"), p("valueSelector", STRING, false, "取值属性"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        List<BigDecimal> values = CollectionNumbers.values(collection("collection", arguments),
                optionalText(arguments, "valueSelector"), "valueSelector");
        return Outcome.of(CollectionNumbers.sum(values));
    }

    /** 可选的字符串参数：没有绑定或为空时返回 null。 */
    static String optionalText(Map<String, Object> arguments, String parameter) {
        return arguments.get(parameter) instanceof String text && !text.isBlank() ? text : null;
    }
}
