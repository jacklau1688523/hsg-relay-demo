package com.hsg.relay.routing;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.FxWindow;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Transactional
class C10ServiceTest {
 @Autowired C10Service c10; @Autowired RoutingService routing; @Autowired ObjectMapper json;
 @Autowired com.hsg.relay.repo.AppetiteRepository appetites;
 private static final ZonedDateTime FRI=ZonedDateTime.parse("2026-11-20T10:00:00+08:00");
 private static final ZonedDateTime SAT=ZonedDateTime.parse("2026-11-21T10:00:00+08:00");
 private static final ZonedDateTime MON=ZonedDateTime.parse("2026-11-23T09:05:00+08:00");
 private static final ZonedDateTime MON_LATE=ZonedDateTime.parse("2026-11-23T10:00:00+08:00");

 @Test void listingCoversDemoWindows(){
  var listing=c10.windows();
  assertThat(listing.rows()).hasSize(16); // 资金部 6 对 ＋ Wise AUD/SGD/KRW 共 10 行（周末行含加点）
  assertThat(listing.rows()).filteredOn(r->r.window().provider().equals("TREASURY")).hasSize(6);
  assertThat(listing.currencies()).contains("HKD","USD","CNH","AUD","SGD","KRW");
  assertThat(listing.hongKongHolidays()).isNotNull();
  var krw=listing.rows().stream().filter(r->r.window().payment().equals("KRW")).findFirst().orElseThrow();
  assertThat(krw.window().maxUsd()).isEqualByComparingTo("200000");
  assertThat(krw.window().spreadBps()).isEqualByComparingTo("50");
 }
 @Test void saveAddEditDeleteSharesConfigWithRouting(){
  int before=routing.config().config().fxWindows().size();
  FxWindow w=new FxWindow("PROV-GD","HKD","USD",List.of("MON"),LocalTime.of(9,0),LocalTime.of(17,0),new BigDecimal("15"),BigDecimal.ZERO,null);
  c10.save(new C10Service.Save(c10.windows().revision(),null,w));
  assertThat(routing.config().config().fxWindows()).hasSize(before+1);
  int added=c10.windows().rows().stream().filter(r->r.window().equals(w)).findFirst().orElseThrow().index();
  c10.save(new C10Service.Save(c10.windows().revision(),added,new FxWindow("PROV-GD","HKD","USD",List.of("MON"),LocalTime.of(9,0),LocalTime.of(17,0),new BigDecimal("12"),BigDecimal.ZERO,null)));
  assertThat(c10.windows().rows().get(added).window().spreadBps()).isEqualByComparingTo("12");
  c10.delete(new C10Service.Delete(c10.windows().revision(),added));
  assertThat(routing.config().config().fxWindows()).hasSize(before);
  assertThatThrownBy(()->c10.delete(new C10Service.Delete(c10.windows().revision(),added))).hasMessageContaining("不存在");
 }
 @Test void saveValidatesWindow(){
  assertThatThrownBy(()->c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("PROV-XX","HKD","USD",List.of("MON"),LocalTime.of(9,0),LocalTime.of(17,0),BigDecimal.TEN,BigDecimal.ZERO,null)))).hasMessageContaining("不存在");
  assertThatThrownBy(()->c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("PROV-GD","USD","USD",List.of("MON"),LocalTime.of(9,0),LocalTime.of(17,0),BigDecimal.TEN,BigDecimal.ZERO,null)))).hasMessageContaining("无需换汇");
  assertThatThrownBy(()->c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("PROV-GD","HKD","USD",List.of("MONDAY"),LocalTime.of(9,0),LocalTime.of(17,0),BigDecimal.TEN,BigDecimal.ZERO,null)))).hasMessageContaining("窗口日期");
  assertThatThrownBy(()->c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("PROV-GD","HKD","USD",List.of("MON"),LocalTime.of(18,0),LocalTime.of(9,0),BigDecimal.TEN,BigDecimal.ZERO,null)))).hasMessageContaining("同日");
  assertThatThrownBy(()->c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("PROV-GD","HKD","USD",List.of("MON"),LocalTime.of(9,0),LocalTime.of(17,0),new BigDecimal("-1"),BigDecimal.ZERO,null)))).hasMessageContaining("非负");
  // 其他合作方在重叠时段配窗口不受资金部行影响
  c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("PROV-GD","HKD","USD",List.of("MON","TUE"),LocalTime.of(10,0),LocalTime.of(12,0),BigDecimal.TEN,BigDecimal.ZERO,null)));
  assertThat(routing.config().config().fxWindows()).hasSize(17);
 }
 @Test void overlappingWindowRejectedButSplitHoursAllowed(){
  // 同渠道同货币对、日期相交且时段重叠
  assertThatThrownBy(()->c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("TREASURY","HKD","USD",List.of("WED"),LocalTime.of(10,0),LocalTime.of(12,0),BigDecimal.TEN,BigDecimal.ZERO,null)))).hasMessageContaining("重叠");
  // 日期相交但时段错开 → 允许
  c10.save(new C10Service.Save(c10.windows().revision(),null,
   new FxWindow("TREASURY","HKD","USD",List.of("WED"),LocalTime.of(18,0),LocalTime.of(23,0),BigDecimal.TEN,BigDecimal.ZERO,null)));
  assertThat(routing.config().config().fxWindows()).hasSize(17);
 }
 @Test void saveRejectsStaleRevision(){
  long revision=c10.windows().revision();
  c10.save(new C10Service.Save(revision,null,new FxWindow("PROV-GD","HKD","USD",List.of("MON"),LocalTime.of(9,0),LocalTime.of(17,0),BigDecimal.TEN,BigDecimal.ZERO,null)));
  assertThatThrownBy(()->c10.save(new C10Service.Save(revision,null,new FxWindow("PROV-GD","HKD","EUR",List.of("MON"),LocalTime.of(9,0),LocalTime.of(17,0),BigDecimal.TEN,BigDecimal.ZERO,null)))).hasMessageContaining("已更新");
 }
 @Test void simulateTreasuryOpenAndClosed(){
  var open=c10.simulate(new C10Service.Input("HKD","USD",new BigDecimal("20000"),MON));
  assertThat(open.status()).isEqualTo("PASS");assertThat(open.fx()).isTrue();
  assertThat(open.channels()).hasSize(1); // 仅 TREASURY：PROV-T1/W 无 HKD/USD 窗口
  var treasury=open.channels().get(0);
  assertThat(treasury.venue()).isEqualTo("TREASURY");assertThat(treasury.passed()).isTrue();
  assertThat(treasury.totalSpreadBps()).isEqualByComparingTo("10");
  var closed=c10.simulate(new C10Service.Input("HKD","USD",new BigDecimal("20000"),SAT));
  assertThat(closed.status()).isEqualTo("FX_WINDOW_CLOSED");
  assertThat(closed.channels().get(0).reason()).isEqualTo("FX_TREASURY_CLOSED");
  assertThat(closed.nextFxWindow().toString()).startsWith("2026-11-23T09:00");
 }
 @Test void simulateWiseWeekendAndTicketLimit(){
  var weekend=c10.simulate(new C10Service.Input("HKD","AUD",new BigDecimal("20000"),SAT));
  assertThat(weekend.status()).isEqualTo("PASS");
  var provider=weekend.channels().stream().filter(c->c.venue().equals("PROVIDER")).findFirst().orElseThrow();
  assertThat(provider.owner()).isEqualTo("PROV-W");assertThat(provider.totalSpreadBps()).isEqualByComparingTo("55");
  var ticket=c10.simulate(new C10Service.Input("HKD","KRW",new BigDecimal("300000000"),MON_LATE));
  assertThat(ticket.amountUsd()).isEqualByComparingTo("215384.62");
  assertThat(ticket.status()).isEqualTo("FX_WINDOW_CLOSED");
  var krw=ticket.channels().get(0);
  assertThat(krw.reason()).isEqualTo("FX_TICKET_LIMIT");
  assertThat(krw.inTime()).isTrue();assertThat(krw.ticketOk()).isFalse();
  assertThat(ticket.nextFxWindow()).isNull(); // 超全部窗口限额，无下一窗口
 }
 @Test void simulateNoFxVenueAndNone(){
  var none=c10.simulate(new C10Service.Input("HKD","EUR",new BigDecimal("10000"),FRI));
  assertThat(none.status()).isEqualTo("NO_FX_VENUE");assertThat(none.channels()).isEmpty();
  assertThat(none.note()).contains("NO_FX_VENUE");
  var same=c10.simulate(new C10Service.Input("USD","USD",new BigDecimal("10000"),FRI));
  assertThat(same.fx()).isFalse();assertThat(same.status()).isEqualTo("PASS");
  assertThat(same.channels()).hasSize(1);assertThat(same.channels().get(0).venue()).isEqualTo("NONE");
  assertThat(same.channels().get(0).passed()).isTrue();
 }
 @Test void simulateValidatesInput(){
  assertThatThrownBy(()->c10.simulate(new C10Service.Input("HKD","USD",BigDecimal.ZERO,FRI))).hasMessageContaining("金额");
  assertThatThrownBy(()->c10.simulate(new C10Service.Input("HKD","XXX",BigDecimal.TEN,FRI))).hasMessageContaining("汇率");
 }
 @Test void routingUsesSameC10Decision() throws Exception {
  ObjectNode req=(ObjectNode)json.readTree("""
   {"paymentId":"C10-TEST","clientId":"C10002","debitCurrency":"HKD","paymentCurrency":"USD","amount":20000,
   "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
   "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
   "requestedValueDate":"2026-11-23","at":"2026-11-21T10:00:00+08:00"}
   """);
  var result=routing.simulate(json.convertValue(req,RoutingService.Request.class));
  assertThat(result.result()).isEqualTo("REFER");assertThat(result.reasonCode()).isEqualTo("FX_WINDOW_CLOSED");
  assertThat(result.nextFxWindow().toString()).startsWith("2026-11-23T09:00");
  var t1=result.candidates().stream().filter(c->c.providerCode.equals("PROV-T1")).findFirst().orElseThrow();
  var check=t1.checks.stream().filter(x->x.code().equals("C10")).findFirst().orElseThrow();
  var standalone=c10.simulate(new C10Service.Input("HKD","USD",new BigDecimal("20000"),SAT));
  assertThat(check.reasons()).containsExactly(standalone.channels().get(0).reason());
  assertThat(result.nextFxWindow()).isEqualTo(standalone.nextFxWindow());
 }
 @Test void routingTicketLimitMatchesStandalone() throws Exception {
  var a=appetites.findAll().stream().filter(x->x.providerCode.equals("PROV-W")).findFirst().orElseThrow();
  var copy=json.convertValue(json.valueToTree(a),com.hsg.relay.domain.Appetite.class);copy.id=null;copy.currency="AUD";appetites.save(copy);
  ObjectNode req=(ObjectNode)json.readTree("""
   {"paymentId":"C10-TICKET","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"AUD","amount":1000000,
   "beneficiaryBankCountry":"AU","beneficiaryAddressCountry":"AU","beneficiaryBankBic":"CHASAU2S","chargeOption":"SHA",
   "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
   "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
   """);
  var result=routing.simulate(json.convertValue(req,RoutingService.Request.class));
  var wise=result.candidates().stream().filter(c->c.providerCode.equals("PROV-W")&&"PROVIDER".equals(c.fxVenue)).findFirst().orElseThrow();
  var check=wise.checks.stream().filter(x->x.code().equals("C10")).findFirst().orElseThrow();
  assertThat(check.reasons()).containsExactly("FX_TICKET_LIMIT");
  var standalone=c10.simulate(new C10Service.Input("USD","AUD",new BigDecimal("1000000"),FRI));
  var provider=standalone.channels().stream().filter(c->c.venue().equals("PROVIDER")).findFirst().orElseThrow();
  assertThat(provider.reason()).isEqualTo("FX_TICKET_LIMIT");
  assertThat(standalone.amountUsd()).isEqualByComparingTo("653846.15");
 }
}
