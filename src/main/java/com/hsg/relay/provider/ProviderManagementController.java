package com.hsg.relay.provider;
import com.hsg.relay.domain.RuleAudit;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.util.*;
@RestController @RequestMapping("/api/provider-management")
public class ProviderManagementController {
 private final ProviderManagementService service;
 public ProviderManagementController(ProviderManagementService s){service=s;}
 @GetMapping public List<ProviderManagementService.View> list(){return service.list();}
 @GetMapping("/{code}") public ProviderManagementService.View get(@PathVariable String code){return service.get(code);}
 @PostMapping public ProviderManagementService.View create(@RequestBody ProviderManagementService.Input in){return service.create(in);}
 @PutMapping("/{code}") public ProviderManagementService.View update(@PathVariable String code,@RequestBody ProviderManagementService.Input in){return service.update(code,in);}
 @GetMapping("/{code}/related") public ProviderManagementService.Related related(@PathVariable String code){return service.related(code);}
 @GetMapping("/{code}/audit") public List<RuleAudit> audit(@PathVariable String code){return service.history(code);}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<?> invalid(Exception e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
 @ExceptionHandler(ObjectOptimisticLockingFailureException.class) public ResponseEntity<?> conflict(Exception e){return ResponseEntity.status(409).body(Map.of("error","合作方已被其他操作更新，请刷新后重试"));}
}
