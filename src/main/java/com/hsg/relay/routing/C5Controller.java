package com.hsg.relay.routing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/c5")
public class C5Controller {
    private final C5Service service;

    public C5Controller(C5Service s) {
        service = s;
    }

    @GetMapping("/schedules")
    public C3Service.Listing list() {
        return service.list();
    }

    @PutMapping("/schedules")
    public C3Service.Listing save(@RequestBody C5Service.Save in) {
        return service.save(in);
    }

    @PostMapping("/simulate")
    public C5Service.Simulation simulate(@RequestBody C5Service.Input in) {
        return service.simulate(in);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
