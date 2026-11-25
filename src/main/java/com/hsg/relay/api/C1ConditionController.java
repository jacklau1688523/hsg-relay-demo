package com.hsg.relay.api;

import com.hsg.relay.domain.C1ConditionConfig;
import com.hsg.relay.service.C1ConditionService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/c1/condition")
public class C1ConditionController {
    private final C1ConditionService conditions;

    public C1ConditionController(C1ConditionService conditions) { this.conditions = conditions; }

    @GetMapping
    public C1ConditionConfig get() { return conditions.get(); }

    @PostMapping("/compile")
    public Map<String, String> compile(@RequestBody C1ConditionService.Input input) {
        return Map.of("expression", conditions.compile(input));
    }

    @PutMapping
    public C1ConditionConfig save(@RequestBody C1ConditionService.Input input) { return conditions.save(input); }
}
