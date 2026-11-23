package com.hsg.relay.routing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/c10")
public class C10Controller {
    private final C10Service service;

    public C10Controller(C10Service s) {
        service = s;
    }

    @GetMapping("/windows")
    public C10Service.Listing windows() {
        return service.windows();
    }

    @PutMapping("/windows")
    public C10Service.Listing save(@RequestBody C10Service.Save in) {
        return service.save(in);
    }

    @PostMapping("/windows/delete")
    public C10Service.Listing delete(@RequestBody C10Service.Delete in) {
        return service.delete(in);
    }

    @PostMapping("/simulate")
    public C10Service.Simulation simulate(@RequestBody C10Service.Input in) {
        return service.simulate(in);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
