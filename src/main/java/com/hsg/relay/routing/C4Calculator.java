package com.hsg.relay.routing;

import java.math.*;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.Balance;

/** C4 filter shared by the isolated page and the C1–C10 engine: nostro position check (BRD 6.4). */
public final class C4Calculator {
  private static final MathContext MC=MathContext.DECIMAL128;
  /** 唯一原因码：可用头寸不足本笔账户币种等价金额。 */
  public static final String SHORTFALL="FUNDING_SHORTFALL";
  private static final Set<String> VENUES=Set.of("NONE","TREASURY","PROVIDER");
  private C4Calculator() {}
  /**
   * enabled 合作方资金校验开关（9.1）；fxVenue 外汇渠道；accountCurrency 头寸账户币种（步骤2）；
   * rate HSG 当日汇率＝1 交易币种折合账户币种（PROVIDER 以外恒为1）；required 本笔账户币种等价金额（步骤4）；
   * balance / reserved / available 往来账余额、未记账的已路由交易占用、可用头寸＝余额−占用（步骤3），未配置该币种余额行时 accountFound=false 且各项按0计。
   * 开关 OFF 时各项数值仅供展示，passed 恒为 true。reasons: FUNDING_SHORTFALL。
   */
  public record Result(boolean enabled,String fxVenue,String paymentCurrency,String debitCurrency,String accountCurrency,
   BigDecimal amount,BigDecimal rate,BigDecimal required,BigDecimal balance,BigDecimal reserved,BigDecimal available,
   boolean accountFound,boolean passed,List<String> reasons) {}
  /** 步骤2 账户币种判定：外汇渠道 PROVIDER 取扣账账户币种；NONE / TREASURY 取交易币种。 */
  public static String accountCurrency(String fxVenue,String paymentCurrency,String debitCurrency){
    if(fxVenue==null||!VENUES.contains(fxVenue))throw new IllegalArgumentException("外汇渠道须为 NONE / TREASURY / PROVIDER");
    return "PROVIDER".equals(fxVenue)?debitCurrency:paymentCurrency;
  }
  public static Result check(String provider,boolean fundingCheckEnabled,String fxVenue,String paymentCurrency,String debitCurrency,
   BigDecimal amount,Map<String,BigDecimal> hkdRates,List<Balance> balances){
    Objects.requireNonNull(amount,"交易金额必填");
    String account=accountCurrency(fxVenue,paymentCurrency,debitCurrency);
    BigDecimal rate=BigDecimal.ONE,required=amount;
    if("PROVIDER".equals(fxVenue)){
      // 步骤4：PROVIDER 渠道等价金额＝交易金额×HSG 当日汇率（交易币种、扣账币种经港币交叉），与全流程 amountHkd÷账户汇率 同一口径
      BigDecimal paymentRate=rate(hkdRates,paymentCurrency),accountRate=rate(hkdRates,account);
      rate=paymentRate.divide(accountRate,MC);
      required=amount.multiply(paymentRate,MC).divide(accountRate,MC);
    }
    Balance row=balances==null?null:balances.stream().filter(b->b.provider().equals(provider)&&b.currency().equals(account)).findFirst().orElse(null);
    BigDecimal balance=row==null?BigDecimal.ZERO:row.balance(),reserved=row==null?BigDecimal.ZERO:row.reserved();
    BigDecimal available=balance.subtract(reserved);
    List<String> reasons=!fundingCheckEnabled||available.compareTo(required)>=0?List.of():List.of(SHORTFALL);
    return new Result(fundingCheckEnabled,fxVenue,paymentCurrency,debitCurrency,account,amount,rate,required,balance,reserved,available,row!=null,reasons.isEmpty(),reasons);
  }
  private static BigDecimal rate(Map<String,BigDecimal> hkdRates,String currency){
    BigDecimal v=hkdRates==null?null:hkdRates.get(currency);if(v==null||v.signum()<=0)throw new IllegalArgumentException("缺少汇率："+currency);return v;
  }
}
