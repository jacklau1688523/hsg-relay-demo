package com.hsg.relay.routing;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController @RequestMapping("/api/c7")
public class C7Controller {
 private final C7Service service;
 public C7Controller(C7Service s){service=s;}
 @GetMapping("/fees") public C7Service.Listing fees(){return service.fees();}
 @PutMapping("/fees") public C7Service.Listing save(@RequestBody C7Service.Save in){return service.save(in);}
 @PostMapping("/fees/delete") public C7Service.Listing delete(@RequestBody C7Service.Delete in){return service.delete(in);}
 @PostMapping("/simulate") public C7Service.Simulation simulate(@RequestBody C7Service.Input in){return service.simulate(in);}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<?> invalid(Exception ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()));}
}
