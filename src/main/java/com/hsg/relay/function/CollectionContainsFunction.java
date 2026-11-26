package com.hsg.relay.function;

import com.hsg.relay.function.field.RuleCollection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通用函数 collectionContains（集合是否包含指定值）：集合里有这个元素返回 true，否则 false；空集合返回 false。
 * <p>集合来自元数据字段（如 providerExcludedClientIds，由字段解析器取数），元素可来自交易、候选路由、上下文或另一个字段。
 * 本函数只做计算，不查询业务表；集合数据未加载时由解析器报错，不会按 false 处理。
 */
@Component
public class CollectionContainsFunction implements BusinessFunction {
    public static final String CODE = "collectionContains";

    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("collection", CollectionArguments.SET, CollectionArguments.ANY, true),
            new Parameter("element", "STRING", CollectionArguments.ANY, true));

    @Override
    public String handlerCode() {
        return CODE;
    }

    @Override
    public String returnType() {
        return "BOOLEAN";
    }

    @Override
    public String dataSource() {
        return "ARGUMENT";
    }

    @Override
    public List<Parameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        String owner = "函数 " + CODE;
        RuleCollection collection = CollectionArguments.collection(owner, "collection", arguments.get("collection"));
        String element = CollectionArguments.element(owner, "element", arguments.get("element"));
        RuleCollection.Lookup lookup = collection.lookup(element);
        return lookup.hit() ? new Outcome(true, lookup.recordId()) : Outcome.of(false);
    }
}
