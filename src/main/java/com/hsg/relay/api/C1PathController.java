package com.hsg.relay.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.service.C1PathConfigService;
import com.hsg.relay.service.C1Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * C1 合作方动态规则路径配置的页面接口：草稿、校验、发布、历史版本、审计、迁移说明，以及用草稿试算。
 * 参数错误与状态错误统一返回 400 + {error}；判定结果（含 C1_CONFIG_MISSING 等配置原因的拒绝）是 200 + Result（passed=false）。
 */
@RestController
@RequestMapping("/api/c1/paths")
public class C1PathController {
    private final C1PathConfigService paths;
    private final C1Service c1;
    private final ObjectMapper json;

    public C1PathController(C1PathConfigService paths, C1Service c1, ObjectMapper json) {
        this.paths = paths;
        this.c1 = c1;
        this.json = json;
    }

    public record RuleBody(JsonNode rule) {
    }

    /** rule 为空时用已保存的草稿试算。 */
    public record SimulateBody(JsonNode rule, C1Service.Simulation simulation) {
    }

    /** 页面一次取齐：草稿、最新已发布版本、草稿是否有改动、历史版本、迁移提示。 */
    @GetMapping
    public C1PathConfigService.View view() {
        return paths.view();
    }

    /** 保存草稿：结构 + 编译 + 删除保护；不参与执行。 */
    @PutMapping("/draft")
    public C1PathConfigService.View saveDraft(@RequestBody C1PathConfigService.DraftRequest request) {
        if (request == null) throw new IllegalArgumentException("保存请求不能为空");
        return paths.saveDraft(request.rule(), request.expectedDraftVersion(), request.operator());
    }

    /** 结构 + 编译：每条非空条件树的可读表达式。 */
    @PostMapping("/compile")
    public C1PathConfigService.CheckResult compile(@RequestBody RuleBody body) {
        return paths.compile(body == null ? null : body.rule());
    }

    /** 全部校验（与发布时的检查一致），不落库。 */
    @PostMapping("/check")
    public C1PathConfigService.CheckResult check(@RequestBody RuleBody body) {
        return paths.check(body == null ? null : body.rule());
    }

    @PostMapping("/publish")
    public C1PathConfigService.PublishedView publish(@RequestBody C1PathConfigService.PublishRequest request) {
        return paths.publish(request);
    }

    /** 用页面草稿试算（只试算，不落库）；rule 为空时用已保存的草稿。 */
    @PostMapping("/simulate")
    public C1Service.Result simulate(@RequestBody SimulateBody body) {
        if (body == null || body.simulation() == null) throw new IllegalArgumentException("模拟请求不能为空");
        JsonNode rule = body.rule();
        if (rule == null || rule.isNull()) {
            rule = paths.storedDraft();
            if (rule == null) throw new IllegalArgumentException("没有可试算的路径草稿");
        }
        return c1.evaluateDraft(body.simulation(), rule);
    }

    /** 历史版本（新 → 旧）。 */
    @GetMapping("/history")
    public List<C1PathConfigService.VersionSummary> history() {
        return paths.history();
    }

    @GetMapping("/versions/{version}")
    public C1PathConfigService.PublishedView version(@PathVariable int version) {
        return paths.version(version);
    }

    /** 审计记录（新 → 旧）。 */
    @GetMapping("/audits")
    public List<RuleAudit> audits() {
        return paths.audits();
    }

    /** 从旧版全局准入条件迁入的说明。 */
    @GetMapping("/migration")
    public C1PathConfigService.MigrationView migration() {
        return paths.migrationView();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage() == null ? ex.toString() : ex.getMessage()));
    }
}
