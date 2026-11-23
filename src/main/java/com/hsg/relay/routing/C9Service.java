package com.hsg.relay.routing;
import com.hsg.relay.domain.Provider;
import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.IntStream;
import static com.hsg.relay.routing.RoutingConfig.Agreement;

@Service
public class C9Service {
 private final RoutingService routing; private final ProviderRepository providers;
 public C9Service(RoutingService r,ProviderRepository p){routing=r;providers=p;}
 public record AgreementView(int index,String provider,String metric,BigDecimal monthlyTarget) {}
 public record RowView(int index,String provider,String name,String status,List<AgreementView> agreements,
  BigDecimal monthUsd,long monthCount,C9Calculator.Result calculation) {}
 public record Listing(long revision,List<RowView> rows) {}
 public record Save(Long revision,String provider,String metric,BigDecimal monthlyTarget) {}
 public record Delete(Long revision,String provider,String metric) {}
 public record Mtd(Long revision,String provider,BigDecimal monthUsd,Long monthCount) {}
 public record Input(String providerCode,String metric,BigDecimal monthlyTarget,BigDecimal monthUsd,Long monthCount,ZonedDateTime at) {}
 public record Simulation(long revision,String providerCode,List<AgreementView> agreements,BigDecimal monthUsd,long monthCount,
  C9Calculator.Result calculation,String note) {}
 private void requireProvider(String code){if(code==null||code.isBlank()||!providers.existsById(code))throw new IllegalArgumentException("合作方不存在");}
 public Listing list(){
  var cv=routing.config();var cfg=cv.config();
  List<RowView> rows=providers.findAll().stream().sorted(Comparator.comparing(p->p.code)).map(p->new RowView(0,p.code,p.name,p.status,
   IntStream.range(0,cfg.agreements().size()).filter(i->cfg.agreements().get(i).provider().equals(p.code))
    .mapToObj(i->{Agreement a=cfg.agreements().get(i);return new AgreementView(i,a.provider(),a.metric(),a.monthlyTarget());}).toList(),
   cfg.monthUsd().getOrDefault(p.code,BigDecimal.ZERO),cfg.monthCount().getOrDefault(p.code,0L),
   C9Calculator.calculate(cfg.agreements().stream().filter(a->a.provider().equals(p.code)).toList(),
    cfg.monthUsd().getOrDefault(p.code,BigDecimal.ZERO),cfg.monthCount().getOrDefault(p.code,0L),null))).toList();
  return new Listing(cv.revision(),rows);
 }
 private RoutingService.ConfigView updated(Long revision,java.util.function.UnaryOperator<RoutingConfig> change){
  if(revision==null)throw new IllegalArgumentException("配置版本必填");
  var cv=routing.config();if(revision!=cv.revision())throw new IllegalArgumentException("配置已更新，请刷新后重试");
  return routing.saveConfig(new RoutingService.ConfigView(cv.revision(),change.apply(cv.config())));
 }
 @Transactional public Listing save(Save in){
  if(in==null)throw new IllegalArgumentException("保存请求必填");
  requireProvider(in.provider());C9Calculator.validate(in.metric(),in.monthlyTarget());
  updated(in.revision(),cfg->{
   List<Agreement> rows=new ArrayList<>(cfg.agreements());
   Agreement next=new Agreement(in.provider(),in.metric(),in.monthlyTarget());
   int at=IntStream.range(0,rows.size()).filter(i->rows.get(i).provider().equals(in.provider())&&rows.get(i).metric().equals(in.metric())).findFirst().orElse(rows.size());
   if(at<rows.size())rows.set(at,next);else rows.add(next);
   return new RoutingConfig(cfg.hkdRates(),cfg.reaches(),cfg.balances(),cfg.fees(),cfg.fxWindows(),cfg.preferences(),rows,cfg.weights(),cfg.todayUsd(),cfg.monthUsd(),cfg.monthCount(),cfg.hongKongHolidays());
  });
  return list();
 }
 @Transactional public Listing delete(Delete in){
  if(in==null)throw new IllegalArgumentException("删除请求必填");
  requireProvider(in.provider());if(in.metric()==null||!Set.of("AMOUNT","COUNT").contains(in.metric()))throw new IllegalArgumentException("履约指标应为 AMOUNT 或 COUNT");
  updated(in.revision(),cfg->{
   List<Agreement> rows=new ArrayList<>(cfg.agreements().stream().filter(a->!(a.provider().equals(in.provider())&&a.metric().equals(in.metric()))).toList());
   if(rows.size()==cfg.agreements().size())throw new IllegalArgumentException("协议不存在");
   return new RoutingConfig(cfg.hkdRates(),cfg.reaches(),cfg.balances(),cfg.fees(),cfg.fxWindows(),cfg.preferences(),rows,cfg.weights(),cfg.todayUsd(),cfg.monthUsd(),cfg.monthCount(),cfg.hongKongHolidays());
  });
  return list();
 }
 @Transactional public Listing mtd(Mtd in){
  if(in==null)throw new IllegalArgumentException("MTD 请求必填");
  requireProvider(in.provider());
  if(in.monthUsd()!=null&&in.monthUsd().signum()<0)throw new IllegalArgumentException("月累计金额必须非负");
  if(in.monthCount()!=null&&in.monthCount()<0)throw new IllegalArgumentException("月累计笔数必须非负");
  updated(in.revision(),cfg->{
   Map<String,BigDecimal> usd=new HashMap<>(cfg.monthUsd());if(in.monthUsd()!=null)usd.put(in.provider(),in.monthUsd());
   Map<String,Long> count=new HashMap<>(cfg.monthCount());if(in.monthCount()!=null)count.put(in.provider(),in.monthCount());
   return new RoutingConfig(cfg.hkdRates(),cfg.reaches(),cfg.balances(),cfg.fees(),cfg.fxWindows(),cfg.preferences(),cfg.agreements(),cfg.weights(),cfg.todayUsd(),usd,count,cfg.hongKongHolidays());
  });
  return list();
 }
 public Simulation simulate(Input in){
  if(in==null||in.providerCode()==null)throw new IllegalArgumentException("试算请求及合作方必填");
  requireProvider(in.providerCode());
  var cv=routing.config();var cfg=cv.config();
  List<Agreement> saved=cfg.agreements().stream().filter(a->a.provider().equals(in.providerCode())).toList();
  List<Agreement> used=in.metric()==null?saved:List.of(new Agreement(in.providerCode(),in.metric(),
   in.monthlyTarget()!=null?in.monthlyTarget():saved.stream().filter(a->a.metric().equals(in.metric())).map(Agreement::monthlyTarget).findFirst().orElseThrow(()->new IllegalArgumentException("该指标尚无已保存协议，请填写月度目标"))));
  for(Agreement a:used)C9Calculator.validate(a.metric(),a.monthlyTarget());
  BigDecimal monthUsd=in.monthUsd()!=null?in.monthUsd():cfg.monthUsd().getOrDefault(in.providerCode(),BigDecimal.ZERO);
  long monthCount=in.monthCount()!=null?in.monthCount():cfg.monthCount().getOrDefault(in.providerCode(),0L);
  var calc=C9Calculator.calculate(used,monthUsd,monthCount,in.at());
  return new Simulation(cv.revision(),in.providerCode(),
   IntStream.range(0,used.size()).mapToObj(i->new AgreementView(-1,used.get(i).provider(),used.get(i).metric(),used.get(i).monthlyTarget())).toList(),
   monthUsd,monthCount,calc,"C9 仅提供履约分数，0分也不会剔除候选；分数按协议权重参与综合排序。MTD 为试算时点累计快照，真实环境由交易统计回填。");
 }
}
