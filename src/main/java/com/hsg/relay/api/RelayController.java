package com.hsg.relay.api;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import com.hsg.relay.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
@RestController @RequestMapping("/api")
public class RelayController {
 private final ProviderRepository providers;private final AppetiteRepository appetites;private final ClientProfileRepository clients;private final ClientExclusionRepository exclusions;private final CaseApprovalRepository approvals;private final ProviderUsageRepository usages;private final RuleAuditRepository audit;private final FieldDefinitionRepository fields;private final C1Service c1;private final ExpressionService expressions;private final ObjectMapper json;
 public RelayController(ProviderRepository p,AppetiteRepository a,ClientProfileRepository c,ClientExclusionRepository e,CaseApprovalRepository ap,ProviderUsageRepository u,RuleAuditRepository audit,FieldDefinitionRepository f,C1Service c1,ExpressionService ex,ObjectMapper json){this.providers=p;this.appetites=a;this.clients=c;this.exclusions=e;this.approvals=ap;this.usages=u;this.audit=audit;this.fields=f;this.c1=c1;this.expressions=ex;this.json=json;}
 @GetMapping("/providers") public List<Provider> providers(){return providers.findAll();}
 @GetMapping("/appetites") public List<Appetite> appetites(@RequestParam(required=false) String providerCode){return appetites.findAll().stream().filter(a->providerCode==null||providerCode.isBlank()||a.providerCode.equals(providerCode)).sorted(Comparator.comparing(a->a.id)).toList();}
 @PostMapping("/appetites") public Appetite save(@RequestBody Appetite input){
  if(!providers.existsById(input.providerCode))throw new IllegalArgumentException("无效合作方");
  if(!List.of("STANDARD","PSP","WEB3").contains(input.flowCategory))throw new IllegalArgumentException("无效流转类别");
  if(!List.of("ACCEPT","EXCLUDE","CASE_BY_CASE").contains(input.action))throw new IllegalArgumentException("无效准入方式");
  if(input.currency==null||input.currency.isBlank()||input.bankCountry==null||input.bankCountry.isBlank()||input.evidenceDocNo==null||input.evidenceDocNo.isBlank()||input.nextReviewDate==null||input.effectiveFrom==null)throw new IllegalArgumentException("必填项缺失");
  if(input.effectiveTo!=null&&!input.effectiveTo.isAfter(input.effectiveFrom))throw new IllegalArgumentException("失效时间应晚于生效时间");
  if(input.monthlyCapUsd!=null&&input.monthlyCapUsd.signum()<0)throw new IllegalArgumentException("月度上限不可小于0");
  if(!"ACCEPT".equals(input.action))input.monthlyCapUsd=null;
  Appetite previous=input.id==null?null:appetites.findById(input.id).orElseThrow(()->new IllegalArgumentException("配置不存在"));
  if(previous!=null){input.versionNo=previous.versionNo+1;}
  else{input.versionNo=1;}
  boolean collision=appetites.findAll().stream().anyMatch(a->a.enabled&&input.enabled&&!Objects.equals(a.id,input.id)&&a.providerCode.equals(input.providerCode)&&a.flowCategory.equals(input.flowCategory)&&a.currency.equalsIgnoreCase(input.currency)&&a.bankCountry.equalsIgnoreCase(input.bankCountry)&&overlap(a.effectiveFrom,a.effectiveTo,input.effectiveFrom,input.effectiveTo));
  if(collision)throw new IllegalArgumentException("同一合作方、流转类别、币种及国家的生效期重叠");
  Appetite saved=appetites.save(input);
  try{audit.save(new RuleAudit("APPETITE",saved.id.toString(),previous==null?"CREATE":"UPDATE","demo.operator",previous==null?null:json.writeValueAsString(previous),json.writeValueAsString(saved)));}catch(Exception e){throw new IllegalStateException("审计写入失败",e);}
  return saved;
 }
 private boolean overlap(LocalDateTime a1,LocalDateTime a2,LocalDateTime b1,LocalDateTime b2){return (a2==null||b1.isBefore(a2))&&(b2==null||a1.isBefore(b2));}
 @PostMapping("/appetites/{id}/toggle") public Appetite toggle(@PathVariable Long id){Appetite a=appetites.findById(id).orElseThrow();a.enabled=!a.enabled;a.versionNo++;Appetite s=appetites.save(a);audit.save(new RuleAudit("APPETITE",id.toString(),a.enabled?"ENABLE":"DISABLE","demo.operator",null,"{\"enabled\":"+s.enabled+"}"));return s;}
 @GetMapping("/clients") public List<ClientProfile> clients(){return clients.findAll();}
 @GetMapping("/exclusions") public List<ClientExclusion> exclusions(){return exclusions.findAll();}
 @PostMapping("/exclusions") public ClientExclusion addExclusion(@RequestBody ClientExclusion x){if(!clients.existsById(x.clientId)||!providers.existsById(x.providerCode)||x.reason==null||x.reason.isBlank())throw new IllegalArgumentException("客户/合作方/原因无效");if(x.effectiveFrom==null)x.effectiveFrom=LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));ClientExclusion saved=exclusions.save(x);audit.save(new RuleAudit("EXCLUSION",saved.id.toString(),"CREATE","demo.compliance",null,"client="+x.clientId+",provider="+x.providerCode));return saved;}
 @GetMapping("/approvals") public List<CaseApproval> approvals(){return approvals.findAll();}
 @PostMapping("/approvals") public CaseApproval addApproval(@RequestBody CaseApproval x){if(!clients.existsById(x.clientId)||!providers.existsById(x.providerCode)||x.flowCategory==null||x.approvalReference==null||x.approvalReference.isBlank()||x.effectiveFrom==null||x.expiresAt==null||!x.expiresAt.isAfter(x.effectiveFrom))throw new IllegalArgumentException("审批字段无效");CaseApproval saved=approvals.save(x);audit.save(new RuleAudit("APPROVAL",saved.id.toString(),"CREATE","demo.ops",null,"reference="+x.approvalReference));return saved;}
 @GetMapping("/usage") public List<ProviderUsage> usage(){return usages.findAll();}
 @GetMapping("/audit") public List<RuleAudit> audit(){return audit.findAll().stream().sorted(Comparator.comparing((RuleAudit x)->x.id).reversed()).limit(100).toList();}
 @GetMapping("/fields") public List<FieldDefinition> fields(){return fields.findAll();}
 @PostMapping("/c1/simulate") public C1Service.Result simulate(@RequestBody C1Service.Simulation req){return c1.evaluate(req);}
 @PostMapping("/expression/preview") public ExpressionService.PreviewResult preview(@RequestBody ExpressionService.Preview req){return expressions.preview(req);}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class}) public ResponseEntity<Map<String,Object>> invalid(Exception ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()));}
}
