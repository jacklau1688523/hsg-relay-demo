package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * collectionAnyMatch（是否有一条记录满足条件）：至少一条记录满足谓词返回 true，否则 false；已确认的空集合返回 false。
 * 谓词逐条判断（属性名取自元素结构，可嵌套 AND / OR）。比较对象没有取值时报错，不会按 false 处理。
 */
@Component
public class CollectionAnyMatchFunction extends CollectionFunction {
    public static final String CODE = "collectionAnyMatch";

    public CollectionAnyMatchFunction() {
        super(CODE, "BOOLEAN", null, p("collection", COLLECTION, true, "记录集合"), p("predicate", PREDICATE, true, "匹配条件"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        return Outcome.of(CollectionMatchSupport.any(collection("collection", arguments), predicate("predicate", arguments), context));
    }
}
