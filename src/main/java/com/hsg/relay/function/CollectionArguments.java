package com.hsg.relay.function;

import com.hsg.relay.function.field.RuleCollection;

import java.util.Map;

/**
 * 通用集合函数共用的实参检查：只认 {@link RuleCollection}（集合字段 / 集合参数）和字符串元素，
 * 类型不符时给出带参数名的明确错误。函数本身只做计算，不查询业务表。
 */
final class CollectionArguments {
    private CollectionArguments() {
    }

    static final String SET = "STRING_SET";
    /** 参数来源 ANY：CANDIDATE / TRANSACTION / CONTEXT / FIELD 都可以，但必须显式绑定（不按参数名猜路径）。 */
    static final String ANY = "ANY";

    static RuleCollection collection(String owner, String code, Object value) {
        if (value instanceof RuleCollection collection) return collection;
        // null 表示数据没有加载（解析器未返回结果）：不能当成空集合
        throw new IllegalArgumentException(owner + " 的参数 " + code + " 应为集合（" + SET + "），实际为"
                + (value == null ? "空（数据未加载）" : value.getClass().getSimpleName() + " " + value));
    }

    static String element(String owner, String code, Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalArgumentException(owner + " 的参数 " + code + " 应为非空字符串（STRING 集合元素），实际为"
                + (value == null ? "空" : value.getClass().getSimpleName() + " " + value));
    }
}
