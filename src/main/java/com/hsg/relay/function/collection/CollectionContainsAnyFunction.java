package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionContainsAny（集合是否包含候选元素中的任意一个）：候选清单里至少有一个元素在集合里返回 true，否则 false。
 * 候选清单是已确认的空集合时返回 false；候选清单未加载、两边元素类型不一致时报错。
 * 集合可以是成员查询模式（未全量加载）——逐个候选做成员查询，不会全量加载。
 */
@Component
public class CollectionContainsAnyFunction extends CollectionFunction {
    public static final String CODE = "collectionContainsAny";

    public CollectionContainsAnyFunction() {
        super(CODE, "BOOLEAN", null, p("collection", COLLECTION, true, "集合"), p("candidates", COLLECTION, true, "候选元素"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        CollectionInput candidates = collection("candidates", arguments);
        CollectionInput.sameElements(collection, candidates);
        for (Object candidate : candidates.elements()) if (collection.contains(candidate)) return Outcome.of(true);
        return Outcome.of(false);
    }
}
