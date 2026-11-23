package com.hsg.relay.routing;

import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import static com.hsg.relay.routing.RoutingConfig.FxWindow;

/** C10 外汇能力校验：窗口配置维护 + 渠道级判定（BRD 6.10、8.1、9.3）。C10 为过滤器，仅换汇交易适用。 */
@Service
public class C10Service {
 private static final MathContext MC=MathContext.DECIMAL128;
 private static final ZoneId HK=ZoneId.of("Asia/Hong_Kong");
 private static final Set<String> VALID_DAYS=Set.of("MON","TUE","WED","THU","FRI","SAT","SUN","HOLIDAY");
 private final RoutingService routing; private final ProviderRepository providers;
 public C10Service(RoutingService r,ProviderRepository p){routing=r;providers=p;}
 public record WindowRow(int index,FxWindow window) {}
 public record Listing(long revision,List<WindowRow> rows,Set<String> currencies,List<LocalDate> hongKongHolidays) {}
 public record Save(Long revision,Integer index,FxWindow window) {}
 public record Delete(Long revision,Integer index) {}
 public record Input(String debitCurrency,String paymentCurrency,BigDecimal amount,ZonedDateTime at) {}
 public record Simulation(String status,long revision,boolean fx,BigDecimal amountUsd,List<C10Calculator.Channel> channels,ZonedDateTime nextFxWindow,String note) {}
 public Listing windows(){
  var cv=routing.config();
  return new Listing(cv.revision(),IntStream.range(0,cv.config().fxWindows().size()).mapToObj(i->new WindowRow(i,cv.config().fxWindows().get(i))).toList(),
   cv.config().hkdRates().keySet(),cv.config().hongKongHolidays());
 }
 @Transactional public Listing save(Save in){
  if(in==null||in.revision()==null||in.window()==null)throw new IllegalArgumentException("配置版本及窗口行必填");
  validateWindow(in.window(),in.index());
  var cv=routing.config();if(in.revision()!=cv.revision())throw new IllegalArgumentException("配置已更新，请刷新后重试");
  var rows=new ArrayList<>(cv.config().fxWindows());
  if(in.index()==null)rows.add(in.window());
  else{
   if(in.index()<0||in.index()>=rows.size())throw new IllegalArgumentException("窗口行不存在");
   rows.set(in.index(),in.window());
  }
  routing.saveConfig(new RoutingService.ConfigView(cv.revision(),cv.config().withFxWindows(rows)));return windows();
 }
 @Transactional public Listing delete(Delete in){
  if(in==null||in.revision()==null||in.index()==null)throw new IllegalArgumentException("配置版本及窗口行必填");
  var cv=routing.config();if(in.revision()!=cv.revision())throw new IllegalArgumentException("配置已更新，请刷新后重试");
  if(in.index()<0||in.index()>=cv.config().fxWindows().size())throw new IllegalArgumentException("窗口行不存在");
  var rows=new ArrayList<>(cv.config().fxWindows());rows.remove(in.index().intValue());
  routing.saveConfig(new RoutingService.ConfigView(cv.revision(),cv.config().withFxWindows(rows)));return windows();
 }
 private void validateWindow(FxWindow w,Integer skipIndex){
  if(w.provider()==null||w.provider().isBlank())throw new IllegalArgumentException("外汇渠道必填（TREASURY 或合作方编码）");
  if(!"TREASURY".equals(w.provider())&&!providers.existsById(w.provider()))throw new IllegalArgumentException("合作方不存在");
  var cv=routing.config();rate(cv.config(),w.debit());rate(cv.config(),w.payment());
  if(w.debit().equals(w.payment()))throw new IllegalArgumentException("扣账与交易币种相同无需换汇，不必配置窗口");
  if(w.days()==null||w.days().isEmpty()||w.days().stream().anyMatch(d->!VALID_DAYS.contains(d)))throw new IllegalArgumentException("窗口日期须为 MON–SUN / HOLIDAY 的非空组合");
  if(w.start()==null||w.end()==null||!w.end().isAfter(w.start()))throw new IllegalArgumentException("窗口须同日且结束时间晚于开始时间");
  nonnegative(w.spreadBps(),w.extraBps());
  if(w.maxUsd()!=null&&w.maxUsd().signum()<0)throw new IllegalArgumentException("单笔限额必须非负");
  for(int i=0;i<cv.config().fxWindows().size();i++){
   if(skipIndex!=null&&i==skipIndex.intValue())continue;
   FxWindow x=cv.config().fxWindows().get(i);
   if(x.provider().equals(w.provider())&&x.debit().equals(w.debit())&&x.payment().equals(w.payment())
    &&x.days().stream().anyMatch(w.days()::contains)&&w.start().isBefore(x.end())&&x.start().isBefore(w.end()))
    throw new IllegalArgumentException("同一渠道币种对存在日期与时段重叠的窗口");
  }
 }
 public Simulation simulate(Input in){
  if(in==null)throw new IllegalArgumentException("模拟请求必填");
  if(in.amount()==null||in.amount().signum()<=0)throw new IllegalArgumentException("交易金额必须大于0");
  var cv=routing.config();var cfg=cv.config();
  rate(cfg,in.debitCurrency());rate(cfg,in.paymentCurrency());
  ZonedDateTime now=in.at()==null?ZonedDateTime.now(HK):in.at().withZoneSameInstant(HK);
  BigDecimal amountHkd=in.amount().multiply(rate(cfg,in.paymentCurrency()),MC);
  BigDecimal usd=amountHkd.divide(rate(cfg,"USD"),MC);
  boolean fx=!in.debitCurrency().equals(in.paymentCurrency());
  List<C10Calculator.Channel> channels=new ArrayList<>();
  if(!fx)channels.add(C10Calculator.check("NONE",null,in.debitCurrency(),in.paymentCurrency(),cfg.fxWindows(),usd,now,cfg));
  else{
   if(!C10Calculator.windowsFor(cfg.fxWindows(),"TREASURY",in.debitCurrency(),in.paymentCurrency()).isEmpty())
    channels.add(C10Calculator.check("TREASURY","TREASURY",in.debitCurrency(),in.paymentCurrency(),cfg.fxWindows(),usd,now,cfg));
   for(String owner:cfg.fxWindows().stream().filter(w->!"TREASURY".equals(w.provider())&&w.debit().equals(in.debitCurrency())&&w.payment().equals(in.paymentCurrency())).map(FxWindow::provider).distinct().sorted().toList())
    channels.add(C10Calculator.check("PROVIDER",owner,in.debitCurrency(),in.paymentCurrency(),cfg.fxWindows(),usd,now,cfg));
  }
  String status;
  if(!fx)status="PASS";
  else if(channels.isEmpty())status="NO_FX_VENUE";
  else if(channels.stream().anyMatch(C10Calculator.Channel::passed))status="PASS";
  else status="FX_WINDOW_CLOSED";
  ZonedDateTime next=channels.stream().filter(c->!c.passed()).map(C10Calculator.Channel::nextWindow).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
  String note;
  if(!fx)note="扣账币种＝交易币种，无需换汇，C10 直接通过（不校验窗口）。";
  else if(channels.isEmpty())note="没有任何外汇渠道配置该货币对窗口：全流程路由不生成换汇候选，返回 REFER NO_FX_VENUE（8.1），需新增外汇渠道配置。";
  else note="C10 为过滤器：TREASURY 渠道当前时间落在资金部窗口内才通过（节假日关闭），否则 FX_TREASURY_CLOSED；PROVIDER 渠道需在合作方窗口内且本笔美元等价 ≤ 窗口单笔限额，否则 FX_PROVIDER_CLOSED / FX_TICKET_LIMIT。与全流程一致：资金部渠道所有合作方共用，每个配置了该货币对窗口的合作方各生成一个 PROVIDER 候选；全部渠道失败且仅 C10 失败时整体返回 FX_WINDOW_CLOSED 并给出下一窗口。";
  return new Simulation(status,cv.revision(),fx,usd.setScale(2,RoundingMode.HALF_UP),List.copyOf(channels),next,note);
 }
 private static BigDecimal rate(RoutingConfig cfg,String currency){
  if(currency==null||!currency.matches("[A-Z]{3}"))throw new IllegalArgumentException("币种须为三位大写编码");
  BigDecimal v=cfg.hkdRates().get(currency);if(v==null||v.signum()<=0)throw new IllegalArgumentException("缺少汇率："+currency);return v;
 }
 private static void nonnegative(BigDecimal... v){for(BigDecimal x:v)if(x==null||x.signum()<0)throw new IllegalArgumentException("点差必须非负");}
}
