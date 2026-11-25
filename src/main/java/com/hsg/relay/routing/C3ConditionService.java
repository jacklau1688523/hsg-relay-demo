package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.service.ExpressionService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class C3ConditionService {
    private final C3ConditionConfigRepository repository;
    private final ExpressionService expressions;
    private final ObjectMapper mapper;

    public C3ConditionService(C3ConditionConfigRepository repository, ExpressionService expressions, ObjectMapper mapper) {
        this.repository=repository; this.expressions=expressions; this.mapper=mapper;
    }

    public record Input(ExpressionService.Node tree, Integer versionNo) {}

    public C3ConditionConfig get() { return repository.findById(1L).orElseGet(C3ConditionConfig::new); }
    public String compile(Input input) { return expressions.compile(input.tree(), "C3"); }
    public C3ConditionConfig save(Input input) {
        if(input==null || input.tree()==null) throw new IllegalArgumentException("条件树不能为空");
        compile(input);
        C3ConditionConfig row=get();
        if(input.versionNo()!=null && input.versionNo()!=row.versionNo) throw new IllegalArgumentException("条件配置已更新，请刷新后重试");
        try { row.conditionJson=mapper.writeValueAsString(input.tree()); }
        catch(Exception e) { throw new IllegalArgumentException("条件树无效",e); }
        if(row.conditionJson.length()>12000) throw new IllegalArgumentException("C3 条件树过长");
        row.versionNo++;
        return repository.save(row);
    }

    public boolean matches(C3Matcher.Row row, C3Matcher.Input input) {
        C3ConditionConfig config=get();
        if(config.conditionJson==null) return true;
        try {
            ExpressionService.Node tree=mapper.readValue(config.conditionJson, ExpressionService.Node.class);
            Map<String,Object> values=new HashMap<>();
            values.put("beneficiaryBankCountry",input.beneficiaryBankCountry());
            for(C3Matcher.Criterion criterion:row.criteria()) {
                String prefix=switch(criterion.field()) {
                    case "currency" -> "reachCurrency";
                    case "country" -> "reachCountry";
                    case "region" -> "reachRegion";
                    case "bankScope" -> "reachBankScope";
                    case "chargeOption" -> "reachChargeOption";
                    default -> throw new IllegalArgumentException("未知覆盖条件");
                };
                values.put(prefix+"Enabled",row.reach().active());
                values.put(prefix+"Matched",criterion.passed());
            }
            return expressions.preview(new ExpressionService.Preview(tree,values,"C3")).matched();
        } catch(Exception e) { return false; }
    }

    public Optional<C3Matcher.Row> select(List<RoutingConfig.Reach> reaches,C3Matcher.Input input) {
        return C3Matcher.rows(reaches,input).stream().filter(C3Matcher.Row::matched).filter(row->matches(row,input))
                .sorted(Comparator.comparingInt(C3Matcher.Row::specificity).reversed().thenComparingInt(C3Matcher.Row::index)).findFirst();
    }
}
