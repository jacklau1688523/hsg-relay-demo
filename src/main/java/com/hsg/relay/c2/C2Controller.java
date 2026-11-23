package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/c2")
public class C2Controller {
    private final C2Service service;private final C2FieldCatalog catalog;
    public C2Controller(C2Service service,C2FieldCatalog catalog){this.service=service;this.catalog=catalog;}
    @GetMapping("/fields") public List<C2FieldCatalog.Field> fields(){return catalog.all();}
    @GetMapping("/capability-tags") public List<CapabilityTag> tags(){return service.capabilityTags();}
    @GetMapping("/regulatory-rules") public List<C2Service.RegulatoryView> rules(){return service.regulations();}
    @PostMapping("/regulatory-rules") public C2Service.RegulatoryView save(@RequestBody C2Service.RegulatoryInput rule){return service.saveRegulatory(rule);}
    @PostMapping("/regulatory-rules/{id}/toggle") public C2Service.RegulatoryView toggle(@PathVariable String id){return service.toggleRegulatory(id);}
    @PostMapping("/regulatory-rules/compile") public C2Service.CompileView compile(@RequestBody JsonNode condition){return service.compile(condition);}
    @GetMapping("/restrictions") public List<ProviderRestriction> restrictions(){return service.restrictions();}
    @PostMapping("/restrictions") public ProviderRestriction restriction(@RequestBody C2Service.RestrictionInput body){return service.saveRestriction(body);}
    @PostMapping("/restrictions/{id}/toggle") public ProviderRestriction toggleRestriction(@PathVariable Long id){return service.toggleRestriction(id);}
    @GetMapping("/providers/{code}/capabilities") public List<String> capabilities(@PathVariable String code){return service.providerCapabilities(code);}
    @PutMapping("/providers/{code}/capabilities") public List<String> saveCapabilities(@PathVariable String code,@RequestBody C2Service.CapabilitiesInput body){return service.setProviderCapabilities(code,body);}
    @PostMapping("/simulate") public C2Service.Result simulate(@RequestBody C2Service.Simulation req){return service.simulate(req);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()));}
}
