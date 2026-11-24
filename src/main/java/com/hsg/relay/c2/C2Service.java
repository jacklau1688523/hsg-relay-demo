package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.Provider;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.ProviderRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.service.ExpressionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class C2Service {
    private final RegulatoryRuleRepository regulatoryRepo;
    private final ProviderRestrictionRepository restrictionRepo;
    private final ProviderCapabilityRepository capabilityRepo;
    private final CapabilityTagRepository tagRepo;
    private final ProviderRepository providerRepo;
    private final FieldDefinitionRepository fieldRepo;
    private final RuleAuditRepository auditRepo;
    private final C2ExpressionCompiler compiler;
    private final ExpressionService expressions;
    private final ObjectMapper mapper;

    public C2Service(RegulatoryRuleRepository regulatoryRepo, ProviderRestrictionRepository restrictionRepo,
                     ProviderCapabilityRepository capabilityRepo, CapabilityTagRepository tagRepo, ProviderRepository providerRepo,
                     FieldDefinitionRepository fieldRepo, RuleAuditRepository auditRepo, C2ExpressionCompiler compiler,
                     ExpressionService expressions, ObjectMapper mapper) {
        this.regulatoryRepo = regulatoryRepo;
        this.restrictionRepo = restrictionRepo;
        this.capabilityRepo = capabilityRepo;
        this.tagRepo = tagRepo;
        this.providerRepo = providerRepo;
        this.fieldRepo = fieldRepo;
        this.auditRepo = auditRepo;
        this.compiler = compiler;
        this.expressions = expressions;
        this.mapper = mapper;
    }

    public record Requirement(String type, List<String> requiredTags) {
    }

    public record FailureAction(String reasonCode) {
    }

    public record RegulatoryInput(String ruleId, String ruleName, String ruleCode, String scope, String currency, String bankCountry,
                                  Requirement requirement, FailureAction onFailure, Boolean enabled, Integer versionNo,
                                  LocalDateTime effectiveFrom,
                                  LocalDateTime effectiveTo, String remark) {
    }

    /**
     * legacy=true 表示这条规则仍带着旧版页面保存的自由条件树：照原条件执行，但无法用「币种 + 受益行国家」表达，
     * 在页面重新保存后转为标准形态。
     * triggerCondition / triggerText 为附加触发条件（按字段库构建，未配置时为 null）；triggerProblem 是该条件按当前字段库不能执行的原因（可执行时为 null）。
     */
    public record RegulatoryView(String ruleId, String ruleName, String ruleCode, String scope, String currency, String bankCountry,
                                 boolean legacy, String conditionText, JsonNode condition,
                                 JsonNode triggerCondition, String triggerText, String triggerProblem,
                                 Requirement requirement, FailureAction onFailure, boolean enabled, int versionNo,
                                 LocalDateTime effectiveFrom, LocalDateTime effectiveTo, String remark) {
    }

    /** 附加触发条件的保存请求：enabled 缺省或为 true 时保存 tree，enabled=false 清除；versionNo 可选，带上时须与规则当前版本一致。 */
    public record TriggerConditionInput(ExpressionService.Node tree, Boolean enabled, Integer versionNo) {
    }

    public record RegulatoryOptions(List<String> currencies, List<RegulatoryScope.Country> countries, List<CapabilityTag> tags) {
    }

    public record RestrictionInput(Long id, String providerCode, String restrictionType, String blockedValues,
                                   Boolean enabled, Integer versionNo, LocalDateTime effectiveFrom,
                                   LocalDateTime effectiveTo, String remark) {
    }

    public record CapabilitiesInput(List<String> tags) {
    }

    /** variables：附加触发条件字段的取值（字段编码 → 值）。九个固定交易要素以同名字段为准，不接受 variables 覆盖。 */
    public record Simulation(String providerCode, String paymentCurrency, String debitCurrency,
                             String beneficiaryBankCountry, String beneficiaryAddressCountry, String beneficiaryRegion,
                             String orderingCountry, String clientCountry, String intermediaryCountry, String purpose,
                             List<String> directorUboNationalities, Map<String, Object> variables) {
        public Simulation(String providerCode, String paymentCurrency, String debitCurrency,
                          String beneficiaryBankCountry, String beneficiaryAddressCountry, String beneficiaryRegion,
                          String orderingCountry, String clientCountry, String intermediaryCountry, String purpose,
                          List<String> directorUboNationalities) {
            this(providerCode, paymentCurrency, debitCurrency, beneficiaryBankCountry, beneficiaryAddressCountry, beneficiaryRegion,
                    orderingCountry, clientCountry, intermediaryCountry, purpose, directorUboNationalities, null);
        }
    }

    public record RuleHit(int order, String ruleId, String restrictionType, String reasonCode, String field,
                          String detail) {
    }

    /**
     * condition 为规则的适用范围（或旧版条件树摘要）；expression 仅旧版规则才有，是其编译后的 AviatorScript。
     * triggerCondition / triggerExpression 为附加触发条件的摘要和编译后的表达式（未配置或未能求值时为 null）；
     * note 说明附加条件缺值（按未触发）或失效（按触发）时的处理。
     */
    public record RegulatoryCheck(String ruleId, boolean triggered, List<String> missingTags, String condition, String expression,
                                  String triggerCondition, String triggerExpression, String note) {
    }

    public record Result(boolean passed, String reasonCode, List<RuleHit> hits, List<RegulatoryCheck> regulatoryChecks,
                         List<String> providerCapabilities, String note) {
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private JsonNode parse(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("数据库规则 JSON 不合法", e);
        }
    }

    private void audit(String type, String id, String action, String before, Object after) {
        auditRepo.save(new RuleAudit(type, id, action, "demo.operator", before, after == null ? null : encode(after)));
    }

    private void validateDates(LocalDateTime from, LocalDateTime to) {
        if (from == null || (to != null && !to.isAfter(from)))
            throw new IllegalArgumentException("生效时间必填，失效时间必须晚于生效时间");
    }

    private void validateTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) throw new IllegalArgumentException("必须选择至少一个能力标签");
        if (tags.size() > 20 || new HashSet<>(tags).size() != tags.size())
            throw new IllegalArgumentException("能力标签不可重复且不能超过20个");
        for (String tag : tags)
            if (!tagRepo.existsById(tag)) throw new IllegalArgumentException("无效能力标签: " + tag);
    }

    private void validateRegulatory(RegulatoryInput in) {
        if (in == null || in.ruleId() == null || !in.ruleId().matches("[A-Z0-9_-]{3,48}"))
            throw new IllegalArgumentException("规则编号格式错误");
        if (in.ruleName() == null || in.ruleName().isBlank() || in.ruleName().length() > 140)
            throw new IllegalArgumentException("规则名称不能为空且不能超过140字符");
        if (!"C2".equals(in.ruleCode()) || !"GLOBAL".equals(in.scope()))
            throw new IllegalArgumentException("监管规则固定为 C2 / GLOBAL");
        if (in.onFailure() == null || !"REG_RULE_NOT_MET".equals(in.onFailure().reasonCode()))
            throw new IllegalArgumentException("监管规则原因码固定为 REG_RULE_NOT_MET");
        if (in.requirement() == null || !"PROVIDER_CAPABILITY".equals(in.requirement().type()))
            throw new IllegalArgumentException("监管规则要求类型必须为 PROVIDER_CAPABILITY");
        List<String> currencies = currencies();
        String currency = normalize(in.currency());
        if (currency.isEmpty() || !currencies.contains(currency))
            throw new IllegalArgumentException("交易币种必填，且须为字段库「交易币种」的可选值：" + String.join(" / ", currencies));
        String country = normalize(in.bankCountry());
        if (country.isEmpty() || !(RegulatoryScope.ANY_COUNTRY.equals(country) || RegulatoryScope.isCountry(country)))
            throw new IllegalArgumentException("受益行国家必填，须为 ISO 3166-1 两位国家代码或 ALL");
        validateTags(in.requirement().requiredTags());
        validateDates(in.effectiveFrom(), in.effectiveTo());
    }

    /** 币种下拉与保存校验共用：元数据中心「交易币种」字段（paymentCurrency）的可选值。 */
    private List<String> currencies() {
        FieldDefinition field = fieldRepo.findById(RegulatoryScope.CURRENCY_FIELD).orElse(null);
        if (field == null || field.valueOptions == null) return List.of();
        return Arrays.stream(field.valueOptions.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .map(x -> x.toUpperCase(Locale.ROOT)).toList();
    }

    public RegulatoryOptions regulatoryOptions() {
        return new RegulatoryOptions(currencies(), RegulatoryScope.options(), capabilityTags());
    }

    public List<RegulatoryView> regulations() {
        return regulatoryRepo.findAll().stream().sorted(Comparator.comparing(x -> x.ruleId)).map(this::view).toList();
    }

    private RegulatoryView view(RegulatoryRule rule) {
        try {
            List<String> tags = Arrays.asList(mapper.readValue(rule.requiredTagsJson, String[].class));
            JsonNode condition = parse(rule.conditionJson);
            RegulatoryScope.Scope scope = RegulatoryScope.of(condition);
            ExpressionService.Node trigger = RegulatoryTrigger.parse(rule.triggerConditionJson, mapper);
            return new RegulatoryView(rule.ruleId, rule.ruleName, rule.ruleCode, rule.scope,
                    scope == null ? null : scope.currency(), scope == null ? null : scope.bankCountry(), scope == null,
                    scope == null ? RegulatoryScope.legacyText(condition)
                            : RegulatoryScope.text(scope.currency(), scope.bankCountry()),
                    condition, trigger == null ? null : mapper.valueToTree(trigger), RegulatoryTrigger.text(trigger),
                    RegulatoryTrigger.problem(trigger, expressions),
                    new Requirement("PROVIDER_CAPABILITY", tags), new FailureAction(rule.reasonCode), rule.enabled,
                    rule.versionNo, rule.effectiveFrom, rule.effectiveTo, rule.remark);
        } catch (Exception e) {
            throw new IllegalStateException("监管规则数据无法解析: " + rule.ruleId, e);
        }
    }

    @Transactional
    public RegulatoryView saveRegulatory(RegulatoryInput in) {
        validateRegulatory(in);
        RegulatoryRule rule = regulatoryRepo.findById(in.ruleId()).orElse(null);
        String before = rule == null ? null : encode(view(rule));
        if (rule != null && in.versionNo() != null && rule.versionNo != in.versionNo())
            throw new IllegalArgumentException("规则版本已变化，请刷新后重试");
        boolean creating = rule == null;
        if (creating) rule = new RegulatoryRule();
        else rule.versionNo++;
        rule.ruleId = in.ruleId();
        rule.ruleName = in.ruleName().trim();
        rule.ruleCode = "C2";
        rule.scope = "GLOBAL";
        rule.conditionJson = encode(RegulatoryScope.tree(in.currency(), in.bankCountry(), mapper));
        rule.requiredTagsJson = encode(in.requirement().requiredTags());
        rule.reasonCode = "REG_RULE_NOT_MET";
        rule.effectiveFrom = in.effectiveFrom();
        rule.effectiveTo = in.effectiveTo();
        rule.enabled = in.enabled() == null ? true : in.enabled();
        rule.remark = in.remark();
        RegulatoryView saved = view(regulatoryRepo.save(rule));
        audit("REGULATORY", rule.ruleId, creating ? "CREATE" : "UPDATE", before, saved);
        return saved;
    }

    /** 按字段库校验附加触发条件并返回编译后的表达式，不保存。 */
    public String compileTrigger(ExpressionService.Node tree) {
        return expressions.compile(tree, "C2");
    }

    /**
     * 保存监管规则的附加触发条件：范围（币种 + 受益行国家）和能力标签不变。
     * 保存前按元数据中心校验；不通过返回 400，规则不变。
     */
    @Transactional
    public RegulatoryView saveTriggerCondition(String id, TriggerConditionInput in) {
        RegulatoryRule rule = regulatoryRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if (in == null) throw new IllegalArgumentException("条件不能为空");
        if (in.versionNo() != null && rule.versionNo != in.versionNo())
            throw new IllegalArgumentException("规则版本已变化，请刷新后重试");
        String before = encode(view(rule));
        if (Boolean.FALSE.equals(in.enabled())) rule.triggerConditionJson = null;
        else {
            compileTrigger(in.tree());
            String conditionJson = encode(in.tree());
            if (conditionJson.length() > 12000) throw new IllegalArgumentException("附加触发条件过长");
            rule.triggerConditionJson = conditionJson;
        }
        rule.versionNo++;
        RegulatoryView saved = view(regulatoryRepo.save(rule));
        audit("REGULATORY", rule.ruleId, "UPDATE_CONDITION", before, saved);
        return saved;
    }

    @Transactional
    public RegulatoryView toggleRegulatory(String id) {
        RegulatoryRule rule = regulatoryRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        String before = encode(view(rule));
        rule.enabled = !rule.enabled;
        rule.versionNo++;
        RegulatoryView updated = view(regulatoryRepo.save(rule));
        audit("REGULATORY", id, rule.enabled ? "ENABLE" : "DISABLE", before, updated);
        return updated;
    }

    @Transactional
    public void deleteRegulatory(String id, Integer versionNo) {
        RegulatoryRule rule = regulatoryRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("规则不存在"));
        if (versionNo == null || rule.versionNo != versionNo)
            throw new IllegalArgumentException("规则版本已变化，请刷新后重试");
        String before = encode(view(rule));
        regulatoryRepo.delete(rule);
        audit("REGULATORY", id, "DELETE", before, null);
    }

    public static final Map<String, String> REASONS = Map.of(
            "BENEFICIARY_COUNTRY", "RESTRICTED_COUNTRY",
            "DIRECTOR_UBO_NATIONALITY", "RESTRICTED_NATIONALITY",
            "BENEFICIARY_REGION", "RESTRICTED_REGION",
            "ORDERING_COUNTRY", "RESTRICTED_COUNTRY",
            "INTERMEDIARY_COUNTRY", "RESTRICTED_COUNTRY",
            "CURRENCY", "RESTRICTED_CURRENCY",
            "PURPOSE", "RESTRICTED_PURPOSE");
    private static final Map<String, Integer> PRIORITIES = Map.of(
            "BENEFICIARY_COUNTRY", 1, "DIRECTOR_UBO_NATIONALITY", 2, "BENEFICIARY_REGION", 3,
            "ORDERING_COUNTRY", 4, "INTERMEDIARY_COUNTRY", 5, "CURRENCY", 6, "PURPOSE", 7, "REGULATORY", 8);

    public List<ProviderRestriction> restrictions() {
        return restrictionRepo.findAll().stream().sorted(Comparator.comparing(x -> x.id)).toList();
    }

    @Transactional
    public ProviderRestriction saveRestriction(RestrictionInput in) {
        if (in == null || !providerRepo.existsById(in.providerCode()))
            throw new IllegalArgumentException("合作方不存在");
        if (!REASONS.containsKey(in.restrictionType())) throw new IllegalArgumentException("限制类型无效");
        validateDates(in.effectiveFrom(), in.effectiveTo());
        if (in.blockedValues() == null || in.blockedValues().isBlank())
            throw new IllegalArgumentException("屏蔽编码不能为空");
        List<String> values = Arrays.stream(in.blockedValues().split(",")).map(x -> x.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (values.size() > 100 || values.stream().anyMatch(x -> !x.matches("[A-Z0-9_-]{2,40}")))
            throw new IllegalArgumentException("屏蔽编码格式错误，支持英文编码、下划线、短横线，数量最多100个");
        ProviderRestriction existing = in.id() == null ? null : restrictionRepo.findById(in.id()).orElseThrow(() -> new IllegalArgumentException("限制记录不存在"));
        if (existing != null && in.versionNo() != null && existing.versionNo != in.versionNo())
            throw new IllegalArgumentException("限制记录版本已变化，请刷新");
        boolean creating = existing == null;
        String before = creating ? null : encode(existing);
        ProviderRestriction r = creating ? new ProviderRestriction() : existing;
        if (!creating) r.versionNo++;
        r.providerCode = in.providerCode();
        r.restrictionType = in.restrictionType();
        r.blockedValues = String.join(",", values);
        r.effectiveFrom = in.effectiveFrom();
        r.effectiveTo = in.effectiveTo();
        r.enabled = in.enabled() == null ? true : in.enabled();
        r.remark = in.remark();
        ProviderRestriction saved = restrictionRepo.save(r);
        audit("C2_RESTRICTION", saved.id.toString(), creating ? "CREATE" : "UPDATE", before, saved);
        return saved;
    }

    @Transactional
    public ProviderRestriction toggleRestriction(Long id) {
        ProviderRestriction r = restrictionRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("限制记录不存在"));
        String before = encode(r);
        r.enabled = !r.enabled;
        r.versionNo++;
        ProviderRestriction saved = restrictionRepo.save(r);
        audit("C2_RESTRICTION", id.toString(), r.enabled ? "ENABLE" : "DISABLE", before, saved);
        return saved;
    }

    public List<CapabilityTag> capabilityTags() {
        return tagRepo.findAll().stream().sorted(Comparator.comparing(x -> x.tagCode)).toList();
    }

    public List<String> providerCapabilities(String code) {
        if (!providerRepo.existsById(code)) throw new IllegalArgumentException("合作方不存在");
        return capabilityRepo.findByProviderCode(code).stream().map(c -> c.tagCode).sorted().toList();
    }

    @Transactional
    public List<String> setProviderCapabilities(String code, CapabilitiesInput body) {
        if (!providerRepo.existsById(code)) throw new IllegalArgumentException("合作方不存在");
        List<String> tags = body == null || body.tags() == null ? List.of() : body.tags();
        if (tags.size() > 20 || new HashSet<>(tags).size() != tags.size())
            throw new IllegalArgumentException("能力标签不可重复，最多20个");
        for (String tag : tags) if (!tagRepo.existsById(tag)) throw new IllegalArgumentException("无效标签: " + tag);
        List<String> before = providerCapabilities(code);
        capabilityRepo.deleteByProviderCode(code);
        capabilityRepo.flush();
        for (String tag : tags) capabilityRepo.save(new ProviderCapability(code, tag));
        audit("PROVIDER_CAPABILITY", code, "UPDATE", encode(before), tags);
        return providerCapabilities(code);
    }

    private static boolean active(boolean enabled, LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return enabled && start != null && !now.isBefore(start) && (end == null || now.isBefore(end));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Set<String> blocked(ProviderRestriction r) {
        return Arrays.stream(r.blockedValues.split(",")).map(C2Service::normalize).collect(Collectors.toSet());
    }

    private static boolean hit(Set<String> blocked, String value) {
        return !normalize(value).isEmpty() && blocked.contains(normalize(value));
    }

    private static boolean hitNationality(Set<String> blocked, List<String> nationalities) {
        return nationalities != null && nationalities.stream().anyMatch(v -> hit(blocked, v));
    }

    private static String matchedField(ProviderRestriction r, Simulation req) {
        Set<String> blocked = blocked(r);
        return switch (r.restrictionType) {
            case "BENEFICIARY_COUNTRY" ->
                    hit(blocked, req.beneficiaryBankCountry()) ? "beneficiaryBankCountry" : hit(blocked, req.beneficiaryAddressCountry()) ? "beneficiaryAddressCountry" : null;
            case "DIRECTOR_UBO_NATIONALITY" ->
                    hitNationality(blocked, req.directorUboNationalities()) ? "directorUboNationalities" : null;
            case "BENEFICIARY_REGION" -> hit(blocked, req.beneficiaryRegion()) ? "beneficiaryRegion" : null;
            case "ORDERING_COUNTRY" ->
                    hit(blocked, req.clientCountry()) ? "clientCountry" : hit(blocked, req.orderingCountry()) ? "orderingCountry" : null;
            case "INTERMEDIARY_COUNTRY" -> hit(blocked, req.intermediaryCountry()) ? "intermediaryCountry" : null;
            case "CURRENCY" ->
                    hit(blocked, req.debitCurrency()) ? "debitCurrency" : hit(blocked, req.paymentCurrency()) ? "paymentCurrency" : null;
            case "PURPOSE" -> hit(blocked, req.purpose()) ? "purpose" : null;
            default -> throw new IllegalStateException("未知限制类型");
        };
    }

    public Result simulate(Simulation req) {
        return simulate(req, LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")));
    }

    public Result simulate(Simulation req, LocalDateTime now) {
        if (req == null || req.providerCode() == null || !providerRepo.existsById(req.providerCode()))
            throw new IllegalArgumentException("有效合作方必填");
        if (normalize(req.paymentCurrency()).isEmpty() || normalize(req.beneficiaryBankCountry()).isEmpty())
            throw new IllegalArgumentException("交易币种、受益行国家必填");
        List<String> capabilities = providerCapabilities(req.providerCode());
        List<RuleHit> hits = new ArrayList<>();
        for (ProviderRestriction rule : restrictionRepo.findAll()) {
            if (!req.providerCode().equals(rule.providerCode) || !active(rule.enabled, rule.effectiveFrom, rule.effectiveTo, now))
                continue;
            String field = matchedField(rule, req);
            if (field != null) {
                // Never expose actual UBO nationality values in public response.
                hits.add(new RuleHit(PRIORITIES.get(rule.restrictionType), "C2-RESTRICTION-" + rule.id,
                        rule.restrictionType, REASONS.get(rule.restrictionType), field, "命中合作方限制类型 " + rule.restrictionType));
            }
        }
        Map<String, Object> variables = new HashMap<>();
        // 附加触发条件字段的取值；九个固定交易要素以模拟请求为准，不接受 variables 覆盖
        if (req.variables() != null) variables.putAll(req.variables());
        variables.put("paymentCurrency", req.paymentCurrency());
        variables.put("debitCurrency", req.debitCurrency());
        variables.put("beneficiaryBankCountry", req.beneficiaryBankCountry());
        variables.put("beneficiaryAddressCountry", req.beneficiaryAddressCountry());
        variables.put("beneficiaryRegion", req.beneficiaryRegion());
        variables.put("orderingCountry", req.orderingCountry());
        variables.put("clientCountry", req.clientCountry());
        variables.put("intermediaryCountry", req.intermediaryCountry());
        variables.put("purpose", req.purpose());
        List<RegulatoryCheck> checks = new ArrayList<>();
        for (RegulatoryRule rule : regulatoryRepo.findAll()) {
            if (!active(rule.enabled, rule.effectiveFrom, rule.effectiveTo, now)) continue;
            RegulatoryView view = view(rule);
            String expression = null;
            boolean triggered;
            if (view.legacy()) {
                // 旧版自由条件树沿用原编译链路。Fail closed: invalid published rule configuration is not swallowed as a PASS.
                expression = compiler.compile(view.condition());
                triggered = compiler.evaluate(view.condition(), variables);
            } else
                triggered = RegulatoryScope.matches(view.currency(), view.bankCountry(), req.paymentCurrency(), req.beneficiaryBankCountry());
            // 范围命中后，配置了附加触发条件的规则还须附加条件命中才触发
            String triggerExpression = null, note = null;
            if (triggered && view.triggerCondition() != null) {
                RegulatoryTrigger.Outcome outcome = RegulatoryTrigger.evaluate(
                        RegulatoryTrigger.parse(rule.triggerConditionJson, mapper), variables, expressions);
                triggered = outcome.matched();
                triggerExpression = outcome.expression();
                note = outcome.note();
            }
            List<String> missing = triggered ? view.requirement().requiredTags().stream().filter(tag -> !capabilities.contains(tag)).toList() : List.of();
            checks.add(new RegulatoryCheck(rule.ruleId, triggered, missing, view.conditionText(), expression,
                    view.triggerText(), triggerExpression, note));
            if (!missing.isEmpty()) hits.add(new RuleHit(8, rule.ruleId, "REGULATORY", "REG_RULE_NOT_MET",
                    "providerCapabilities", "监管规则命中，缺少能力标签：" + String.join(",", missing)));
        }
        hits.sort(Comparator.comparingInt(RuleHit::order).thenComparing(RuleHit::ruleId));
        return new Result(hits.isEmpty(), hits.isEmpty() ? "PASS" : hits.get(0).reasonCode(), List.copyOf(hits), checks, capabilities,
                "仅模拟 C2；不代表通过 C1、C3/C4/C6/C10。未填写的可选字段按未命中处理；监管规则的附加触发条件引用的字段没有取值时，该规则按未触发处理。");
    }
}
