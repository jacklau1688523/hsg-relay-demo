package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * collectionFilterRecords（筛选记录）：保留满足谓词的对象记录，保持原有先后顺序；没有记录满足时结果为空集合。
 * 结果仍为 OBJECT_LIST（同元素结构），可以继续接后续函数（计数、合计、排序、取首条等）。
 */
@Component
public class CollectionFilterRecordsFunction extends CollectionFunction {
    public static final String CODE = "collectionFilterRecords";

    public CollectionFilterRecordsFunction() {
        super(CODE, "COLLECTION", "collection", p("collection", COLLECTION, true, "记录集合"), p("predicate", PREDICATE, true, "筛选条件"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        List<Map<String, Object>> matched = CollectionMatchSupport.matched(collection, predicate("predicate", arguments), context);
        return Outcome.of(CollectionInput.recordsOf(CODE, collection.schema, matched));
    }
}
