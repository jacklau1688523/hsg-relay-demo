package com.hsg.relay.routing;

import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.RequiredFunctionProvider;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.routing.RoutingConfig.Reach;
import com.hsg.relay.service.ScoreRuleEngine;
import com.hsg.relay.service.ScoreRuleService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C5 评分执行器：把「C3 选中的覆盖行 → calculateValueDate → 已发布的评分规则 → FIRST_MATCH 命中 → 分数」串起来。
 * 每个候选路由调用一次，顺序是：
 * <ol>
 *   <li>没有 C3 匹配结果：直接返回 NO_REACH（起息日和分数都不计算，更不会另挑一条覆盖行）；</li>
 *   <li>执行 calculateValueDate（{@link CalculateValueDateFunction}，系统必检）：得到最早可实现起息日、候选起息日、延迟工作日数；</li>
 *   <li>取决策时间生效的已发布 C5 评分规则版本（{@link ScoreRuleService#effectiveAt}）；</li>
 *   <li>把日期计算结果作为条件输入字段，交给 {@link ScoreRuleEngine} 按优先级匹配评分组。</li>
 * </ol>
 * 不是 SCORED 时 {@link ScoreResult#score()} 为 null，调用方按「评分不可计算」处理。C5 只打分，不剔除候选、不改变资金或交易状态。
 */
@Service
public class C5ScoreService implements RequiredFunctionProvider {
    public static final String RULE_CODE = "C5";
    public static final String FUNCTION_CODE = CalculateValueDateFunction.CODE;
    /** C5 的最终分数：评分结果，只出现在输出里，不能作为评分条件的输入。 */
    public static final String OUTPUT_FIELD = "c5SpeedScore";

    /** 评分条件可以引用的输入字段，全部由 calculateValueDate 一次算出并写入求值变量（与元数据字段库的编码一致）。 */
    public static final Set<String> INPUT_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(List.of(
            "requestedValueDate", "providerBusinessDay", "beforeCutoff", "valueDateOffsetDays",
            "earliestValueDate", "candidateValueDate", "delayBusinessDays")));

    /**
     * 发布前穷举覆盖范围用的取值：可枚举的输入字段 → 探测值。延迟工作日数覆盖 0–60 以及 120、366（远期请求起息日），
     * 偏移覆盖配置允许的 0–5。日期字段和函数条件无法穷举，不在其中。
     */
    public static final Map<String, List<Object>> PROBE_DOMAIN = probeDomain();

    private final ScoreRuleService rules;
    private final ScoreRuleEngine engine;
    private final FunctionExecutionService functions;
    private final CalculateValueDateFunction handler;
    private final Clock clock;
    // 已发布版本只增不改，编译结果按「规则编码:版本号:规则内容」缓存（内容也进键，版本内容被回滚或替换时不会串用）
    private final Map<String, ScoreRuleEngine.Prepared> compiled = new ConcurrentHashMap<>();

    public C5ScoreService(ScoreRuleService rules, ScoreRuleEngine engine, FunctionExecutionService functions,
                          CalculateValueDateFunction handler, Clock clock) {
        this.rules = rules;
        this.engine = engine;
        this.functions = functions;
        this.handler = handler;
        this.clock = clock;
    }

    /** 一次 C5 评分：日期计算结果 + 评分结果。没有 C3 匹配结果时两者都为空。 */
    public record Outcome(C5Calculator.Result calculation, boolean requestedValueDateDefaulted, ScoreResult score) {
        public boolean scored() {
            return score != null && score.scored();
        }
    }

    @Override
    public String ruleCode() {
        return RULE_CODE;
    }

    @Override
    public Map<String, String> requiredFunctions() {
        return Map.of(FUNCTION_CODE, "C5 评分执行器在代码里调用：按 C3 最终选中的覆盖行计算最早可实现起息日、候选起息日和延迟工作日数，"
                + "每个候选只计算一次，结果供全部评分组使用。返回结构不能作为条件节点。");
    }

    /** 用决策时间生效的已发布版本评分。 */
    public Outcome evaluate(ReachMatch match, String providerCode, String clientId, String paymentCurrency,
                            LocalDate requestedValueDate, LocalDateTime decisionTime) {
        return evaluate(match, providerCode, clientId, paymentCurrency, requestedValueDate, decisionTime,
                rules.effectiveAt(RULE_CODE, decisionTime));
    }

    /** 用指定的已发布版本评分（回放历史版本时使用；rule 为空表示该时间没有生效版本）。 */
    public Outcome evaluate(ReachMatch match, String providerCode, String clientId, String paymentCurrency,
                            LocalDate requestedValueDate, LocalDateTime decisionTime, ScoreRuleService.PublishedView rule) {
        if (decisionTime == null) decisionTime = LocalDateTime.now(clock);
        Map<String, Object> details = new LinkedHashMap<>();
        if (match == null)
            return new Outcome(null, false, new ScoreResult(RULE_CODE, "NO_REACH", null, null, rule == null ? null : rule.version(), List.of(),
                    "C3 未匹配覆盖行，C5 无法计算起息日和分数；这不是 C5 拒绝候选", details));
        Reach reach = match.reach();
        details.put("reach", reachDetail(match));
        Integer version = rule == null ? null : rule.version();
        C5Calculator.Result calculation = null;
        boolean defaulted = false;
        try {
            // 1. 日期计算：每个候选一次。覆盖行通过执行上下文交给函数，函数只核对、不挑选
            RuleExecutionContext context = RuleExecutionContext.c5(providerCode, clientId, paymentCurrency, requestedValueDate, decisionTime);
            context.set("context.matchedReachRowId", match.rowIndex());
            context.set("context.matchedReachRuleVersion", match.c3ConditionVersion());
            context.supply(ReachMatch.CONTEXT_KEY, match);
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("providerCode", "$candidate.providerCode");
            arguments.put("paymentCurrency", "$transaction.paymentCurrency");
            arguments.put("decisionTime", "$context.decisionTime");
            arguments.put("matchedReachRowId", "$context.matchedReachRowId");
            arguments.put("matchedReachRuleVersion", "$context.matchedReachRuleVersion");
            // HSG 没有上送请求起息日时不传：函数按业务规格默认取决策日的香港日期，并记录「已默认」
            if (requestedValueDate != null) arguments.put("requestedValueDate", requestedValueDate.toString());
            FunctionExecutionService.Call call = functions.execute(handler, FUNCTION_CODE, arguments, context);
            CalculateValueDateFunction.Output output = (CalculateValueDateFunction.Output) call.value();
            calculation = output.calculation();
            defaulted = output.requestedValueDateDefaulted();
            details.put("function", Map.of("code", FUNCTION_CODE, "durationMs", call.durationMs(), "recordId", call.recordId()));
            details.put("decisionTime", decisionTime.toString());

            // 2. 评分规则：没有生效的已发布版本时只返回日期计算结果，不给分数
            if (rule == null)
                return new Outcome(calculation, defaulted, new ScoreResult(RULE_CODE, "NOT_PUBLISHED", null, null, null,
                        List.of(), "没有在决策时间 " + decisionTime + " 生效的已发布 C5 评分规则版本，未给出分数", details));
            if (!rule.rule().ruleCode().equals(RULE_CODE)) throw new IllegalStateException("评分规则版本不属于 " + RULE_CODE);
            ScoreResult score = engine.evaluate(prepare(rule), variables(calculation, reach), context);
            details.putAll(score.details());
            return new Outcome(calculation, defaulted, new ScoreResult(RULE_CODE, score.status(), score.score(),
                    score.matchedGroupCode(), rule.version(), score.groups(), score.message(), details));
        } catch (RuntimeException ex) {
            // 日期计算或条件求值出错：明确记为 ERROR，不当作 0 分，也不中断整笔路由；已算出的日期结果照常返回
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            return new Outcome(calculation, defaulted, new ScoreResult(RULE_CODE, "ERROR", null, null, version, List.of(),
                    "C5 评分执行出错，未给出分数：" + reason, details));
        }
    }

    /** C3 匹配记录（审计用）：选中的覆盖行、行号、C3 条件版本、路由配置版本。 */
    static Map<String, Object> reachDetail(ReachMatch match) {
        Reach reach = match.reach();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rowIndex", match.rowIndex());
        detail.put("provider", reach.provider());
        detail.put("currency", reach.currency());
        detail.put("country", reach.country());
        detail.put("region", reach.region());
        detail.put("zone", reach.zone());
        detail.put("cutoff", reach.cutoff().toString());
        detail.put("offsetDays", reach.offsetDays());
        detail.put("holidays", reach.holidays().stream().map(LocalDate::toString).toList());
        detail.put("c3ConditionVersion", match.c3ConditionVersion());
        detail.put("configRevision", match.configRevision());
        return detail;
    }

    /** 页面「校验规则」：与发布同一套检查。 */
    public ScoreRuleEngine.PublishCheck check(com.fasterxml.jackson.databind.JsonNode rule) {
        return rules.check(rule, RULE_CODE, INPUT_FIELDS, PROBE_DOMAIN);
    }

    private ScoreRuleEngine.Prepared prepare(ScoreRuleService.PublishedView rule) {
        return compiled.computeIfAbsent(RULE_CODE + ":" + rule.version() + ":" + rule.rule().hashCode(),
                key -> engine.compile(rule.rule(), rule.version(), INPUT_FIELDS));
    }

    /** 日期计算结果 → 评分条件的输入字段。日期用 ISO 字符串，与条件值的写法一致。 */
    static Map<String, Object> variables(C5Calculator.Result r, Reach reach) {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("requestedValueDate", r.requestedValueDate().toString());
        vars.put("providerBusinessDay", r.currentBusinessDay());
        vars.put("beforeCutoff", r.beforeCutoff());
        vars.put("valueDateOffsetDays", reach.offsetDays());
        vars.put("earliestValueDate", r.earliestValueDate().toString());
        vars.put("candidateValueDate", r.candidateValueDate().toString());
        vars.put("delayBusinessDays", r.delayBusinessDays());
        return Collections.unmodifiableMap(vars);
    }

    private static Map<String, List<Object>> probeDomain() {
        List<Object> delays = new ArrayList<>();
        for (int d = 0; d <= 60; d++) delays.add(d);
        delays.add(120);
        delays.add(366);
        Map<String, List<Object>> domain = new LinkedHashMap<>();
        domain.put("delayBusinessDays", List.copyOf(delays));
        domain.put("valueDateOffsetDays", List.of(0, 1, 2, 3, 4, 5));
        domain.put("providerBusinessDay", List.of("true", "false"));
        domain.put("beforeCutoff", List.of("true", "false"));
        return Collections.unmodifiableMap(domain);
    }
}
