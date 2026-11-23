package com.hsg.relay.routing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/c4")
public class C4Controller {
    private final C4Service service;

    public C4Controller(C4Service s) {
        service = s;
    }

    @GetMapping("/balances")
    public C4Service.Listing balances() {
        return service.balances();
    }

    @PutMapping("/balances")
    public C4Service.Listing save(@RequestBody C4Service.Save in) {
        return service.save(in);
    }

    @PostMapping("/balances/delete")
    public C4Service.Listing delete(@RequestBody C4Service.Delete in) {
        return service.delete(in);
    }

    @PostMapping("/simulate")
    public C4Service.Simulation simulate(@RequestBody C4Service.Input in) {
        return service.simulate(in);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
