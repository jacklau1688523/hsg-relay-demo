package com.hsg.relay.service;

import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.FieldValueResolver;
import com.hsg.relay.function.field.RuleList;
import com.hsg.relay.routing.C7Calculator;
import com.hsg.relay.routing.C7Service;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据字段 costComponents 的解析器：当前候选合作方在本笔交易下的预估成本构成（对象集合，元素结构 COST_COMPONENT）。
 * <p>每一项都是按 HSG 中间汇率折算后的港币金额，取值复用 C7 自己的成本预估（{@link C7Service#simulate} →
 * {@link C7Calculator#estimate}），不另写算法；四项相加等于 C7 的预估总成本（港币）。
 * 需要换汇时每个外汇渠道各有一组构成（venue = TREASURY / PROVIDER）；无需换汇时只有一组（venue = NONE）；
 * 货币对没有任何外汇渠道窗口时没有成本构成，返回已确认的空集合。要素不全、缺少汇率或手续费配置时抛异常。
 */
@Component
public class CostComponentsResolver implements FieldValueResolver {
    public static final String RESOLVER_CODE = "costComponentsResolver";
    public static final String FIELD_CODE = "costComponents";

    /** 成本构成的元素结构：component 项目、venue 外汇渠道、currency 币种（恒为 HKD）、amountHkd 港币金额。 */
    public static final ElementSchema SCHEMA = new ElementSchema("COST_COMPONENT", "成本构成", List.of(
            new ElementSchema.Attribute("component", "成本项目", ElementSchema.STRING),
            new ElementSchema.Attribute("venue", "外汇渠道", ElementSchema.STRING),
            new ElementSchema.Attribute("currency", "币种", ElementSchema.STRING),
            new ElementSchema.Attribute("amountHkd", "港币金额", ElementSchema.DECIMAL)));

    private final C7Service costs;

    // C7Service → RoutingService → C1Service → ExpressionService → FieldValueService → 字段解析器：这里延迟取，避免循环依赖
    public CostComponentsResolver(@Lazy C7Service costs) {
        this.costs = costs;
    }

    @Override
    public String resolverCode() {
        return RESOLVER_CODE;
    }

    @Override
    public RuleList resolve(String fieldCode, RuleExecutionContext context) {
        if (!FIELD_CODE.equals(fieldCode))
            throw new IllegalArgumentException("字段解析器 " + RESOLVER_CODE + " 不支持字段 " + fieldCode);
        if (!"C7".equals(context.ruleCode()))
            throw new IllegalStateException("字段 " + FIELD_CODE + " 只能用于 C7，当前规则为 " + context.ruleCode());
        if (context.decisionTime() == null) throw new IllegalStateException("字段 " + FIELD_CODE + " 需要决策时间");
        C7Service.Input input = new C7Service.Input(provider(context), required(context, "transaction.debitCurrency"),
                required(context, "transaction.paymentCurrency"), amount(context), required(context, "transaction.chargeOption"),
                context.decisionTime().atZone(ZoneId.of("Asia/Hong_Kong")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (C7Service.Venue venue : costs.simulate(input).venues()) {
            C7Calculator.Result calc = venue.calculation();
            rows.add(row("COMMISSION", venue.fxVenue(), calc.commissionHkd()));
            rows.add(row("CABLE", venue.fxVenue(), calc.cableHkd()));
            rows.add(row("INTERMEDIARY", venue.fxVenue(), calc.intermediaryHkd()));
            rows.add(row("FX_SPREAD", venue.fxVenue(), calc.fxCostHkd()));
        }
        return RuleList.records("FEE_CONFIG", SCHEMA, rows);
    }

    private static Map<String, Object> row(String component, String venue, BigDecimal amountHkd) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("component", component);
        row.put("venue", venue);
        row.put("currency", "HKD");
        row.put("amountHkd", amountHkd);
        return row;
    }

    private static String provider(RuleExecutionContext context) {
        Object value = context.value("candidate.providerCode");
        if (value == null || value.toString().isBlank())
            throw new IllegalStateException("字段 " + FIELD_CODE + " 需要候选合作方，当前上下文没有");
        return value.toString().trim();
    }

    private static String required(RuleExecutionContext context, String path) {
        Object value = context.value(path);
        if (value == null || value.toString().isBlank())
            throw new IllegalStateException("字段 " + FIELD_CODE + " 需要交易要素 " + path + "，本次没有取值");
        return value.toString().trim();
    }

    private static BigDecimal amount(RuleExecutionContext context) {
        Object value = context.value("transaction.amount");
        if (value instanceof BigDecimal decimal) return decimal;
        throw new IllegalStateException("字段 " + FIELD_CODE + " 需要交易金额 transaction.amount（交易币种），本次没有取值");
    }
}
