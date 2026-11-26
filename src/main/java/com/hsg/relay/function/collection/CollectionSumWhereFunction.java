package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * collectionSumWhere（按条件合计）：先按谓词条件筛选元素，再对留下的元素求合计（对象集合须用取值属性 valueSelector）。
 * 没有元素满足条件时返回 0；被筛掉的记录不参与合计，留下的记录取不到数值时报错，不会静默跳过。
 * 例：C8 偏好规则命中后的加分合计（封顶另由 numberMin 完成）。
 */
@Component
public class CollectionSumWhereFunction extends CollectionFunction {
    public static final String CODE = "collectionSumWhere";

    public CollectionSumWhereFunction() {
        super(CODE, "DECIMAL", null, p("collection", COLLECTION, true, "集合"), p("predicate", PREDICATE, true, "条件"),
                p("valueSelector", STRING, false, "取值属性"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        ElementPredicate predicate = predicate("predicate", arguments);
        String selector = CollectionSumFunction.optionalText(arguments, "valueSelector");
        List<Object> matched = new ArrayList<>();
        for (Object element : collection.elements()) if (predicate.test(element, context)) matched.add(element);
        List<BigDecimal> values = CollectionNumbers.values(collection.subset(matched), selector, "valueSelector");
        return Outcome.of(CollectionNumbers.sum(values));
    }
}
