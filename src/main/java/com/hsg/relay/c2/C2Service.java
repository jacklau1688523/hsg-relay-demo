package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.Provider;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.ProviderRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class C2Service {
    private final RegulatoryRuleRepository regulatoryRepo;
    private final ProviderRestrictionRepository restrictionRepo;
    private final ProviderCapabilityRepository capabilityRepo;
    private final CapabilityTagRepository tagRepo;
    private final ProviderRepository providerRepo;
    private final RuleAuditRepository auditRepo;
    private final C2ExpressionCompiler compiler;
    private final ObjectMapper mapper;

    public C2Service(RegulatoryRuleRepository regulatoryRepo,ProviderRestrictionRepository restrictionRepo,
        ProviderCapabilityRepository capabilityRepo,CapabilityTagRepository tagRepo,ProviderRepository providerRepo,
        RuleAuditRepository auditRepo,C2ExpressionCompiler compiler,ObjectMapper mapper){
        this.regulatoryRepo=regulatoryRepo;this.restrictionRepo=restrictionRepo;this.capabilityRepo=capabilityRepo;
        this.tagRepo=tagRepo;this.providerRepo=providerRepo;this.auditRepo=auditRepo;this.compiler=compiler;this.mapper=mapper;
    }

    public record Requirement(String type,List<String> requiredTags) {}
    public record FailureAction(String reasonCode) {}
    public record RegulatoryInput(String ruleId,String ruleName,String ruleCode,String scope,JsonNode condition,
        Requirement requirement,FailureAction onFailure,Boolean enabled,Integer versionNo,LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,String remark) {}
    public record RegulatoryView(String ruleId,String ruleName,String ruleCode,String scope,JsonNode condition,
        Requirement requirement,FailureAction onFailure,boolean enabled,int versionNo,LocalDateTime effectiveFrom,
        LocalDateTime effectiveTo,String remark) {}
    public record RestrictionInput(Long id,String providerCode,String restrictionType,String blockedValues,
        Boolean enabled,Integer versionNo,LocalDateTime effectiveFrom,LocalDateTime effectiveTo,String remark) {}
    public record CapabilitiesInput(List<String> tags) {}
    public record CompileView(String expression) {}
    public record Simulation(String providerCode,String paymentCurrency,String debitCurrency,
        String beneficiaryBankCountry,String beneficiaryAddressCountry,String beneficiaryRegion,
        String orderingCountry,String clientCountry,String intermediaryCountry,String purpose,
        List<String> directorUboNationalities) {}
    public record RuleHit(int order,String ruleId,String restrictionType,String reasonCode,String field,String detail) {}
    public record RegulatoryCheck(String ruleId,boolean triggered,List<String> missingTags,String expression) {}
    public record Result(boolean passed,String reasonCode,List<RuleHit> hits,List<RegulatoryCheck> regulatoryChecks,
        List<String> providerCapabilities,String note) {}

    private String encode(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("JSON 序列化失败",e);}}
    private JsonNode parse(String text){try{return mapper.readTree(text);}catch(Exception e){throw new IllegalStateException("数据库规则 JSON 不合法",e);}}
    private void audit(String type,String id,String action,String before,Object after){
        auditRepo.save(new RuleAudit(type,id,action,"demo.operator",before,encode(after)));
    }
    private void validateDates(LocalDateTime from,LocalDateTime to){
        if(from==null||(to!=null&&!to.isAfter(from)))throw new IllegalArgumentException("生效时间必填，失效时间必须晚于生效时间");
    }
    private void validateTags(List<String> tags){
        if(tags==null||tags.isEmpty())throw new IllegalArgumentException("必须选择至少一个能力标签");
        if(tags.size()>20||new HashSet<>(tags).size()!=tags.size())throw new IllegalArgumentException("能力标签不可重复且不能超过20个");
        for(String tag:tags)if(!tagRepo.existsById(tag))throw new IllegalArgumentException("无效能力标签: "+tag);
    }
    private void validateRegulatory(RegulatoryInput in){
        if(in==null||in.ruleId()==null||!in.ruleId().matches("[A-Z0-9_-]{3,48}"))throw new IllegalArgumentException("规则编号格式错误");
        if(in.ruleName()==null||in.ruleName().isBlank()||in.ruleName().length()>140)throw new IllegalArgumentException("规则名称不能为空且不能超过140字符");
        if(!"C2".equals(in.ruleCode())||!"GLOBAL".equals(in.scope()))throw new IllegalArgumentException("监管规则固定为 C2 / GLOBAL");
        if(in.onFailure()==null||!"REG_RULE_NOT_MET".equals(in.onFailure().reasonCode()))throw new IllegalArgumentException("监管规则原因码固定为 REG_RULE_NOT_MET");
        if(in.requirement()==null||!"PROVIDER_CAPABILITY".equals(in.requirement().type()))throw new IllegalArgumentException("监管规则要求类型必须为 PROVIDER_CAPABILITY");
        validateTags(in.requirement().requiredTags());validateDates(in.effectiveFrom(),in.effectiveTo());
        compiler.compile(in.condition());
    }
    public CompileView compile(JsonNode condition){return new CompileView(compiler.compile(condition));}
    public List<RegulatoryView> regulations(){return regulatoryRepo.findAll().stream().sorted(Comparator.comparing(x->x.ruleId)).map(this::view).toList();}
    private RegulatoryView view(RegulatoryRule rule){
        try{
            List<String> tags=Arrays.asList(mapper.readValue(rule.requiredTagsJson,String[].class));
            return new RegulatoryView(rule.ruleId,rule.ruleName,rule.ruleCode,rule.scope,parse(rule.conditionJson),
                new Requirement("PROVIDER_CAPABILITY",tags),new FailureAction(rule.reasonCode),rule.enabled,
                rule.versionNo,rule.effectiveFrom,rule.effectiveTo,rule.remark);
        }catch(Exception e){throw new IllegalStateException("监管规则数据无法解析: "+rule.ruleId,e);}
    }
    @Transactional
    public RegulatoryView saveRegulatory(RegulatoryInput in){
        validateRegulatory(in);
        RegulatoryRule rule=regulatoryRepo.findById(in.ruleId()).orElse(null);
        String before=rule==null?null:encode(view(rule));
        if(rule!=null&&in.versionNo()!=null&&rule.versionNo!=in.versionNo())throw new IllegalArgumentException("规则版本已变化，请刷新后重试");
        boolean creating=rule==null;
        if(creating)rule=new RegulatoryRule();else rule.versionNo++;
        rule.ruleId=in.ruleId();rule.ruleName=in.ruleName().trim();rule.ruleCode="C2";rule.scope="GLOBAL";
        rule.conditionJson=encode(in.condition());rule.requiredTagsJson=encode(in.requirement().requiredTags());
        rule.reasonCode="REG_RULE_NOT_MET";rule.effectiveFrom=in.effectiveFrom();rule.effectiveTo=in.effectiveTo();
        rule.enabled=in.enabled()==null?true:in.enabled();rule.remark=in.remark();
        RegulatoryView saved=view(regulatoryRepo.save(rule));
        audit("REGULATORY",rule.ruleId,creating?"CREATE":"UPDATE",before,saved);
        return saved;
    }
    @Transactional
    public RegulatoryView toggleRegulatory(String id){
        RegulatoryRule rule=regulatoryRepo.findById(id).orElseThrow(()->new IllegalArgumentException("规则不存在"));
        String before=encode(view(rule));rule.enabled=!rule.enabled;rule.versionNo++;
        RegulatoryView updated=view(regulatoryRepo.save(rule));
        audit("REGULATORY",id,rule.enabled?"ENABLE":"DISABLE",before,updated);
        return updated;
    }

    public static final Map<String,String> REASONS=Map.of(
        "BENEFICIARY_COUNTRY","RESTRICTED_COUNTRY",
        "DIRECTOR_UBO_NATIONALITY","RESTRICTED_NATIONALITY",
        "BENEFICIARY_REGION","RESTRICTED_REGION",
        "ORDERING_COUNTRY","RESTRICTED_COUNTRY",
        "INTERMEDIARY_COUNTRY","RESTRICTED_COUNTRY",
        "CURRENCY","RESTRICTED_CURRENCY",
        "PURPOSE","RESTRICTED_PURPOSE");
    private static final Map<String,Integer> PRIORITIES=Map.of(
        "BENEFICIARY_COUNTRY",1,"DIRECTOR_UBO_NATIONALITY",2,"BENEFICIARY_REGION",3,
        "ORDERING_COUNTRY",4,"INTERMEDIARY_COUNTRY",5,"CURRENCY",6,"PURPOSE",7,"REGULATORY",8);
    public List<ProviderRestriction> restrictions(){return restrictionRepo.findAll().stream().sorted(Comparator.comparing(x->x.id)).toList();}
    @Transactional
    public ProviderRestriction saveRestriction(RestrictionInput in){
        if(in==null||!providerRepo.existsById(in.providerCode()))throw new IllegalArgumentException("合作方不存在");
        if(!REASONS.containsKey(in.restrictionType()))throw new IllegalArgumentException("限制类型无效");
        validateDates(in.effectiveFrom(),in.effectiveTo());
        if(in.blockedValues()==null||in.blockedValues().isBlank())throw new IllegalArgumentException("屏蔽编码不能为空");
        List<String> values=Arrays.stream(in.blockedValues().split(",")).map(x->x.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if(values.size()>100||values.stream().anyMatch(x->!x.matches("[A-Z0-9_-]{2,40}")))throw new IllegalArgumentException("屏蔽编码格式错误，支持英文编码、下划线、短横线，数量最多100个");
        ProviderRestriction existing=in.id()==null?null:restrictionRepo.findById(in.id()).orElseThrow(()->new IllegalArgumentException("限制记录不存在"));
        if(existing!=null&&in.versionNo()!=null&&existing.versionNo!=in.versionNo())throw new IllegalArgumentException("限制记录版本已变化，请刷新");
        boolean creating=existing==null;String before=creating?null:encode(existing);
        ProviderRestriction r=creating?new ProviderRestriction():existing;
        if(!creating)r.versionNo++;
        r.providerCode=in.providerCode();r.restrictionType=in.restrictionType();r.blockedValues=String.join(",",values);
        r.effectiveFrom=in.effectiveFrom();r.effectiveTo=in.effectiveTo();r.enabled=in.enabled()==null?true:in.enabled();r.remark=in.remark();
        ProviderRestriction saved=restrictionRepo.save(r);
        audit("C2_RESTRICTION",saved.id.toString(),creating?"CREATE":"UPDATE",before,saved);
        return saved;
    }
    @Transactional
    public ProviderRestriction toggleRestriction(Long id){
        ProviderRestriction r=restrictionRepo.findById(id).orElseThrow(()->new IllegalArgumentException("限制记录不存在"));
        String before=encode(r);r.enabled=!r.enabled;r.versionNo++;
        ProviderRestriction saved=restrictionRepo.save(r);
        audit("C2_RESTRICTION",id.toString(),r.enabled?"ENABLE":"DISABLE",before,saved);
        return saved;
    }
    public List<CapabilityTag> capabilityTags(){return tagRepo.findAll().stream().sorted(Comparator.comparing(x->x.tagCode)).toList();}
    public List<String> providerCapabilities(String code){
        if(!providerRepo.existsById(code))throw new IllegalArgumentException("合作方不存在");
        return capabilityRepo.findByProviderCode(code).stream().map(c->c.tagCode).sorted().toList();
    }
    @Transactional
    public List<String> setProviderCapabilities(String code,CapabilitiesInput body){
        if(!providerRepo.existsById(code))throw new IllegalArgumentException("合作方不存在");
        List<String> tags=body==null||body.tags()==null?List.of():body.tags();
        if(tags.size()>20||new HashSet<>(tags).size()!=tags.size())throw new IllegalArgumentException("能力标签不可重复，最多20个");
        for(String tag:tags)if(!tagRepo.existsById(tag))throw new IllegalArgumentException("无效标签: "+tag);
        List<String> before=providerCapabilities(code);
        capabilityRepo.deleteByProviderCode(code);
        capabilityRepo.flush();
        for(String tag:tags)capabilityRepo.save(new ProviderCapability(code,tag));
        audit("PROVIDER_CAPABILITY",code,"UPDATE",encode(before),tags);
        return providerCapabilities(code);
    }
    private static boolean active(boolean enabled,LocalDateTime start,LocalDateTime end,LocalDateTime now){
        return enabled&&start!=null&&!now.isBefore(start)&&(end==null||now.isBefore(end));
    }
    private static String normalize(String value){return value==null?"":value.trim().toUpperCase(Locale.ROOT);}
    private static Set<String> blocked(ProviderRestriction r){return Arrays.stream(r.blockedValues.split(",")).map(C2Service::normalize).collect(Collectors.toSet());}
    private static boolean hit(Set<String> blocked,String value){return !normalize(value).isEmpty()&&blocked.contains(normalize(value));}
    private static boolean hitNationality(Set<String> blocked,List<String> nationalities){return nationalities!=null&&nationalities.stream().anyMatch(v->hit(blocked,v));}
    private static String matchedField(ProviderRestriction r,Simulation req){
        Set<String> blocked=blocked(r);
        return switch(r.restrictionType){
            case "BENEFICIARY_COUNTRY" -> hit(blocked,req.beneficiaryBankCountry())?"beneficiaryBankCountry":hit(blocked,req.beneficiaryAddressCountry())?"beneficiaryAddressCountry":null;
            case "DIRECTOR_UBO_NATIONALITY" -> hitNationality(blocked,req.directorUboNationalities())?"directorUboNationalities":null;
            case "BENEFICIARY_REGION" -> hit(blocked,req.beneficiaryRegion())?"beneficiaryRegion":null;
            case "ORDERING_COUNTRY" -> hit(blocked,req.clientCountry())?"clientCountry":hit(blocked,req.orderingCountry())?"orderingCountry":null;
            case "INTERMEDIARY_COUNTRY" -> hit(blocked,req.intermediaryCountry())?"intermediaryCountry":null;
            case "CURRENCY" -> hit(blocked,req.debitCurrency())?"debitCurrency":hit(blocked,req.paymentCurrency())?"paymentCurrency":null;
            case "PURPOSE" -> hit(blocked,req.purpose())?"purpose":null;
            default -> throw new IllegalStateException("未知限制类型");
        };
    }
    public Result simulate(Simulation req){ return simulate(req, LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"))); }
    public Result simulate(Simulation req, LocalDateTime now){
        if(req==null||req.providerCode()==null||!providerRepo.existsById(req.providerCode()))throw new IllegalArgumentException("有效合作方必填");
        if(normalize(req.paymentCurrency()).isEmpty()||normalize(req.beneficiaryBankCountry()).isEmpty())throw new IllegalArgumentException("交易币种、受益行国家必填");
        List<String> capabilities=providerCapabilities(req.providerCode());
        List<RuleHit> hits=new ArrayList<>();
        for(ProviderRestriction rule:restrictionRepo.findAll()){
            if(!req.providerCode().equals(rule.providerCode)||!active(rule.enabled,rule.effectiveFrom,rule.effectiveTo,now))continue;
            String field=matchedField(rule,req);
            if(field!=null){
                // Never expose actual UBO nationality values in public response.
                hits.add(new RuleHit(PRIORITIES.get(rule.restrictionType),"C2-RESTRICTION-"+rule.id,
                    rule.restrictionType,REASONS.get(rule.restrictionType),field,"命中合作方限制类型 "+rule.restrictionType));
            }
        }
        Map<String,Object> variables=new HashMap<>();
        variables.put("paymentCurrency",req.paymentCurrency());variables.put("debitCurrency",req.debitCurrency());
        variables.put("beneficiaryBankCountry",req.beneficiaryBankCountry());
        variables.put("beneficiaryAddressCountry",req.beneficiaryAddressCountry());
        variables.put("beneficiaryRegion",req.beneficiaryRegion());variables.put("orderingCountry",req.orderingCountry());
        variables.put("clientCountry",req.clientCountry());variables.put("intermediaryCountry",req.intermediaryCountry());
        variables.put("purpose",req.purpose());
        List<RegulatoryCheck> checks=new ArrayList<>();
        for(RegulatoryRule rule:regulatoryRepo.findAll()){
            if(!active(rule.enabled,rule.effectiveFrom,rule.effectiveTo,now))continue;
            RegulatoryView view=view(rule);
            // Fail closed: invalid published rule configuration is not swallowed as a PASS.
            String expression=compiler.compile(view.condition());
            boolean triggered=compiler.evaluate(view.condition(),variables);
            List<String> missing=triggered?view.requirement().requiredTags().stream().filter(tag->!capabilities.contains(tag)).toList():List.of();
            checks.add(new RegulatoryCheck(rule.ruleId,triggered,missing,expression));
            if(!missing.isEmpty())hits.add(new RuleHit(8,rule.ruleId,"REGULATORY","REG_RULE_NOT_MET",
                "providerCapabilities","监管规则命中，缺少能力标签："+String.join(",",missing)));
        }
        hits.sort(Comparator.comparingInt(RuleHit::order).thenComparing(RuleHit::ruleId));
        return new Result(hits.isEmpty(),hits.isEmpty()?"PASS":hits.get(0).reasonCode(),List.copyOf(hits),checks,capabilities,
            "仅模拟 C2；不代表通过 C1、C3/C4/C6/C10。未填写的可选字段按未命中处理。");
    }
}
