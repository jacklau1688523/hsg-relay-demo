package com.hsg.relay.routing;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.Map;

@RestController
@RequestMapping("/api/c9")
public class C9Controller {
    private final C9Service service;

    public C9Controller(C9Service s) {
        service = s;
    }

    @GetMapping("/agreements")
    public C9Service.Listing list() {
        return service.list();
    }

    @PutMapping("/agreements")
    public C9Service.Listing save(@RequestBody C9Service.Save in) {
        return service.save(in);
    }

    @PostMapping("/agreements/delete")
    public C9Service.Listing delete(@RequestBody C9Service.Delete in) {
        return service.delete(in);
    }

    @PutMapping("/mtd")
    public C9Service.Listing mtd(@RequestBody C9Service.Mtd in) {
        return service.mtd(in);
    }

    @PostMapping("/mtd/delete")
    public C9Service.Listing deleteMtd(@RequestBody C9Service.DeleteMtd in) {
        return service.deleteMtd(in);
    }

    @PostMapping("/simulate")
    public C9Service.Simulation simulate(@RequestBody C9Service.Input in) {
        return service.simulate(in);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<?> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
