package com.hsg.relay.service;

import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.FieldValueResolver;
import com.hsg.relay.function.field.RuleList;
import com.hsg.relay.routing.C8Calculator;
import com.hsg.relay.routing.RoutingConfig;
import com.hsg.relay.routing.RoutingService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据字段 matchedPreferenceRules 的解析器：当前候选合作方在本笔交易下命中的全部偏好规则
 * （对象集合，元素结构 PREFERENCE_RULE）。命中判断复用 {@link C8Calculator#matches}，规则取自当前路由配置。
 * <p>用它配合 collectionSumWhere（取值属性 points）得到偏好加分合计，再接 numberMin 封顶 100，
 * 与 C8 的 {@link C8Calculator#scoreFor} 口径一致；封顶由规则里的函数完成，这里不封顶、也不合计。
 * 交易要素（币种、受益行国家、地区、美元等价金额、交易类型、客户分层）取自规则上下文，缺少时报错，不返回空集合。
 */
@Component
public class MatchedPreferenceRulesResolver implements FieldValueResolver {
    public static final String RESOLVER_CODE = "matchedPreferenceRulesResolver";
    public static final String FIELD_CODE = "matchedPreferenceRules";

    /** 偏好规则的元素结构：rowId 行号、provider 合作方、币种 / 国家 / 地区 / 交易类型 / 客户分层（* 表示不限）、金额区间、points 加分。 */
    public static final ElementSchema SCHEMA = new ElementSchema("PREFERENCE_RULE", "偏好规则", List.of(
            new ElementSchema.Attribute("rowId", "规则行号", ElementSchema.INTEGER),
            new ElementSchema.Attribute("provider", "合作方", ElementSchema.STRING),
            new ElementSchema.Attribute("currency", "币种", ElementSchema.STRING),
            new ElementSchema.Attribute("country", "受益行国家", ElementSchema.STRING),
            new ElementSchema.Attribute("region", "受益人地区", ElementSchema.STRING),
            new ElementSchema.Attribute("paymentType", "交易类型", ElementSchema.STRING),
            new ElementSchema.Attribute("segment", "客户分层", ElementSchema.STRING),
            new ElementSchema.Attribute("minUsd", "金额下限 USD", ElementSchema.DECIMAL, true),
            new ElementSchema.Attribute("maxUsd", "金额上限 USD", ElementSchema.DECIMAL, true),
            new ElementSchema.Attribute("points", "加分", ElementSchema.INTEGER)), "rowId");

    private final RoutingService routing;

    // RoutingService → C1Service → ExpressionService → FieldValueService → 字段解析器：这里延迟取，避免循环依赖
    public MatchedPreferenceRulesResolver(@Lazy RoutingService routing) {
        this.routing = routing;
    }

    @Override
    public String resolverCode() {
        return RESOLVER_CODE;
    }

    @Override
    public RuleList resolve(String fieldCode, RuleExecutionContext context) {
        if (!FIELD_CODE.equals(fieldCode))
            throw new IllegalArgumentException("字段解析器 " + RESOLVER_CODE + " 不支持字段 " + fieldCode);
        if (!"C8".equals(context.ruleCode()))
            throw new IllegalStateException("字段 " + FIELD_CODE + " 只能用于 C8，当前规则为 " + context.ruleCode());
        String provider = required(context, "candidate.providerCode");
        C8Calculator.Txn txn = new C8Calculator.Txn(required(context, "transaction.paymentCurrency"),
                required(context, "transaction.beneficiaryBankCountry"), text(context, "transaction.beneficiaryRegion"),
                amount(context), required(context, "transaction.paymentType"), required(context, "transaction.clientSegment"));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<RoutingConfig.Preference> preferences = routing.config().config().preferences();
        for (int i = 0; i < preferences.size(); i++) {
            RoutingConfig.Preference rule = preferences.get(i);
            if (!rule.provider().equals(provider) || !C8Calculator.matches(rule, txn)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rowId", (long) i);
            row.put("provider", rule.provider());
            row.put("currency", rule.currency());
            row.put("country", rule.country());
            row.put("region", rule.region());
            row.put("paymentType", rule.paymentType());
            row.put("segment", rule.segment());
            row.put("minUsd", rule.minUsd());
            row.put("maxUsd", rule.maxUsd());
            row.put("points", (long) rule.points());
            rows.add(row);
        }
        return RuleList.records("ROUTING_CONFIG", SCHEMA, rows);
    }

    private static String text(RuleExecutionContext context, String path) {
        Object value = context.value(path);
        return value == null ? "" : value.toString().trim();
    }

    private static String required(RuleExecutionContext context, String path) {
        String value = text(context, path);
        if (value.isEmpty())
            throw new IllegalStateException("字段 " + FIELD_CODE + " 需要交易要素 " + path + "，本次没有取值，无法判断偏好规则");
        return value;
    }

    private static BigDecimal amount(RuleExecutionContext context) {
        Object value = context.value("transaction.amountUsd");
        if (value instanceof BigDecimal decimal) return decimal;
        throw new IllegalStateException("字段 " + FIELD_CODE + " 需要本笔美元等价金额 transaction.amountUsd，本次没有取值");
    }
}
