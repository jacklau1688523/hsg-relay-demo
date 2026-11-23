package com.hsg.relay.routing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/routing")
public class RoutingController {
    private final RoutingService service;

    public RoutingController(RoutingService s) {
        service = s;
    }

    @GetMapping("/config")
    public RoutingService.ConfigView config() {
        return service.config();
    }

    @PutMapping("/config")
    public RoutingService.ConfigView save(@RequestBody RoutingService.ConfigView c) {
        return service.saveConfig(c);
    }

    @PostMapping("/simulate")
    public RoutingService.Result route(@RequestBody RoutingService.Request r) {
        return service.simulate(r);
    }

    @GetMapping("/decisions/{id}")
    public Object decision(@PathVariable String id) {
        return service.decision(id);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> error(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
