package com.hsg.relay.service;

import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class C1Service {
    private final ProviderRepository providers;
    private final AppetiteRepository appetites;
    private final ClientProfileRepository clients;
    private final ClientExclusionChecker exclusionChecker;
    private final CaseApprovalRepository approvals;
    private final ProviderUsageRepository usages;
    private final ExpressionService expressions;
    private final ObjectMapper json;

    public C1Service(ProviderRepository p, AppetiteRepository a, ClientProfileRepository c, CaseApprovalRepository ap, ProviderUsageRepository u, ExpressionService expressions, ObjectMapper json, ClientExclusionChecker exclusionChecker) {
        providers = p;
        appetites = a;
        clients = c;
        this.exclusionChecker = exclusionChecker;
        approvals = ap;
        usages = u;
        this.expressions = expressions;
        this.json = json;
    }

    // variables：附加条件字段的模拟取值（字段编码 → 值）。除下列固定交易要素外，字段库里启用的 C1 字段都从这里取值。
    public record Simulation(String clientId, String providerCode, String paymentCurrency,
                             String beneficiaryBankCountry, BigDecimal amountUsd, Map<String, Object> variables) {
        public Simulation(String clientId, String providerCode, String paymentCurrency, String beneficiaryBankCountry, BigDecimal amountUsd) {
            this(clientId, providerCode, paymentCurrency, beneficiaryBankCountry, amountUsd, null);
        }
    }

    public record Trace(String step, String status, String detail) {
    }

    public record Result(boolean passed, String reasonCode, Long matchedRuleId, String matchedRuleAction,
                         List<Trace> steps) {
    }

    private Result fail(String code, Long ruleId, String action, List<Trace> steps, String detail) {
        steps.add(new Trace("失败原因", "FAIL", detail));
        return new Result(false, code, ruleId, action, steps);
    }

    public Result evaluate(Simulation req) {
        return evaluate(req, LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")));
    }

    public Result evaluate(Simulation req, LocalDateTime now) {
        List<Trace> steps = new ArrayList<>();
        if (req == null || req.amountUsd() == null || req.amountUsd().signum() < 0 || req.clientId() == null || req.providerCode() == null || req.paymentCurrency() == null || req.beneficiaryBankCountry() == null)
            throw new IllegalArgumentException("模拟请求字段缺失或金额无效");
        Provider provider = providers.findById(req.providerCode()).orElse(null);
        if (provider == null) throw new IllegalArgumentException("合作方不存在");
        // C1 自身的判断与合作方 LIVE 过滤分开：这里只做 C1。
        ClientProfile client = clients.findById(req.clientId()).orElse(null);
        if (client == null || !"COMPLETE".equals(client.classificationStatus))
            return fail("CLIENT_NOT_CLASSIFIED", null, null, steps, "客户分类不是 COMPLETE");
        steps.add(new Trace("1. 客户分类", "PASS", "COMPLETE"));
        boolean excluded = exclusionChecker.clientExcluded(req.providerCode(), req.clientId(), now);
        if (excluded) return fail("CLIENT_EXCLUDED", null, null, steps, "命中合作方客户排除记录");
        steps.add(new Trace("2. 客户排除", "PASS", "未命中排除记录"));
        List<String> flows = client.flowCategories == null ? List.of() : Arrays.stream(client.flowCategories.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (flows.isEmpty()) flows = List.of("STANDARD");
        Long lastId = null;
        String lastAction = null;
        for (String flow : flows) {
            // 附加条件缺值或已失效的规则按不命中处理，并在校验过程里逐条说明
            List<String> skipped = new ArrayList<>();
            Optional<Appetite> match = appetites.findAll().stream().filter(a -> a.enabled && req.providerCode().equals(a.providerCode) && flow.equals(a.flowCategory) && active(a.effectiveFrom, a.effectiveTo, now) && scopeMatch(a.currency, req.paymentCurrency()) && scopeMatch(a.bankCountry, req.beneficiaryBankCountry()) && conditionMatches(a, req, client, excluded, flow, skipped)).max(Comparator.comparingInt(a -> specificity(a, req)));
            skipped.forEach(detail -> steps.add(new Trace("3. 规则匹配 " + flow, "SKIP", detail)));
            if (match.isEmpty()) return fail("APPETITE_NOT_SET", null, null, steps, flow + " 无有效准入配置（默认拒绝）");
            Appetite a = match.get();
            lastId = a.id;
            lastAction = a.action;
            steps.add(new Trace("3. 规则匹配 " + flow, "PASS", "命中 #" + a.id + "，" + a.currency + "/" + a.bankCountry + "，动作 " + a.action + "，版本 " + a.versionNo));
            if ("EXCLUDE".equals(a.action))
                return fail("APPETITE_EXCLUDED", a.id, a.action, steps, flow + " 被合作方禁止");
            if ("CASE_BY_CASE".equals(a.action)) {
                boolean approved = approvals.findAll().stream().anyMatch(x -> x.clientId.equals(req.clientId()) && x.providerCode.equals(req.providerCode()) && x.flowCategory.equals(flow) && !now.isBefore(x.effectiveFrom) && now.isBefore(x.expiresAt));
                if (!approved)
                    return fail("APPETITE_APPROVAL_MISSING", a.id, a.action, steps, "没有有效的合作方-客户-流转类别审批");
                steps.add(new Trace("4. 个案审批 " + flow, "PASS", "找到有效审批"));
            } else if ("ACCEPT".equals(a.action)) {
                if (a.monthlyCapUsd != null) {
                    String month = YearMonth.from(now).toString();
                    BigDecimal mtd = usages.findAll().stream().filter(u -> u.providerCode.equals(req.providerCode()) && u.flowCategory.equals(flow) && u.monthKey.equals(month)).map(u -> u.amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal total = mtd.add(req.amountUsd());
                    if (total.compareTo(a.monthlyCapUsd) > 0)
                        return fail("APPETITE_CAP_REACHED", a.id, a.action, steps, "MTD " + mtd + " + 本笔 " + req.amountUsd() + " > 月上限 " + a.monthlyCapUsd);
                    steps.add(new Trace("4. MTD " + flow, "PASS", "MTD " + mtd + " + " + req.amountUsd() + " <= " + a.monthlyCapUsd));
                } else steps.add(new Trace("4. MTD " + flow, "PASS", "ACCEPT 未设置月度上限"));
            } else throw new IllegalStateException("未知准入动作 " + a.action);
        }
        steps.add(new Trace("5. C1 汇总", "PASS", "全部流转类别通过；模拟不占用 MTD"));
        return new Result(true, "PASS", lastId, lastAction, steps);
    }

    private boolean active(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return start != null && !now.isBefore(start) && (end == null || now.isBefore(end));
    }

    private boolean scopeMatch(String configured, String actual) {
        return "ALL".equals(configured) || configured.equalsIgnoreCase(actual);
    }

    private int specificity(Appetite a, Simulation r) {
        return (a.currency.equalsIgnoreCase(r.paymentCurrency()) ? 2 : 0) + (a.bankCountry.equalsIgnoreCase(r.beneficiaryBankCountry()) ? 1 : 0);
    }

    private boolean conditionMatches(Appetite rule, Simulation req, ClientProfile client, boolean excluded, String flow, List<String> skipped) {
        if (rule.conditionJson == null || rule.conditionJson.isBlank()) return true;
        try {
            var tree = json.readValue(rule.conditionJson, ExpressionService.Node.class);
            // 规则保存后字段在元数据中心被停用、不再适用 C1 或可选值被删：条件已无法按当前字段库执行，按不命中处理并说明
            try { expressions.compile(tree, "C1"); }
            catch (IllegalArgumentException ex) {
                skipped.add("规则 #" + rule.id + " 的附加条件已失效（" + ex.getMessage() + "），按不命中处理，请在 C1 业务准入页核对后重新保存");
                return false;
            }
            Map<String, Object> variables = new HashMap<>();
            if (req.variables() != null) variables.putAll(req.variables());
            // 客户和合作方状态从档案、排除记录读取，不接受模拟请求覆盖
            variables.put("clientClassificationStatus", client.classificationStatus);
            variables.put("clientProviderExcluded", excluded);
            // 四个交易要素以模拟请求为准，不接受 variables 覆盖
            variables.put("flowCategory", flow);
            variables.put("paymentCurrency", req.paymentCurrency());
            variables.put("beneficiaryBankCountry", req.beneficiaryBankCountry());
            variables.put("amountUsd", req.amountUsd());
            List<String> missing = expressions.referencedFields(tree).stream()
                    .filter(field -> variables.get(field) == null || variables.get(field).toString().isBlank()).toList();
            if (!missing.isEmpty()) {
                skipped.add("规则 #" + rule.id + " 的附加条件引用了字段 " + String.join("、", missing) + "，本次没有提供取值，按不命中处理");
                return false;
            }
            return expressions.preview(new ExpressionService.Preview(tree, variables, "C1")).matched();
        } catch (Exception ex) {
            throw new IllegalStateException("C1 规则 #" + rule.id + " 条件执行失败: " + ex.getMessage(), ex);
        }
    }
}
