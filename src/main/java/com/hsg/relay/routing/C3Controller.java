package com.hsg.relay.routing;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController @RequestMapping("/api/c3")
public class C3Controller {
 private final C3Service service;
 public C3Controller(C3Service service){this.service=service;}
 @GetMapping("/reaches") public C3Service.Listing list(){return service.list();}
 @PostMapping("/reaches") public C3Service.Listing save(@RequestBody C3Service.Save in){return service.save(in);}
 @PostMapping("/reaches/{index}/toggle") public C3Service.Listing toggle(@PathVariable int index,@RequestBody C3Service.Toggle in){return service.toggle(index,in);}
 @PostMapping("/simulate") public C3Service.Simulation simulate(@RequestBody C3Matcher.Input in){return service.simulate(in);}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
}
