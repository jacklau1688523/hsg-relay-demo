package com.hsg.relay.service;

import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.routing.ScoreResult;
import com.hsg.relay.routing.ScoreRule;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 评分规则引擎（ruleType = SCORE，matchMode = FIRST_MATCH）。只做三件事：编译校验、发布前检查、求值。
 * <ul>
 *   <li>评分组的条件就是现有条件树 {@link ExpressionService.Node}（FIELD / FUNCTION / GROUP + AND/OR），
 *       由 {@link ExpressionService} 编译和求值，这里不另写一套解析；</li>
 *   <li>FIRST_MATCH：按 priority 升序逐个判断已启用的评分组，第一个命中的组给出分数，后面的不再判断；</li>
 *   <li>没有任何评分组命中时返回 NOT_MATCHED（score 为 null），不默认满分、也不给任意分数。</li>
 * </ul>
 * 引擎本身不感知 C5：可用的条件输入字段、发布前探测用的取值范围都由调用方传入。
 */
@Service
public class ScoreRuleEngine {
    public static final String RULE_TYPE = "SCORE";
    public static final String MATCH_MODE = "FIRST_MATCH";
    public static final int MAX_GROUPS = 50;
    private static final int MIN_SCORE = 0, MAX_SCORE = 100;

    private final ExpressionService expressions;

    public ScoreRuleEngine(ExpressionService expressions) {
        this.expressions = expressions;
    }

    /** 已编译的评分组。 */
    public record PreparedGroup(ScoreRule.Group group, ExpressionService.Compiled condition) {
        public String code() {
            return group.groupCode();
        }
    }

    /** 已编译的评分规则：enabled 是已启用评分组按 priority 升序排列的清单。版本号只在发布快照里有意义（草稿为 0）。 */
    public record Prepared(ScoreRule rule, int version, List<PreparedGroup> enabled) {
        public boolean enabledRule() {
            return Boolean.TRUE.equals(rule.enabled());
        }
    }

    /**
     * 结构校验并编译：规则类型 / 匹配方式、分数范围、评分组编码与优先级唯一、分数为范围内的整数、条件可编译，
     * 条件引用的字段必须在 allowedInputs 里。草稿保存和发布都走这里；不满足抛参数错误（消息可直接展示）。
     */
    public Prepared compile(ScoreRule rule, int version, Set<String> allowedInputs) {
        if (rule == null) throw new IllegalArgumentException("评分规则不能为空");
        if (!RULE_TYPE.equals(rule.ruleType())) throw new IllegalArgumentException("评分规则的规则类型必须为 " + RULE_TYPE);
        if (!MATCH_MODE.equals(rule.matchMode())) throw new IllegalArgumentException("评分规则的匹配方式必须为 " + MATCH_MODE);
        ScoreRule.ScoreRange range = rule.scoreRange();
        if (range == null || range.min() == null || range.max() == null || range.min() < MIN_SCORE || range.max() > MAX_SCORE || range.min() >= range.max())
            throw new IllegalArgumentException("分数范围必须在 " + MIN_SCORE + "–" + MAX_SCORE + " 之间，且最小值小于最大值");
        if (rule.groups() == null || rule.groups().size() > MAX_GROUPS)
            throw new IllegalArgumentException("评分组最多 " + MAX_GROUPS + " 个");
        Set<String> codes = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        List<PreparedGroup> enabled = new ArrayList<>();
        for (ScoreRule.Group g : rule.groups()) {
            String where = "评分组 " + (g == null || g.groupCode() == null ? "（未命名）" : g.groupCode());
            if (g == null || g.groupCode() == null || !g.groupCode().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
                throw new IllegalArgumentException(where + "：评分组 ID 须以字母或数字开头，仅含字母、数字、下划线、点、横线，最多64位");
            if (g.name() == null || g.name().isBlank() || g.name().length() > 80)
                throw new IllegalArgumentException(where + "：评分组名称必填，最多80位");
            if (!codes.add(g.groupCode())) throw new IllegalArgumentException("评分组 ID 重复：" + g.groupCode());
            if (g.priority() == null) throw new IllegalArgumentException(where + "：优先级必填");
            if (!priorities.add(g.priority()))
                throw new IllegalArgumentException("优先级重复：" + g.priority() + "（" + where + "）；每个评分组的优先级必须唯一，才有确定的命中顺序");
            if (g.result() == null || !"SCORE".equals(g.result().type()) || g.result().score() == null)
                throw new IllegalArgumentException(where + "：必须设置命中后的分数");
            Integer score = g.result().score();
            if (score < range.min() || score > range.max())
                throw new IllegalArgumentException(where + "：分数 " + score + " 超出合法范围 " + range.min() + "–" + range.max() + "，不能发布");
            if (g.condition() == null) throw new IllegalArgumentException(where + "：条件不能为空");
            String error = null;
            ExpressionService.Compiled compiled = null;
            try {
                compiled = expressions.compileDetailed(g.condition(), rule.ruleCode());
            } catch (IllegalArgumentException ex) {
                error = ex.getMessage();
            }
            if (compiled == null) throw new IllegalArgumentException(where + "：条件无效 — " + error);
            for (String field : expressions.referencedFields(g.condition()))
                if (allowedInputs != null && !allowedInputs.contains(field))
                    throw new IllegalArgumentException(where + "：条件引用了字段 " + field + "，" + rule.ruleCode()
                            + " 评分条件只能使用 " + String.join("、", allowedInputs) + "；c5SpeedScore 是评分结果，不能作为评分条件");
            if (Boolean.TRUE.equals(g.enabled())) enabled.add(new PreparedGroup(g, compiled));
        }
        enabled.sort(Comparator.comparingInt(g -> g.group().priority()));
        return new Prepared(rule, version, List.copyOf(enabled));
    }

    /**
     * 求值：按优先级逐个判断已启用的评分组，第一个命中的组给出分数；未启用或未轮到的组不判断。
     * 条件求值出错（如函数执行失败）时异常原样向上抛出，由调用方决定如何记录，不会当成未命中。
     */
    public ScoreResult evaluate(Prepared prepared, Map<String, Object> variables, RuleExecutionContext context) {
        String ruleCode = prepared.rule().ruleCode();
        if (!prepared.enabledRule())
            return new ScoreResult(ruleCode, "RULE_DISABLED", null, null, prepared.version(), List.of(),
                    "评分规则 " + ruleCode + " 已停用，不参与评分", Map.of());
        List<ScoreResult.GroupOutcome> outcomes = new ArrayList<>();
        for (PreparedGroup g : prepared.enabled()) {
            boolean matched = expressions.evaluate(g.condition(), variables, context, ruleCode + " 评分组 " + g.code()).matched();
            outcomes.add(new ScoreResult.GroupOutcome(g.code(), g.group().name(), g.group().priority(), g.group().result().score(), matched,
                    g.condition().readable(), matched ? "命中，返回 " + g.group().result().score() + " 分" : "未命中"));
            if (matched)
                return new ScoreResult(ruleCode, "SCORED", g.group().result().score(), g.code(), prepared.version(), List.copyOf(outcomes),
                        "命中评分组 " + g.code() + "（" + g.group().name() + "），得分 " + g.group().result().score(), Map.of());
        }
        if (outcomes.isEmpty())
            return new ScoreResult(ruleCode, "NOT_MATCHED", null, null, prepared.version(), List.of(),
                    "评分规则 " + ruleCode + " 没有任何已启用的评分组，没有分数", Map.of());
        return new ScoreResult(ruleCode, "NOT_MATCHED", null, null, prepared.version(), List.copyOf(outcomes),
                "评分规则 " + ruleCode + "（v" + prepared.version() + "）没有评分组命中：这是配置异常，未给出分数（不默认满分）", Map.of());
    }

    /** 发布前检查的结果：errors 任一条存在就不能发布；warnings 需要发布人明确确认。 */
    public record PublishCheck(List<String> errors, List<String> warnings, Map<String, Object> summary) {
        @com.fasterxml.jackson.annotation.JsonProperty("ok")
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * 发布前检查：冲突（条件完全相同的评分组、永不命中的评分组）与覆盖范围（有取值没有任何评分组命中）。
     * <p>覆盖检查用 probeDomain 做穷举：key 是可枚举的条件输入字段，value 是探测取值（字符串）。引用的字段全部可枚举、
     * 且不含函数节点的评分组是「可验证」的；其余（引用日期字段、函数条件）无法穷举，只能作为提示。
     * 探测范围内所有可验证评分组都没命中的取值：没有任何不可验证评分组时算「覆盖缺口」（错误），否则算「可能缺口」（提示）。
     */
    public PublishCheck checkForPublish(Prepared prepared, Map<String, List<Object>> probeDomain) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (prepared.enabled().isEmpty())
            errors.add("没有已启用的评分组：发布后所有候选都得不到分数");
        Map<String, List<Object>> domain = probeDomain == null ? Map.of() : probeDomain;
        Set<String> probeFields = new LinkedHashSet<>();
        Map<String, Boolean> verifiable = new LinkedHashMap<>();
        for (PreparedGroup g : prepared.enabled()) {
            Set<String> fields = expressions.referencedFields(g.group().condition());
            boolean ok = g.condition().functions().isEmpty() && domain.keySet().containsAll(fields);
            verifiable.put(g.code(), ok);
            if (ok) probeFields.addAll(fields);
        }
        // 有评分组无法穷举时，覆盖缺口只能降级为提示（它可能在真实数据上命中）
        boolean anyUnverifiable = verifiable.values().stream().anyMatch(x -> !x);
        // 冲突：条件完全相同的两个评分组，后者永远不会命中
        Map<String, String> seen = new HashMap<>();
        for (PreparedGroup g : prepared.enabled()) {
            String previous = seen.put(g.condition().expression(), g.code());
            if (previous != null)
                errors.add("评分组 " + g.code() + " 与 " + previous + " 的条件完全相同，" + g.code() + " 永远不会命中（FIRST_MATCH 只取优先级最高的一组）");
        }
        // 覆盖与永不命中：穷举可枚举字段
        List<Object> uncovered = new ArrayList<>();
        Map<String, Long> hits = new LinkedHashMap<>();
        prepared.enabled().forEach(g -> hits.put(g.code(), 0L));
        List<String> names = new ArrayList<>(probeFields);
        long combinations = enumerate(names, domain, 0, new LinkedHashMap<>(), prepared, verifiable, hits, uncovered);
        for (PreparedGroup g : prepared.enabled())
            if (verifiable.get(g.code()) && hits.get(g.code()) == 0)
                (anyUnverifiable ? warnings : errors).add("评分组 " + g.code() + "（" + g.group().name() + "）在可枚举的取值范围内永不命中：被更高优先级的评分组完全覆盖，或条件取值超出范围");
        if (!uncovered.isEmpty()) {
            String examples = uncovered.stream().limit(5).map(String::valueOf).collect(java.util.stream.Collectors.joining("；"));
            String more = uncovered.size() > 5 ? " 等 " + uncovered.size() + " 种" : "";
            if (anyUnverifiable) warnings.add("有 " + uncovered.size() + " 种取值没有任何可验证的评分组命中，只能依赖不可验证的评分组兜底：" + examples + more);
            else errors.add("覆盖缺口：" + examples + more + " 没有任何评分组命中，命中时将得不到分数");
        }
        if (anyUnverifiable)
            warnings.add("有评分组引用了日期字段或函数条件，无法穷举验证覆盖范围，请用模拟试算确认这些评分组能命中");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("probeCombinations", combinations);
        summary.put("verifiableGroups", verifiable.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList());
        summary.put("unverifiableGroups", verifiable.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList());
        summary.put("groupHits", hits);
        summary.put("uncoveredExamples", uncovered.stream().limit(20).map(String::valueOf).toList());
        return new PublishCheck(List.copyOf(errors), List.copyOf(warnings), summary);
    }

    /** 深度优先穷举 names 里各字段的探测取值；每个组合判断第一个命中的可验证评分组，没有任何命中则记入 uncovered。 */
    private long enumerate(List<String> names, Map<String, List<Object>> domain, int depth, Map<String, Object> current, Prepared prepared,
                           Map<String, Boolean> verifiable, Map<String, Long> hits, List<Object> uncovered) {
        if (depth == names.size()) {
            for (PreparedGroup g : prepared.enabled()) {
                if (!verifiable.get(g.code())) continue;
                if (expressions.evaluate(g.condition(), current, null, "发布校验 " + g.code()).matched()) {
                    hits.merge(g.code(), 1L, Long::sum);
                    return 1;
                }
            }
            uncovered.add(new LinkedHashMap<>(current));
            return 1;
        }
        long total = 0;
        for (Object option : domain.get(names.get(depth))) {
            current.put(names.get(depth), option);
            total += enumerate(names, domain, depth + 1, current, prepared, verifiable, hits, uncovered);
        }
        current.remove(names.get(depth));
        return total;
    }
}
