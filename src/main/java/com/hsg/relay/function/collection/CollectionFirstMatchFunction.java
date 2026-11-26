package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.SortField;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * collectionFirstMatch（取第一条满足条件的记录）：按排序字段（sortFields，可省略，省略时按原有顺序）依次找第一条满足谓词的记录。
 * <p>排序是显式的优先级：多字段、ASC / DESC，排序键相等的记录保持原有先后，属性为空的记录排在末尾。
 * 没有记录满足条件时抛出 {@link CollectionFunction.NoMatch}（受控的「未命中」，与取数失败、执行异常区分开），
 * 不返回 null，也不随便返回一条记录。返回类型 OBJECT，属性按元素结构给出，可供后置函数引用。
 */
@Component
public class CollectionFirstMatchFunction extends CollectionFunction {
    public static final String CODE = "collectionFirstMatch";

    public CollectionFirstMatchFunction() {
        super(CODE, "OBJECT", null, p("collection", COLLECTION, true, "记录集合"), p("predicate", PREDICATE, true, "匹配条件"),
                p("sortFields", SORT_FIELDS, false, "排序字段（优先级）"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        if (!collection.records())
            throw new IllegalArgumentException(collection.owner + " 的参数 collection 应为对象集合 OBJECT_LIST，实际为 " + collection.describe());
        List<SortField> fields = arguments.get("sortFields") == null ? List.of() : sortFields("sortFields", arguments);
        List<Map<String, Object>> rows = CollectionSort.sorted(collection, fields);
        int index = 0;
        for (Map<String, Object> row : rows) {
            if (predicate("predicate", arguments).test(row, context))
                return new Outcome(row, String.valueOf(collection.schema.recordId(row, index)));
            index++;
        }
        throw new NoMatch(owner() + "：没有满足条件的记录");
    }
}
