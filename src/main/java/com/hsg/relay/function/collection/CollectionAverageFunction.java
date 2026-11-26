package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * collectionAvg（平均值）：合计 ÷ 个数，统一保留 6 位小数、四舍五入（HALF_UP），中间运算 DECIMAL128。
 * 空集合没有平均值，报「没有可计算的平均值」，不返回 0 或 null。
 */
@Component
public class CollectionAverageFunction extends CollectionFunction {
    public static final String CODE = "collectionAvg";

    public CollectionAverageFunction() {
        super(CODE, "DECIMAL", null, p("collection", COLLECTION, true, "集合"), p("valueSelector", STRING, false, "取值属性"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        List<BigDecimal> values = CollectionNumbers.values(collection,
                CollectionSumFunction.optionalText(arguments, "valueSelector"), "valueSelector");
        if (values.isEmpty()) throw noResult("平均值");
        return Outcome.of(CollectionNumbers.average(values));
    }
}
