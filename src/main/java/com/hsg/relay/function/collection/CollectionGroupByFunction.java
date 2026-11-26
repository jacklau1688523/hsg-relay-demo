package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction.Outcome;
import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.RuleList;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * collectionGroupBy（分组）：按对象集合的一个属性（groupField）把记录分组，返回「分组键 → 记录集合」的 MAP；
 * 可选谓词先筛选再分组。分组顺序按该键第一次出现的位置，组内记录保持原有先后；属性为空的记录归到「（空）」组。
 * 没有记录时结果为空的 MAP（不是 null）。
 */
@Component
public class CollectionGroupByFunction extends CollectionFunction {
    public static final String CODE = "collectionGroupBy";
    /** 分组属性为空的记录所在的组。 */
    public static final String NULL_KEY = "（空）";

    public CollectionGroupByFunction() {
        super(CODE, "MAP", null, p("collection", COLLECTION, true, "记录集合"), p("groupField", STRING, true, "分组属性"),
                p("predicate", PREDICATE, false, "筛选条件"));
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        CollectionInput collection = collection("collection", arguments);
        List<Map<String, Object>> rows = CollectionInput.rows(collection);
        String groupField = text("groupField", arguments);
        ElementSchema.Attribute attribute;
        try {
            attribute = collection.schema.attribute(groupField);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(owner() + " 的分组属性 " + groupField + " 不在元素结构 " + collection.schema.code() + " 里");
        }
        if (ElementSchema.STRING_SET.equals(attribute.type()))
            throw new IllegalArgumentException(owner() + " 的分组属性 " + groupField + " 是集合类型，不能作为分组键");
        ElementPredicate predicate = optionalPredicate("predicate", arguments);
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            if (predicate != null && !predicate.test(row, context)) continue;
            Object value = row.get(groupField);
            groups.computeIfAbsent(value == null ? NULL_KEY : String.valueOf(ElementSchema.display(value)), key -> new java.util.ArrayList<>()).add(row);
        }
        Map<String, RuleList> result = new LinkedHashMap<>();
        groups.forEach((key, members) -> result.put(key, RuleList.derivedRecords("FUNCTION:" + CODE + " 分组 " + key, collection.schema, members)));
        return Outcome.of(Collections.unmodifiableMap(result));
    }
}
