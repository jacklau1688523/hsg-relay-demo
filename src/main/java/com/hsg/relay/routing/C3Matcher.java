package com.hsg.relay.routing;

import java.util.*;
import static com.hsg.relay.routing.RoutingConfig.Reach;

/** Shared by isolated C3 simulation and the C1–C10 engine. */
public final class C3Matcher {
 private C3Matcher() {}
 public record Input(String providerCode,String paymentCurrency,String beneficiaryBankCountry,
  String beneficiaryRegion,String beneficiaryBankBic,String chargeOption) {}
 public record Criterion(String field,boolean passed,String expected,String actual) {}
 public record Row(int index,Reach reach,boolean matched,int specificity,List<Criterion> criteria) {}
 private static boolean scope(String configured,String actual){return "ALL".equals(configured)||configured.equals(actual);}
 public static List<Row> rows(List<Reach> reaches,Input in){
  List<Row> result=new ArrayList<>();String bic=in.beneficiaryBankBic()==null?"":in.beneficiaryBankBic();
  String bic8=bic.length()>=8?bic.substring(0,8):bic;
  for(int i=0;i<reaches.size();i++){
   Reach r=reaches.get(i);if(!r.provider().equals(in.providerCode()))continue;
   List<Criterion> criteria=List.of(
    new Criterion("currency",r.currency().equals(in.paymentCurrency()),r.currency(),in.paymentCurrency()),
    new Criterion("country",scope(r.country(),in.beneficiaryBankCountry()),r.country(),in.beneficiaryBankCountry()),
    new Criterion("region",scope(r.region(),in.beneficiaryRegion()),r.region(),in.beneficiaryRegion()),
    new Criterion("bankScope",r.bics().isEmpty()||r.bics().contains(bic8),r.bics().isEmpty()?"ALL":String.join(",",r.bics()),bic8),
    new Criterion("chargeOption",r.charges().contains(in.chargeOption()),String.join(",",r.charges()),in.chargeOption()));
   int specificity=(!"ALL".equals(r.region())?4:0)+(!"ALL".equals(r.country())?2:0)+(!r.bics().isEmpty()?1:0);
   result.add(new Row(i,r,r.active()&&criteria.stream().allMatch(Criterion::passed),specificity,criteria));
  }
  return result;
 }
 public static Optional<Row> select(List<Reach> reaches,Input in){return rows(reaches,in).stream().filter(Row::matched).sorted(Comparator.comparingInt(Row::specificity).reversed().thenComparingInt(Row::index)).findFirst();}
 public static void validate(Reach r){
  if(r.currency()==null||!r.currency().matches("[A-Z]{3}"))throw new IllegalArgumentException("币种须为三位大写编码");
  if(r.country()==null||!r.country().matches("ALL|[A-Z]{2}"))throw new IllegalArgumentException("国家须为 ALL 或两位大写编码");
  if(r.region()==null||!r.region().matches("ALL|[A-Z]{2}-[A-Z0-9]{1,3}"))throw new IllegalArgumentException("地区须为 ALL 或 ISO3166-2 编码");
  if(!"ALL".equals(r.region())&&!"ALL".equals(r.country())&&!r.region().startsWith(r.country()+"-"))throw new IllegalArgumentException("地区编码与国家不一致");
  if(r.bics()==null||r.bics().size()>500||r.bics().stream().anyMatch(b->b==null||!b.matches("[A-Z]{6}[A-Z0-9]{2}"))||new HashSet<>(r.bics()).size()!=r.bics().size())throw new IllegalArgumentException("BIC 列表须为不重复的 BIC8 编码（最多500项）");
  if(r.charges()==null||r.charges().isEmpty()||r.charges().stream().anyMatch(x->x==null||!Set.of("OUR","SHA","BEN").contains(x))||new HashSet<>(r.charges()).size()!=r.charges().size())throw new IllegalArgumentException("至少选择一种有效扣费方式，不可重复");
 }
 public static void validateInput(Input in){
  if(in==null||in.providerCode()==null||in.providerCode().isBlank())throw new IllegalArgumentException("合作方必填");
  if(in.paymentCurrency()==null||!in.paymentCurrency().matches("[A-Z]{3}")||in.beneficiaryBankCountry()==null||!in.beneficiaryBankCountry().matches("[A-Z]{2}"))throw new IllegalArgumentException("交易币种及受益行国家格式无效");
  if(in.beneficiaryRegion()!=null&&!in.beneficiaryRegion().isBlank()&&!in.beneficiaryRegion().matches("[A-Z]{2}-[A-Z0-9]{1,3}"))throw new IllegalArgumentException("受益人地区格式无效");
  if(in.beneficiaryBankBic()==null||!in.beneficiaryBankBic().matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?"))throw new IllegalArgumentException("受益行 BIC 须为8或11位");
  if(in.chargeOption()==null||!Set.of("OUR","SHA","BEN").contains(in.chargeOption()))throw new IllegalArgumentException("扣费方式无效");
 }
}
