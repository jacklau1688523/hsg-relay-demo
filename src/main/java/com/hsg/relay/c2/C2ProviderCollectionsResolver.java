package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.FieldValueResolver;
import com.hsg.relay.function.field.RuleList;
import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * C2 的集合类元数据字段解析器（四个字段共用一个解析器，按 fieldCode 区分）：
 * <ul>
 *   <li>providerRestrictedNationalities（STRING_SET）：当前合作方在决策时点生效的「董事 / UBO 国籍」限制取值；</li>
 *   <li>providerRestrictions（OBJECT_LIST · PROVIDER_RESTRICTION）：当前合作方在决策时点生效的全部限制记录；</li>
 *   <li>providerCapabilityTags（STRING_SET）：当前合作方已配置的能力标签；</li>
 *   <li>requiredCapabilityTags（STRING_SET）：正在判断附加触发条件的那条监管规则要求的能力标签
 *       （取 context.regulatoryRuleId；判断 C2 全局附加条件时该路径为空，字段不可解析）。</li>
 * </ul>
 * 取值口径与 {@link C2Service#simulate} 一致：生效判断用 {@link C2Service#active}，取值归一化用 {@link C2Service#normalize}
 * （去首尾空白、转大写）。合作方不存在、监管规则不存在或已失效时抛异常，不返回空集合。只读。
 */
@Component
public class C2ProviderCollectionsResolver implements FieldValueResolver {
    public static final String RESOLVER_CODE = "c2ProviderCollectionsResolver";

    public static final String RESTRICTED_NATIONALITIES = "providerRestrictedNationalities";
    public static final String RESTRICTIONS = "providerRestrictions";
    public static final String CAPABILITY_TAGS = "providerCapabilityTags";
    public static final String REQUIRED_TAGS = "requiredCapabilityTags";

    /** 国籍限制在 C2 里的限制类型。 */
    static final String NATIONALITY_TYPE = "DIRECTOR_UBO_NATIONALITY";

    /** 限制记录的元素结构：restrictionId（C2-RESTRICTION-<id>）、restrictionType、blockedValues、生效 / 失效时间。 */
    public static final ElementSchema RESTRICTION_SCHEMA = new ElementSchema("PROVIDER_RESTRICTION", "合作方限制", List.of(
            new ElementSchema.Attribute("restrictionId", "限制记录 ID", ElementSchema.STRING),
            new ElementSchema.Attribute("restrictionType", "限制类型", ElementSchema.STRING),
            new ElementSchema.Attribute("blockedValues", "限制取值", ElementSchema.STRING_SET),
            new ElementSchema.Attribute("effectiveFrom", "生效时间", ElementSchema.DATE, true),
            new ElementSchema.Attribute("effectiveTo", "失效时间", ElementSchema.DATE, true)), "restrictionId");

    private final ProviderRestrictionRepository restrictions;
    private final ProviderCapabilityRepository capabilities;
    private final RegulatoryRuleRepository rules;
    private final ProviderRepository providers;
    private final ObjectMapper json;

    public C2ProviderCollectionsResolver(ProviderRestrictionRepository restrictions, ProviderCapabilityRepository capabilities,
                                         RegulatoryRuleRepository rules, ProviderRepository providers, ObjectMapper json) {
        this.restrictions = restrictions;
        this.capabilities = capabilities;
        this.rules = rules;
        this.providers = providers;
        this.json = json;
    }

    @Override
    public String resolverCode() {
        return RESOLVER_CODE;
    }

    @Override
    public Object resolve(String fieldCode, RuleExecutionContext context) {
        if (!List.of(RESTRICTED_NATIONALITIES, RESTRICTIONS, CAPABILITY_TAGS, REQUIRED_TAGS).contains(fieldCode == null ? "" : fieldCode))
            throw new IllegalArgumentException("字段解析器 " + RESOLVER_CODE + " 不支持字段 " + fieldCode);
        if (!"C2".equals(context.ruleCode()))
            throw new IllegalStateException("字段 " + fieldCode + " 只能用于 C2，当前规则为 " + context.ruleCode());
        String provider = text(context, "candidate.providerCode");
        if (provider.isEmpty()) throw new IllegalStateException("字段 " + fieldCode + " 需要候选合作方，当前上下文没有");
        if (!providers.existsById(provider)) throw new IllegalStateException("字段 " + fieldCode + "：合作方 " + provider + " 不存在");
        LocalDateTime now = context.decisionTime();
        if (now == null) throw new IllegalStateException("字段 " + fieldCode + " 需要决策时间");
        List<ProviderRestriction> active = restrictions.findAll().stream()
                .filter(r -> provider.equals(r.providerCode) && C2Service.active(r.enabled, r.effectiveFrom, r.effectiveTo, now))
                .toList();
        switch (fieldCode) {
            case RESTRICTED_NATIONALITIES: {
                // 归一化后的取值（大写）：与集合函数的区分大小写比较配合使用时，交易侧国籍同样归一化
                TreeSet<String> values = new TreeSet<>();
                for (ProviderRestriction r : active)
                    if (NATIONALITY_TYPE.equals(r.restrictionType)) values.addAll(blocked(r));
                return RuleList.ofPrivate(fieldCode, RuleList.STRING_SET, values);
            }
            case RESTRICTIONS: {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ProviderRestriction r : active) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("restrictionId", "C2-RESTRICTION-" + r.id);
                    row.put("restrictionType", r.restrictionType);
                    row.put("blockedValues", blocked(r));
                    row.put("effectiveFrom", r.effectiveFrom);
                    row.put("effectiveTo", r.effectiveTo);
                    rows.add(row);
                }
                return RuleList.records("PROVIDER_RESTRICTION", RESTRICTION_SCHEMA, rows);
            }
            case CAPABILITY_TAGS:
                return RuleList.of(fieldCode, RuleList.STRING_SET, capabilities.findByProviderCode(provider).stream()
                        .map(c -> c.tagCode).sorted().toList());
            default:
                return requiredTags(context, fieldCode, now);
        }
    }

    /** 正在判断的监管规则要求的能力标签；规则须存在且在决策时点生效。 */
    private Object requiredTags(RuleExecutionContext context, String fieldCode, LocalDateTime now) {
        String ruleId = text(context, "context.regulatoryRuleId");
        if (ruleId.isEmpty())
            throw new IllegalStateException("字段 " + fieldCode + " 需要正在判断附加触发条件的监管规则（context.regulatoryRuleId），当前上下文没有");
        RegulatoryRule rule = rules.findById(ruleId).orElse(null);
        if (rule == null) throw new IllegalStateException("字段 " + fieldCode + "：监管规则 " + ruleId + " 不存在");
        if (!C2Service.active(rule.enabled, rule.effectiveFrom, rule.effectiveTo, now))
            throw new IllegalStateException("字段 " + fieldCode + "：监管规则 " + ruleId + " 在决策时点未生效");
        List<String> tags;
        try {
            tags = List.of(json.readValue(rule.requiredTagsJson, String[].class));
        } catch (Exception ex) {
            throw new IllegalStateException("字段 " + fieldCode + "：监管规则 " + ruleId + " 的能力要求无法解析", ex);
        }
        return RuleList.of(fieldCode, RuleList.STRING_SET, tags);
    }

    /** 一条限制的取值：与 C2 判定同一套归一化，去掉空白项。 */
    static Collection<String> blocked(ProviderRestriction r) {
        TreeSet<String> values = new TreeSet<>();
        for (String value : r.blockedValues == null ? new String[0] : r.blockedValues.split(","))
            if (!C2Service.normalize(value).isEmpty()) values.add(C2Service.normalize(value));
        return values;
    }

    private static String text(RuleExecutionContext context, String path) {
        Object value = context.value(path);
        return value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    }
}
