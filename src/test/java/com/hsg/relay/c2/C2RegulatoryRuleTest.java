package com.hsg.relay.c2;

import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// 监管规则按「交易币种 + 受益行国家」配置：保存即存为标准条件树，求值直接比对；旧版自由条件树仍按原条件执行
@SpringBootTest
@Transactional
class C2RegulatoryRuleTest {
    @Autowired
    C2Service c2;
    @Autowired
    RegulatoryRuleRepository rules;
    @Autowired
    FieldDefinitionRepository fields;

    private static C2Service.RegulatoryInput input(String id, String currency, String country) {
        return new C2Service.RegulatoryInput(id, "测试监管规则 " + id, "C2", "GLOBAL", currency, country,
                new C2Service.Requirement("PROVIDER_CAPABILITY", List.of("SWIFT")),
                new C2Service.FailureAction("REG_RULE_NOT_MET"), true, null, LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
    }

    private static C2Service.Simulation payment(String provider, String currency, String country, String purpose) {
        return new C2Service.Simulation(provider, currency, currency, country, country, null, "HK", "HK", null, purpose, List.of());
    }

    private C2Service.RegulatoryView rule(String ruleId) {
        return c2.regulations().stream().filter(r -> r.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    private static C2Service.RegulatoryCheck check(C2Service.Result result, String ruleId) {
        return result.regulatoryChecks().stream().filter(c -> c.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    @BeforeEach
    void startFromAnEmptyRuleSet() {
        // 各用例只看自己建的规则，不受种子数据和同一 JVM 里其他用例遗留数据的影响（事务结束后回滚）
        rules.deleteAll();
    }

    @Test
    void ruleMatchesByCurrencyAndCountryRegardlessOfFieldLibraryState() {
        c2.saveRegulatory(input("REG-T1", " usd", "JP"));
        // 字段库里的「交易币种」被停用，已发布的监管规则照常执行
        FieldDefinition field = fields.findById("paymentCurrency").orElseThrow();
        field.enabled = false;
        fields.save(field);

        C2Service.RegulatoryView saved = rule("REG-T1");
        assertFalse(saved.legacy());
        assertEquals("USD", saved.currency());
        assertEquals("JP", saved.bankCountry());
        assertEquals("交易币种 = USD 且 受益行国家 = JP", saved.conditionText());

        C2Service.Result hit = c2.simulate(payment("PROV-W", "USD", "JP", null));
        assertFalse(hit.passed());
        assertEquals("REG_RULE_NOT_MET", hit.reasonCode());
        assertEquals(List.of("SWIFT"), check(hit, "REG-T1").missingTags());
        assertNull(check(hit, "REG-T1").expression(), "标准规则直接比对币种和国家，不再生成表达式");
        assertTrue(c2.simulate(payment("PROV-T1", "USD", "JP", null)).passed());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", null)), "REG-T1").triggered());
        assertFalse(check(c2.simulate(payment("PROV-W", "HKD", "JP", null)), "REG-T1").triggered());
    }

    @Test
    void allCountryAppliesToEveryBeneficiaryCountry() {
        c2.saveRegulatory(input("REG-T2", "HKD", "ALL"));
        assertEquals("交易币种 = HKD 且 受益行国家 = 不限（ALL）", rule("REG-T2").conditionText());
        assertTrue(check(c2.simulate(payment("PROV-W", "HKD", "SG", null)), "REG-T2").triggered());
        assertTrue(check(c2.simulate(payment("PROV-W", "HKD", "DE", null)), "REG-T2").triggered());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "SG", null)), "REG-T2").triggered());
    }

    @Test
    void legacyFreeFormTreeRunsOnItsOwnConditionUntilResaved() {
        RegulatoryRule legacy = new RegulatoryRule();
        legacy.ruleId = "REG-T3";
        legacy.ruleName = "旧版条件树规则";
        legacy.conditionJson = """
                {"operator":"OR","children":[
                  {"field":"paymentCurrency","operator":"EQ","value":"EUR"},
                  {"field":"purpose","operator":"EQ","value":"INVESTMENT"}
                ]}
                """;
        legacy.requiredTagsJson = "[\"SWIFT\"]";
        legacy.effectiveFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
        rules.save(legacy);

        C2Service.RegulatoryView before = rule("REG-T3");
        assertTrue(before.legacy());
        assertNull(before.currency());
        assertEquals("(paymentCurrency EQ EUR 或 purpose EQ INVESTMENT)", before.conditionText());
        C2Service.Result result = c2.simulate(payment("PROV-W", "USD", "US", "INVESTMENT"));
        assertTrue(check(result, "REG-T3").triggered());
        assertNotNull(check(result, "REG-T3").expression());
        assertEquals(List.of("SWIFT"), check(result, "REG-T3").missingTags());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", "GOODS")), "REG-T3").triggered());

        // 页面重新保存后转为标准形态
        C2Service.RegulatoryView resaved = c2.saveRegulatory(input("REG-T3", "EUR", "ALL"));
        assertFalse(resaved.legacy());
        assertEquals("EUR", resaved.currency());
        assertEquals(2, resaved.versionNo());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", "INVESTMENT")), "REG-T3").triggered());
        assertTrue(check(c2.simulate(payment("PROV-W", "EUR", "US", "GOODS")), "REG-T3").triggered());
    }

    @Test
    void saveRejectsUnknownCurrencyCountryAndLegacyOnlyInput() {
        var badCurrency = assertThrows(IllegalArgumentException.class, () -> c2.saveRegulatory(input("REG-T4", "XXX", "CN")));
        assertTrue(badCurrency.getMessage().startsWith("交易币种必填"), badCurrency.getMessage());
        var badCountry = assertThrows(IllegalArgumentException.class, () -> c2.saveRegulatory(input("REG-T4", "CNY", "UK")));
        assertEquals("受益行国家必填，须为 ISO 3166-1 两位国家代码或 ALL", badCountry.getMessage());
        // 只带旧版 condition、不带币种和国家的请求不再被接受
        var noScope = assertThrows(IllegalArgumentException.class, () -> c2.saveRegulatory(input("REG-T4", null, null)));
        assertTrue(noScope.getMessage().startsWith("交易币种必填"), noScope.getMessage());
        assertTrue(c2.regulations().stream().noneMatch(r -> r.ruleId().equals("REG-T4")));
    }

    @Test
    void optionsFollowTheFieldLibraryAndTheIsoList() {
        FieldDefinition field = fields.findById("paymentCurrency").orElseThrow();
        field.valueOptions = " cny , USD, ";
        fields.save(field);
        C2Service.RegulatoryOptions options = c2.regulatoryOptions();
        assertEquals(List.of("CNY", "USD"), options.currencies());
        assertEquals("ALL", options.countries().get(0).code());
        assertTrue(options.tags().stream().anyMatch(t -> "SWIFT".equals(t.tagCode)));
    }
}
