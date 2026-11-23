package com.hsg.relay.routing;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController @RequestMapping("/api/c8")
public class C8Controller {
 private final C8Service service;
 public C8Controller(C8Service s){service=s;}
 @GetMapping("/rules") public C8Service.Listing rules(){return service.rules();}
 @PutMapping("/rules") public C8Service.Listing save(@RequestBody C8Service.Save in){return service.save(in);}
 @PostMapping("/rules/delete") public C8Service.Listing delete(@RequestBody C8Service.Delete in){return service.delete(in);}
 @PostMapping("/simulate") public C8Service.Simulation simulate(@RequestBody C8Service.Input in){return service.simulate(in);}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<?> invalid(Exception ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()));}
}
