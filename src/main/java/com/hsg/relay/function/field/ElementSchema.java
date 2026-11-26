package com.hsg.relay.function.field;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * OBJECT_LIST 的元素结构（代码登记，不是页面自由填写）：对象记录有哪些属性、各自是什么类型、能否为空、支持哪些运算符。
 * 集合函数和谓词只能按这里登记的属性名取值，不能借属性名访问任何 Java 对象的其它内容。
 * <p>属性类型：STRING、INTEGER、DECIMAL、BOOLEAN、DATE、STRING_SET。数值一律 BigDecimal / 整数，不接受 double、float；
 * 日期规整为 LocalDateTime（纯日期取当天零点）。
 */
public record ElementSchema(String code, String name, List<Attribute> attributes, String idAttribute) {

    public static final String STRING = "STRING", INTEGER = "INTEGER", DECIMAL = "DECIMAL", BOOLEAN = "BOOLEAN",
            DATE = "DATE", STRING_SET = "STRING_SET";

    /** 属性类型清单；属性类型不在此列的结构不能登记。 */
    public static final List<String> TYPES = List.of(STRING, INTEGER, DECIMAL, BOOLEAN, DATE, STRING_SET);

    public ElementSchema {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("元素结构缺少编码");
        if (attributes == null || attributes.isEmpty()) throw new IllegalArgumentException("元素结构 " + code + " 没有属性");
        attributes = List.copyOf(attributes);
        Set<String> codes = new HashSet<>();
        for (Attribute attribute : attributes) {
            if (!codes.add(attribute.code())) throw new IllegalArgumentException("元素结构 " + code + " 的属性重复：" + attribute.code());
            if (!TYPES.contains(attribute.type()))
                throw new IllegalArgumentException("元素结构 " + code + " 的属性 " + attribute.code() + " 类型不支持：" + attribute.type());
        }
        if (idAttribute != null && !codes.contains(idAttribute))
            throw new IllegalArgumentException("元素结构 " + code + " 的记录 ID 属性不存在：" + idAttribute);
    }

    public ElementSchema(String code, String name, List<Attribute> attributes) {
        this(code, name, attributes, null);
    }

    /** 一个属性：code 属性名（记录里的键）、name 中文名、type 类型、nullable 是否允许为空、operators 可用的比较运算符。 */
    public record Attribute(String code, String name, String type, boolean nullable, List<String> operators) {
        public Attribute {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("属性缺少编码");
            operators = operators == null ? List.of() : List.copyOf(operators);
        }

        public Attribute(String code, String name, String type) {
            this(code, name, type, false, defaultOperators(type));
        }

        public Attribute(String code, String name, String type, boolean nullable) {
            this(code, name, type, nullable, defaultOperators(type));
        }

        /** 各类型的默认比较运算符；空值不参与比较。 */
        public static List<String> defaultOperators(String type) {
            return switch (type == null ? "" : type) {
                case INTEGER, DECIMAL -> List.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
                case DATE -> List.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
                case BOOLEAN, STRING_SET -> List.of("EQ", "NE");
                default -> List.of("EQ", "NE", "IN", "NOT_IN", "CONTAINS", "NOT_CONTAINS");
            };
        }
    }

    /** 按属性名取属性声明；没有则报错（不会回落到其它对象属性）。 */
    public Attribute attribute(String attributeCode) {
        for (Attribute attribute : attributes) if (attribute.code().equals(attributeCode)) return attribute;
        throw new IllegalArgumentException("元素结构 " + code + " 没有属性 " + attributeCode);
    }

    /**
     * 校验并规整一条记录：属性须与结构完全一致（不缺、不多），取值类型须与声明一致；声明为可空的属性允许为 null。
     * where 是出错时的位置说明（如「集合 X 第 3 个元素」）。
     */
    public Map<String, Object> normalize(Map<?, ?> row, String where) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Attribute attribute : attributes) {
            boolean present = row.containsKey(attribute.code());
            Object value = present ? row.get(attribute.code()) : null;
            if (value == null) {
                if (attribute.nullable()) normalized.put(attribute.code(), null);
                else throw new IllegalArgumentException(where + " 缺少属性 " + code + "." + attribute.code() + "（数据不完整）");
                continue;
            }
            try {
                normalized.put(attribute.code(), normalizeValue(attribute.type(), value));
            } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException ex) {
                throw new IllegalArgumentException(where + " 的属性 " + code + "." + attribute.code() + " 应为 " + attribute.type()
                        + "，实际为 " + value.getClass().getSimpleName() + " " + value);
            }
        }
        for (Object key : row.keySet())
            if (!normalized.containsKey(String.valueOf(key)))
                throw new IllegalArgumentException(where + " 含元素结构 " + code + " 未定义的属性：" + key);
        return Collections.unmodifiableMap(normalized);
    }

    /** 记录是否已符合本结构（属性集合一致且取值合法）。 */
    public boolean matches(Map<?, ?> row) {
        if (row == null || row.size() != attributes.size()) return false;
        for (Attribute attribute : attributes) {
            if (!row.containsKey(attribute.code())) return false;
            Object value = row.get(attribute.code());
            if (value == null) {
                if (!attribute.nullable()) return false;
                continue;
            }
            try {
                if (!normalizeValue(attribute.type(), value).equals(value)) return false;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return true;
    }

    /** 记录 ID（试算展示用）：结构的 ID 属性；没有 ID 属性时用行号。 */
    public Object recordId(Object element, int index) {
        if (idAttribute != null && element instanceof Map<?, ?> row && row.get(idAttribute) != null) return row.get(idAttribute);
        return "#" + (index + 1);
    }

    /** 展示形态（接口返回用）：编码、名称、属性清单、记录 ID 属性。 */
    public Map<String, Object> view() {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("code", code);
        shown.put("name", name);
        shown.put("idAttribute", idAttribute);
        List<Map<String, Object>> listed = new ArrayList<>();
        for (Attribute attribute : attributes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", attribute.code());
            item.put("name", attribute.name());
            item.put("type", attribute.type());
            item.put("nullable", attribute.nullable());
            item.put("operators", attribute.operators());
            listed.add(Collections.unmodifiableMap(item));
        }
        shown.put("attributes", Collections.unmodifiableList(listed));
        return Collections.unmodifiableMap(shown);
    }

    /** 单个取值按属性类型规整；类型不符时抛 IllegalArgumentException。 */
    public static Object normalizeValue(String type, Object value) {
        if (value == null) throw new IllegalArgumentException("取值为空");
        return switch (type) {
            case STRING -> value instanceof String text && !text.isBlank() ? text : reject(value, type);
            case INTEGER -> whole(value, type);
            case DECIMAL -> {
                BigDecimal decimal = RuleList.decimal(value);
                if (decimal == null) throw new IllegalArgumentException("应为 " + type + "，实际为 " + value);
                yield decimal;
            }
            case BOOLEAN -> value instanceof Boolean flag ? flag : reject(value, type);
            case DATE -> {
                LocalDateTime date = RuleList.date(value);
                if (date == null) throw new IllegalArgumentException("应为 " + type + "，实际为 " + value);
                yield date;
            }
            case STRING_SET -> strings(value);
            default -> throw new IllegalArgumentException("属性类型不支持：" + type);
        };
    }

    /** 类型不符：统一抛错；声明为泛型只为让调用处的返回类型成立，实际不会返回。 */
    private static <T> T reject(Object value, String type) {
        throw new IllegalArgumentException("应为 " + type + "，实际为 " + value.getClass().getSimpleName() + " " + value);
    }

    /** 整数：Integer / Long / BigInteger / 无小数部分的 BigDecimal，规整为 Long；超出 Long 范围或不合法时报错。 */
    static Long whole(Object value, String type) {
        try {
            if (value instanceof Integer number) return number.longValue();
            if (value instanceof Long number) return number;
            if (value instanceof BigInteger number) return number.longValueExact();
            if (value instanceof BigDecimal number) return number.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("超出整数范围：" + value);
        }
        return reject(value, type);
    }

    private static Set<String> strings(Object value) {
        if (!(value instanceof Collection<?> items)) return reject(value, STRING_SET);
        Set<String> normalized = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) return reject(item, STRING_SET);
            normalized.add(text);
        }
        return Collections.unmodifiableSet(normalized);
    }

    /** 展示用的属性取值：日期只到天，其余原样。 */
    public static Object display(Object value) {
        if (value instanceof LocalDateTime time) return time.toLocalDate().toString();
        return value;
    }
}
