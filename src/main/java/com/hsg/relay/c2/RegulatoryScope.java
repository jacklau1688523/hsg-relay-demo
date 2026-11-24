package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * 监管规则的适用范围：一个交易币种 + 受益行国家（ISO 3166-1 alpha-2 两位代码，ALL 表示不限国家）。
 * 范围以固定的标准条件树落库（condition_json）；旧版页面保存的自由条件树不是这个形态，称为旧版规则。
 */
public final class RegulatoryScope {
    public static final String ANY_COUNTRY = "ALL";
    public static final String CURRENCY_FIELD = "paymentCurrency";
    public static final String COUNTRY_FIELD = "beneficiaryBankCountry";

    private static final Set<String> ISO_CODES = Set.copyOf(Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2));
    private static final List<Country> COUNTRIES = countries();

    public record Country(String code, String name) {
    }

    public record Scope(String currency, String bankCountry) {
    }

    private RegulatoryScope() {
    }

    private static List<Country> countries() {
        List<Country> result = new ArrayList<>();
        result.add(new Country(ANY_COUNTRY, "全部国家"));
        for (String code : ISO_CODES.stream().sorted().toList()) {
            // 中文名称取自 JDK 语言数据；缺少语言数据时返回代码本身，此时只展示代码
            String name = new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.SIMPLIFIED_CHINESE);
            result.add(new Country(code, name.isBlank() || name.equals(code) ? "" : name));
        }
        return List.copyOf(result);
    }

    /** 受益行国家下拉：ALL 在首位，其后是全部 ISO 3166-1 两位代码（按代码排序）及中文名称。 */
    public static List<Country> options() {
        return COUNTRIES;
    }

    public static boolean isCountry(String code) {
        return ISO_CODES.contains(code);
    }

    /** 页面或接口提交的范围 → 标准条件树：币种等于 X，且（指定了国家时）受益行国家等于 Y。 */
    public static JsonNode tree(String currency, String bankCountry, ObjectMapper mapper) {
        ObjectNode group = mapper.createObjectNode();
        group.put("operator", "AND");
        ArrayNode children = group.putArray("children");
        leaf(children, CURRENCY_FIELD, code(currency));
        if (!ANY_COUNTRY.equals(code(bankCountry))) leaf(children, COUNTRY_FIELD, code(bankCountry));
        return group;
    }

    /** 已落库的条件树 → 适用范围；不是标准形态（旧版自由条件）时返回 null。 */
    public static Scope of(JsonNode tree) {
        if (tree == null || !tree.isObject() || !"AND".equals(tree.path("operator").asText())) return null;
        JsonNode children = tree.get("children");
        if (children == null || !children.isArray() || children.isEmpty() || children.size() > 2) return null;
        String currency = null, country = null;
        for (JsonNode child : children) {
            String value = singleValue(child);
            String field = child.path("field").asText();
            if (value == null) return null;
            if (CURRENCY_FIELD.equals(field) && currency == null) currency = value;
            else if (COUNTRY_FIELD.equals(field) && country == null) country = value;
            else return null;
        }
        return currency == null ? null : new Scope(currency, country == null ? ANY_COUNTRY : country);
    }

    /** 叶子条件的取值：EQ 的文本值，或 IN 里仅有的一个值；其余（条件组、NE、多值 IN）都不是标准形态。 */
    private static String singleValue(JsonNode leaf) {
        if (leaf == null || !leaf.isObject() || leaf.has("children")) return null;
        JsonNode value = leaf.get("value");
        JsonNode item = null;
        if ("EQ".equals(leaf.path("operator").asText())) item = value;
        else if ("IN".equals(leaf.path("operator").asText()) && value != null && value.isArray() && value.size() == 1) item = value.get(0);
        if (item == null || !item.isTextual() || item.asText().isBlank()) return null;
        return code(item.asText());
    }

    private static void leaf(ArrayNode children, String field, String value) {
        children.addObject().put("field", field).put("operator", "EQ").put("value", value);
    }

    /** 交易的币种、受益行国家是否落在范围内；比较口径与原条件编译器一致（去空白、转大写）。 */
    public static boolean matches(String currency, String bankCountry, String paymentCurrency, String beneficiaryBankCountry) {
        if (!code(currency).equals(code(paymentCurrency))) return false;
        return ANY_COUNTRY.equals(code(bankCountry)) || code(bankCountry).equals(code(beneficiaryBankCountry));
    }

    public static String text(String currency, String bankCountry) {
        String country = code(bankCountry);
        return "交易币种 = " + code(currency) + " 且 受益行国家 = " + (ANY_COUNTRY.equals(country) ? "不限（ALL）" : country);
    }

    /** 旧版自由条件树的摘要，如「(paymentCurrency EQ CNY 或 purpose EQ GOODS)」。 */
    public static String legacyText(JsonNode node) {
        if (node == null || !node.isObject()) return "—";
        if (node.has("children")) {
            JsonNode children = node.get("children");
            List<String> parts = new ArrayList<>();
            if (children.isArray()) for (JsonNode child : children) parts.add(legacyText(child));
            return "(" + String.join("OR".equals(node.path("operator").asText()) ? " 或 " : " 且 ", parts) + ")";
        }
        JsonNode value = node.get("value");
        String text = value == null ? "" : value.isTextual() ? value.asText() : value.toString();
        return node.path("field").asText() + " " + node.path("operator").asText() + " " + text;
    }

    private static String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
