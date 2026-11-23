package com.hsg.relay.routing;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.FxWindow;

/** C10 filter shared by the isolated page and the C1–C10 engine: FX window + ticket limit (BRD 6.10). */
public final class C10Calculator {
 private static final ZoneId HK=ZoneId.of("Asia/Hong_Kong");
 private C10Calculator() {}
 /**
  * venue: NONE 无需换汇直接通过 / TREASURY 资金部窗口 / PROVIDER 合作方窗口。
  * reasons: FX_TREASURY_CLOSED 资金部窗口关闭、FX_PROVIDER_CLOSED 合作方窗口关闭、FX_TICKET_LIMIT 超窗口单笔限额。
  * windows 为空表示未配置该渠道货币对窗口：不生成该渠道候选（passed=false 但 reason 为空），全部渠道皆无窗口时整体返回 NO_FX_VENUE（8.1）。
  */
 public record Channel(String venue,String owner,String debit,String payment,List<FxWindow> windows,FxWindow open,
  boolean inTime,boolean ticketOk,boolean passed,String reason,ZonedDateTime nextWindow) {
  public BigDecimal totalSpreadBps(){return open==null?null:open.spreadBps().add(open.extraBps());}
 }
 /** 资金部窗口在香港节假日一律关闭（即使含 HOLIDAY 日）；合作方窗口按 days 是否含 HOLIDAY 决定节假日开闭。 */
 static boolean day(FxWindow w,LocalDate d,RoutingConfig cfg){
  boolean holiday=cfg.hongKongHolidays().contains(d);
  if("TREASURY".equals(w.provider())&&holiday)return false;
  return w.days().contains(holiday?"HOLIDAY":d.getDayOfWeek().name().substring(0,3));
 }
 static ZonedDateTime next(FxWindow w,ZonedDateTime now,RoutingConfig cfg){
  for(int i=0;i<=366;i++){LocalDate d=now.toLocalDate().plusDays(i);ZonedDateTime start=d.atTime(w.start()).atZone(HK);if(day(w,d,cfg)&&start.isAfter(now))return start;}
  return null;
 }
 public static List<FxWindow> windowsFor(List<FxWindow> all,String owner,String debit,String payment){
  return all.stream().filter(w->w.provider().equals(owner)&&w.debit().equals(debit)&&w.payment().equals(payment)).toList();
 }
 public static Channel check(String venue,String owner,String debit,String payment,List<FxWindow> all,BigDecimal amountUsd,ZonedDateTime now,RoutingConfig cfg){
  if("NONE".equals(venue))return new Channel(venue,owner,debit,payment,List.of(),null,true,true,true,null,null);
  List<FxWindow> windows=windowsFor(all,owner,debit,payment);
  if(windows.isEmpty())return new Channel(venue,owner,debit,payment,windows,null,false,true,false,null,null);
  boolean inTime=windows.stream().anyMatch(w->day(w,now.toLocalDate(),cfg)&&!now.toLocalTime().isBefore(w.start())&&now.toLocalTime().isBefore(w.end()));
  boolean ticketOk=!"PROVIDER".equals(venue)||windows.stream().filter(w->day(w,now.toLocalDate(),cfg)&&!now.toLocalTime().isBefore(w.start())&&now.toLocalTime().isBefore(w.end())).anyMatch(w->w.maxUsd()==null||amountUsd.compareTo(w.maxUsd())<=0);
  FxWindow open=windows.stream().filter(w->day(w,now.toLocalDate(),cfg)&&!now.toLocalTime().isBefore(w.start())&&now.toLocalTime().isBefore(w.end()))
   .filter(w->!"PROVIDER".equals(venue)||w.maxUsd()==null||amountUsd.compareTo(w.maxUsd())<=0)
   .min(Comparator.comparing(w->w.spreadBps().add(w.extraBps()))).orElse(null);
  String reason=null;
  if(open==null)reason=inTime?"FX_TICKET_LIMIT":"TREASURY".equals(venue)?"FX_TREASURY_CLOSED":"FX_PROVIDER_CLOSED";
  ZonedDateTime next=windows.stream().filter(w->!"PROVIDER".equals(venue)||w.maxUsd()==null||amountUsd.compareTo(w.maxUsd())<=0)
   .map(w->next(w,now,cfg)).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
  return new Channel(venue,owner,debit,payment,windows,open,inTime,ticketOk,open!=null,reason,next);
 }
}
