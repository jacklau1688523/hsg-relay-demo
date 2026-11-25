package com.hsg.relay.routing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/c3")
public class C3Controller {
    private final C3Service service;
    private final C3ConditionService conditions;

    public C3Controller(C3Service service, C3ConditionService conditions) {
        this.service = service;
        this.conditions = conditions;
    }

    @GetMapping("/condition")
    public C3ConditionConfig condition() { return conditions.get(); }

    @PostMapping("/condition/compile")
    public Map<String,String> compileCondition(@RequestBody C3ConditionService.Input input) {
        return Map.of("expression",conditions.compile(input));
    }

    @PutMapping("/condition")
    public C3ConditionConfig saveCondition(@RequestBody C3ConditionService.Input input) { return conditions.save(input); }

    @GetMapping("/reaches")
    public C3Service.Listing list() {
        return service.list();
    }

    @PostMapping("/reaches")
    public C3Service.Listing save(@RequestBody C3Service.Save in) {
        return service.save(in);
    }

    @PostMapping("/reaches/{index}/toggle")
    public C3Service.Listing toggle(@PathVariable int index, @RequestBody C3Service.Toggle in) {
        return service.toggle(index, in);
    }

    @PostMapping("/reaches/{index}/delete")
    public C3Service.Listing delete(@PathVariable int index, @RequestBody C3Service.Toggle in) {
        return service.delete(index, in);
    }

    @PostMapping("/simulate")
    public C3Service.Simulation simulate(@RequestBody C3Matcher.Input in) {
        return service.simulate(in);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
