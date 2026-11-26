package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.service.ExpressionService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
public class C3ConditionService {
    private final C3ConditionConfigRepository repository;
    private final ExpressionService expressions;
    private final ObjectMapper mapper;

    private final RoutingService routing;

    // RoutingService → C3ConditionService：这里延迟取，避免循环依赖
    public C3ConditionService(C3ConditionConfigRepository repository, ExpressionService expressions, ObjectMapper mapper,
                              @Lazy RoutingService routing) {
        this.repository=repository; this.expressions=expressions; this.mapper=mapper; this.routing=routing;
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

    /**
     * 当前生效的 C3 全局条件（未配置时为 null）。保存时已按字段库编译过，这里按当前字段库再编译一次。
     */
    private Condition condition() {
        C3ConditionConfig config=get();
        if(config.conditionJson==null) return null;
        try {
            ExpressionService.Node tree=mapper.readValue(config.conditionJson, ExpressionService.Node.class);
            return new Condition(tree, expressions.compileDetailed(tree, "C3"), config.versionNo);
        } catch(Exception e) { throw new IllegalArgumentException("C3 条件树无效",e); }
    }

    /** 条件树与编译结果。needsContext 为 true（含函数节点 / 数据解析字段）时按执行上下文求值。 */
    private record Condition(ExpressionService.Node tree, ExpressionService.Compiled compiled, int versionNo) {
        boolean needsContext() { return compiled.needsContext(); }
    }

    /**
     * 一条覆盖行是否满足 C3 全局条件。条件里没有函数节点和数据解析字段时走原来的求值路径（结果不变）；
     * 引用了函数 / 数据解析字段（如合作方覆盖行 providerReachRows）时按执行上下文求值，当前覆盖行的属性写入 ruleRow.*。
     * 求值失败（取值没有加载、读取失败）一律按「不满足」处理，与原行为一致：失败的覆盖行不会被选中。
     */
    public boolean matches(C3Matcher.Row row, C3Matcher.Input input) {
        try {
            Condition condition=condition();
            if(condition==null) return true;
            RuleExecutionContext context=condition.needsContext() ? context(input, condition.versionNo()) : null;
            return matches(row, input, condition, context);
        } catch(Exception e) { return false; }
    }

    private boolean matches(C3Matcher.Row row, C3Matcher.Input input, Condition condition, RuleExecutionContext context) {
        Map<String,Object> values=values(row,input);
        if(!condition.needsContext()) return expressions.preview(new ExpressionService.Preview(condition.tree(),values,"C3")).matched();
        // 同一次选择里逐行复用上下文：与当前行无关的数据（如合作方覆盖行）只解析一次，各行只换 ruleRow.*
        context.ruleRow(ReachRows.attributes(row));
        return expressions.preview(new ExpressionService.Preview(condition.tree(),values,"C3"),context).matched();
    }

    /** 叶子条件的取值（原有变量名，不变）。 */
    private static Map<String,Object> values(C3Matcher.Row row, C3Matcher.Input input) {
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
        return values;
    }

    /**
     * C3 条件的执行上下文：候选合作方、覆盖行匹配要素、当前路由配置版本与 C3 条件版本、决策时间。
     * ruleRow.* 由逐行求值时写入。
     */
    private RuleExecutionContext context(C3Matcher.Input input, int conditionVersion) {
        Map<String,Object> transaction=new HashMap<>();
        transaction.put("transaction.paymentCurrency",input.paymentCurrency());
        transaction.put("transaction.beneficiaryBankCountry",input.beneficiaryBankCountry());
        transaction.put("transaction.beneficiaryRegion",input.beneficiaryRegion());
        transaction.put("transaction.beneficiaryBankBic",input.beneficiaryBankBic());
        transaction.put("transaction.chargeOption",input.chargeOption());
        return RuleExecutionContext.c3(input.providerCode(),transaction,routing.config().revision(),conditionVersion,
                LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")));
    }

    /** 最精确的满足条件的覆盖行：选择算法不变（命中的行里按匹配精度、行号取第一），只是行的条件判断支持函数 / 数据解析字段。 */
    public Optional<C3Matcher.Row> select(List<RoutingConfig.Reach> reaches,C3Matcher.Input input) {
        Condition condition;
        // 已发布的条件无法解析 / 编译：按「没有覆盖行满足」处理，与原行为一致（原来每行的判断都抛异常 → false）
        try { condition=condition(); } catch(Exception e) { return Optional.empty(); }
        if(condition==null) return C3Matcher.select(reaches,input);
        if(condition.needsContext()) {
            RuleExecutionContext context=context(input,condition.versionNo());
            return C3Matcher.rows(reaches,input).stream().filter(C3Matcher.Row::matched).filter(row->matches(row,input,condition,context))
                    .sorted(Comparator.comparingInt(C3Matcher.Row::specificity).reversed().thenComparingInt(C3Matcher.Row::index)).findFirst();
        }
        return C3Matcher.rows(reaches,input).stream().filter(C3Matcher.Row::matched).filter(row->matches(row,input,condition,null))
                .sorted(Comparator.comparingInt(C3Matcher.Row::specificity).reversed().thenComparingInt(C3Matcher.Row::index)).findFirst();
    }
}
