package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.service.ScoreRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

/** 发布名单：配置 relay.score-rule.publishers 后，只有名单内的操作人能发布评分规则。 */
@SpringBootTest(properties = "relay.score-rule.publishers=ops.lead,ops.manager")
@Transactional
class C5ScorePublishPermissionTest {
    @Autowired ScoreRuleService rules;
    @Autowired ObjectMapper json;

    @Test
    void onlyListedOperatorsCanPublish() throws Exception {
        ObjectNode tree = (ObjectNode) json.readTree(json.writeValueAsString(rules.version("C5", 1).rule()));
        for (var group : tree.get("groups"))
            if (group.get("groupCode").asText().equals("C5-ONE-DAY")) ((ObjectNode) group.get("result")).put("score", 44);
        var draft = rules.saveDraft("C5", tree, null, "ops.junior", C5ScoreService.INPUT_FIELDS);
        // 保存草稿不受名单限制；发布受限制
        assertThatThrownBy(() -> rules.publish("C5", new ScoreRuleService.PublishRequest(draft.draft().version(), "ops.junior", null, null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("不在评分规则发布名单内");
        assertThat(rules.view("C5").published().version()).isEqualTo(1);
        var published = rules.publish("C5", new ScoreRuleService.PublishRequest(draft.draft().version(), "ops.manager", "名单内发布", null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN);
        assertThat(published.publishedBy()).isEqualTo("ops.manager");
        assertThat(rules.view("C5").published().version()).isEqualTo(2);
    }
}
