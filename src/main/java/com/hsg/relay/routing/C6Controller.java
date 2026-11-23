package com.hsg.relay.routing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/c6")
public class C6Controller {
    private final C6Service service;

    public C6Controller(C6Service s) {
        service = s;
    }

    @GetMapping("/limits")
    public C6Service.Listing limits() {
        return service.limits();
    }

    @PutMapping("/limits")
    public C6Service.Listing save(@RequestBody C6Service.Save in) {
        return service.save(in);
    }

    @PutMapping("/today")
    public C6Service.Listing today(@RequestBody C6Service.Today in) {
        return service.today(in);
    }

    @PostMapping("/simulate")
    public C6Service.Simulation simulate(@RequestBody C6Service.Input in) {
        return service.simulate(in);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
