package com.hsg.relay.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import com.hsg.relay.service.*;
import org.springframework.http.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class RelayController {
    private final ProviderRepository providers;
    private final AppetiteRepository appetites;
    private final ClientProfileRepository clients;
    private final ClientExclusionRepository exclusions;
    private final CaseApprovalRepository approvals;
    private final ProviderUsageRepository usages;
    private final RuleAuditRepository audit;
    private final FieldDefinitionRepository fields;
    private final C1Service c1;
    private final ExpressionService expressions;
    private final ObjectMapper json;

    public RelayController(ProviderRepository p, AppetiteRepository a, ClientProfileRepository c, ClientExclusionRepository e, CaseApprovalRepository ap, ProviderUsageRepository u, RuleAuditRepository audit, FieldDefinitionRepository f, C1Service c1, ExpressionService ex, ObjectMapper json) {
        this.providers = p;
        this.appetites = a;
        this.clients = c;
        this.exclusions = e;
        this.approvals = ap;
        this.usages = u;
        this.audit = audit;
        this.fields = f;
        this.c1 = c1;
        this.expressions = ex;
        this.json = json;
    }

    @GetMapping("/providers")
    public List<Provider> providers() {
        return providers.findAll();
    }

    @GetMapping("/appetites")
    public List<Appetite> appetites(@RequestParam(required = false) String providerCode) {
        return appetites.findAll().stream().filter(a -> providerCode == null || providerCode.isBlank() || a.providerCode.equals(providerCode)).sorted(Comparator.comparing(a -> a.id)).toList();
    }

    @PostMapping("/appetites")
    public Appetite save(@RequestBody Appetite input) {
        if (input.conditionJson != null && !input.conditionJson.isBlank()) {
            if (input.conditionJson.length() > 12000) throw new IllegalArgumentException("C1 条件树过长");
            try { var tree = json.readValue(input.conditionJson, ExpressionService.Node.class); validateC1Fields(tree); expressions.compile(tree, "C1"); }
            catch (IllegalArgumentException ex) { throw ex; }
            catch (Exception ex) { throw new IllegalArgumentException("C1 条件树 JSON 无效", ex); }
        } else input.conditionJson = null;
        if (!providers.existsById(input.providerCode)) throw new IllegalArgumentException("无效合作方");
        if (!List.of("STANDARD", "PSP", "WEB3").contains(input.flowCategory))
            throw new IllegalArgumentException("无效流转类别");
        if (!List.of("ACCEPT", "EXCLUDE", "CASE_BY_CASE").contains(input.action))
            throw new IllegalArgumentException("无效准入方式");
        if (input.currency == null || input.currency.isBlank() || input.bankCountry == null || input.bankCountry.isBlank() || input.evidenceDocNo == null || input.evidenceDocNo.isBlank() || input.nextReviewDate == null || input.effectiveFrom == null)
            throw new IllegalArgumentException("必填项缺失");
        if (input.effectiveTo != null && !input.effectiveTo.isAfter(input.effectiveFrom))
            throw new IllegalArgumentException("失效时间应晚于生效时间");
        if (input.monthlyCapUsd != null && input.monthlyCapUsd.signum() < 0)
            throw new IllegalArgumentException("月度上限不可小于0");
        if (!"ACCEPT".equals(input.action)) input.monthlyCapUsd = null;
        Appetite previous = input.id == null ? null : appetites.findById(input.id).orElseThrow(() -> new IllegalArgumentException("配置不存在"));
        if (previous != null && input.conditionJson == null) input.conditionJson = previous.conditionJson;
        if (previous != null) {
            input.versionNo = previous.versionNo + 1;
        } else {
            input.versionNo = 1;
        }
        boolean collision = appetites.findAll().stream().anyMatch(a -> a.enabled && input.enabled && !Objects.equals(a.id, input.id) && a.providerCode.equals(input.providerCode) && a.flowCategory.equals(input.flowCategory) && a.currency.equalsIgnoreCase(input.currency) && a.bankCountry.equalsIgnoreCase(input.bankCountry) && overlap(a.effectiveFrom, a.effectiveTo, input.effectiveFrom, input.effectiveTo));
        if (collision) throw new IllegalArgumentException("同一合作方、流转类别、币种及国家的生效期重叠");
        Appetite saved = appetites.save(input);
        try {
            audit.save(new RuleAudit("APPETITE", saved.id.toString(), previous == null ? "CREATE" : "UPDATE", "demo.operator", previous == null ? null : json.writeValueAsString(previous), json.writeValueAsString(saved)));
        } catch (Exception e) {
            throw new IllegalStateException("审计写入失败", e);
        }
        return saved;
    }

    public record C1ConditionInput(ExpressionService.Node tree) {}

    public record C1ConditionUpdate(boolean enabled, ExpressionService.Node tree) {}

    @PutMapping("/appetites/{id}/condition")
    @Transactional
    public Appetite saveC1Condition(@PathVariable Long id, @RequestBody C1ConditionUpdate input) {
        Appetite row = appetites.findById(id).orElseThrow(() -> new IllegalArgumentException("准入规则不存在"));
        String before;
        try { before = json.writeValueAsString(row); }
        catch (Exception ex) { throw new IllegalStateException("审计序列化失败", ex); }
        if (input.enabled()) {
            validateC1Fields(input.tree());
            expressions.compile(input.tree(), "C1");
            try { row.conditionJson = json.writeValueAsString(input.tree()); }
            catch (Exception ex) { throw new IllegalArgumentException("C1 条件树无效", ex); }
            if (row.conditionJson.length() > 12000) throw new IllegalArgumentException("C1 条件树过长");
        } else row.conditionJson = null;
        row.versionNo++;
        Appetite saved = appetites.save(row);
        try { audit.save(new RuleAudit("APPETITE", id.toString(), "UPDATE_CONDITION", "demo.operator", before, json.writeValueAsString(saved))); }
        catch (Exception ex) { throw new IllegalStateException("审计写入失败", ex); }
        return saved;
    }

    @PostMapping("/appetites/compile")
    public Map<String, String> compileC1(@RequestBody C1ConditionInput input) {
        validateC1Fields(input.tree());
        return Map.of("expression", expressions.compile(input.tree(), "C1"));
    }

    private void validateC1Fields(ExpressionService.Node node) {
        if (node == null) throw new IllegalArgumentException("C1 条件树不能为空");
        if (node.children() != null && !node.children().isEmpty()) node.children().forEach(this::validateC1Fields);
        else if (node.field() == null || !Set.of("flowCategory", "paymentCurrency", "beneficiaryBankCountry", "amountUsd").contains(node.field()))
            throw new IllegalArgumentException("C1 条件仅支持流转类别、交易币种、受益行国家和金额");
    }

    private boolean overlap(LocalDateTime a1, LocalDateTime a2, LocalDateTime b1, LocalDateTime b2) {
        return (a2 == null || b1.isBefore(a2)) && (b2 == null || a1.isBefore(b2));
    }

    @PostMapping("/appetites/{id}/toggle")
    public Appetite toggle(@PathVariable Long id) {
        Appetite a = appetites.findById(id).orElseThrow();
        a.enabled = !a.enabled;
        a.versionNo++;
        Appetite s = appetites.save(a);
        audit.save(new RuleAudit("APPETITE", id.toString(), a.enabled ? "ENABLE" : "DISABLE", "demo.operator", null, "{\"enabled\":" + s.enabled + "}"));
        return s;
    }

    @GetMapping("/clients")
    public List<ClientProfile> clients() {
        return clients.findAll();
    }

    @GetMapping("/exclusions")
    public List<ClientExclusion> exclusions(@RequestParam(required = false) String providerCode) {
        return exclusions.findAll().stream().filter(x -> providerCode == null || x.providerCode.equals(providerCode)).toList();
    }

    @PostMapping("/exclusions")
    public ClientExclusion addExclusion(@RequestBody ClientExclusion x) {
        if (!clients.existsById(x.clientId) || !providers.existsById(x.providerCode) || x.reason == null || x.reason.isBlank())
            throw new IllegalArgumentException("客户/合作方/原因无效");
        if (x.effectiveFrom == null) x.effectiveFrom = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
        ClientExclusion saved = exclusions.save(x);
        audit.save(new RuleAudit("EXCLUSION", saved.id.toString(), "CREATE", "demo.compliance", null, "client=" + x.clientId + ",provider=" + x.providerCode));
        return saved;
    }

    @PostMapping("/exclusions/{id}/release")
    @Transactional
    public ClientExclusion releaseExclusion(@PathVariable Long id) {
        ClientExclusion row = exclusions.findById(id).orElseThrow(() -> new IllegalArgumentException("排除记录不存在"));
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
        if (row.effectiveTo != null && !row.effectiveTo.isAfter(now)) throw new IllegalArgumentException("排除记录已结束");
        row.effectiveTo = now;
        ClientExclusion saved = exclusions.save(row);
        audit.save(new RuleAudit("EXCLUSION", id.toString(), "RELEASE", "demo.compliance", null, "effectiveTo=" + now));
        return saved;
    }

    @GetMapping("/approvals")
    public List<CaseApproval> approvals(@RequestParam(required = false) String providerCode) {
        return approvals.findAll().stream().filter(x -> providerCode == null || x.providerCode.equals(providerCode)).toList();
    }

    @PostMapping("/approvals")
    public CaseApproval addApproval(@RequestBody CaseApproval x) {
        if (!clients.existsById(x.clientId) || !providers.existsById(x.providerCode) || x.flowCategory == null || x.approvalReference == null || x.approvalReference.isBlank() || x.effectiveFrom == null || x.expiresAt == null || !x.expiresAt.isAfter(x.effectiveFrom))
            throw new IllegalArgumentException("审批字段无效");
        CaseApproval saved = approvals.save(x);
        audit.save(new RuleAudit("APPROVAL", saved.id.toString(), "CREATE", "demo.ops", null, "reference=" + x.approvalReference));
        return saved;
    }

    @PostMapping("/approvals/{id}/revoke")
    @Transactional
    public CaseApproval revokeApproval(@PathVariable Long id) {
        CaseApproval row = approvals.findById(id).orElseThrow(() -> new IllegalArgumentException("审批记录不存在"));
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
        if (!row.expiresAt.isAfter(now)) throw new IllegalArgumentException("审批已失效");
        row.expiresAt = now;
        CaseApproval saved = approvals.save(row);
        audit.save(new RuleAudit("APPROVAL", id.toString(), "REVOKE", "demo.ops", null, "expiresAt=" + now));
        return saved;
    }

    @GetMapping("/usage")
    public List<ProviderUsage> usage() {
        return usages.findAll();
    }

    // 演示用：模拟统计任务回填 MTD。按「合作方＋流转类别＋月份」覆盖写入，不存在则新增。
    // 入参用 record 而不是实体：实体的 amountUsd 默认值是 0，漏传金额时会被当成 0 覆盖掉原有 MTD
    public record UsageInput(String providerCode, String flowCategory, String monthKey, BigDecimal amountUsd) {
    }

    @PutMapping("/usage")
    @Transactional
    public ProviderUsage saveUsage(@RequestBody UsageInput in) {
        if (in.providerCode() == null || !providers.existsById(in.providerCode())) throw new IllegalArgumentException("无效合作方");
        if (in.flowCategory() == null || !List.of("STANDARD", "PSP", "WEB3").contains(in.flowCategory())) throw new IllegalArgumentException("无效流转类别");
        if (in.monthKey() == null || !in.monthKey().matches("\\d{4}-(0[1-9]|1[0-2])")) throw new IllegalArgumentException("月份格式应为 YYYY-MM");
        if (in.amountUsd() == null) throw new IllegalArgumentException("月累计金额不可为空");
        if (in.amountUsd().signum() < 0) throw new IllegalArgumentException("月累计金额不可小于0");
        ProviderUsage row = usages.findAll().stream().filter(u -> u.providerCode.equals(in.providerCode()) && u.flowCategory.equals(in.flowCategory()) && u.monthKey.equals(in.monthKey())).findFirst().orElse(new ProviderUsage(in.providerCode(), in.flowCategory(), in.monthKey(), BigDecimal.ZERO));
        boolean created = row.id == null;
        ProviderUsage saved;
        try {
            String before = created ? null : json.writeValueAsString(row);
            row.amountUsd = in.amountUsd().setScale(2, RoundingMode.HALF_UP);
            saved = usages.save(row);
            audit.save(new RuleAudit("USAGE", saved.id.toString(), created ? "CREATE" : "UPDATE", "demo.operator", before, json.writeValueAsString(saved)));
        } catch (Exception e) {
            throw new IllegalStateException("审计写入失败", e);
        }
        return saved;
    }

    @DeleteMapping("/usage/{id}")
    @Transactional
    public Map<String, Object> deleteUsage(@PathVariable Long id) {
        ProviderUsage row = usages.findById(id).orElseThrow(() -> new IllegalArgumentException("MTD 记录不存在"));
        try {
            usages.delete(row);
            audit.save(new RuleAudit("USAGE", id.toString(), "DELETE", "demo.operator", json.writeValueAsString(row), null));
        } catch (Exception e) {
            throw new IllegalStateException("审计写入失败", e);
        }
        return Map.of("id", id, "deleted", true);
    }

    @GetMapping("/audit")
    public List<RuleAudit> audit() {
        return audit.findAll().stream().sorted(Comparator.comparing((RuleAudit x) -> x.id).reversed()).limit(100).toList();
    }

    @GetMapping("/fields")
    public List<FieldDefinition> fields() {
        return fields.findAll();
    }

    @PostMapping("/c1/simulate")
    public C1Service.Result simulate(@RequestBody C1Service.Simulation req) {
        return c1.evaluate(req);
    }

    @PostMapping("/expression/preview")
    public ExpressionService.PreviewResult preview(@RequestBody ExpressionService.Preview req) {
        return expressions.preview(req);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> invalid(Exception ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
