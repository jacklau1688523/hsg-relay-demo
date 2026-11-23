package com.hsg.relay.routing;

import java.math.BigDecimal;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.Reach;

/** C6 filter shared by the isolated page and the C1–C10 engine: per-payment and daily USD-equivalent limits. */
public final class C6Calculator {
 private C6Calculator() {}
 /** reasons: LIMIT_PER_PAYMENT 单笔超限、LIMIT_DAILY 当日累计超限；今日累计不含本笔自身（BRD 6.6、术语表）。 */
 public record Result(BigDecimal amountUsd,BigDecimal perPaymentUsd,BigDecimal dailyUsd,BigDecimal todayUsd,
  BigDecimal todayAfterUsd,boolean perPaymentPassed,boolean dailyPassed,List<String> reasons) {
  public boolean passed(){return reasons.isEmpty();}
 }
 public static void validate(BigDecimal perPaymentUsd,BigDecimal dailyUsd){
  if(perPaymentUsd==null||dailyUsd==null||perPaymentUsd.signum()<0||dailyUsd.signum()<0)throw new IllegalArgumentException("单笔及每日限额必须为非负数字（美元等价）");
 }
 public static Result check(Reach reach,BigDecimal amountUsd,BigDecimal todayUsd){
  Objects.requireNonNull(reach,"覆盖行必填");Objects.requireNonNull(amountUsd,"本笔美元等价金额必填");
  List<String> reasons=new ArrayList<>(2);
  boolean per=amountUsd.compareTo(reach.perPaymentUsd())<=0;
  BigDecimal base=todayUsd==null?BigDecimal.ZERO:todayUsd;
  BigDecimal after=base.add(amountUsd);
  boolean daily=after.compareTo(reach.dailyUsd())<=0;
  if(!per)reasons.add("LIMIT_PER_PAYMENT");
  if(!daily)reasons.add("LIMIT_DAILY");
  return new Result(amountUsd,reach.perPaymentUsd(),reach.dailyUsd(),base,after,per,daily,List.copyOf(reasons));
 }
}
