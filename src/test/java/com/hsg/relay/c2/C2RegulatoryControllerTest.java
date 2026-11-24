package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 监管规则接口的 HTTP 契约：按「币种 + 受益行国家」提交，校验失败返回 400 + error
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
}
