package com.hsg.relay.function;

import java.util.List;
import java.util.Set;

/**
 * 函数库、元数据字段和条件节点共用的类型编码与来源编码（白名单，页面和元数据只能从这里选）。
 */
public final class FunctionTypes {
    private FunctionTypes() {
    }

    public static final String STRING = "STRING", INTEGER = "INTEGER", DECIMAL = "DECIMAL", BOOLEAN = "BOOLEAN", DATE = "DATE", ENUM = "ENUM";

    /** 集合类型（元数据字段的 dataType、函数返回的实际类型）。 */
    public static final Set<String> COLLECTIONS = Set.of("STRING_SET", "STRING_LIST", "NUMBER_LIST", "DATE_LIST", "OBJECT_LIST");

    /** ELEMENT：单个元素的参数，类型跟随集合参数的元素类型（STRING / DECIMAL / DATE），编译期确定。 */
    /** 函数参数 / 返回值专用的类型：COLLECTION 任意集合，OBJECT 单个对象，MAP 分组结果，PREDICATE 元素谓词，SORT_FIELDS 排序字段。 */
public static final String COLLECTION = "COLLECTION", OBJECT = "OBJECT", MAP = "MAP", PREDICATE = "PREDICATE",
            SORT_FIELDS = "SORT_FIELDS", ELEMENT = "ELEMENT", NUMBER_LIST = "NUMBER_LIST", DATE_LIST = "DATE_LIST", OBJECT_LIST = "OBJECT_LIST",
            STRING_SET = "STRING_SET", STRING_LIST = "STRING_LIST";

    /** 元数据字段可以使用的全部数据类型。 */
    public static final List<String> FIELD_TYPES = List.of(STRING, ENUM, INTEGER, DECIMAL, BOOLEAN, DATE, STRING_SET, STRING_LIST,
            NUMBER_LIST, DATE_LIST, OBJECT_LIST, MAP, OBJECT);

    /** 函数参数可以使用的全部类型。 */
    public static final List<String> PARAMETER_TYPES = List.of(STRING, INTEGER, DECIMAL, BOOLEAN, DATE, STRING_SET, STRING_LIST,
            NUMBER_LIST, DATE_LIST, OBJECT_LIST, COLLECTION, MAP, OBJECT, PREDICATE, SORT_FIELDS, ELEMENT);

    /** 函数返回值可以使用的全部类型。 */
    public static final List<String> RETURN_TYPES = List.of(BOOLEAN, STRING, INTEGER, DECIMAL, DATE, STRING_SET, STRING_LIST,
            NUMBER_LIST, DATE_LIST, OBJECT_LIST, COLLECTION, MAP, OBJECT);

    /** 可以直接作为条件比较的类型（有比较运算符）；其余类型只能被后置函数引用。 */
    public static final List<String> COMPARABLE = List.of(BOOLEAN, STRING, INTEGER, DECIMAL, DATE);

    public static boolean isCollection(String type) {
        return type != null && COLLECTIONS.contains(type);
    }

    public static boolean isComparable(String type) {
        return type != null && COMPARABLE.contains(type);
    }
}
