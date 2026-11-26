package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * collectionDifference（差集，有方向）：在左边集合里、但不在右边集合里的元素，按左边集合的先后顺序，已去重
 * （A − B 与 B − A 不同）。左边是已确认的空集合时结果为空集合。两边元素类型不一致时报错；结果类型跟随左边集合。
 */
@Component
public class CollectionDifferenceFunction extends CollectionFunction {
    public static final String CODE = "collectionDifference";

    public CollectionDifferenceFunction() {
        super(CODE, "COLLECTION", "leftCollection", p("leftCollection", COLLECTION, true, "左边集合"),
                p("rightCollection", COLLECTION, true, "右边集合"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput left = collection("leftCollection", arguments);
        CollectionInput right = collection("rightCollection", arguments);
        CollectionInput.sameElements(left, right);
        List<Object> result = CollectionFunctionSupport.kept(left, element -> !right.contains(element));
        return Outcome.of(left.derived(CODE, result));
    }
}
