package com.hsg.relay.routing;

import java.math.BigDecimal;
import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.Preference;

/** C8 preference scoring shared by the isolated page and the C1–C10 engine: hits add up, capped at 100. */
public final class C8Calculator {
 private C8Calculator() {}
 public record Txn(String paymentCurrency,String beneficiaryBankCountry,String beneficiaryRegion,
  BigDecimal amountUsd,String paymentType,String clientSegment) {}
 /** wildcard=true 表示该条件未配置（空/ALL），匹配全部；matched=该条件是否命中。 */
 public record Condition(String field,String label,String configured,String actual,boolean wildcard,boolean matched) {}
 public record RuleEvaluation(int index,Preference rule,List<Condition> conditions,boolean matched) {}
 public record ProviderScore(String provider,int rawPoints,int score,List<Integer> hitIndexes) {}
 public record Result(List<RuleEvaluation> evaluations,List<ProviderScore> providers) {}
 public static List<Condition> conditions(Preference p,Txn t){
  return List.of(
   text("currency","交易币种",p.currency(),t.paymentCurrency()),
   text("country","受益行国家",p.country(),t.beneficiaryBankCountry()),
   text("region","受益人地区",p.region(),t.beneficiaryRegion()),
   amount("minUsd","金额下限 USD",p.minUsd(),t.amountUsd(),true),
   amount("maxUsd","金额上限 USD",p.maxUsd(),t.amountUsd(),false),
   text("paymentType","交易类型",p.paymentType(),t.paymentType()),
   text("segment","客户分层",p.segment(),t.clientSegment()));
 }
 public static boolean matches(Preference p,Txn t){return conditions(p,t).stream().allMatch(Condition::matched);}
 /** 每一条命中规则给指定合作方加分，合计封顶100；未命中得0分（BRD 6.8）。 */
 public static int scoreFor(List<Preference> preferences,String provider,Txn t){
  int sum=0;for(Preference p:preferences)if(p.provider().equals(provider)&&matches(p,t))sum+=p.points();
  return Math.min(100,sum);
 }
 public static Result evaluate(List<Preference> preferences,Txn t){
  class Agg{int raw;final List<Integer> hits=new ArrayList<>();}
  List<RuleEvaluation> evals=new ArrayList<>();
  Map<String,Agg> aggMap=new LinkedHashMap<>();
  for(int i=0;i<preferences.size();i++){
   Preference p=preferences.get(i);List<Condition> cs=conditions(p,t);boolean matched=cs.stream().allMatch(Condition::matched);
   evals.add(new RuleEvaluation(i,p,cs,matched));
   Agg agg=aggMap.computeIfAbsent(p.provider(),k->new Agg());
   if(matched){agg.raw+=p.points();agg.hits.add(i);}
  }
  List<ProviderScore> providers=aggMap.entrySet().stream()
   .map(e->new ProviderScore(e.getKey(),e.getValue().raw,Math.min(100,e.getValue().raw),e.getValue().hits)).toList();
  return new Result(List.copyOf(evals),providers);
 }
 private static Condition text(String field,String label,String configured,String actual){
  boolean wildcard=configured==null||configured.isBlank()||"ALL".equals(configured);
  String shown=actual==null||actual.isBlank()?"未填写":actual;
  return new Condition(field,label,wildcard?"匹配全部":configured,shown,wildcard,wildcard||configured.equals(actual));
 }
 private static Condition amount(String field,String label,BigDecimal configured,BigDecimal actual,boolean lowerBound){
  Objects.requireNonNull(actual,"本笔美元等价金额必填");
  boolean wildcard=configured==null;
  boolean ok=wildcard||(lowerBound?actual.compareTo(configured)>=0:actual.compareTo(configured)<=0);
  return new Condition(field,label,wildcard?"不限":configured.toPlainString(),actual.toPlainString(),wildcard,ok);
 }
}
