package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.service.ScoreRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** C5 评分规则配置接口：读取、校验、保存草稿、发布、历史版本、回放、审计。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class C5ScoreRuleApiTest {
    @Autowired MockMvc mvc;
    @Autowired ScoreRuleService rules;
    @Autowired ObjectMapper json;

    private String defaultRule() throws Exception {
        return json.writeValueAsString(rules.version("C5", 1).rule());
    }

    private String ruleWithScore(String groupCode, int score) throws Exception {
        ObjectNode tree = (ObjectNode) json.readTree(defaultRule());
        for (var group : tree.get("groups"))
            if (group.get("groupCode").asText().equals(groupCode)) ((ObjectNode) group.get("result")).put("score", score);
        return tree.toString();
    }

    /** 保存草稿的请求体：{operator, rule[, expectedDraftVersion]}。 */
    private String draftBody(String operator, String rule) throws Exception {
        ObjectNode body = json.createObjectNode();
        if (operator != null) body.put("operator", operator);
        body.set("rule", json.readTree(rule));
        return body.toString();
    }

    @Test
    void viewShowsDraftPublishedVersionAndHistory() throws Exception {
        mvc.perform(get("/api/c5/score-rule")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleCode").value("C5"))
                .andExpect(jsonPath("$.ruleType").value("SCORE"))
                .andExpect(jsonPath("$.matchMode").value("FIRST_MATCH"))
                .andExpect(jsonPath("$.scoreRange.min").value(0))
                .andExpect(jsonPath("$.scoreRange.max").value(100))
                .andExpect(jsonPath("$.published.version").value(1))
                .andExpect(jsonPath("$.published.rule.groups[0].groupCode").value("C5-ON-TIME"))
                .andExpect(jsonPath("$.published.rule.groups[0].result.score").value(100))
                .andExpect(jsonPath("$.published.rule.groups[1].result.score").value(50))
                .andExpect(jsonPath("$.published.rule.groups[2].condition.operator").value("GTE"))
                .andExpect(jsonPath("$.published.rule.groups[2].result.score").value(0))
                .andExpect(jsonPath("$.draft.version").value(1))
                .andExpect(jsonPath("$.draftChanged").value(false))
                .andExpect(jsonPath("$.history.length()").value(1));
        mvc.perform(get("/api/c5/score-rule/versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].publishedBy").value("system"));
    }

    @Test
    void validateRunsTheSameChecksAsPublishing() throws Exception {
        mvc.perform(post("/api/c5/score-rule/validate").contentType("application/json").content(defaultRule()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(jsonPath("$.warnings").isEmpty());
        var gap = (ObjectNode) json.readTree(defaultRule());
        ((ArrayNode) gap.get("groups")).remove(2);
        mvc.perform(post("/api/c5/score-rule/validate").contentType("application/json").content(gap.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errors[0]").value(containsString("覆盖缺口")));
    }

    @Test
    void draftNeedsAnOperatorAndScoresInTheRange() throws Exception {
        mvc.perform(put("/api/c5/score-rule/draft").contentType("application/json")
                        .content(draftBody(null, defaultRule())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("操作人")));
        mvc.perform(put("/api/c5/score-rule/draft").contentType("application/json")
                        .content(draftBody("ops", ruleWithScore("C5-ONE-DAY", 101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("超出合法范围")));
        // 保存草稿不等于生效：草稿改了分数，已发布版本不变，页面据此提示「草稿未发布」
        mvc.perform(put("/api/c5/score-rule/draft").contentType("application/json")
                        .content(draftBody("ops", ruleWithScore("C5-ONE-DAY", 55))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftChanged").value(true))
                .andExpect(jsonPath("$.published.rule.groups[1].result.score").value(50))
                .andExpect(jsonPath("$.draft.rule.groups[1].result.score").value(55));
    }

    /** 演示覆盖行（PROV-T1 USD）偏移 1：请求 11-20、11-20 决策 → 延迟 1 个工作日。 */
    private static final String SIM_INPUT = """
            {"providerCode":"PROV-T1","paymentCurrency":"USD","beneficiaryBankCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
             "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
            """;

    @Test
    void draftSimulationUsesTheUnpublishedDraftAndWritesNothing() throws Exception {
        ObjectNode body = json.createObjectNode();
        body.set("rule", json.readTree(ruleWithScore("C5-ONE-DAY", 55)));
        body.set("input", json.readTree(SIM_INPUT));
        mvc.perform(post("/api/c5/score-rule/simulate").contentType("application/json").content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(true))
                .andExpect(jsonPath("$.replay").value(false))
                .andExpect(jsonPath("$.scoreStatus").value("SCORED"))
                .andExpect(jsonPath("$.score").value(55))
                .andExpect(jsonPath("$.matchedScoreGroup").value("C5-ONE-DAY"))
                .andExpect(jsonPath("$.scoreRuleVersion").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.groupResults[0].matched").value(false))
                .andExpect(jsonPath("$.groupResults[1].matched").value(true));
        // 草稿没有保存也没有发布：生效版本仍是 v1（50 分），历史里只有一个版本
        mvc.perform(post("/api/c5/simulate").contentType("application/json").content(SIM_INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(50))
                .andExpect(jsonPath("$.scoreRuleVersion").value(1));
        mvc.perform(get("/api/c5/score-rule")).andExpect(jsonPath("$.draftChanged").value(false));
        // 结构不合法的草稿不能试算
        ObjectNode bad = (ObjectNode) json.readTree(ruleWithScore("C5-ONE-DAY", 101));
        body.set("rule", bad);
        mvc.perform(post("/api/c5/score-rule/simulate").contentType("application/json").content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("超出合法范围")));
    }

    @Test
    void publishCreatesAnImmutableVersionAndReplayUsesTheOldOne() throws Exception {
        mvc.perform(put("/api/c5/score-rule/draft").contentType("application/json")
                .content(draftBody("ops", ruleWithScore("C5-ONE-DAY", 55))));
        mvc.perform(post("/api/c5/score-rule/publish").contentType("application/json")
                        .content("{\"operator\":\"ops\",\"note\":\"延迟一天调整为 55\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published.version").value(2))
                .andExpect(jsonPath("$.view.draftChanged").value(false));
        mvc.perform(post("/api/c5/score-rule/publish").contentType("application/json").content("{\"operator\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("操作人")));
        // 演示覆盖行偏移 1：11-20 请求 → 延迟 1 个工作日
        mvc.perform(post("/api/c5/score-rule/versions/1/replay").contentType("application/json").content(SIM_INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replay").value(true))
                .andExpect(jsonPath("$.scoreStatus").value("SCORED"))
                .andExpect(jsonPath("$.score").value(50))
                .andExpect(jsonPath("$.scoreRuleVersion").value(1))
                .andExpect(jsonPath("$.groupResults[0].groupCode").value("C5-ON-TIME"))
                .andExpect(jsonPath("$.groupResults[0].matched").value(false))
                .andExpect(jsonPath("$.groupResults[1].groupCode").value("C5-ONE-DAY"))
                .andExpect(jsonPath("$.groupResults[1].matched").value(true));
        mvc.perform(get("/api/c5/score-rule/versions/1")).andExpect(jsonPath("$.rule.groups[1].result.score").value(50));
        mvc.perform(get("/api/c5/score-rule/versions/2")).andExpect(jsonPath("$.rule.groups[1].result.score").value(55));
        mvc.perform(get("/api/c5/score-rule/audits")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operation").value("PUBLISH"))
                .andExpect(jsonPath("$[0].operator").value("ops"));
    }
}
