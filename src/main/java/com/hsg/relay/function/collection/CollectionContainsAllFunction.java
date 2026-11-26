package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionContainsAll（集合是否包含必备元素的全部）：必备清单里每个元素都在集合里返回 true，有一个不在就返回 false。
 * 必备清单是已确认的空集合时返回 true（没有必备项）；必备清单未加载、两边元素类型不一致时报错。
 */
@Component
public class CollectionContainsAllFunction extends CollectionFunction {
    public static final String CODE = "collectionContainsAll";

    public CollectionContainsAllFunction() {
        super(CODE, "BOOLEAN", null, p("collection", COLLECTION, true, "集合"), p("required", COLLECTION, true, "必备元素"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        CollectionInput required = collection("required", arguments);
        CollectionInput.sameElements(collection, required);
        for (Object element : required.elements()) if (!collection.contains(element)) return Outcome.of(false);
        return Outcome.of(true);
    }
}
