package com.hsg.relay.api;

import com.hsg.relay.service.CheckSwitchService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/check-switches")
public class CheckSwitchController {
    private final CheckSwitchService service;

    public CheckSwitchController(CheckSwitchService service) {
        this.service = service;
    }

    public record Input(Boolean enabled) {}

    @GetMapping("/{code}")
    public CheckSwitchService.State get(@PathVariable String code) {
        return service.get(code);
    }

    @PutMapping("/{code}")
    public CheckSwitchService.State save(@PathVariable String code, @RequestBody Input input) {
        return service.save(code, input == null ? null : input.enabled());
    }
}
