package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class C2ServiceTest {
    @Autowired
    C2Service c2;
    @Autowired
    C2ExpressionCompiler compiler;
    @Autowired
    ObjectMapper mapper;

    private C2Service.Simulation request(String provider, String currency, String bankCountry, List<String> nationalities) {
        return new C2Service.Simulation(provider, currency, currency, bankCountry, bankCountry, null, "HK", "HK", null, null, nationalities);
    }

    @Test
    void regulatoryRuleRequiresOnshoreCapability() {
        C2Service.Result failed = c2.simulate(request("PROV-HO", "CNY", "CN", List.of()));
        assertFalse(failed.passed());
        assertEquals("REG_RULE_NOT_MET", failed.reasonCode());
        C2Service.RegulatoryCheck check = failed.regulatoryChecks().get(0);
        assertEquals("CNY_ONSHORE", check.missingTags().get(0));
        assertEquals("交易币种 = CNY 且 受益行国家 = CN", check.condition());
        C2Service.Result passed = c2.simulate(request("PROV-T1", "CNY", "CN", List.of()));
        assertTrue(passed.passed());
    }

    @Test
    void wiseCountryAndNationalityRestrictionReturnOrderedReasons() {
        C2Service.Result result = c2.simulate(request("PROV-W", "USD", "KG", List.of("RU")));
        assertFalse(result.passed());
        assertEquals("RESTRICTED_COUNTRY", result.reasonCode());
        assertTrue(result.hits().stream().anyMatch(h -> "RESTRICTED_NATIONALITY".equals(h.reasonCode())));
    }

    /** 只传合并的 directorUboNationalities（全流程 Check、原有调用方）：两个国籍叶子按同一份名单判断，结果与拆分前一致。 */
    @Test
    void mergedNationalitiesStillApplyToBothNationalityLeaves() {
        C2Service.GroupCheck group = c2.simulate(request("PROV-W", "USD", "KG", List.of("RU"))).groups().get(1);
        assertEquals("DIRECTOR_UBO_NATIONALITY", group.code());
        assertFalse(group.passed());
        assertEquals(List.of("directorNationalities", "uboNationalities"), group.leaves().stream().map(C2Service.LeafCheck::field).toList());
        assertTrue(group.leaves().stream().allMatch(C2Service.LeafCheck::hit));
    }

    @Test
    void checksFollowTheConfiguredGroupsInOrder() {
        C2Service.Result result = c2.simulate(request("PROV-T1", "CNY", "CN", List.of()));
        assertTrue(result.passed());
        assertEquals(List.of("BENEFICIARY_COUNTRY", "DIRECTOR_UBO_NATIONALITY", "BENEFICIARY_REGION", "ORDERING_COUNTRY",
                "INTERMEDIARY_COUNTRY", "CURRENCY", "PURPOSE", "REGULATORY"), codes(result));
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8), result.groups().stream().map(C2Service.GroupCheck::order).toList());
        assertTrue(result.groups().stream().allMatch(C2Service.GroupCheck::passed));
        assertTrue(result.groups().stream().flatMap(g -> g.leaves().stream()).noneMatch(C2Service.LeafCheck::hit));
        // 每个规则组的叶子与规则编辑器里的条件结构一致
        assertEquals(List.of("beneficiaryBankCountry", "beneficiaryAddressCountry"), fields(result, 0));
        assertEquals(List.of("directorNationalities", "uboNationalities"), fields(result, 1));
        assertEquals(List.of("beneficiaryRegion"), fields(result, 2));
        assertEquals(List.of("clientCountry", "orderingCountry"), fields(result, 3));
        assertEquals(List.of("intermediaryCountry"), fields(result, 4));
        assertEquals(List.of("debitCurrency", "paymentCurrency"), fields(result, 5));
        assertEquals(List.of("purpose"), fields(result, 6));
        assertEquals(List.of("providerCapabilities"), fields(result, 7));
        assertEquals(List.of("RESTRICTED_COUNTRY", "RESTRICTED_NATIONALITY", "RESTRICTED_REGION", "RESTRICTED_COUNTRY",
                "RESTRICTED_COUNTRY", "RESTRICTED_CURRENCY", "RESTRICTED_PURPOSE", "REG_RULE_NOT_MET"),
                result.groups().stream().map(C2Service.GroupCheck::reasonCode).toList());
    }

    /** 董事、UBO 国籍分别判断：只有 UBO 国籍命中时，第 2 组不满足，董事国籍这个叶子仍为 false。 */
    @Test
    void directorAndUboNationalitiesAreCheckedSeparately() throws Exception {
        C2Service.Result result = c2.simulate(request("PROV-W", "KG", List.of("US"), List.of("RU")));
        assertFalse(result.passed());
        assertEquals("RESTRICTED_COUNTRY", result.reasonCode());
        C2Service.GroupCheck country = result.groups().get(0), nationality = result.groups().get(1);
        assertFalse(country.passed());
        assertEquals(List.of(true, true), country.leaves().stream().map(C2Service.LeafCheck::hit).toList());
        assertEquals("RESTRICTED_NATIONALITY", nationality.reasonCode());
        assertFalse(nationality.leaves().get(0).hit());
        assertTrue(nationality.leaves().get(1).hit());
        // hits 不变：国籍命中仍是一条、字段名为合并字段
        C2Service.RuleHit hit = result.hits().stream().filter(h -> "RESTRICTED_NATIONALITY".equals(h.reasonCode())).findFirst().orElseThrow();
        assertEquals("directorUboNationalities", hit.field());
        assertEquals(2, hit.order());
        // 国籍取值不出现在响应里
        String response = mapper.writeValueAsString(result);
        assertFalse(response.contains("\"RU\""));
        assertFalse(response.contains("\"US\""));
    }

    /** 同一个规则组的两个叶子各命中一条不同的限制记录：两个叶子都为 true，hits 仍是每条限制记录一条、取组内首个命中字段。 */
    @Test
    @Transactional
    void leavesInOneGroupReportTheirOwnHits() {
        c2.saveRestriction(new C2Service.RestrictionInput(null, "PROV-W", "BENEFICIARY_COUNTRY", "US", true, null,
                LocalDateTime.of(2025, 10, 1, 0, 0), null, null));
        // 受益行国家 US 命中新建的限制记录，受益人地址国家 KG 命中预置的那条
        C2Service.Result result = c2.simulate(new C2Service.Simulation("PROV-W", "USD", "USD", "US", "KG", null, "HK", "HK", null, null, List.of()));
        C2Service.GroupCheck group = result.groups().get(0);
        assertFalse(group.passed());
        assertTrue(group.leaves().stream().allMatch(C2Service.LeafCheck::hit));
        assertNotEquals(group.leaves().get(0).ruleIds(), group.leaves().get(1).ruleIds());
        assertEquals(2, result.hits().stream().filter(h -> h.order() == 1).count());
        assertEquals(Set.of("beneficiaryBankCountry", "beneficiaryAddressCountry"),
                result.hits().stream().filter(h -> h.order() == 1).map(C2Service.RuleHit::field).collect(Collectors.toSet()));
    }

    @Test
    void unmetRegulatoryRuleFailsTheLastGroup() {
        C2Service.GroupCheck group = c2.simulate(request("PROV-HO", "CNY", "CN", List.of())).groups().get(7);
        assertEquals("REGULATORY", group.code());
        assertEquals(8, group.order());
        assertFalse(group.passed());
        assertEquals("REG_RULE_NOT_MET", group.reasonCode());
        assertEquals("providerCapabilities", group.leaves().get(0).field());
        assertEquals(List.of("REG-001"), group.leaves().get(0).ruleIds());
    }

    private static List<String> codes(C2Service.Result result) {
        return result.groups().stream().map(C2Service.GroupCheck::code).toList();
    }

    private static List<String> fields(C2Service.Result result, int index) {
        return result.groups().get(index).leaves().stream().map(C2Service.LeafCheck::field).toList();
    }

    private C2Service.Simulation request(String provider, String country, List<String> directors, List<String> ubos) {
        return new C2Service.Simulation(provider, "USD", "USD", country, country, null, "HK", "HK", null, null,
                null, null, directors, ubos);
    }

    @Test
    void nestedConditionGroupSupportsAndOr() throws Exception {
        var tree = mapper.readTree("""
                {"operator":"OR","children":[
                  {"operator":"AND","children":[
                    {"field":"paymentCurrency","operator":"EQ","value":"CNY"},
                    {"field":"beneficiaryBankCountry","operator":"EQ","value":"CN"}
                  ]},
                  {"field":"purpose","operator":"EQ","value":"GOODS"}
                ]}
                """);
        assertTrue(compiler.evaluate(tree, Map.of("paymentCurrency", "USD", "beneficiaryBankCountry", "HK", "purpose", "GOODS")));
        assertFalse(compiler.evaluate(tree, Map.of("paymentCurrency", "USD", "beneficiaryBankCountry", "HK", "purpose", "SALARY")));
    }

    @Test
    void fieldWhitelistRejectsArbitraryField() throws Exception {
        var tree = mapper.readTree("{\"field\":\"java.lang.Runtime\",\"operator\":\"EQ\",\"value\":\"CNY\"}");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(tree));
    }
}
