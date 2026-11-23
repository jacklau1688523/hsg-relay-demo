package com.hsg.relay.routing;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static com.hsg.relay.routing.RoutingConfig.Fee;
import static org.assertj.core.api.Assertions.*;

class C7CalculatorTest {
 private static final Fee FEE=new Fee("PROV-W","USD","ALL","HKD",new BigDecimal("50"),BigDecimal.ZERO,BigDecimal.ZERO,null,new BigDecimal("30"),new BigDecimal("20"));
 @Test void feeForPrefersExactChargeOverAll(){
  Fee sha=new Fee("PROV-W","USD","SHA","HKD",new BigDecimal("70"),BigDecimal.ZERO,BigDecimal.ZERO,null,new BigDecimal("30"),new BigDecimal("20"));
  Fee fee=C7Calculator.feeFor(List.of(FEE,sha),"PROV-W","USD","SHA");
  assertThat(fee.charge()).isEqualTo("SHA");
  assertThat(C7Calculator.feeFor(List.of(FEE,sha),"PROV-W","USD","OUR").charge()).isEqualTo("ALL");
 }
 @Test void feeForMissingThrows(){
  assertThatThrownBy(()->C7Calculator.feeFor(List.of(FEE),"PROV-T1","USD","SHA")).hasMessageContaining("缺少手续费配置");
 }
 @Test void estimateWithoutFxSumsFeeItems(){
  var r=C7Calculator.estimate(FEE,new BigDecimal("78000"),BigDecimal.ONE,BigDecimal.ZERO);
  assertThat(r.commission()).isEqualByComparingTo("50");assertThat(r.clamp()).isEqualTo("NONE");
  assertThat(r.feeSubtotalHkd()).isEqualByComparingTo("100");assertThat(r.fxCostHkd()).isEqualByComparingTo("0");
  assertThat(r.totalHkd()).isEqualByComparingTo("100");
 }
 @Test void estimateClampsToMinimum(){
  Fee fee=new Fee("P","USD","ALL","HKD",new BigDecimal("10"),BigDecimal.ZERO,new BigDecimal("50"),null,BigDecimal.ZERO,BigDecimal.ZERO);
  var r=C7Calculator.estimate(fee,new BigDecimal("78000"),BigDecimal.ONE,BigDecimal.ZERO);
  assertThat(r.rawCommission()).isEqualByComparingTo("10");assertThat(r.commission()).isEqualByComparingTo("50");assertThat(r.clamp()).isEqualTo("MIN");
 }
 @Test void estimateClampsToMaximum(){
  Fee fee=new Fee("P","USD","ALL","HKD",BigDecimal.ZERO,new BigDecimal("0.01"),BigDecimal.ZERO,new BigDecimal("5000"),BigDecimal.ZERO,BigDecimal.ZERO);
  var r=C7Calculator.estimate(fee,new BigDecimal("780000"),BigDecimal.ONE,BigDecimal.ZERO); // 780000 HKD → 金额×0.01＝7800＞5000
  assertThat(r.commission()).isEqualByComparingTo("5000");assertThat(r.clamp()).isEqualTo("MAX");
 }
 @Test void estimateAppliesFxSpreadOnHkdAmount(){
  var r=C7Calculator.estimate(FEE,new BigDecimal("156000"),BigDecimal.ONE,new BigDecimal("10"));
  assertThat(r.fxCostHkd()).isEqualByComparingTo("156");assertThat(r.totalHkd()).isEqualByComparingTo("256");
 }
 @Test void scoreIsMinOverCostAndZeroCostScores100(){
  assertThat(C7Calculator.score(new BigDecimal("120"),new BigDecimal("120"))).isEqualByComparingTo("100");
  assertThat(C7Calculator.score(new BigDecimal("120"),new BigDecimal("150"))).isEqualByComparingTo("80");
  assertThat(C7Calculator.score(new BigDecimal("120"),new BigDecimal("240"))).isEqualByComparingTo("50");
  assertThat(C7Calculator.score(BigDecimal.ZERO,BigDecimal.ZERO)).isEqualByComparingTo("100");
 }
}
