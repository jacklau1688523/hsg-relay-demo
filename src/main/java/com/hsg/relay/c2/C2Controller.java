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

    @PostMapping("/regulatory-rules")
    public C2Service.RegulatoryView save(@RequestBody C2Service.RegulatoryInput rule) {
        return service.saveRegulatory(rule);
    }

    @PostMapping("/regulatory-rules/{id}/toggle")
    public C2Service.RegulatoryView toggle(@PathVariable String id) {
        return service.toggleRegulatory(id);
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
