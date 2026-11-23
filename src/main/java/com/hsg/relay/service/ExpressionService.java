package com.hsg.relay.service;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ExpressionService {
 private final FieldDefinitionRepository fields;
 private final AviatorEvaluatorInstance engine=AviatorEvaluator.newInstance();
 public ExpressionService(FieldDefinitionRepository fields){this.fields=fields;}
 public record Node(String logic,List<Node> children,String field,String operator,Object value) {}
 public record Preview(Node tree,Map<String,Object> variables,String ruleCode) {}
 public record PreviewResult(String expression,boolean matched) {}
 public PreviewResult preview(Preview req){if(req.tree()==null||req.variables()==null)throw new IllegalArgumentException("tree / variables 必填");if(req.ruleCode()!=null&&!req.ruleCode().matches("C([1-9]|10)"))throw new IllegalArgumentException("无效规则范围");String expression=compile(req.tree(),0,req.ruleCode());Expression exp=engine.compile(expression,false);Map<String,Object> vars=new HashMap<>();for(FieldDefinition field:fields.findAll()){if(req.variables().containsKey(field.fieldCode)){Object value=req.variables().get(field.fieldCode);if(List.of("DECIMAL","INTEGER").contains(field.dataType)&&value!=null)value=new BigDecimal(value.toString());vars.put(field.fieldCode,value);}}Object value=exp.execute(vars);return new PreviewResult(expression,Boolean.TRUE.equals(value));}
 private String compile(Node n,int depth,String ruleCode){
  if(depth>6)throw new IllegalArgumentException("最多允许 6 层条件组");
  if(n.children()!=null&&!n.children().isEmpty()){
   if(n.children().size()>20)throw new IllegalArgumentException("单组条件数不可超过20");
   if(!"AND".equals(n.logic())&&!"OR".equals(n.logic()))throw new IllegalArgumentException("条件组只允许 AND/OR");
   String delimiter="AND".equals(n.logic())?" && ":" || ";
   return "("+String.join(delimiter,n.children().stream().map(x->compile(x,depth+1,ruleCode)).toList())+")";
  }
  FieldDefinition f=fields.findById(n.field()==null?"":n.field()).orElseThrow(()->new IllegalArgumentException("非法字段: "+n.field()));
  if(!f.enabled||!Arrays.asList(f.allowedOperators.split(",")).contains(n.operator())||(ruleCode!=null&&!Arrays.asList(f.allowedRules.split(",")).contains(ruleCode)))throw new IllegalArgumentException("字段不适用于当前规则或不支持该操作符");
  if(n.value()==null)throw new IllegalArgumentException("条件值不可为空");
  String literal;
  if(List.of("DECIMAL","INTEGER").contains(f.dataType))literal=new BigDecimal(n.value().toString()).toPlainString();
  else if("BOOLEAN".equals(f.dataType)){if(!List.of("true","false").contains(n.value().toString()))throw new IllegalArgumentException("布尔值只能为 true 或 false");literal=n.value().toString();}
  else literal="'"+n.value().toString().replace("\\","\\\\").replace("'","\\'")+"'";
  if("IN".equals(n.operator())){
   if(List.of("DECIMAL","INTEGER","BOOLEAN").contains(f.dataType))throw new IllegalArgumentException("该类型不支持 IN");
   String[] parts=n.value().toString().split(",");
   if(parts.length==0||parts.length>40)throw new IllegalArgumentException("IN 只允许1~40个值");
   for(String part:parts)checkOption(f,part.trim());
   return "("+String.join(" || ",Arrays.stream(parts).map(x->"("+f.fieldCode+" == '"+x.trim().replace("\\","\\\\").replace("'","\\'")+"')").toList())+")";
  }
  checkOption(f,n.value().toString());
  String op=switch(n.operator()){case "EQ"->"==";case "NE"->"!=";case "GT"->">";case "GTE"->">=";case "LT"->"<";case "LTE"->"<=";default->throw new IllegalArgumentException("不允许的运算符");};
  return "("+f.fieldCode+" "+op+" "+literal+")";
 }
 private void checkOption(FieldDefinition field,String value){
  if(field.valueOptions!=null&&!field.valueOptions.isBlank()&&Arrays.stream(field.valueOptions.split(",")).map(String::trim).noneMatch(value::equals))throw new IllegalArgumentException(field.fieldCode+" 不支持该值："+value);
 }
}
