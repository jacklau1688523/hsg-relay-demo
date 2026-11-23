package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.c2.C2Service;
import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import com.hsg.relay.service.C1Service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.*;

@Service
public class RoutingService {
 private static final MathContext MC=MathContext.DECIMAL128;
 private static final ZoneId HK=ZoneId.of("Asia/Hong_Kong");
 private final RoutingDocumentRepository docs; private final ObjectMapper json;
 private final ProviderRepository providers; private final ClientProfileRepository clients;
 private final RuleAuditRepository audit; private final C1Service c1; private final C2Service c2;
 public RoutingService(RoutingDocumentRepository d,ObjectMapper j,ProviderRepository p,ClientProfileRepository cl,RuleAuditRepository a,C1Service c1,C2Service c2){docs=d;json=j;providers=p;clients=cl;audit=a;this.c1=c1;this.c2=c2;}
 public record ConfigView(long revision,RoutingConfig config) {}
 public record Request(String paymentId,String clientId,String debitCurrency,String paymentCurrency,BigDecimal amount,
  String beneficiaryBankCountry,String beneficiaryAddressCountry,String beneficiaryRegion,String beneficiaryBankBic,
  String chargeOption,String orderingCountry,String clientCountry,String intermediaryCountry,String purpose,
  List<String> directorUboNationalities,String paymentType,String clientSegment,@com.fasterxml.jackson.annotation.JsonAlias("requested_value_date") LocalDate requestedValueDate,
  ZonedDateTime at,List<String> includeProviders) {}
 public record Check(String code,boolean passed,List<String> reasons,Object detail) {}
 public static class Candidate {
  public String providerCode,fxVenue,channel; public int priority; public boolean passed;
  public List<Check> checks=new ArrayList<>(); public LocalDate valueDate; public BigDecimal estimatedCostHkd;
  public Map<String,BigDecimal> scores=new LinkedHashMap<>(); public BigDecimal totalScore; public Integer rank;
  public ZonedDateTime nextFxWindow;
  public C5Calculator.Result c5;
  @com.fasterxml.jackson.annotation.JsonIgnore public BigDecimal rawCost,rawScore;
 }
 public record Result(String decisionId,String paymentId,String result,String reasonCode,Candidate route,List<Candidate> alternatives,
  List<Candidate> candidates,ZonedDateTime nextFxWindow,long configRevision,boolean simulation,boolean opsCheckRequired,List<String> messages) {}
 private String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
 public ConfigView config(){
  RoutingDocument d=docs.findById("CONFIG").orElse(null);
  try {return d==null?new ConfigView(0,json.readValue(getClass().getResourceAsStream("/routing-demo.json"),RoutingConfig.class)):new ConfigView(d.revision,json.readValue(d.payload,RoutingConfig.class));}
  catch(Exception e){throw new IllegalStateException("路由参考配置不可读",e);}
 }
 @Transactional public ConfigView saveConfig(ConfigView v){
  if(v==null||v.config()==null)throw new IllegalArgumentException("配置必填");
  ConfigView before=config();if(v.revision()!=before.revision())throw new IllegalArgumentException("配置已更新，请重新加载");
  validateConfig(v.config());
  docs.save(new RoutingDocument("CONFIG",v.revision()+1,encode(v.config())));
  audit.save(new RuleAudit("ROUTING_CONFIG","CONFIG","UPDATE","demo.operator",encode(before),encode(v.config())));
  return new ConfigView(v.revision()+1,v.config());
 }
 public void validateConfig(RoutingConfig c){
  try {
   if(c.hkdRates()==null||c.reaches()==null||c.balances()==null||c.fees()==null||c.fxWindows()==null||c.preferences()==null||c.agreements()==null||c.weights()==null||c.todayUsd()==null||c.monthUsd()==null||c.monthCount()==null||c.hongKongHolidays()==null)throw new IllegalArgumentException("配置集合不可缺失");
   c.hkdRates().values().forEach(x->{if(x==null||x.signum()<=0)throw new IllegalArgumentException("汇率必须大于零");});
   if(!BigDecimal.ONE.equals(c.hkdRates().get("HKD")))throw new IllegalArgumentException("HKD 汇率必须为1");
   Set<String> keys=new HashSet<>();
   for(Weight w:c.weights()){if(w.cost()<0||w.speed()<0||w.preference()<0||w.agreement()<0||w.cost()+w.speed()+w.preference()+w.agreement()!=100||!keys.add(w.paymentType()+":"+w.segment()))throw new IllegalArgumentException("权重必须非负、合计100且维度唯一");}
   if(c.weights().stream().noneMatch(w->"OTHER".equals(w.paymentType())&&"ALL".equals(w.segment())))throw new IllegalArgumentException("缺少 OTHER/ALL 默认权重");
   keys.clear();for(Reach r:c.reaches()){provider(r.provider());rate(c,r.currency());C3Matcher.validate(r);ZoneId.of(r.zone());Objects.requireNonNull(r.cutoff());Objects.requireNonNull(r.country());Objects.requireNonNull(r.region());Objects.requireNonNull(r.bics());Objects.requireNonNull(r.charges());Objects.requireNonNull(r.holidays());nonnegative(r.perPaymentUsd(),r.dailyUsd());C5Calculator.validate(r.zone(),r.cutoff(),r.offsetDays(),r.holidays());if(!keys.add(r.provider()+r.currency()+r.country()+r.region()+new TreeSet<>(r.bics())+new TreeSet<>(r.charges())))throw new IllegalArgumentException("覆盖范围重复");}
   keys.clear();for(Balance b:c.balances()){provider(b.provider());rate(c,b.currency());nonnegative(b.balance(),b.reserved());if(!keys.add(b.provider()+b.currency()))throw new IllegalArgumentException("余额账户重复");}
   keys.clear();for(Fee f:c.fees()){provider(f.provider());rate(c,f.currency());rate(c,f.feeCurrency());nonnegative(f.fixed(),f.rate(),f.minimum(),f.cable(),f.intermediary());if(f.maximum()!=null&&f.maximum().compareTo(f.minimum())<0)throw new IllegalArgumentException("最高手续费小于最低费");if(!keys.add(f.provider()+f.currency()+f.charge()))throw new IllegalArgumentException("费用维度重复");}
   for(FxWindow w:c.fxWindows()){if(!"TREASURY".equals(w.provider()))provider(w.provider());rate(c,w.debit());rate(c,w.payment());if(w.days()==null||w.days().isEmpty()||w.days().stream().anyMatch(d->!Set.of("MON","TUE","WED","THU","FRI","SAT","SUN","HOLIDAY").contains(d))||w.start()==null||w.end()==null||!w.end().isAfter(w.start()))throw new IllegalArgumentException("外汇窗口需同日且起止时间有效");nonnegative(w.spreadBps(),w.extraBps());if(w.maxUsd()!=null)nonnegative(w.maxUsd());}
   for(Preference p:c.preferences()){provider(p.provider());if(p.points()<1||p.points()>100)throw new IllegalArgumentException("偏好奖励范围1–100");if(p.minUsd()!=null)nonnegative(p.minUsd());if(p.maxUsd()!=null&&(p.maxUsd().signum()<0||(p.minUsd()!=null&&p.maxUsd().compareTo(p.minUsd())<0)))throw new IllegalArgumentException("偏好金额范围无效");}
   for(Agreement a:c.agreements()){provider(a.provider());if(!Set.of("AMOUNT","COUNT").contains(a.metric()))throw new IllegalArgumentException("履约指标应为 AMOUNT 或 COUNT");nonnegative(a.monthlyTarget());}
   c.todayUsd().values().forEach(x->nonnegative(x));c.monthUsd().values().forEach(x->nonnegative(x));if(c.monthCount().values().stream().anyMatch(x->x==null||x<0))throw new IllegalArgumentException("累计笔数不可负");
  } catch(NullPointerException|DateTimeException e){throw new IllegalArgumentException("配置字段缺失或时区无效",e);}
 }
 private void provider(String code){if(code==null||!providers.existsById(code))throw new IllegalArgumentException("无效合作方："+code);}
 private static void nonnegative(BigDecimal... v){for(BigDecimal x:v)if(x==null||x.signum()<0)throw new IllegalArgumentException("配置金额必须非负");}
 private static BigDecimal rate(RoutingConfig c,String currency){BigDecimal v=c.hkdRates().get(currency);if(v==null||v.signum()<=0)throw new IllegalArgumentException("缺少汇率："+currency);return v;}
 private static BigDecimal n(int x){return BigDecimal.valueOf(x);} private static BigDecimal money(BigDecimal x){return x.setScale(2,RoundingMode.HALF_UP);}
 private static boolean scope(String expected,String actual){return expected==null||expected.isBlank()||"ALL".equals(expected)||expected.equals(actual);}
 private static void check(Candidate c,String code,List<String> reasons,Object detail){c.checks.add(new Check(code,reasons.isEmpty(),reasons,detail));}
 private static List<String> only(Candidate c){return c.checks.stream().filter(x->!x.passed()).map(Check::code).toList();}
 static boolean day(FxWindow w,LocalDate d,RoutingConfig cfg){return C10Calculator.day(w,d,cfg);}
 public Result simulate(Request req){
  ConfigView cv=config();String id=UUID.randomUUID().toString();List<String> errors=validateRequest(req,cv.config());
  Result result;
  if(!errors.isEmpty())result=new Result(id,req==null?null:req.paymentId(),"REFER","INVALID_REQUEST",null,List.of(),List.of(),null,cv.revision(),true,true,errors);
  else result=evaluate(req,cv,id);
  docs.save(new RoutingDocument("DECISION-"+id,cv.revision(),encode(Map.of("request",req==null?Map.of():req,"response",result))));
  return result;
 }
 private List<String> validateRequest(Request r,RoutingConfig c){
  if(r==null)return List.of("请求不能为空");List<String> e=new ArrayList<>();
  if(r.paymentId()==null||r.paymentId().isBlank()||r.clientId()==null||!clients.existsById(r.clientId()))e.add("交易编号及有效客户必填");
  if(r.amount()==null||r.amount().signum()<=0)e.add("交易金额必须大于0");
  if(!c.hkdRates().containsKey(r.debitCurrency())||!c.hkdRates().containsKey(r.paymentCurrency()))e.add("币种无效或未配置汇率");
  for(String s:Arrays.asList(r.beneficiaryBankCountry(),r.beneficiaryAddressCountry(),r.orderingCountry(),r.clientCountry()))if(s==null||!s.matches("[A-Z]{2}"))e.add("国家编码需为两位大写字母");
  if(r.beneficiaryBankBic()==null||!r.beneficiaryBankBic().matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?"))e.add("受益行 BIC 格式无效");
  if(r.chargeOption()==null||!Set.of("OUR","SHA","BEN").contains(r.chargeOption()))e.add("扣费方式无效");
  if(r.paymentType()==null||!Set.of("TRADE","TREASURY","INTRAGROUP","PAYROLL","FEE","REFUND","OTHER").contains(r.paymentType()))e.add("交易类型无效");
  if(r.clientSegment()==null||r.clientSegment().isBlank()||r.directorUboNationalities()==null||r.directorUboNationalities().stream().anyMatch(x->x==null||!x.matches("[A-Z]{2}")))e.add("客户层级及董事/UBO国籍列表必填");
  if(r.includeProviders()!=null&&r.includeProviders().stream().anyMatch(x->x==null||!providers.existsById(x)))e.add("包含的合作方无效");return e;
 }
 private Result evaluate(Request r,ConfigView cv,String id){
  RoutingConfig cfg=cv.config();ZonedDateTime now=r.at()==null?ZonedDateTime.now(HK):r.at().withZoneSameInstant(HK);
  BigDecimal amountHkd=r.amount().multiply(rate(cfg,r.paymentCurrency()),MC),usd=amountHkd.divide(rate(cfg,"USD"),MC);
  boolean fx=!r.debitCurrency().equals(r.paymentCurrency());List<Candidate> all=new ArrayList<>();
  var txn=new C8Calculator.Txn(r.paymentCurrency(),r.beneficiaryBankCountry(),r.beneficiaryRegion(),usd,r.paymentType(),r.clientSegment());
  for(Provider p:providers.findAll()){
   if(!"LIVE".equals(p.status)&&(r.includeProviders()==null||!r.includeProviders().contains(p.code)))continue;
   List<String> venues=new ArrayList<>();if(!fx)venues.add("NONE");else {if(cfg.fxWindows().stream().anyMatch(w->w.provider().equals("TREASURY")&&w.debit().equals(r.debitCurrency())&&w.payment().equals(r.paymentCurrency())))venues.add("TREASURY");if(cfg.fxWindows().stream().anyMatch(w->w.provider().equals(p.code)&&w.debit().equals(r.debitCurrency())&&w.payment().equals(r.paymentCurrency())))venues.add("PROVIDER");}
   for(String venue:venues){
    Candidate c=new Candidate();c.providerCode=p.code;c.fxVenue=venue;c.channel=p.channel;c.priority=p.priority;all.add(c);
    var a=c1.evaluate(new C1Service.Simulation(r.clientId(),p.code,r.paymentCurrency(),r.beneficiaryBankCountry(),usd),now.toLocalDateTime());check(c,"C1",a.passed()?List.of():List.of(a.reasonCode()),a);
    var b=c2.simulate(new C2Service.Simulation(p.code,r.paymentCurrency(),r.debitCurrency(),r.beneficiaryBankCountry(),r.beneficiaryAddressCountry(),r.beneficiaryRegion(),r.orderingCountry(),r.clientCountry(),r.intermediaryCountry(),r.purpose(),r.directorUboNationalities()),now.toLocalDateTime());check(c,"C2",b.hits().stream().map(C2Service.RuleHit::reasonCode).distinct().toList(),b);
    Reach reach=C3Matcher.select(cfg.reaches(),new C3Matcher.Input(p.code,r.paymentCurrency(),r.beneficiaryBankCountry(),r.beneficiaryRegion(),r.beneficiaryBankBic(),r.chargeOption())).map(C3Matcher.Row::reach).orElse(null);
    check(c,"C3",reach==null?List.of("NO_REACH"):List.of(),reach);
    String account="PROVIDER".equals(venue)?r.debitCurrency():r.paymentCurrency();BigDecimal needed=amountHkd.divide(rate(cfg,account),MC);
    Balance bal=cfg.balances().stream().filter(x->x.provider().equals(p.code)&&x.currency().equals(account)).findFirst().orElse(null);
    check(c,"C4",p.fundingCheckEnabled&&(bal==null||bal.balance().subtract(bal.reserved()).compareTo(needed)<0)?List.of("FUNDING_SHORTFALL"):List.of(),Map.of("enabled",p.fundingCheckEnabled,"currency",account,"required",needed,"available",bal==null?BigDecimal.ZERO:bal.balance().subtract(bal.reserved())));
    var c6=reach==null?null:C6Calculator.check(reach,usd,cfg.todayUsd().getOrDefault(p.code,BigDecimal.ZERO));
    check(c,"C6",reach==null?List.of():c6.reasons(),reach==null?"无覆盖行，限额不适用":c6);
    var c10=C10Calculator.check(venue,"TREASURY".equals(venue)?"TREASURY":p.code,r.debitCurrency(),r.paymentCurrency(),cfg.fxWindows(),usd,now,cfg);
    c.nextFxWindow=c10.nextWindow();
    check(c,"C10",fx&&!c10.passed()?List.of(c10.reason()):List.of(),c10.open()!=null?c10.open():"NONE".equals(venue)?"无需换汇":"无可执行窗口");
    c.passed=only(c).isEmpty();
    if(reach!=null){c.c5=C5Calculator.calculate(reach,now,r.requestedValueDate());c.valueDate=c.c5.candidateValueDate();c.scores.put("C5",n(c.c5.speedScore()));}
    if(c.passed){
     Fee fee=C7Calculator.feeFor(cfg.fees(),p.code,r.paymentCurrency(),r.chargeOption());
     BigDecimal spread=c10.open()==null?BigDecimal.ZERO:c10.open().spreadBps().add(c10.open().extraBps());
     var c7=C7Calculator.estimate(fee,amountHkd,rate(cfg,fee.feeCurrency()),spread);
     c.rawCost=c7.totalHkd();c.estimatedCostHkd=money(c.rawCost);
     c.scores.put("C8",n(C8Calculator.scoreFor(cfg.preferences(),p.code,txn)));
     int agreement=C9Calculator.score(cfg.agreements().stream().filter(x->x.provider().equals(p.code)).toList(),cfg.monthUsd().getOrDefault(p.code,BigDecimal.ZERO),cfg.monthCount().getOrDefault(p.code,0L),now);c.scores.put("C9",n(agreement));
    }
   }
  }
  List<Candidate> valid=new ArrayList<>(all.stream().filter(c->c.passed).toList());BigDecimal min=valid.stream().map(c->c.rawCost).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
  Weight weight=cfg.weights().stream().filter(w->(w.paymentType().equals(r.paymentType())&&scope(w.segment(),r.clientSegment()))||(w.paymentType().equals("OTHER")&&w.segment().equals("ALL"))).max(Comparator.comparingInt(w->(w.paymentType().equals(r.paymentType())?2:0)+(!w.segment().equals("ALL")?1:0))).orElseThrow();
  for(Candidate c:valid){BigDecimal cost=C7Calculator.score(min,c.rawCost);c.scores.put("C7",money(cost));c.rawScore=cost.multiply(n(weight.cost()),MC).add(c.scores.get("C5").multiply(n(weight.speed()))).add(c.scores.get("C8").multiply(n(weight.preference()))).add(c.scores.get("C9").multiply(n(weight.agreement()))).divide(n(100),MC);c.totalScore=money(c.rawScore);}
  valid.sort(Comparator.comparing((Candidate c)->c.rawScore).reversed().thenComparing(c->c.rawCost).thenComparing(c->c.valueDate).thenComparingInt(c->c.priority).thenComparing(c->c.providerCode).thenComparing(c->c.fxVenue));for(int i=0;i<valid.size();i++)valid.get(i).rank=i+1;
  String reason=null;if(valid.isEmpty()){
   if(!"COMPLETE".equals(clients.findById(r.clientId()).orElseThrow().classificationStatus))reason="CLIENT_NOT_CLASSIFIED";
   else if(fx&&all.isEmpty())reason="NO_FX_VENUE";
   else if(!all.isEmpty()&&all.stream().allMatch(c->c.checks.stream().anyMatch(x->Set.of("C1","C2").contains(x.code())&&!x.passed())))reason="NO_PROVIDER_ACCEPTS";
   else if(all.stream().anyMatch(c->only(c).equals(List.of("C10"))))reason="FX_WINDOW_CLOSED";
   else if(all.stream().anyMatch(c->only(c).equals(List.of("C4"))))reason="FUNDING_SHORTFALL";
   else reason="NO_ROUTE";
  }
  ZonedDateTime next="FX_WINDOW_CLOSED".equals(reason)?all.stream().filter(c->only(c).equals(List.of("C10"))).map(c->c.nextFxWindow).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null):null;
  return new Result(id,r.paymentId(),valid.isEmpty()?"REFER":"ROUTE",reason,valid.isEmpty()?null:valid.get(0),valid.stream().skip(1).limit(2).toList(),all,next,cv.revision(),true,true,List.of("模拟结果不占用资金或限额；客户分类与流转类别读取本地客户档案；国籍、余额、汇率及累计量为演示输入。"));
 }
 public Object decision(String id){return docs.findById("DECISION-"+id).map(d->{try{return json.readTree(d.payload);}catch(Exception e){throw new IllegalStateException(e);}}).orElseThrow(()->new IllegalArgumentException("决策不存在"));}
}
