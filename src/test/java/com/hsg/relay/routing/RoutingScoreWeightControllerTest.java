package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 页面用到的全部接口：参数校验失败返回 400 + error，正常请求返回配置视图。 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutingScoreWeightControllerTest extends ScoreWeightCleanSlate {
    static final String BASE = "/api/routing/score-weights";
    static final String OPS = "ops.api";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    long createDraft(String type, String tier, int w1, int w2, int w3, int w4) throws Exception {
        String content = mvc.perform(post(BASE + "/drafts").contentType(MediaType.APPLICATION_JSON).content(draftJson(type, tier, w1, w2, w3, w4)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(content).path("id").asLong();
    }

    @Test
    void listFiltersByTransactionTypeTierAndStatus() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].transactionType").value("FEE"))
                .andExpect(jsonPath("$[0].versionNo").value(1))
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].weightSum").value(100));
        mvc.perform(get(BASE).param("transactionType", "TRADE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].w1Cost").value(40)).andExpect(jsonPath("$[0].w2Speed").value(30))
                .andExpect(jsonPath("$[0].w3Preference").value(20)).andExpect(jsonPath("$[0].w4Commercial").value(10));
        mvc.perform(get(BASE).param("status", "DRAFT")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(BASE).param("clientTier", "VIP")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void optionsVersionsAndAuditsAreReadable() throws Exception {
        mvc.perform(get(BASE + "/options")).andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionTypes.length()").value(7))
                .andExpect(jsonPath("$.clientTiers[0]").value("ALL"))
                .andExpect(jsonPath("$.tierField").value("clientSegment"))
                .andExpect(jsonPath("$.fallbackEffective").value(true));
        mvc.perform(get(BASE + "/versions")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].effectiveTo").isEmpty());
        mvc.perform(get(BASE + "/audits")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void matchExplainsEachLevelAndStopsAtTheFirstHit() throws Exception {
        mvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionType\":\"TRADE\",\"clientTier\":\"VIP\",\"at\":\"2026-11-20T10:00:00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.length()").value(3))
                .andExpect(jsonPath("$.steps[0].level").value("P1")).andExpect(jsonPath("$.steps[0].hit").value("MISS"))
                .andExpect(jsonPath("$.steps[1].level").value("P2")).andExpect(jsonPath("$.steps[1].hit").value("HIT"))
                .andExpect(jsonPath("$.steps[1].clientTier").value("ALL"))
                .andExpect(jsonPath("$.steps[2].level").value("P3")).andExpect(jsonPath("$.steps[2].hit").value("SKIPPED"))
                .andExpect(jsonPath("$.matched.level").value("P2"))
                .andExpect(jsonPath("$.matched.versionNo").value(1))
                .andExpect(jsonPath("$.matched.weights.cost").value(40))
                .andExpect(jsonPath("$.error").isEmpty());
        // 交易类型有自己的 ALL 配置：P1 落空后在 P2 命中
        mvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionType\":\"PAYROLL\",\"clientTier\":\"VIP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].hit").value("MISS"))
                .andExpect(jsonPath("$.steps[1].hit").value("HIT"))
                .andExpect(jsonPath("$.matched.level").value("P2"))
                .andExpect(jsonPath("$.matched.transactionType").value("PAYROLL"));
        mvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON).content("{\"transactionType\":\"TRADE\",\"clientTier\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void draftLifecycleThroughTheApi() throws Exception {
        // 合计不是 100：服务端校验（数据库还有一道 CHECK 约束）
        mvc.perform(post(BASE + "/drafts").contentType(MediaType.APPLICATION_JSON).content(draftJson("TRADE", "ALL", 40, 30, 20, 9)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("99")));
        long id = createDraft("TRADE", "ALL", 40, 30, 20, 10);
        mvc.perform(get(BASE).param("status", "DRAFT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].rowVersion").value(0))
                .andExpect(jsonPath("$[0].createdBy").value(OPS));
        // 同一组不能再建第二份草稿
        mvc.perform(post(BASE + "/drafts").contentType(MediaType.APPLICATION_JSON).content(draftJson("TRADE", "ALL", 25, 50, 20, 5)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("已有待发布的草稿")));
        // 发布：必须带操作人
        mvc.perform(post(BASE + "/drafts/" + id + "/publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("操作人")));
        mvc.perform(post(BASE + "/drafts/" + id + "/publish").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\":\"" + OPS + "\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.publishedBy").value(OPS));
        // 上一版本在发布时刻结束；内存快照已经用上新版本
        mvc.perform(get(BASE).param("transactionType", "TRADE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].versionNo").value(2))
                .andExpect(jsonPath("$[1].enabled").value(false))
                .andExpect(jsonPath("$[1].effectiveTo").isNotEmpty());
        assertThat(matchNow("TRADE", "VIP").versionNo()).isEqualTo(2);
        // 已发布的不能再发布、不能删除
        mvc.perform(post(BASE + "/drafts/" + id + "/publish").contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"" + OPS + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("只有草稿可以发布")));
        mvc.perform(delete(BASE + "/drafts/" + id).param("operator", OPS)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("只有草稿可以删除")));
        // 停用：行版本号不对 / 缺操作人 → 400；正确 → DISABLED
        mvc.perform(post(BASE + "/" + id + "/disable").contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"" + OPS + "\",\"expectedVersion\":99}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("配置已被他人修改")));
        mvc.perform(post(BASE + "/" + id + "/disable").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("操作人")));
        mvc.perform(post(BASE + "/" + id + "/disable").contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"" + OPS + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        // 停用后 TRADE 没有生效的版本 → 落到 P3
        mvc.perform(post(BASE + "/match").contentType(MediaType.APPLICATION_JSON).content("{\"transactionType\":\"TRADE\",\"clientTier\":\"VIP\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matched.level").value("P3"));
        // 兜底配置不能停用
        long fallback = json.readTree(mvc.perform(get(BASE).param("transactionType", "OTHER")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get(0).path("id").asLong();
        mvc.perform(post(BASE + "/" + fallback + "/disable").contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"" + OPS + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value(containsString("兜底")));
        // 新建草稿、发布、交接、停用各一条审计，操作人都是提交的那位
        mvc.perform(get(BASE + "/audits")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].operation").value(containsInAnyOrder("CREATE_DRAFT", "PUBLISH", "SUPERSEDED", "DISABLE")))
                .andExpect(jsonPath("$[*].operator").value(everyItem(is(OPS))));
        mvc.perform(get(BASE + "/versions")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(8));
    }

    @Test
    void draftsCanBeDeletedAndUnknownIdsAreRejected() throws Exception {
        long id = createDraft("PAYROLL", "vip", 55, 25, 10, 10);
        mvc.perform(get(BASE).param("status", "DRAFT")).andExpect(status().isOk()).andExpect(jsonPath("$[0].clientTier").value("VIP"));
        mvc.perform(delete(BASE + "/drafts/" + id)).andExpect(status().isBadRequest());
        mvc.perform(delete(BASE + "/drafts/" + id).param("operator", OPS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true)).andExpect(jsonPath("$.id").value((int) id));
        mvc.perform(get(BASE).param("transactionType", "PAYROLL")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete(BASE + "/drafts/" + id).param("operator", OPS)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("权重配置不存在")));
        mvc.perform(post(BASE + "/drafts/999999/publish").contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"" + OPS + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/999999/disable").contentType(MediaType.APPLICATION_JSON).content("{\"operator\":\"" + OPS + "\"}"))
                .andExpect(status().isBadRequest());
    }

    static String draftJson(String type, String tier, int w1, int w2, int w3, int w4) {
        return "{\"transactionType\":\"" + type + "\",\"clientTier\":\"" + tier + "\",\"w1Cost\":" + w1 + ",\"w2Speed\":" + w2
                + ",\"w3Preference\":" + w3 + ",\"w4Commercial\":" + w4 + ",\"operator\":\"" + OPS + "\"}";
    }
}
