package com.hsg.relay.function;

import com.hsg.relay.function.field.RuleCollection;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通用函数 collectionIntersects（两个集合是否存在交集）：有共同元素返回 true，否则 false；任一方为空集合返回 false。
 * <p>与 collectionContains 语义一致：交集判断就是对其中一个集合的每个元素做成员查询。
 * 两边都已加载时逐个比对；有一边是成员查询模式（未全量加载）时，枚举已加载的一方、对另一方做成员查询；
 * 两边都未加载则报错，不会为了求交集而全量加载。只做计算，不查询业务表。
 */
@Component
public class CollectionIntersectsFunction implements BusinessFunction {
    public static final String CODE = "collectionIntersects";

    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("leftCollection", CollectionArguments.SET, CollectionArguments.ANY, true),
            new Parameter("rightCollection", CollectionArguments.SET, CollectionArguments.ANY, true));

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
        RuleCollection left = CollectionArguments.collection(owner, "leftCollection", arguments.get("leftCollection"));
        RuleCollection right = CollectionArguments.collection(owner, "rightCollection", arguments.get("rightCollection"));
        // 优先枚举元素少的那一边：另一方按成员查询，对未全量加载的集合也只产生有限次查询
        RuleCollection enumerated = left.materialized() && (!right.materialized() || left.elements().size() <= right.elements().size())
                ? left : right;
        RuleCollection probed = enumerated == left ? right : left;
        if (!enumerated.materialized())
            throw new IllegalArgumentException(owner + " 的两个集合都未全量加载（数据量过大），无法枚举求交集；请改用 collectionContains 做成员判断");
        for (Object element : enumerated.elements()) {
            RuleCollection.Lookup lookup = probed.lookup(element);
            if (lookup.hit()) return new Outcome(true, lookup.recordId());
        }
        return Outcome.of(false);
    }
}
