package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * collectionUnion（并集）：左边集合的元素在前、右边集合的元素在后。
 * STRING_SET（集合语义）去重；STRING_LIST / NUMBER_LIST / DATE_LIST（清单语义）保持两边的全部元素，重复元素保留
 * （需要去重时接 collectionDistinct）。结果类型跟随左边集合。
 */
@Component
public class CollectionUnionFunction extends CollectionFunction {
    public static final String CODE = "collectionUnion";

    public CollectionUnionFunction() {
        super(CODE, "COLLECTION", "leftCollection", p("leftCollection", COLLECTION, true, "左边集合"),
                p("rightCollection", COLLECTION, true, "右边集合"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput left = collection("leftCollection", arguments);
        CollectionInput right = collection("rightCollection", arguments);
        CollectionInput.sameElements(left, right);
        List<Object> result = new ArrayList<>(left.elements());
        result.addAll(right.elements());
        if ("STRING_SET".equals(left.dataType)) result = CollectionFunctionSupport.distinct(result);
        return Outcome.of(left.derived(CODE, result, left.sensitive() || right.sensitive()));
    }
}
