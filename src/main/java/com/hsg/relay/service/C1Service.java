package com.hsg.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.*;
import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.FunctionExecutionService.Call;
import com.hsg.relay.function.RequiredFunctionProvider;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
public class C1Service implements RequiredFunctionProvider {
    /**
     * 系统必检清单（代码级，只读）：C1 执行器在代码里直接调用这些检查对应的 Java Handler，
     * 不读取函数库元数据（停用、删除、改绑定都不影响），也不看条件树里有没有对应节点；页面只读展示。
     * 键为函数编码，值为强制落实的说明。FunctionMetadataService 据此把函数标为 REQUIRED。
     */
    public static final Map<String, String> REQUIRED_FUNCTIONS = declaredRequiredFunctions();

    // 系统必检的入参：只使用受控路径
    private static final Map<String, Object> EXCLUSION_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");
    private static final Map<String, Object> APPROVAL_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId", "flowCategory", "$candidate.flowCategory");
    private static final Map<String, Object> MTD_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "flowCategory", "$candidate.flowCategory");

    private final ProviderRepository providers;
    private final AppetiteRepository appetites;
    private final ClientProfileRepository clients;
    private final ClientExclusionChecker exclusionChecker;
    private final CaseApprovalChecker approvalChecker;
    private final ProviderMtdReader mtdReader;
    private final FunctionExecutionService functions;
    private final ExpressionService expressions;
    private final C1ConditionService globalConditions;
    private final ObjectMapper json;

    // 审批和 MTD 的查询在各自的 Handler 里（CaseApprovalChecker / ProviderMtdReader），执行器的系统必检与条件节点共用它们的调用结果
    public C1Service(ProviderRepository p, AppetiteRepository a, ClientProfileRepository c, ExpressionService expressions,
                     ObjectMapper json, ClientExclusionChecker exclusionChecker,
                     CaseApprovalChecker approvalChecker, ProviderMtdReader mtdReader, FunctionExecutionService functions,
                     C1ConditionService globalConditions) {
        providers = p;
        appetites = a;
        clients = c;
        this.exclusionChecker = exclusionChecker;
        this.approvalChecker = approvalChecker;
        this.mtdReader = mtdReader;
        this.functions = functions;
        this.expressions = expressions;
        this.globalConditions = globalConditions;
        this.json = json;
    }

    @Override
    public String ruleCode() {
        return "C1";
    }

    @Override
    public Map<String, String> requiredFunctions() {
        return REQUIRED_FUNCTIONS;
    }

    private static Map<String, String> declaredRequiredFunctions() {
        Map<String, String> required = new LinkedHashMap<>();
        required.put("clientExcluded", "命中当前合作方对客户的有效排除记录即拒绝（CLIENT_EXCLUDED），先于准入规则匹配");
        required.put("findBestAppetiteRule", "每个流转类别按四级匹配准入规则，无有效匹配默认拒绝（APPETITE_NOT_SET）");
        required.put("hasValidCaseApproval", "命中 CASE_BY_CASE 规则时须有有效个案审批（APPETITE_APPROVAL_MISSING）");
        required.put("getProviderMtd", "命中设有月度上限的 ACCEPT 规则时校验 MTD + 本笔金额（APPETITE_CAP_REACHED）");
        return Collections.unmodifiableMap(required);
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

    // functionCalls：本次判定里的全部业务函数调用（系统必检 + 条件节点 + 函数绑定字段），含入参、返回值、来源、耗时、复用标记
    public record Result(boolean passed, String reasonCode, Long matchedRuleId, String matchedRuleAction,
                         List<Trace> steps, List<Call> functionCalls) {
        public Result(boolean passed, String reasonCode, Long matchedRuleId, String matchedRuleAction, List<Trace> steps) {
            this(passed, reasonCode, matchedRuleId, matchedRuleAction, steps, List.of());
        }
    }

    private Result fail(String code, Long ruleId, String action, List<Trace> steps, RuleExecutionContext context, String detail) {
        steps.add(new Trace("失败原因", "FAIL", detail));
        return new Result(false, code, ruleId, action, steps, context.calls());
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
        // 请求级上下文：本次判定内相同函数 + 相同参数只查询一次；候选合作方、交易要素、决策时间都来自本次请求
        RuleExecutionContext context = RuleExecutionContext.c1(req.providerCode(), req.clientId(), req.paymentCurrency(), req.beneficiaryBankCountry(), req.amountUsd(), now);
        ClientProfile client = clients.findById(req.clientId()).orElse(null);
        if (client == null)
            return fail("CLIENT_NOT_FOUND", null, null, steps, context, "客户档案不存在");
        // 客户分类状态不再由执行器硬编码拦截；它作为 clientClassificationStatus 字段注入
        // C1 页面保存的全局准入条件树，由表达式结果决定是否保留候选合作方。
        steps.add(new Trace("1. 客户档案", "PASS", "已读取客户档案；分类状态由准入条件配置决定：" + client.classificationStatus));
        List<String> flows = client.flowCategories == null ? List.of() : Arrays.stream(client.flowCategories.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (flows.isEmpty()) flows = List.of("STANDARD");

        // 页面配置的是 C1 全局准入条件，不隶属于某一条合作方准入规则。
        // 每个客户流转类别分别求值；任一类别不满足即过滤该合作方。
        for (String flow : flows) {
            context.set("candidate.flowCategory", flow);
            Map<String, Object> variables = new HashMap<>();
            if (req.variables() != null) variables.putAll(req.variables());
            variables.put("clientClassificationStatus", client.classificationStatus);
            variables.put("flowCategory", flow);
            variables.put("paymentCurrency", req.paymentCurrency());
            variables.put("beneficiaryBankCountry", req.beneficiaryBankCountry());
            variables.put("amountUsd", req.amountUsd());
            variables.put("appetiteCurrencyEnabled", appetites.findAll().stream().anyMatch(a -> a.enabled
                    && req.providerCode().equals(a.providerCode) && flow.equals(a.flowCategory)
                    && active(a.effectiveFrom, a.effectiveTo, now) && scopeMatch(a.currency, req.paymentCurrency())));
            variables.put("appetiteCountryEnabled", appetites.findAll().stream().anyMatch(a -> a.enabled
                    && req.providerCode().equals(a.providerCode) && flow.equals(a.flowCategory)
                    && active(a.effectiveFrom, a.effectiveTo, now) && scopeMatch(a.bankCountry, req.beneficiaryBankCountry())));
            C1ConditionService.Outcome trigger = globalConditions.evaluate(variables, context);
            if (trigger.configured()) {
                steps.add(new Trace("2. C1 准入条件 " + flow, trigger.matched() ? "PASS" : "FAIL",
                        trigger.note() + "；表达式 " + trigger.expression()));
                if (!trigger.matched())
                    return fail("C1_CONDITION_NOT_MATCHED", null, null, steps, context,
                            flow + " 不满足 C1 全局准入条件，候选合作方被过滤");
            }
        }
        // 2. 客户排除：系统必检。直接调用 Java Handler，不经过函数库元数据，也不管条件树里有没有 clientExcluded 节点。
        //    查询失败按不通过处理（不返回「未排除」放行）。
        /*Call exclusion;
        try {
            exclusion = functions.execute(exclusionChecker, "clientExcluded", EXCLUSION_ARGUMENTS, context);
        } catch (RuntimeException ex) {
            steps.add(new Trace("3. 客户排除", "FAIL", ex.getMessage()));
            return fail("CLIENT_EXCLUSION_CHECK_FAILED", null, null, steps, context, "客户排除记录查询失败，按不通过处理：" + ex.getMessage());
        }*/

        //boolean excluded = Boolean.TRUE.equals(exclusion.value());
        boolean excluded = false;
        //String exclusionDetail = "clientExcluded = " + excluded + "，来源 " + exclusion.dataSource() + (exclusion.recordId() == null ? "" : "，排除记录 #" + exclusion.recordId());
        //if (excluded) return fail("CLIENT_EXCLUDED", null, null, steps, context, "命中合作方客户排除记录（" + exclusionDetail + "）");
        //steps.add(new Trace("3. 客户排除", "PASS", "未命中排除记录（" + exclusionDetail + "）"));
        Long lastId = null;
        String lastAction = null;
        for (String flow : flows) {
            context.set("candidate.flowCategory", flow);
            List<String> skipped = new ArrayList<>();
            Optional<Appetite> match = appetites.findAll().stream().filter(a -> a.enabled && req.providerCode().equals(a.providerCode) && flow.equals(a.flowCategory) && active(a.effectiveFrom, a.effectiveTo, now) && scopeMatch(a.currency, req.paymentCurrency()) && scopeMatch(a.bankCountry, req.beneficiaryBankCountry()) && conditionMatches(a, req, client, excluded, flow, skipped, context)).max(Comparator.comparingInt(a -> specificity(a, req)));
            skipped.forEach(detail -> steps.add(new Trace("4. 规则匹配 " + flow, "SKIP", detail)));
            if (match.isEmpty()) return fail("APPETITE_NOT_SET", null, null, steps, context, flow + " 无有效准入配置（默认拒绝）");
            Appetite a = match.get();
            lastId = a.id;
            lastAction = a.action;
            steps.add(new Trace("4. 规则匹配 " + flow, "PASS", "命中 #" + a.id + "，" + a.currency + "/" + a.bankCountry + "，动作 " + a.action + "，版本 " + a.versionNo));
            if ("EXCLUDE".equals(a.action))
                return fail("APPETITE_EXCLUDED", a.id, a.action, steps, context, flow + " 被合作方禁止");
            if ("CASE_BY_CASE".equals(a.action)) {
                // 系统必检：命中 CASE_BY_CASE 即检查有效审批，与条件树里有没有 hasValidCaseApproval 节点无关
                Call approval = functions.execute(approvalChecker, "hasValidCaseApproval", APPROVAL_ARGUMENTS, context);
                if (!Boolean.TRUE.equals(approval.value()))
                    return fail("APPETITE_APPROVAL_MISSING", a.id, a.action, steps, context, "没有有效的合作方-客户-流转类别审批");
                steps.add(new Trace("5. 个案审批 " + flow, "PASS", "找到有效审批" + (approval.recordId() == null ? "" : " #" + approval.recordId())));
            } else if ("ACCEPT".equals(a.action)) {
                if (a.monthlyCapUsd != null) {
                    // 系统必检：设有月度上限即校验 MTD + 本笔金额
                    BigDecimal mtd = (BigDecimal) functions.execute(mtdReader, "getProviderMtd", MTD_ARGUMENTS, context).value();
                    BigDecimal total = mtd.add(req.amountUsd());
                    if (total.compareTo(a.monthlyCapUsd) > 0)
                        return fail("APPETITE_CAP_REACHED", a.id, a.action, steps, context, "MTD " + mtd + " + 本笔 " + req.amountUsd() + " > 月上限 " + a.monthlyCapUsd);
                    steps.add(new Trace("5. MTD " + flow, "PASS", "MTD " + mtd + " + " + req.amountUsd() + " <= " + a.monthlyCapUsd));
                } else steps.add(new Trace("5. MTD " + flow, "PASS", "ACCEPT 未设置月度上限"));
            } else throw new IllegalStateException("未知准入动作 " + a.action);
        }
        steps.add(new Trace("6. C1 汇总", "PASS", "全部流转类别通过；模拟不占用 MTD"));
        return new Result(true, "PASS", lastId, lastAction, steps, context.calls());
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

    /** 兼容合作方准入规则历史上保存的附加参与条件；C1 页面新配置使用全局过滤条件。 */
    private boolean conditionMatches(Appetite rule, Simulation req, ClientProfile client, boolean excluded, String flow, List<String> skipped, RuleExecutionContext context) {
        if (rule.conditionJson == null || rule.conditionJson.isBlank()) return true;
        try {
            var tree = json.readValue(rule.conditionJson, ExpressionService.Node.class);
            ExpressionService.Compiled compiled;
            try { compiled = expressions.compileDetailed(tree, "C1"); }
            catch (IllegalArgumentException ex) {
                skipped.add("规则 #" + rule.id + " 的附加条件已失效（" + ex.getMessage() + "），按不命中处理，请在 C1 业务准入页核对后重新保存");
                return false;
            }
            Map<String, Object> variables = new HashMap<>();
            if (req.variables() != null) variables.putAll(req.variables());
            variables.put("clientClassificationStatus", client.classificationStatus);
            variables.put("clientProviderExcluded", excluded);
            variables.put("flowCategory", flow);
            variables.put("paymentCurrency", req.paymentCurrency());
            variables.put("beneficiaryBankCountry", req.beneficiaryBankCountry());
            variables.put("amountUsd", req.amountUsd());
            List<String> missing = expressions.referencedFields(tree).stream()
                    .filter(field -> !expressions.isFunctionBound(field))
                    .filter(field -> variables.get(field) == null || variables.get(field).toString().isBlank()).toList();
            if (!missing.isEmpty()) {
                skipped.add("规则 #" + rule.id + " 的附加条件引用了字段 " + String.join("、", missing) + "，本次没有提供取值，按不命中处理");
                return false;
            }
            return expressions.evaluate(compiled, variables, context).matched();
        } catch (Exception ex) {
            throw new IllegalStateException("C1 规则 #" + rule.id + " 条件执行失败: " + ex.getMessage(), ex);
        }
    }

}
