package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.service.ScoreRuleEngine;
import com.hsg.relay.service.ScoreRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * C5 评分规则（SCORE / FIRST_MATCH）的配置接口：读取草稿与已发布版本、校验、保存草稿、发布、历史版本、回放、审计。
 * 保存草稿和发布都要求提交操作人；发布还受发布名单与发布前校验约束，见 {@link ScoreRuleService}。
 */
@RestController
@RequestMapping("/api/c5/score-rule")
public class C5ScoreController {
    private final ScoreRuleService rules;
    private final C5ScoreService scoring;
    private final C5Service c5;
    private final ObjectMapper json;

    public C5ScoreController(ScoreRuleService rules, C5ScoreService scoring, C5Service c5, ObjectMapper json) {
        this.rules = rules;
        this.scoring = scoring;
        this.c5 = c5;
        this.json = json;
    }

    /** 草稿 + 最新已发布版本 + 历史。草稿与已发布不同时 draftChanged = true，页面据此提示「草稿未发布」。 */
    @GetMapping
    public ScoreRuleService.View view() {
        return rules.view(C5ScoreService.RULE_CODE);
    }

    public record DraftRequest(JsonNode rule, Integer expectedDraftVersion, String operator) {
    }

    /** 校验规则：与发布同一套检查（结构、分数合法性、冲突、覆盖范围），不落库。 */
    @PostMapping("/validate")
    public ScoreRuleEngine.PublishCheck validate(@RequestBody JsonNode rule) {
        return scoring.check(rule);
    }

    @PutMapping("/draft")
    public ScoreRuleService.View saveDraft(@RequestBody DraftRequest request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        return rules.saveDraft(C5ScoreService.RULE_CODE, request.rule(), request.expectedDraftVersion(), request.operator(), C5ScoreService.INPUT_FIELDS);
    }

    /** 发布草稿为新的不可变版本。校验不通过返回 400，已发布版本保持不变。 */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody ScoreRuleService.PublishRequest request) {
        var published = rules.publish(C5ScoreService.RULE_CODE, request, C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN);
        return Map.of("published", published, "view", rules.view(C5ScoreService.RULE_CODE));
    }

    public record DraftSimulation(JsonNode rule, C5Service.Input input) {
    }

    /** 用页面上的草稿试算：只做结构校验和编译，草稿不需要已保存；不写任何数据，正式评分只认已发布版本。 */
    @PostMapping("/simulate")
    public C5Service.Simulation simulate(@RequestBody DraftSimulation request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        return c5.simulateDraft(request.input(), request.rule());
    }

    @GetMapping("/versions")
    public List<ScoreRuleService.VersionSummary> versions() {
        return rules.history(C5ScoreService.RULE_CODE);
    }

    @GetMapping("/versions/{version}")
    public ScoreRuleService.PublishedView version(@PathVariable int version) {
        return rules.version(C5ScoreService.RULE_CODE, version);
    }

    /** 用指定的已发布版本重放一次试算（不改变当前生效版本，也不写任何数据）。 */
    @PostMapping("/versions/{version}/replay")
    public C5Service.Simulation replay(@PathVariable int version, @RequestBody C5Service.Input input) {
        return c5.simulate(input, version);
    }

    /** 评分规则的保存草稿 / 发布审计，按时间倒序。 */
    @GetMapping("/audits")
    public List<Map<String, Object>> audits() {
        return rules.audits(C5ScoreService.RULE_CODE).stream().<Map<String, Object>>map(a -> Map.of("id", a.id, "operation", a.operation,
                "operator", a.operatorName, "operatedAt", a.operatedAt.toString(), "version", versionOf(a))).toList();
    }

    private String versionOf(RuleAudit a) {
        try {
            return "PUBLISH".equals(a.operation) ? "v" + json.readTree(a.afterJson).path("groups").path(0).path("version").asText() : "草稿";
        } catch (Exception ex) {
            return "草稿";
        }
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
