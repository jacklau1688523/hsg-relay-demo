package com.hsg.relay.routing;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.FxWindow;
import static org.assertj.core.api.Assertions.*;

class C10CalculatorTest {
 private static final ZonedDateTime MON_MORNING=ZonedDateTime.parse("2026-11-23T09:05:00+08:00"); // 周一
 private static final ZonedDateTime SATURDAY=ZonedDateTime.parse("2026-11-21T10:00:00+08:00");   // 周六
 private static final ZonedDateTime HOLIDAY_FRIDAY=ZonedDateTime.parse("2026-11-20T10:00:00+08:00"); // 2026-11-20 为周五

 private static RoutingConfig cfg(List<FxWindow> windows,List<LocalDate> holidays){
  return new RoutingConfig(Map.of("HKD",BigDecimal.ONE,"USD",new BigDecimal("7.8"),"AUD",new BigDecimal("5.1"),"KRW",new BigDecimal("0.0056")),
   List.of(),List.of(),List.of(),windows,List.of(),List.of(),List.of(),Map.of(),Map.of(),Map.of(),holidays);
 }
 private static FxWindow win(String owner,String debit,String payment,String days,String start,String end,String spread,String extra,String max){
  return new FxWindow(owner,debit,payment,List.of(days.split(",")),LocalTime.parse(start),LocalTime.parse(end),
   new BigDecimal(spread),new BigDecimal(extra),max==null?null:new BigDecimal(max));
 }
 private static final List<FxWindow> DEMO=List.of(
  win("TREASURY","HKD","USD","MON,TUE,WED,THU,FRI","09:00:00","17:30:00","10","0",null),
  win("PROV-W","HKD","AUD","MON,TUE,WED,THU,FRI","00:00:00","23:59:59.999999999".substring(0,8),"35","0","500000"),
  win("PROV-W","HKD","AUD","SAT,SUN,HOLIDAY","00:00:00","23:59:59.999999999".substring(0,8),"35","20","500000"),
  win("PROV-W","HKD","KRW","MON,TUE,WED,THU,FRI","08:00:00","16:30:00","50","0","200000"));

 @Test void windowsForFiltersOwnerAndPair(){
  assertThat(C10Calculator.windowsFor(DEMO,"TREASURY","HKD","USD")).hasSize(1);
  assertThat(C10Calculator.windowsFor(DEMO,"PROV-W","HKD","AUD")).hasSize(2);
  assertThat(C10Calculator.windowsFor(DEMO,"PROV-W","USD","EUR")).isEmpty();
  assertThat(C10Calculator.windowsFor(DEMO,"PROV-T1","HKD","USD")).isEmpty();
 }
 @Test void noneVenuePassesWithoutAnyWindow(){
  var c=C10Calculator.check("NONE",null,"USD","USD",List.of(),BigDecimal.TEN,MON_MORNING,cfg(List.of(),List.of()));
  assertThat(c.passed()).isTrue();assertThat(c.reason()).isNull();assertThat(c.open()).isNull();assertThat(c.windows()).isEmpty();
 }
 @Test void treasuryOpenDuringWindow(){
  var c=C10Calculator.check("TREASURY","TREASURY","HKD","USD",DEMO,BigDecimal.TEN,MON_MORNING,cfg(DEMO,List.of()));
  assertThat(c.passed()).isTrue();assertThat(c.reason()).isNull();
  assertThat(c.totalSpreadBps()).isEqualByComparingTo("10");
  assertThat(c.nextWindow().toString()).startsWith("2026-11-24T09:00"); // 下一窗口＝次日开窗时间
 }
 @Test void treasuryClosedOnWeekendGivesNextMonday(){
  var c=C10Calculator.check("TREASURY","TREASURY","HKD","USD",DEMO,BigDecimal.TEN,SATURDAY,cfg(DEMO,List.of()));
  assertThat(c.passed()).isFalse();assertThat(c.reason()).isEqualTo("FX_TREASURY_CLOSED");
  assertThat(c.nextWindow().toString()).startsWith("2026-11-23T09:00");
 }
 @Test void treasuryClosedOnHolidayEvenWhenHolidayDayConfigured(){
  var treasuryHoliday=win("TREASURY","HKD","USD","MON,TUE,WED,THU,FRI,HOLIDAY","09:00:00","17:30:00","10","0",null);
  var c=C10Calculator.check("TREASURY","TREASURY","HKD","USD",List.of(treasuryHoliday),BigDecimal.TEN,HOLIDAY_FRIDAY,cfg(List.of(treasuryHoliday),List.of(LocalDate.parse("2026-11-20"))));
  assertThat(c.passed()).isFalse();assertThat(c.reason()).isEqualTo("FX_TREASURY_CLOSED");
  assertThat(c.nextWindow().toString()).startsWith("2026-11-23T09:00"); // 跳过节假日与周末
 }
 @Test void providerHolidayWindowOpensOnlyWhenHolidayDayConfigured(){
  var c=C10Calculator.check("PROVIDER","PROV-W","HKD","AUD",DEMO,BigDecimal.TEN,HOLIDAY_FRIDAY,cfg(DEMO,List.of(LocalDate.parse("2026-11-20"))));
  assertThat(c.windows()).hasSize(2);
  assertThat(c.passed()).isTrue(); // SAT/SUN/HOLIDAY 行命中，点差 35+20
  assertThat(c.totalSpreadBps()).isEqualByComparingTo("55");
 }
 @Test void providerWeekendUsesExtraSpread(){
  var c=C10Calculator.check("PROVIDER","PROV-W","HKD","AUD",DEMO,BigDecimal.TEN,SATURDAY,cfg(DEMO,List.of()));
  assertThat(c.passed()).isTrue();assertThat(c.totalSpreadBps()).isEqualByComparingTo("55");
  assertThat(c.reason()).isNull();
 }
 @Test void providerInTimeButOverTicketLimit(){
  BigDecimal usd=new BigDecimal("215384.62"); // > 200000
  var c=C10Calculator.check("PROVIDER","PROV-W","HKD","KRW",DEMO,usd,MON_MORNING,cfg(DEMO,List.of()));
  assertThat(c.inTime()).isTrue();assertThat(c.ticketOk()).isFalse();
  assertThat(c.passed()).isFalse();assertThat(c.reason()).isEqualTo("FX_TICKET_LIMIT");
  assertThat(c.nextWindow()).isNull(); // 金额超全部窗口限额，无下一窗口可等
 }
 @Test void providerClosedOutOfHoursGivesNextOpening(){
  var after=ZonedDateTime.parse("2026-11-23T17:00:00+08:00"); // 周一 16:30 后
  var c=C10Calculator.check("PROVIDER","PROV-W","HKD","KRW",DEMO,BigDecimal.TEN,after,cfg(DEMO,List.of()));
  assertThat(c.passed()).isFalse();assertThat(c.reason()).isEqualTo("FX_PROVIDER_CLOSED");
  assertThat(c.nextWindow().toString()).startsWith("2026-11-24T08:00"); // 次日开窗
 }
 @Test void overlappingOpenWindowsPickLowestTotalSpread(){
  var windows=List.of(
   win("PROV-W","HKD","AUD","MON,TUE,WED,THU,FRI","00:00:00","23:59:59","35","20",null),
   win("PROV-W","HKD","AUD","MON","09:00:00","12:00:00","28","0",null));
  var c=C10Calculator.check("PROVIDER","PROV-W","HKD","AUD",windows,BigDecimal.TEN,MON_MORNING,cfg(windows,List.of()));
  assertThat(c.passed()).isTrue();assertThat(c.totalSpreadBps()).isEqualByComparingTo("28");
 }
 @Test void noWindowChannelIsNotGeneratedCandidate(){
  var c=C10Calculator.check("PROVIDER","PROV-T1","HKD","USD",DEMO,BigDecimal.TEN,MON_MORNING,cfg(DEMO,List.of()));
  assertThat(c.windows()).isEmpty();assertThat(c.passed()).isFalse();assertThat(c.reason()).isNull(); // 无窗口＝不生成候选，非过滤失败
 }
}
