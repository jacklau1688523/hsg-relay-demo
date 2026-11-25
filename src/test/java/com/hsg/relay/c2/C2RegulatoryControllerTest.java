package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.service.ExpressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 监管规则接口的 HTTP 契约：按「币种 + 受益行国家」提交，校验失败返回 400 + error；
// 附加触发条件既可以随规则一次提交（新增 / 编辑页），也可以单独通过 compile / condition 两个接口校验和保存，返回 400 时规则不变
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class C2RegulatoryControllerTest {
    private static final String RULE = """
            {"ruleId":"REG-API","ruleName":"接口测试监管规则","ruleCode":"C2","scope":"GLOBAL",
             "currency":"USD","bankCountry":"JP",
             "requirement":{"type":"PROVIDER_CAPABILITY","requiredTags":["SWIFT"]},
             "onFailure":{"reasonCode":"REG_RULE_NOT_MET"},
             "enabled":true,"effectiveFrom":"2026-01-01T00:00:00"}
            """;
    private static final String SCOPE_JSON = "\"currency\":\"USD\",\"bankCountry\":\"JP\",";
    /** 附加触发条件的保存请求：{enabled, versionNo, tree}，tree 的形态与 C1 附加条件相同 */
    private static final String TRIGGER = """
            {"enabled":true,"versionNo":1,
             "tree":{"logic":"AND","children":[{"field":"purpose","operator":"EQ","value":"INVESTMENT"}]}}
            """;
    private static final String CONDITION_URL = "/api/c2/regulatory-rules/REG-API/condition";
    private static final String SIMULATE_IN_SCOPE = """
            {"providerCode":"PROV-W","paymentCurrency":"USD","beneficiaryBankCountry":"JP",
             "orderingCountry":"HK","clientCountry":"HK"}
            """;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    RegulatoryRuleRepository rules;
    @Autowired
    FieldDefinitionRepository fields;

    @BeforeEach
    void startFromAnEmptyRuleSet() {
        // 各用例只看自己建的规则；事务结束后回滚
        rules.deleteAll();
    }

    @Test
    void ruleIsSavedListedAndEvaluatedAsCurrencyAndCountry() throws Exception {
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(RULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("REG-API"))
                .andExpect(jsonPath("$.legacy").value(false))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.bankCountry").value("JP"))
                .andExpect(jsonPath("$.conditionText").value("交易币种 = USD 且 受益行国家 = JP"))
                .andExpect(jsonPath("$.versionNo").value(1));

        // 落库形态不变：仍是条件树，C2 模拟和路由引擎照常读取
        String listed = mvc.perform(get("/api/c2/regulatory-rules")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        C2Service.RegulatoryView[] views = json.readValue(listed, C2Service.RegulatoryView[].class);
        assertEquals(1, views.length);
        assertEquals(RegulatoryScope.tree("USD", "JP", json), views[0].condition());

        String simulated = mvc.perform(post("/api/c2/simulate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"providerCode":"PROV-W","paymentCurrency":"USD","beneficiaryBankCountry":"JP",
                         "orderingCountry":"HK","clientCountry":"HK"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.reasonCode").value("REG_RULE_NOT_MET"))
                .andExpect(jsonPath("$.regulatoryChecks[0].ruleId").value("REG-API"))
                .andExpect(jsonPath("$.regulatoryChecks[0].condition").value("交易币种 = USD 且 受益行国家 = JP"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNull(json.readValue(simulated, C2Service.Result.class).regulatoryChecks().get(0).expression());
    }

    @Test
    void simulateReturnsTheConfiguredGroupsAndKeepsNationalitiesOutOfTheResponse() throws Exception {
        String body = mvc.perform(post("/api/c2/simulate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"providerCode":"PROV-W","paymentCurrency":"USD","debitCurrency":"USD","beneficiaryBankCountry":"KG",
                         "beneficiaryAddressCountry":"KG","orderingCountry":"HK","clientCountry":"HK",
                         "directorNationalities":["US"],"uboNationalities":["RU"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.reasonCode").value("RESTRICTED_COUNTRY"))
                .andExpect(jsonPath("$.groups.length()").value(8))
                .andExpect(jsonPath("$.groups[0].code").value("BENEFICIARY_COUNTRY"))
                .andExpect(jsonPath("$.groups[0].order").value(1))
                .andExpect(jsonPath("$.groups[0].passed").value(false))
                .andExpect(jsonPath("$.groups[0].reasonCode").value("RESTRICTED_COUNTRY"))
                .andExpect(jsonPath("$.groups[0].leaves[0].field").value("beneficiaryBankCountry"))
                .andExpect(jsonPath("$.groups[0].leaves[0].hit").value(true))
                .andExpect(jsonPath("$.groups[1].code").value("DIRECTOR_UBO_NATIONALITY"))
                .andExpect(jsonPath("$.groups[1].leaves[0].field").value("directorNationalities"))
                .andExpect(jsonPath("$.groups[1].leaves[0].hit").value(false))
                .andExpect(jsonPath("$.groups[1].leaves[1].field").value("uboNationalities"))
                .andExpect(jsonPath("$.groups[1].leaves[1].hit").value(true))
                .andExpect(jsonPath("$.groups[1].leaves[1].ruleIds.length()").value(1))
                .andExpect(jsonPath("$.groups[7].code").value("REGULATORY"))
                .andExpect(jsonPath("$.groups[7].passed").value(true))
                .andExpect(jsonPath("$.hits[?(@.restrictionType=='DIRECTOR_UBO_NATIONALITY')].field").value(contains("directorUboNationalities")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 国籍取值不出现在响应里
        assertFalse(body.contains("\"RU\""));
        assertFalse(body.contains("\"US\""));
    }

    @Test
    void invalidCountryCurrencyAndLegacyOnlyBodiesAreRejected() throws Exception {
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(RULE.replace("\"JP\"", "\"UK\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("受益行国家必填，须为 ISO 3166-1 两位国家代码或 ALL"));
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(RULE.replace("\"USD\"", "\"XXX\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("交易币种必填")));
        // 旧版入参只带条件树、没有币种和国家：不能保存
        String legacyOnly = RULE.replace(SCOPE_JSON,
                "\"condition\":{\"operator\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"USD\"}]},");
        assertNotEquals(RULE, legacyOnly);
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(legacyOnly))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(startsWith("交易币种必填")));
        assertEquals(0, rules.count());
    }

    @Test
    void optionsListCurrenciesCountriesAndTags() throws Exception {
        FieldDefinition field = fields.findById("paymentCurrency").orElseThrow();
        field.valueOptions = "CNY,USD";
        fields.save(field);
        mvc.perform(get("/api/c2/regulatory-rules/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencies").value(contains("CNY", "USD")))
                .andExpect(jsonPath("$.countries[0].code").value("ALL"))
                .andExpect(jsonPath("$.countries[1].code").value("AD"))
                .andExpect(jsonPath("$.countries.length()").value(250))
                .andExpect(jsonPath("$.tags[?(@.tagCode=='SWIFT')]").exists());
    }

    @Test
    void compileValidatesTheConditionAgainstTheFieldLibrary() throws Exception {
        mvc.perform(post("/api/c2/regulatory-rules/compile").contentType(MediaType.APPLICATION_JSON).content(TRIGGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expression").value("((purpose == 'INVESTMENT'))"));
        // 条件值不在字段库可选值中、只适用 C1 的字段：返回 400 + 原因
        mvc.perform(post("/api/c2/regulatory-rules/compile").contentType(MediaType.APPLICATION_JSON)
                        .content(TRIGGER.replace("\"INVESTMENT\"", "\"NOPE\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("purpose 不支持该值：NOPE"));
        mvc.perform(post("/api/c2/regulatory-rules/compile").contentType(MediaType.APPLICATION_JSON)
                        .content(TRIGGER.replace("\"purpose\"", "\"flowCategory\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("字段 flowCategory 不适用于当前规则或不支持该操作符"));
        mvc.perform(post("/api/c2/regulatory-rules/compile").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("条件树不能为空"));
    }

    @Test
    void conditionIsSavedListedEvaluatedAndClearedOverHttp() throws Exception {
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(RULE)).andExpect(status().isOk());

        mvc.perform(put(CONDITION_URL).contentType(MediaType.APPLICATION_JSON).content(TRIGGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("REG-API"))
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.triggerText").value("(purpose = INVESTMENT)"))
                .andExpect(jsonPath("$.triggerProblem").doesNotExist())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.bankCountry").value("JP"))
                .andExpect(jsonPath("$.requirement.requiredTags").value(contains("SWIFT")));

        String listed = mvc.perform(get("/api/c2/regulatory-rules")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        C2Service.RegulatoryView view = json.readValue(listed, C2Service.RegulatoryView[].class)[0];
        assertEquals(new ExpressionService.Node("AND",
                        List.of(new ExpressionService.Node(null, null, "purpose", "EQ", "INVESTMENT")), null, null, null),
                json.treeToValue(view.triggerCondition(), ExpressionService.Node.class));

        // 范围命中但用途没有填写：附加条件引用的字段缺值，按未触发处理并说明；校验本身不报错
        String missing = mvc.perform(post("/api/c2/simulate").contentType(MediaType.APPLICATION_JSON).content(SIMULATE_IN_SCOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.regulatoryChecks[0].triggered").value(false))
                .andExpect(jsonPath("$.regulatoryChecks[0].triggerCondition").value("(purpose = INVESTMENT)"))
                .andExpect(jsonPath("$.regulatoryChecks[0].note").value("附加触发条件引用的字段 purpose 本次没有提供取值，按未触发处理"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertNull(json.readValue(missing, C2Service.Result.class).regulatoryChecks().get(0).triggerExpression());

        mvc.perform(post("/api/c2/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content(SIMULATE_IN_SCOPE.replace("\"orderingCountry\"", "\"purpose\":\"GOODS\",\"orderingCountry\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.regulatoryChecks[0].triggered").value(false))
                .andExpect(jsonPath("$.regulatoryChecks[0].triggerExpression").value("((purpose == 'INVESTMENT'))"))
                .andExpect(jsonPath("$.regulatoryChecks[0].note").doesNotExist());

        mvc.perform(post("/api/c2/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content(SIMULATE_IN_SCOPE.replace("\"orderingCountry\"", "\"purpose\":\"INVESTMENT\",\"orderingCountry\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.reasonCode").value("REG_RULE_NOT_MET"))
                .andExpect(jsonPath("$.regulatoryChecks[0].triggered").value(true))
                .andExpect(jsonPath("$.regulatoryChecks[0].missingTags").value(contains("SWIFT")))
                .andExpect(jsonPath("$.regulatoryChecks[0].triggerExpression").value("((purpose == 'INVESTMENT'))"));

        // enabled=false 清除附加条件：清除后交易只要落在范围内规则即触发
        mvc.perform(put(CONDITION_URL).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"versionNo\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(3))
                .andExpect(jsonPath("$.triggerCondition").doesNotExist())
                .andExpect(jsonPath("$.triggerText").doesNotExist());
        mvc.perform(post("/api/c2/simulate").contentType(MediaType.APPLICATION_JSON).content(SIMULATE_IN_SCOPE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.regulatoryChecks[0].triggered").value(true))
                .andExpect(jsonPath("$.regulatoryChecks[0].note").doesNotExist());
    }

    @Test
    void conditionRejectsUnknownRuleStaleVersionAndInvalidTree() throws Exception {
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(RULE)).andExpect(status().isOk());
        mvc.perform(put("/api/c2/regulatory-rules/REG-NONE/condition").contentType(MediaType.APPLICATION_JSON)
                        .content(TRIGGER.replace("\"versionNo\":1", "\"versionNo\":null")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("规则不存在"));
        mvc.perform(put(CONDITION_URL).contentType(MediaType.APPLICATION_JSON).content(TRIGGER.replace("\"versionNo\":1", "\"versionNo\":5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("规则版本已变化，请刷新后重试"));
        mvc.perform(put(CONDITION_URL).contentType(MediaType.APPLICATION_JSON).content(TRIGGER.replace("\"purpose\"", "\"flowCategory\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("字段 flowCategory 不适用于当前规则或不支持该操作符"));
        // 校验不通过：规则和它的版本都不变
        RegulatoryRule stored = rules.findById("REG-API").orElseThrow();
        assertNull(stored.triggerConditionJson);
        assertEquals(1, stored.versionNo);
    }

    /** 在规则请求里并入附加触发条件（新增 / 编辑页 /c2-regulatory-edit.html 的提交方式）：trigger = {enabled, tree}。 */
    private static String withTrigger(String body, String trigger) {
        String merged = body.replaceFirst("\\{\"ruleId\"", "{\"trigger\":" + trigger + ",\"ruleId\"");
        assertNotEquals(body, merged);
        return merged;
    }

    @Test
    void editPageSavesTheTriggerWithTheRuleAndLoadsOneRule() throws Exception {
        String tree = "{\"logic\":\"AND\",\"children\":[{\"field\":\"purpose\",\"operator\":\"EQ\",\"value\":\"INVESTMENT\"}]}";
        // 新增：范围、标签和附加触发条件一次提交，一次保存、一个版本
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON)
                        .content(withTrigger(RULE, "{\"enabled\":true,\"tree\":" + tree + "}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.triggerText").value("(purpose = INVESTMENT)"));

        // 编辑页按编码载入；不存在的编码返回 400
        mvc.perform(get("/api/c2/regulatory-rules/REG-API"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("REG-API"))
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.triggerCondition.logic").value("AND"));
        mvc.perform(get("/api/c2/regulatory-rules/REG-NONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("规则不存在"));

        // 附加触发条件不通过：连同一起提交的名称、范围都不保存
        String renamed = RULE.replace("接口测试监管规则", "改名后的监管规则").replace("\"JP\"", "\"ALL\"");
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON)
                        .content(withTrigger(renamed, "{\"enabled\":true,\"tree\":" + tree.replace("INVESTMENT", "NOPE") + "}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("purpose 不支持该值：NOPE"));
        RegulatoryRule stored = rules.findById("REG-API").orElseThrow();
        assertEquals("接口测试监管规则", stored.ruleName);
        assertEquals(1, stored.versionNo);

        // 版本过期：带上 trigger 也一样被拒绝
        String stale = RULE.replace("\"enabled\":true,\"effectiveFrom\"", "\"versionNo\":5,\"enabled\":true,\"effectiveFrom\"");
        assertNotEquals(RULE, stale);
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(withTrigger(stale, "{\"enabled\":false}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("规则版本已变化，请刷新后重试"));

        // 不带 trigger 的保存：已保存的附加触发条件保持不变
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(RULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(2))
                .andExpect(jsonPath("$.triggerText").value("(purpose = INVESTMENT)"));

        // enabled=false 清除：清除后交易只要落在范围内规则即触发
        mvc.perform(post("/api/c2/regulatory-rules").contentType(MediaType.APPLICATION_JSON).content(withTrigger(RULE, "{\"enabled\":false}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionNo").value(3))
                .andExpect(jsonPath("$.triggerCondition").doesNotExist())
                .andExpect(jsonPath("$.triggerText").doesNotExist());
        mvc.perform(post("/api/c2/simulate").contentType(MediaType.APPLICATION_JSON)
                        .content(SIMULATE_IN_SCOPE.replace("\"orderingCountry\"", "\"purpose\":\"GOODS\",\"orderingCountry\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regulatoryChecks[0].triggered").value(true));
    }
}
