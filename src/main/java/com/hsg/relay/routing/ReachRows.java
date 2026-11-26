package com.hsg.relay.routing;

import com.hsg.relay.function.field.ElementSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 覆盖行在集合函数里的记录形态（元素结构 REACH_ROW）：每个候选合作方在本笔交易下的全部覆盖行，
 * 取值口径与 {@link C3Matcher#rows} 完全一致（同一个匹配器、同一套命中判断），这里只是换成按属性取值的记录。
 * 属性名与 {@code ruleRow.*} 受控路径一一对应，C3 全局条件逐行求值时把同一条记录写入 ruleRow.*。
 * 属性：rowId 行号、provider 合作方、currency 币种、country 受益行国家、region 地区、enabled 是否启用、
 * currencyMatched / countryMatched / regionMatched / bankScopeMatched / chargeOptionMatched 各项是否命中、
 * matched 整行是否命中、specificity 匹配精度。
 */
public final class ReachRows {
    public static final String SCHEMA_CODE = "REACH_ROW";

    public static final ElementSchema SCHEMA = new ElementSchema(SCHEMA_CODE, "覆盖行", List.of(
            new ElementSchema.Attribute("rowId", "覆盖行行号", ElementSchema.INTEGER),
            new ElementSchema.Attribute("provider", "合作方", ElementSchema.STRING),
            new ElementSchema.Attribute("currency", "币种", ElementSchema.STRING),
            new ElementSchema.Attribute("country", "受益行国家", ElementSchema.STRING),
            new ElementSchema.Attribute("region", "地区", ElementSchema.STRING),
            new ElementSchema.Attribute("enabled", "是否启用", ElementSchema.BOOLEAN),
            new ElementSchema.Attribute("currencyMatched", "币种是否命中", ElementSchema.BOOLEAN),
            new ElementSchema.Attribute("countryMatched", "国家是否命中", ElementSchema.BOOLEAN),
            new ElementSchema.Attribute("regionMatched", "地区是否命中", ElementSchema.BOOLEAN),
            new ElementSchema.Attribute("bankScopeMatched", "BIC 范围是否命中", ElementSchema.BOOLEAN),
            new ElementSchema.Attribute("chargeOptionMatched", "扣费方式是否命中", ElementSchema.BOOLEAN),
            new ElementSchema.Attribute("matched", "整行是否命中", ElementSchema.BOOLEAN),
            new ElementSchema.Attribute("specificity", "匹配精度", ElementSchema.INTEGER)), "rowId");

    private ReachRows() {
    }

    /** 某个合作方在本笔交易下的全部覆盖行记录（原始属性；按元素结构规整由 RuleList.records 完成）。 */
    public static List<Map<String, Object>> rows(List<RoutingConfig.Reach> reaches, C3Matcher.Input input) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (C3Matcher.Row row : C3Matcher.rows(reaches, input)) rows.add(attributes(row));
        return rows;
    }

    /** 一条覆盖行的属性。 */
    public static Map<String, Object> attributes(C3Matcher.Row row) {
        Map<String, Boolean> matched = new LinkedHashMap<>();
        for (C3Matcher.Criterion criterion : row.criteria())
            matched.put(criterion.field() + "Matched", criterion.passed());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rowId", (long) row.index());
        values.put("provider", row.reach().provider());
        values.put("currency", row.reach().currency());
        values.put("country", row.reach().country());
        values.put("region", row.reach().region());
        values.put("enabled", row.reach().active());
        values.put("currencyMatched", matched.get("currencyMatched"));
        values.put("countryMatched", matched.get("countryMatched"));
        values.put("regionMatched", matched.get("regionMatched"));
        values.put("bankScopeMatched", matched.get("bankScopeMatched"));
        values.put("chargeOptionMatched", matched.get("chargeOptionMatched"));
        values.put("matched", row.matched());
        values.put("specificity", (long) row.specificity());
        return values;
    }
}
