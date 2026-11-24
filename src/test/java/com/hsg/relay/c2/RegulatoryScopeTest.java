package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

// 标准条件树 ↔「币种 + 受益行国家」的互转；旧版页面保存的自由条件树必须被识别为旧版，而不是被误读成范围
class RegulatoryScopeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void currencyAndCountryRoundTripThroughTheCanonicalTree() {
        var twoLeaves = RegulatoryScope.tree(" cny ", "cn", mapper);
        assertEquals(read("""
                {"operator":"AND","children":[
                  {"field":"paymentCurrency","operator":"EQ","value":"CNY"},
                  {"field":"beneficiaryBankCountry","operator":"EQ","value":"CN"}
                ]}
                """), twoLeaves);
        assertEquals(new RegulatoryScope.Scope("CNY", "CN"), RegulatoryScope.of(twoLeaves));

        // 不限国家：只存币种一个叶子
        var oneLeaf = RegulatoryScope.tree("USD", "ALL", mapper);
        assertEquals(1, oneLeaf.get("children").size());
        assertEquals(new RegulatoryScope.Scope("USD", "ALL"), RegulatoryScope.of(oneLeaf));

        // 叶子顺序不限；单值 IN 与 EQ 等价
        assertEquals(new RegulatoryScope.Scope("EUR", "SG"), RegulatoryScope.of(read("""
                {"operator":"AND","children":[
                  {"field":"beneficiaryBankCountry","operator":"EQ","value":"SG"},
                  {"field":"paymentCurrency","operator":"IN","value":["eur"]}
                ]}
                """)));
    }

    @Test
    void freeFormTreesAreNotMistakenForAScope() {
        for (String json : new String[]{
                // 条件组逻辑不是 AND
                "{\"operator\":\"OR\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"CNY\"},{\"field\":\"beneficiaryBankCountry\",\"operator\":\"EQ\",\"value\":\"CN\"}]}",
                // 出现币种、受益行国家之外的字段
                "{\"operator\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"CNY\"},{\"field\":\"beneficiaryBankCountry\",\"operator\":\"EQ\",\"value\":\"CN\"},{\"field\":\"purpose\",\"operator\":\"EQ\",\"value\":\"GOODS\"}]}",
                // 同一字段两个取值
                "{\"operator\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"CNY\"},{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"USD\"}]}",
                // 排除类条件、多值 IN
                "{\"operator\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"CNY\"},{\"field\":\"beneficiaryBankCountry\",\"operator\":\"NE\",\"value\":\"CN\"}]}",
                "{\"operator\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"CNY\"},{\"field\":\"beneficiaryBankCountry\",\"operator\":\"IN\",\"value\":[\"CN\",\"HK\"]}]}",
                // 只有国家、没有币种；空条件组
                "{\"operator\":\"AND\",\"children\":[{\"field\":\"beneficiaryBankCountry\",\"operator\":\"EQ\",\"value\":\"CN\"}]}",
                "{\"operator\":\"AND\",\"children\":[]}"})
            assertNull(RegulatoryScope.of(read(json)), json);
        assertNull(RegulatoryScope.of(null));
        assertEquals("(paymentCurrency EQ CNY 或 purpose EQ GOODS)", RegulatoryScope.legacyText(read("""
                {"operator":"OR","children":[{"field":"paymentCurrency","operator":"EQ","value":"CNY"},{"field":"purpose","operator":"EQ","value":"GOODS"}]}
                """)));
    }

    @Test
    void matchingIgnoresCaseAndSurroundingSpaces() {
        assertTrue(RegulatoryScope.matches("CNY", "ALL", " cny ", null));
        assertTrue(RegulatoryScope.matches("CNY", "cn", "CNY", " CN"));
        assertFalse(RegulatoryScope.matches("CNY", "CN", "USD", "CN"));
        assertFalse(RegulatoryScope.matches("CNY", "CN", "CNY", "HK"));
        assertFalse(RegulatoryScope.matches("CNY", "CN", null, "CN"));
    }

    @Test
    void countryOptionsAreAllPlusEveryIsoCode() {
        var options = RegulatoryScope.options();
        assertEquals("ALL", options.get(0).code());
        assertEquals("全部国家", options.get(0).name());
        assertEquals(1 + Locale.getISOCountries(Locale.IsoCountryCode.PART1_ALPHA2).size(), options.size());
        assertEquals("中国", options.stream().filter(c -> c.code().equals("CN")).findFirst().orElseThrow().name());
        assertTrue(RegulatoryScope.isCountry("GB"));
        assertFalse(RegulatoryScope.isCountry("UK"), "UK 不是 ISO 3166-1 代码");
        assertFalse(RegulatoryScope.isCountry("ALL"));
    }
}
