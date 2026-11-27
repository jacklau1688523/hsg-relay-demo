package com.hsg.relay.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * C1 业务准入执行器。每次判定固定按下面的顺序执行，首个失败项决定原因码：
 * <ol>
 *   <li>读取已发布的 C1 路径配置（{@link C1PathConfigService}）。没有已发布版本、版本读不出来、结构损坏都拒绝（C1_CONFIG_MISSING /
 *       C1_CONFIG_BROKEN），不会默认通过。</li>
 *   <li>按候选合作方 candidate.providerCode 选路径（FIRST_MATCH）：启用的专用路径里恰好一条绑定了它就用那条；没有就走 DEFAULT 路径。
 *       路径冲突（多条启用路径绑定同一合作方）拒绝；DEFAULT 不是自动通过，它同样要过自己的条件树。</li>
 *   <li>执行所选路径自己的条件树，每个客户流转类别分别求值；不满足即按这条路径的 failureReasonCode 过滤，不落到其它路径。</li>
 *   <li>必检（对每条路径都执行，不读函数库元数据，不看条件树里有没有对应节点）：客户分类 COMPLETE → 有效客户排除 →
 *       每个流转类别的准入规则四级匹配（APPETITE_MATCH）→ CASE_BY_CASE 的有效审批 → ACCEPT 的 MTD 上限。</li>
 * </ol>
 */
@Service
public class C1Service implements RequiredFunctionProvider {
    /**
     * 系统必检清单（代码级，只读）：C1 执行器在代码里直接调用这些检查对应的 Java Handler，
     * 不读取函数库元数据（停用、删除、改绑定都不影响），也不看条件树里有没有对应节点；页面只读展示。
     * 键为函数编码，值为强制落实的说明。FunctionMetadataService 据此把函数标为 REQUIRED。
     */
    public static final Map<String, String> REQUIRED_FUNCTIONS = declaredRequiredFunctions();

    /**
     * APPETITE_MATCH 的四个匹配级别，由低到高：下标 = (币种精确 ? 2 : 0) + (国家精确 ? 1 : 0)。
     * 同一合作方、同一流转类别下，先看最高的级别；某一级别里有准入规则自身的附加条件成立，就取这一级里编号最小的一条，不再看更低的级别。
     */
    public static final List<String> APPETITE_MATCH_LEVELS = List.of("ALL_ALL", "ALL_EXACT", "EXACT_ALL", "EXACT_EXACT");
    private static final Map<String, String> LEVEL_NAMES = Map.of(
            "ALL_ALL", "ALL/ALL", "ALL_EXACT", "ALL 币种 + 精确国家", "EXACT_ALL", "精确币种 + ALL 国家", "EXACT_EXACT", "精确币种 + 精确国家");
    /**
     * 旧版全局准入条件里由执行器注入取值的两个布尔字段（准入规则是否覆盖该币种 / 国家）。已由 APPETITE_MATCH 取代；
     * 只有回放引用它们的旧版本（迁移产生的 v1）时才按旧语义注入，新发布的配置不允许引用。
     * 清单与内置的字段定义都在 {@link ExpressionService#LEGACY_BOOLEAN_FIELDS}：规则 C1 编译这两个字段时不读字段库，回放不依赖字段库。
     */
    public static final List<String> LEGACY_BOOLEAN_FIELDS = ExpressionService.LEGACY_BOOLEAN_FIELDS;
    /** 客户档案没有流转类别时的缺省类别。 */
    public static final String DEFAULT_FLOW = "STANDARD";

    // 系统必检的入参：只使用受控路径
    private static final Map<String, Object> EXCLUSION_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");
    private static final Map<String, Object> APPROVAL_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId", "flowCategory", "$candidate.flowCategory");
    private static final Map<String, Object> MTD_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "flowCategory", "$candidate.flowCategory");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");

    private final ProviderRepository providers;
    private final AppetiteRepository appetites;
    private final ClientProfileRepository clients;
    private final CaseApprovalChecker approvalChecker;
    private final ProviderMtdReader mtdReader;
    private final ClientExclusionChecker exclusionChecker;
    private final FunctionExecutionService functions;
    private final ExpressionService expressions;
    private final C1PathConfigService pathConfig;
    private final ObjectMapper json;

    // 审批、MTD 和客户排除的查询在各自的 Handler 里（CaseApprovalChecker / ProviderMtdReader / ClientExclusionChecker），
    // 执行器的系统必检与条件节点共用它们的调用结果
    public C1Service(ProviderRepository p, AppetiteRepository a, ClientProfileRepository c, ExpressionService expressions,
                     ObjectMapper json,
                     CaseApprovalChecker approvalChecker, ProviderMtdReader mtdReader, ClientExclusionChecker exclusionChecker,
                     FunctionExecutionService functions, C1PathConfigService pathConfig) {
        providers = p;
        appetites = a;
        clients = c;
        this.approvalChecker = approvalChecker;
        this.mtdReader = mtdReader;
        this.exclusionChecker = exclusionChecker;
        this.functions = functions;
        this.expressions = expressions;
        this.pathConfig = pathConfig;
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
        required.put("findBestAppetiteRule", "每个流转类别按四级匹配准入规则，无有效匹配默认拒绝（APPETITE_NOT_SET）");
        required.put("hasValidCaseApproval", "命中 CASE_BY_CASE 规则时须有有效个案审批（APPETITE_APPROVAL_MISSING）");
        required.put("getProviderMtd", "命中设有月度上限的 ACCEPT 规则时校验 MTD + 本笔金额（APPETITE_CAP_REACHED）");
        return Collections.unmodifiableMap(required);
    }

    // variables：附加条件字段的模拟取值（字段编码 → 值）。除下列固定交易要素外，字段库里启用的 C1 字段都从这里取值。
    // configVersion：按哪个已发布的路径配置版本判定；为空取最新版本。回放历史决策时带上决策记录里的版本号。
    public record Simulation(String clientId, String providerCode, String paymentCurrency,
                             String beneficiaryBankCountry, BigDecimal amountUsd, Map<String, Object> variables, Integer configVersion) {
        @JsonCreator
        public Simulation {
        }

        public Simulation(String clientId, String providerCode, String paymentCurrency, String beneficiaryBankCountry, BigDecimal amountUsd) {
            this(clientId, providerCode, paymentCurrency, beneficiaryBankCountry, amountUsd, null, null);
        }

        public Simulation(String clientId, String providerCode, String paymentCurrency, String beneficiaryBankCountry, BigDecimal amountUsd,
                          Map<String, Object> variables) {
            this(clientId, providerCode, paymentCurrency, beneficiaryBankCountry, amountUsd, variables, null);
        }
    }

    public record Trace(String step, String status, String detail) {
    }

    /**
     * 某个流转类别的准入匹配结果：matchLevel 是命中规则所在的匹配级别（见 {@link #APPETITE_MATCH_LEVELS}）；
     * 没有命中时只有 flowCategory。
     */
    public record AppetiteDecision(String flowCategory, String matchLevel, Long ruleId, String action, String currency, String bankCountry) {
        public AppetiteDecision(String flowCategory, String matchLevel, Long ruleId, String action) {
            this(flowCategory, matchLevel, ruleId, action, null, null);
        }

        public AppetiteDecision(String flowCategory) {
            this(flowCategory, null, null, null, null, null);
        }
    }

    // functionCalls：本次判定里的全部业务函数调用（系统必检 + 条件节点 + 函数绑定字段），含入参、返回值、来源、耗时、复用标记。
    // pathCode / pathName / pathPriority / defaultPathHit：命中的路径；configVersion：判定用的已发布版本（草稿试算时为空）；
    // classificationStatus：客户分类状态；exclusionRecordId：客户排除命中的记录 ID；
    // appetiteMatchLevel：最终命中规则（matchedRuleId）所在的匹配级别；appetiteDecisions：每个流转类别的匹配结果；
    // providerCode：本次判定的候选合作方（结果自带判定对象，页面与调用方不必从请求里回找）。
    public record Result(boolean passed, String reasonCode, Long matchedRuleId, String matchedRuleAction,
                         List<Trace> steps, List<Call> functionCalls,
                         String pathCode, String pathName, Integer pathPriority, Integer configVersion, Boolean defaultPathHit,
                         String classificationStatus, String exclusionRecordId, String appetiteMatchLevel,
                         List<AppetiteDecision> appetiteDecisions, String providerCode) {
        public Result(boolean passed, String reasonCode, Long matchedRuleId, String matchedRuleAction, List<Trace> steps, List<Call> functionCalls) {
            this(passed, reasonCode, matchedRuleId, matchedRuleAction, steps, functionCalls, null, null, null, null, null, null, null, null, List.of(), null);
        }

        public Result(boolean passed, String reasonCode, Long matchedRuleId, String matchedRuleAction, List<Trace> steps) {
            this(passed, reasonCode, matchedRuleId, matchedRuleAction, steps, List.of());
        }
    }

    public Result evaluate(Simulation req) {
        return evaluate(req, LocalDateTime.now(BUSINESS_ZONE));
    }

    /** 按已发布的路径配置判定（默认最新版本；Simulation.configVersion 指定时按该版本回放）。 */
    public Result evaluate(Simulation req, LocalDateTime now) {
        Run run = new Run(req, now);
        C1PathConfigService.Loaded config;
        try {
            config = pathConfig.load(req.configVersion());
        } catch (RuntimeException ex) {
            // 已发布的版本读不出来 = 配置损坏：拒绝，不按「没有配置」处理，更不会默认通过
            return run.failAt("0. 路径配置", "C1_CONFIG_BROKEN", null, null,
                    "C1 路径配置无法读取" + versionLabel(req.configVersion()) + "：" + message(ex));
        }
        if (config == null) {
            // 回放指定的版本不存在是请求错误（不是「没有配置」），直接报错
            if (req.configVersion() != null) throw new IllegalArgumentException("C1 路径配置没有版本 v" + req.configVersion());
            return run.failAt("0. 路径配置", "C1_CONFIG_MISSING", null, null, "从未发布 C1 路径配置：所有候选合作方默认拒绝，请先发布路径配置");
        }
        run.config = config;
        run.rule = config.rule();
        run.steps.add(new Trace("0. 路径配置", "PASS", "已发布版本 v" + config.version()
                + (req.configVersion() == null ? "（最新）" : "（按指定版本回放）") + "，共 " + config.rule().paths().size()
                + " 条路径，启用 " + config.rule().paths().stream().filter(C1PathRule.Path::on).count() + " 条"));
        return run.execute();
    }

    /** 用页面上的草稿试算：只试算、不落库，结果里的 configVersion 为空（草稿不是已发布版本）。 */
    public Result evaluateDraft(Simulation req, JsonNode draft) {
        return evaluateDraft(req, draft, LocalDateTime.now(BUSINESS_ZONE));
    }

    public Result evaluateDraft(Simulation req, JsonNode draft, LocalDateTime now) {
        Run run = new Run(req, now);
        try {
            run.rule = pathConfig.simulationRule(draft);
        } catch (IllegalArgumentException ex) {
            return run.failAt("0. 路径配置", "C1_CONFIG_BROKEN", null, null, "草稿不是有效的路径配置：" + message(ex));
        }
        run.steps.add(new Trace("0. 路径配置", "PASS", "草稿（未发布），共 " + run.rule.paths().size() + " 条路径，启用 "
                + run.rule.paths().stream().filter(C1PathRule.Path::on).count() + " 条"));
        return run.execute();
    }

    public Result evaluateDraft(Simulation req, C1PathRule draft) {
        return evaluateDraft(req, draft == null ? null : json.valueToTree(draft));
    }

    private static String versionLabel(Integer version) {
        return version == null ? "（最新版本）" : "（版本 v" + version + "）";
    }

    private static String message(Throwable ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /** 一次判定的过程状态。 */
    private final class Run {
        final Simulation req;
        final LocalDateTime now;
        final List<Trace> steps = new ArrayList<>();
        final List<AppetiteDecision> decisions = new ArrayList<>();
        final Provider provider;
        RuleExecutionContext context;
        C1PathRule rule;
        C1PathConfigService.Loaded config;
        ClientProfile client;
        List<String> flows;
        C1PathRule.Path path;
        boolean defaultPathHit;
        String exclusionRecordId;
        String appetiteMatchLevel;
        private ExpressionService.Compiled compiled;
        private Set<String> referenced;

        Run(Simulation req, LocalDateTime now) {
            this.req = req;
            this.now = now;
            provider = request(req);
            // C1 自身的判断与合作方 LIVE 过滤分开：这里只做 C1。
            // 请求级上下文：本次判定内相同函数 + 相同参数只查询一次；候选合作方、交易要素、决策时间都来自本次请求
            context = RuleExecutionContext.c1(req.providerCode(), req.clientId(), req.paymentCurrency(), req.beneficiaryBankCountry(), req.amountUsd(), now);
        }

        /** 请求校验：字段缺失、金额无效、合作方不存在都是请求错误（HTTP 400），不产生判定结果；在读配置之前执行。 */
        private Provider request(Simulation req) {
            if (req == null || req.amountUsd() == null || req.amountUsd().signum() < 0 || req.clientId() == null || req.providerCode() == null
                    || req.paymentCurrency() == null || req.beneficiaryBankCountry() == null)
                throw new IllegalArgumentException("模拟请求字段缺失或金额无效");
            Provider provider = providers.findById(req.providerCode()).orElse(null);
            if (provider == null) throw new IllegalArgumentException("合作方不存在");
            return provider;
        }

        Result execute() {
            // 1. 客户档案
            client = clients.findById(req.clientId()).orElse(null);
            if (client == null) return failAt("1. 客户档案", "CLIENT_NOT_FOUND", null, null, "客户 " + req.clientId() + " 的档案不存在");
            flows = client.flowCategories == null ? List.of() : Arrays.stream(client.flowCategories.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).distinct().toList();
            if (flows.isEmpty()) flows = List.of(DEFAULT_FLOW);
            steps.add(new Trace("1. 客户档案", "PASS", "已读取客户档案；分类状态 " + client.classificationStatus + "；流转类别 " + String.join("、", flows)));

            // 2. 路径选择：按候选合作方选一条路径
            Result blocked = selectPath();
            if (blocked != null) return blocked;

            // 2. 路径条件：只执行所选路径自己的条件树，每个流转类别分别求值
            for (String flow : flows) {
                Result failed = pathCondition(flow);
                if (failed != null) return failed;
            }

            // 3. 客户分类（必检）
            if (!"COMPLETE".equals(client.classificationStatus))
                return failAt("3. 客户分类", "CLIENT_NOT_CLASSIFIED", null, null,
                        "客户 " + client.clientId + " 的分类状态为 " + client.classificationStatus + "，仅 COMPLETE 可继续");
            steps.add(new Trace("3. 客户分类", "PASS", "分类状态 COMPLETE"));

            // 3. 客户排除（必检）
            Call exclusion;
            try {
                exclusion = functions.execute(exclusionChecker, "clientExcluded", EXCLUSION_ARGUMENTS, context);
            } catch (RuntimeException ex) {
                // 排除名单查不出来 = 不知道客户有没有被排除：拒绝，不按「未排除」放行
                return failAt("3. 客户排除", "CLIENT_EXCLUSION_CHECK_FAILED", null, null, "客户排除检查失败：" + message(ex));
            }
            if (Boolean.TRUE.equals(exclusion.value())) {
                exclusionRecordId = exclusion.recordId();
                return failAt("3. 客户排除", "CLIENT_EXCLUDED", null, null, "客户 " + client.clientId + " 在合作方 " + provider.code + " 的有效排除记录"
                        + (exclusion.recordId() == null ? "" : " #" + exclusion.recordId()) + "，候选合作方被过滤");
            }
            steps.add(new Trace("3. 客户排除", "PASS", "客户 " + client.clientId + " 不在合作方 " + provider.code + " 的有效排除名单中"));

            // 3. 规则匹配 → 4. 个案审批 / MTD（必检，逐个流转类别）
            Long lastId = null;
            String lastAction = null;
            for (String flow : flows) {
                context.set("candidate.flowCategory", flow);
                Appetite a = matchAppetite(flow);
                if (a == null)
                    return failAt("3. 规则匹配 " + flow, "APPETITE_NOT_SET", null, null, flow + " 无有效准入配置（默认拒绝）");
                lastId = a.id;
                lastAction = a.action;
                if ("EXCLUDE".equals(a.action)) return fail("APPETITE_EXCLUDED", a.id, a.action, flow + " 被合作方禁止");
                if ("CASE_BY_CASE".equals(a.action)) {
                    // 系统必检：命中 CASE_BY_CASE 即检查有效审批，与条件树里有没有 hasValidCaseApproval 节点无关
                    Call approval = functions.execute(approvalChecker, "hasValidCaseApproval", APPROVAL_ARGUMENTS, context);
                    if (!Boolean.TRUE.equals(approval.value()))
                        return failAt("4. 个案审批 " + flow, "APPETITE_APPROVAL_MISSING", a.id, a.action, "没有有效的合作方-客户-流转类别审批");
                    steps.add(new Trace("4. 个案审批 " + flow, "PASS", "找到有效审批" + (approval.recordId() == null ? "" : " #" + approval.recordId())));
                } else if ("ACCEPT".equals(a.action)) {
                    if (a.monthlyCapUsd != null) {
                        // 系统必检：设有月度上限即校验 MTD + 本笔金额
                        BigDecimal mtd = (BigDecimal) functions.execute(mtdReader, "getProviderMtd", MTD_ARGUMENTS, context).value();
                        BigDecimal total = mtd.add(req.amountUsd());
                        if (total.compareTo(a.monthlyCapUsd) > 0)
                            return failAt("4. MTD " + flow, "APPETITE_CAP_REACHED", a.id, a.action, "MTD " + mtd + " + 本笔 " + req.amountUsd() + " > 月上限 " + a.monthlyCapUsd);
                        steps.add(new Trace("4. MTD " + flow, "PASS", "MTD " + mtd + " + " + req.amountUsd() + " <= " + a.monthlyCapUsd));
                    } else steps.add(new Trace("4. MTD " + flow, "PASS", "ACCEPT 未设置月度上限"));
                } else throw new IllegalStateException("未知准入动作 " + a.action);
            }
            steps.add(new Trace("5. C1 汇总", "PASS", "全部流转类别通过路径 " + path.pathCode() + " 的条件和 C1 必检；模拟不占用 MTD"));
            return result(true, "PASS", lastId, lastAction);
        }

        /** 按候选合作方选路径。返回 null 表示已选中 path；否则是拒绝结果（配置损坏、路径冲突、找不到路径），路径选择这一步记为 FAIL。 */
        private Result selectPath() {
            List<C1PathRule.Path> enabled = rule.paths().stream().filter(C1PathRule.Path::on).toList();
            List<C1PathRule.Path> defaults = enabled.stream().filter(p -> C1PathRule.DEFAULT_PATH.equals(p.pathCode())).toList();
            if (defaults.size() > 1)
                return failAt("2. 路径选择", "C1_CONFIG_BROKEN", null, null, "C1 路径配置" + versionName() + " 有 " + defaults.size() + " 条启用的 DEFAULT 路径，无法判定");
            List<C1PathRule.Path> bound = enabled.stream()
                    .filter(p -> !C1PathRule.DEFAULT_PATH.equals(p.pathCode()) && p.providerCodes().contains(provider.code)).toList();
            if (bound.size() > 1)
                return failAt("2. 路径选择", "C1_PATH_CONFLICT", null, null, "合作方 " + provider.code + " 同时绑定在启用的路径 "
                        + String.join("、", bound.stream().map(C1PathRule.Path::pathCode).toList()) + "：路径冲突，候选合作方被过滤；请修正配置后发布新版本");
            if (bound.size() == 1) {
                path = bound.get(0);
                steps.add(new Trace("2. 路径选择", "PASS", "合作方 " + provider.code + " 命中专用路径 " + path.pathCode() + "（" + path.pathName()
                        + "），优先级 " + path.priority() + "；规则版本" + versionName()));
            } else if (defaults.size() == 1) {
                path = defaults.get(0);
                defaultPathHit = true;
                List<String> disabled = rule.paths().stream()
                        .filter(p -> !C1PathRule.DEFAULT_PATH.equals(p.pathCode()) && !p.on() && p.providerCodes().contains(provider.code))
                        .map(C1PathRule.Path::pathCode).toList();
                steps.add(new Trace("2. 路径选择", "PASS", "合作方 " + provider.code + " 没有任何启用的专用路径，命中 DEFAULT 路径（" + path.pathName()
                        + "），优先级 " + path.priority() + "；规则版本" + versionName()
                        + (disabled.isEmpty() ? "" : "；绑定它的专用路径 " + String.join("、", disabled) + " 已停用")));
            } else {
                steps.add(new Trace("2. 路径选择", "FAIL", "合作方 " + provider.code + " 没有启用的专用路径，DEFAULT 路径也未启用"));
                return fail("C1_PATH_NOT_FOUND", null, null, "合作方 " + provider.code + " 没有可用路径（无启用的专用路径，DEFAULT 路径未启用），候选合作方被过滤");
            }
            return null;
        }

        /** 所选路径的条件树在某个流转类别上的求值。返回 null 表示满足；否则是这条路径自己的失败原因码，或配置损坏。 */
        private Result pathCondition(String flow) {
            String step = "2. 路径条件 " + flow;
            if (compiled == null && !C1PathConfigService.isEmptyTree(path.conditionAst())) {
                // 已发布的条件树在发布之后失效（字段停用、函数停用、绑定变更）= 配置损坏，不是「条件不满足」
                try {
                    compiled = expressions.compileDetailed(path.conditionAst(), "C1");
                } catch (RuntimeException ex) {
                    steps.add(new Trace(step, "FAIL", "路径 " + path.pathCode() + " 的条件树无法编译：" + message(ex)));
                    return fail("C1_CONFIG_BROKEN", null, null, "路径 " + path.pathCode() + " 的条件树无法编译" + versionName() + "：" + message(ex));
                }
                referenced = expressions.referencedFields(path.conditionAst());
            }
            if (compiled == null) {
                steps.add(new Trace(step, "FAIL", "路径 " + path.pathCode() + " 已启用但没有条件树"));
                return fail("C1_CONFIG_BROKEN", null, null, "路径 " + path.pathCode() + " 已启用但没有条件树" + versionName() + "：启用的路径必须有自己的条件");
            }
            context.set("candidate.flowCategory", flow);
            // 变量：请求给出的附加字段取值在前，执行器注入的固定要素覆盖它们（客户分类状态、当前流转类别、交易要素）
            Map<String, Object> variables = new HashMap<>();
            if (req.variables() != null) variables.putAll(req.variables());
            variables.put("clientClassificationStatus", client.classificationStatus);
            variables.put("flowCategory", flow);
            variables.put("paymentCurrency", req.paymentCurrency());
            variables.put("beneficiaryBankCountry", req.beneficiaryBankCountry());
            variables.put("amountUsd", req.amountUsd());
            // 旧版本回放：条件树引用了旧版布尔字段时按旧语义注入（新发布的配置不允许引用它们）
            for (String legacy : LEGACY_BOOLEAN_FIELDS)
                if (referenced.contains(legacy)) variables.put(legacy, legacyEnabled(legacy, flow));
            List<String> missing = referenced.stream().filter(expressions::requiresInput)
                    .filter(field -> variables.get(field) == null || variables.get(field).toString().isBlank()).toList();
            if (!missing.isEmpty()) {
                steps.add(new Trace(step, "FAIL", "字段 " + String.join("、", missing) + " 没有取值，路径 " + path.pathCode() + " 的条件不满足"));
                return fail(path.failureReasonCode(), null, null, flow + " 不满足路径 " + path.pathCode() + " 的条件（字段 "
                        + String.join("、", missing) + " 没有取值），候选合作方被过滤");
            }
            boolean matched;
            try {
                matched = expressions.evaluate(compiled, variables, context, "C1 路径条件 " + path.pathCode()).matched();
            } catch (RuntimeException ex) {
                // 条件执行途中的运行时失败（函数查询失败、字段取值非法）不是判定结论，按请求错误上报
                throw new IllegalStateException("C1 路径 " + path.pathCode() + " 条件执行失败: " + message(ex), ex);
            }
            if (!matched) {
                steps.add(new Trace(step, "FAIL", "路径 " + path.pathCode() + " 的表达式不满足：" + compiled.readable()));
                return fail(path.failureReasonCode(), null, null, flow + " 不满足路径 " + path.pathCode() + " 的条件，候选合作方被过滤");
            }
            steps.add(new Trace(step, "PASS", "路径 " + path.pathCode() + " 的条件满足；表达式 " + compiled.readable()));
            return null;
        }

        /** 旧版布尔字段的取值（与旧执行器一致）：该合作方在该流转类别下有有效的准入规则覆盖这个币种 / 国家。 */
        private boolean legacyEnabled(String field, String flow) {
            boolean currency = "appetiteCurrencyEnabled".equals(field);
            return appetites.findAll().stream().anyMatch(a -> a.enabled && provider.code.equals(a.providerCode) && flow.equals(a.flowCategory)
                    && active(a.effectiveFrom, a.effectiveTo, now)
                    && scopeMatch(currency ? a.currency : a.bankCountry, currency ? req.paymentCurrency() : req.beneficiaryBankCountry()));
        }

        /**
         * APPETITE_MATCH：该合作方在该流转类别下、决策时间有效、覆盖本笔币种和国家的准入规则，按匹配级别从高到低找
         * 第一条自身附加条件成立的规则；高一级别里没有成立的规则才看低一级别。没有命中时记录 SKIP 明细并返回 null。
         */
        private Appetite matchAppetite(String flow) {
            List<Appetite> rows = appetites.findAll().stream()
                    .filter(a -> a.enabled && provider.code.equals(a.providerCode) && flow.equals(a.flowCategory)
                            && active(a.effectiveFrom, a.effectiveTo, now)
                            && scopeMatch(a.currency, req.paymentCurrency()) && scopeMatch(a.bankCountry, req.beneficiaryBankCountry()))
                    .sorted(Comparator.comparing(a -> a.id)).toList();
            Appetite match = null;
            String level = null;
            List<String> skipped = new ArrayList<>();
            for (int index = APPETITE_MATCH_LEVELS.size() - 1; index >= 0 && match == null; index--) {
                for (Appetite a : rows) {
                    if (specificity(a, req) != index) continue;
                    if (conditionMatches(a, req, client, flow, skipped, context)) {
                        match = a;
                        level = APPETITE_MATCH_LEVELS.get(index);
                        break;
                    }
                }
            }
            skipped.forEach(detail -> steps.add(new Trace("3. 规则匹配 " + flow, "SKIP", detail)));
            if (match == null) {
                decisions.add(new AppetiteDecision(flow));
                return null;
            }
            appetiteMatchLevel = level;
            decisions.add(new AppetiteDecision(flow, level, match.id, match.action, match.currency, match.bankCountry));
            steps.add(new Trace("3. 规则匹配 " + flow, "PASS", "命中 #" + match.id + "，" + match.currency + "/" + match.bankCountry + "，动作 "
                    + match.action + "，版本 " + match.versionNo + "，匹配级别 " + level + "（" + LEVEL_NAMES.get(level) + "）"));
            return match;
        }

        private String versionName() {
            return config == null ? "（草稿）" : " v" + config.version();
        }

        Result fail(String code, Long ruleId, String action, String detail) {
            steps.add(new Trace("失败原因", "FAIL", detail));
            return result(false, code, ruleId, action);
        }

        /** 某一步不通过：这一步记为 FAIL（过程里看得出停在哪一步），再给出最终原因。 */
        Result failAt(String step, String code, Long ruleId, String action, String detail) {
            steps.add(new Trace(step, "FAIL", detail));
            return fail(code, ruleId, action, detail);
        }

        Result result(boolean passed, String code, Long ruleId, String action) {
            return new Result(passed, code, ruleId, action, List.copyOf(steps), context == null ? List.of() : context.calls(),
                    path == null ? null : path.pathCode(), path == null ? null : path.pathName(), path == null ? null : path.priority(),
                    config == null ? null : config.version(), path == null ? null : defaultPathHit,
                    client == null ? null : client.classificationStatus, exclusionRecordId, appetiteMatchLevel, List.copyOf(decisions),
                    provider.code);
        }
    }

    private boolean active(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        return start != null && !now.isBefore(start) && (end == null || now.isBefore(end));
    }

    private boolean scopeMatch(String configured, String actual) {
        return "ALL".equals(configured) || configured.equalsIgnoreCase(actual);
    }

    /** 匹配级别下标：精确币种 +2、精确国家 +1（规则须先通过币种 / 国家的范围过滤，所以非 ALL 即精确）。 */
    private int specificity(Appetite a, Simulation r) {
        return (a.currency.equalsIgnoreCase(r.paymentCurrency()) ? 2 : 0) + (a.bankCountry.equalsIgnoreCase(r.beneficiaryBankCountry()) ? 1 : 0);
    }

    /**
     * 合作方准入规则历史上保存的附加参与条件；C1 页面新配置使用路径条件树。
     * clientProviderExcluded 与其它字段一样按元数据里配置的取值来源取值（函数绑定 / 请求输入），执行器不再单独计算并注入。
     */
    private boolean conditionMatches(Appetite rule, Simulation req, ClientProfile client, String flow, List<String> skipped, RuleExecutionContext context) {
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
            variables.put("flowCategory", flow);
            variables.put("paymentCurrency", req.paymentCurrency());
            variables.put("beneficiaryBankCountry", req.beneficiaryBankCountry());
            variables.put("amountUsd", req.amountUsd());
            List<String> missing = expressions.referencedFields(tree).stream()
                    .filter(expressions::requiresInput)
                    .filter(field -> variables.get(field) == null || variables.get(field).toString().isBlank()).toList();
            if (!missing.isEmpty()) {
                skipped.add("规则 #" + rule.id + " 的附加条件引用了字段 " + String.join("、", missing) + "，本次没有提供取值，按不命中处理");
                return false;
            }
            return expressions.evaluate(compiled, variables, context, "C1 准入规则 #" + rule.id).matched();
        } catch (Exception ex) {
            throw new IllegalStateException("C1 规则 #" + rule.id + " 条件执行失败: " + ex.getMessage(), ex);
        }
    }

}
