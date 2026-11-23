package com.hsg.relay.routing;
import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.Reach;

@Service
public class C5Service {
 private final RoutingService routing; private final C3Service c3; private final ProviderRepository providers;
 public C5Service(RoutingService r,C3Service c,ProviderRepository p){routing=r;c3=c;providers=p;}
 public record Save(Long revision,Integer index,String zone,LocalTime cutoff,Integer offsetDays,List<LocalDate> holidays) {}
 public record Input(String providerCode,String paymentCurrency,String beneficiaryBankCountry,String beneficiaryRegion,
  String beneficiaryBankBic,String chargeOption,@com.fasterxml.jackson.annotation.JsonAlias("requested_value_date") LocalDate requestedValueDate,ZonedDateTime at) {}
 public record Simulation(String status,long revision,Integer selectedIndex,Reach selectedReach,C5Calculator.Result calculation,
  String note) {}
 public C3Service.Listing list(){return c3.list();}
 @Transactional public C3Service.Listing save(Save in){
  if(in==null||in.revision()==null||in.index()==null)throw new IllegalArgumentException("配置版本及覆盖行必填");
  var cv=routing.config();if(in.revision()!=cv.revision())throw new IllegalArgumentException("配置已更新，请刷新后重试");
  if(in.index()<0||in.index()>=cv.config().reaches().size())throw new IllegalArgumentException("覆盖行不存在");
  C5Calculator.validate(in.zone(),in.cutoff(),in.offsetDays(),in.holidays());
  var rows=new ArrayList<>(cv.config().reaches());Reach r=rows.get(in.index());
  rows.set(in.index(),new Reach(r.provider(),r.currency(),r.country(),r.region(),r.bics(),r.charges(),in.zone(),in.cutoff(),in.offsetDays(),r.perPaymentUsd(),r.dailyUsd(),List.copyOf(in.holidays()),r.enabled()));
  routing.saveConfig(new RoutingService.ConfigView(cv.revision(),cv.config().withReaches(rows)));return list();
 }
 public Simulation simulate(Input in){
  if(in==null)throw new IllegalArgumentException("模拟请求必填");
  var match=new C3Matcher.Input(in.providerCode(),in.paymentCurrency(),in.beneficiaryBankCountry(),in.beneficiaryRegion(),in.beneficiaryBankBic(),in.chargeOption());
  C3Matcher.validateInput(match);if(!providers.existsById(in.providerCode()))throw new IllegalArgumentException("合作方不存在");
  var cv=routing.config();var selected=C3Matcher.select(cv.config().reaches(),match).orElse(null);
  if(selected==null)return new Simulation("NO_REACH",cv.revision(),null,null,null,"C3 未匹配覆盖行，无法计算 C5；此处不是 C5 拒绝候选。请先维护业务覆盖。");
  return new Simulation("SCORED",cv.revision(),selected.index(),selected.reach(),C5Calculator.calculate(selected.reach(),in.at(),in.requestedValueDate()),"C5 仅提供速度分数，即使得0分也不会剔除候选；最终结果仍需检查其他过滤器并加权排序。");
 }
}
