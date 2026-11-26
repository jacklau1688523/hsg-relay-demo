package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.SortField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * collectionSort（排序）：对象集合按 1~5 个属性排序（多字段依次比较，各字段 ASC / DESC；STRING / INTEGER / DECIMAL / BOOLEAN / DATE），
 * 排序稳定（排序键相等的记录保持原有先后），属性为空的记录一律排在末尾。在副本上排序，输入集合不变；结果仍为 OBJECT_LIST。
 */
@Component
public class CollectionSortFunction extends CollectionFunction {
    public static final String CODE = "collectionSort";

    public CollectionSortFunction() {
        super(CODE, "COLLECTION", "collection", p("collection", COLLECTION, true, "记录集合"), p("sortFields", SORT_FIELDS, true, "排序字段"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        List<SortField> fields = CollectionSort.fields(arguments.get("sortFields"), owner(), "sortFields");
        return Outcome.of(CollectionInput.recordsOf(CODE, collection.schema, CollectionSort.sorted(collection, fields)));
    }
}
