package com.hsg.relay.service;

import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class C1Service {
    private final ProviderRepository providers;
    private final AppetiteRepository appetites;
    private final ClientProfileRepository clients;
    private final ClientExclusionRepository exclusions;
    private final CaseApprovalRepository approvals;
    private final ProviderUsageRepository usages;

    public C1Service(ProviderRepository p, AppetiteRepository a, ClientProfileRepository c, ClientExclusionRepository e, CaseApprovalRepository ap, ProviderUsageRepository u) {
        providers = p;
        appetites = a;
        clients = c;
        exclusions = e;
        approvals = ap;
        usages = u;
    }

    public record Simulation(String clientId, String providerCode, String paymentCurrency,
                             String beneficiaryBankCountry, BigDecimal amountUsd) {
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
        boolean excluded = exclusions.findAll().stream().anyMatch(e -> e.clientId.equals(req.clientId()) && e.providerCode.equals(req.providerCode()) && active(e.effectiveFrom, e.effectiveTo, now));
        if (excluded) return fail("CLIENT_EXCLUDED", null, null, steps, "命中合作方客户排除记录");
        steps.add(new Trace("2. 客户排除", "PASS", "未命中排除记录"));
        List<String> flows = client.flowCategories == null || client.flowCategories.isBlank() ? List.of("STANDARD") : Arrays.stream(client.flowCategories.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        Long lastId = null;
        String lastAction = null;
        for (String flow : flows) {
            Optional<Appetite> match = appetites.findAll().stream().filter(a -> a.enabled && req.providerCode().equals(a.providerCode) && flow.equals(a.flowCategory) && active(a.effectiveFrom, a.effectiveTo, now) && scopeMatch(a.currency, req.paymentCurrency()) && scopeMatch(a.bankCountry, req.beneficiaryBankCountry())).max(Comparator.comparingInt(a -> specificity(a, req)));
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
}
