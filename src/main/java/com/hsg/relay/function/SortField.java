package com.hsg.relay.function;

/**
 * 排序字段：OBJECT_LIST 按哪个属性、升序（ASC）还是降序（DESC）。多字段排序按清单顺序依次比较；
 * 属性值为空的记录统一排在末尾（升降序都一样），不参与相互比较。
 */
public record SortField(String field, String direction) {
    public static final String ASC = "ASC", DESC = "DESC";

    public SortField {
        direction = direction == null || direction.isBlank() ? ASC : direction.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public boolean descending() {
        return DESC.equals(direction);
    }

    @Override
    public String toString() {
        return field + " " + direction;
    }
}
