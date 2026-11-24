package com.hsg.relay.c2;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/c2")
public class C2Controller {
    private final C2Service service;
    private final C2FieldCatalog catalog;

    public C2Controller(C2Service service, C2FieldCatalog catalog) {
        this.service = service;
        this.catalog = catalog;
    }

    @GetMapping("/fields")
    public List<C2FieldCatalog.Field> fields() {
        return catalog.all();
    }

    @GetMapping("/capability-tags")
    public List<CapabilityTag> tags() {
        return service.capabilityTags();
    }

    @GetMapping("/regulatory-rules")
    public List<C2Service.RegulatoryView> rules() {
        return service.regulations();
    }

    /** 监管规则表单的下拉项：币种（字段库「交易币种」可选值）、受益行国家（ALL + ISO 3166-1）、能力标签。 */
    @GetMapping("/regulatory-rules/options")
    public C2Service.RegulatoryOptions regulatoryOptions() {
        return service.regulatoryOptions();
    }

    /** 单条监管规则（新增 / 编辑页按编码载入）；不存在时返回 400 + error。 */
    @GetMapping("/regulatory-rules/{id}")
    public C2Service.RegulatoryView rule(@PathVariable String id) {
        return service.regulation(id);
    }

    /**
     * 新增或更新监管规则（按 ruleId）。请求带 trigger（{enabled, tree}）时一并保存附加触发条件，enabled=false 清除；
     * 不带 trigger 时不改动已保存的附加触发条件。
     */
    @PostMapping("/regulatory-rules")
    public C2Service.RegulatoryView save(@RequestBody C2Service.RegulatoryInput rule) {
        return service.saveRegulatory(rule);
    }

    @PostMapping("/regulatory-rules/{id}/toggle")
    public C2Service.RegulatoryView toggle(@PathVariable String id) {
        return service.toggleRegulatory(id);
    }

    /** 只按字段库校验附加触发条件并返回编译后的表达式 `{expression}`，不保存。 */
    @PostMapping("/regulatory-rules/compile")
    public Map<String, String> compileTrigger(@RequestBody C2Service.TriggerConditionInput body) {
        return Map.of("expression", service.compileTrigger(body == null ? null : body.tree()));
    }

    /** 单独保存 / 清除监管规则的附加触发条件：`{enabled, tree, versionNo?}`；`enabled=false` 清除条件。范围和能力标签不变。 */
    @PutMapping("/regulatory-rules/{id}/condition")
    public C2Service.RegulatoryView saveTrigger(@PathVariable String id, @RequestBody C2Service.TriggerConditionInput body) {
        return service.saveTriggerCondition(id, body);
    }

    @DeleteMapping("/regulatory-rules/{id}")
    public void delete(@PathVariable String id, @RequestParam Integer versionNo) {
        service.deleteRegulatory(id, versionNo);
    }

    @GetMapping("/restrictions")
    public List<ProviderRestriction> restrictions() {
        return service.restrictions();
    }

    @PostMapping("/restrictions")
    public ProviderRestriction restriction(@RequestBody C2Service.RestrictionInput body) {
        return service.saveRestriction(body);
    }

    @PostMapping("/restrictions/{id}/toggle")
    public ProviderRestriction toggleRestriction(@PathVariable Long id) {
        return service.toggleRestriction(id);
    }

    @GetMapping("/providers/{code}/capabilities")
    public List<String> capabilities(@PathVariable String code) {
        return service.providerCapabilities(code);
    }

    @PutMapping("/providers/{code}/capabilities")
    public List<String> saveCapabilities(@PathVariable String code, @RequestBody C2Service.CapabilitiesInput body) {
        return service.setProviderCapabilities(code, body);
    }

    @PostMapping("/simulate")
    public C2Service.Result simulate(@RequestBody C2Service.Simulation req) {
        return service.simulate(req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
