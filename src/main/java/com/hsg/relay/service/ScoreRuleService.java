package com.hsg.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.domain.ScoreRuleConfig;
import com.hsg.relay.domain.ScoreRuleVersion;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.repo.ScoreRuleConfigRepository;
import com.hsg.relay.repo.ScoreRuleVersionRepository;
import com.hsg.relay.routing.ScoreRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 评分规则的配置管理：草稿、发布、生效版本、历史版本。
 * <ul>
 *   <li>草稿（{@link ScoreRuleConfig}）：每个规则编码一份，可反复保存，带草稿版本号做并发校验；草稿不参与执行。</li>
 *   <li>发布版本（{@link ScoreRuleVersion}）：只插入、不更新、不删除。发布 = 校验草稿（结构、冲突、覆盖范围、分数合法性）→
 *       写入新版本 → 把「最新已发布版本」指针移过去 → 写审计。已发布的版本不能被覆盖，修改分数必须发布新版本。</li>
 *   <li>生效版本：某一决策时间生效的是「生效时间不晚于它」的最高版本，回放历史决策时按决策时间取版本，结果可重现。</li>
 * </ul>
 * 发布权限：发布必须提供操作人并记入审计；配置 relay.score-rule.publishers（逗号分隔）后，只有名单内的操作人可以发布，
 * 留空表示不限制（演示环境默认）。项目目前没有登录与角色体系，操作人为页面填写，这一点见实现说明。
 */
@Service
public class ScoreRuleService {
    private final ScoreRuleConfigRepository configs;
    private final ScoreRuleVersionRepository versions;
    private final ScoreRuleEngine engine;
    private final RuleAuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final String publishers;

    public ScoreRuleService(ScoreRuleConfigRepository configs, ScoreRuleVersionRepository versions, ScoreRuleEngine engine,
                            RuleAuditRepository audit, ObjectMapper json, Clock clock,
                            @Value("${relay.score-rule.publishers:}") String publishers) {
        this.configs = configs;
        this.versions = versions;
        this.engine = engine;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.publishers = publishers == null ? "" : publishers.trim();
    }

    public static final String AUDIT_TYPE = "SCORE_RULE";

    public record DraftView(ScoreRule rule, int version, String updatedBy, LocalDateTime updatedAt) {
    }

    public record PublishedView(int version, ScoreRule rule, LocalDateTime effectiveFrom, LocalDateTime publishedAt,
                                String publishedBy, String note) {
    }

    public record GroupSummary(String groupCode, String name, int priority, int score, boolean enabled, String condition) {
    }

    public record VersionSummary(int version, LocalDateTime effectiveFrom, LocalDateTime publishedAt, String publishedBy,
                                 String note, List<GroupSummary> groups) {
    }

    /** 页面一次取齐的数据：草稿、当前最新已发布版本、草稿是否与已发布不同、历史版本。 */
    public record View(String ruleCode, String ruleType, String matchMode, ScoreRule.ScoreRange scoreRange, DraftView draft,
                       PublishedView published, boolean draftChanged, boolean publishedAhead, List<VersionSummary> history) {
    }

    public record PublishRequest(Integer expectedDraftVersion, String operator, String note, LocalDateTime effectiveFrom,
                                 Boolean acknowledgeCoverageGaps) {
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public View view(String ruleCode) {
        ScoreRuleConfig config = configs.findById(ruleCode).orElse(null);
        PublishedView published = latest(ruleCode);
        ScoreRule draft = config == null || config.draftJson == null ? null : read(config.draftJson);
        boolean changed = published == null ? draft != null : draft == null || !same(draft, published.rule());
        return new View(ruleCode,
                config == null ? ScoreRuleEngine.RULE_TYPE : config.ruleType, config == null ? ScoreRuleEngine.MATCH_MODE : config.matchMode,
                draft != null ? draft.scoreRange() : published != null ? published.rule().scoreRange() : new ScoreRule.ScoreRange(0, 100),
                draft == null ? null : new DraftView(draft, config.draftVersion, config.draftUpdatedBy, config.draftUpdatedAt),
                published, changed, published != null && published.effectiveFrom().isAfter(now()), history(ruleCode));
    }

    public List<VersionSummary> history(String ruleCode) {
        return versions.findByRuleCodeOrderByVersionDesc(ruleCode).stream().map(v -> summarize(v.version, v.effectiveFrom, v.publishedAt,
                v.publishedBy, v.note, read(v.payloadJson))).toList();
    }

    public PublishedView version(String ruleCode, int version) {
        return versions.findByRuleCodeAndVersion(ruleCode, version).map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("评分规则 " + ruleCode + " 没有版本 v" + version));
    }

    /** 决策时间 at 生效的版本；没有（从未发布，或最早版本的生效时间还没到）返回 null。 */
    public PublishedView effectiveAt(String ruleCode, LocalDateTime at) {
        if (at == null) return null;
        return versions.findTopByRuleCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDescVersionDesc(ruleCode, at)
                .map(this::toView).orElse(null);
    }

    private PublishedView latest(String ruleCode) {
        return versions.findTopByRuleCodeOrderByVersionDesc(ruleCode).map(this::toView).orElse(null);
    }

    private PublishedView toView(ScoreRuleVersion v) {
        return new PublishedView(v.version, read(v.payloadJson), v.effectiveFrom, v.publishedAt, v.publishedBy, v.note);
    }

    private static VersionSummary summarize(int version, LocalDateTime effectiveFrom, LocalDateTime publishedAt, String publishedBy,
                                            String note, ScoreRule rule) {
        List<GroupSummary> groups = rule.groups().stream()
                .map(g -> new GroupSummary(g.groupCode(), g.name(), g.priority(), g.result() == null ? 0 : g.result().score(),
                        Boolean.TRUE.equals(g.enabled()), null)).toList();
        return new VersionSummary(version, effectiveFrom, publishedAt, publishedBy, note, groups);
    }

    /** 保存草稿：只做结构校验（能编译、分数合法、评分组 ID 与优先级唯一）；冲突和覆盖范围在发布时检查。 */
    @Transactional
    public View saveDraft(String ruleCode, JsonNode body, Integer expectedDraftVersion, String operator, Set<String> inputFields) {
        ScoreRule rule = draftRule(body, ruleCode, inputFields);
        String who = requireText(operator, "操作人不能为空");
        ScoreRuleConfig config = configs.findById(ruleCode).orElseGet(() -> new ScoreRuleConfig(ruleCode));
        if (expectedDraftVersion != null && expectedDraftVersion != config.draftVersion)
            throw new IllegalArgumentException("草稿已被他人修改（当前草稿 v" + config.draftVersion + "），请刷新后重新编辑");
        String before = config.draftJson;
        config.ruleType = rule.ruleType();
        config.matchMode = rule.matchMode();
        config.draftJson = write(rule);
        config.draftUpdatedBy = who;
        config.draftUpdatedAt = now();
        config.draftVersion++;
        configs.save(config);
        audit.save(new RuleAudit(AUDIT_TYPE, ruleCode, "SAVE_DRAFT", who, before, config.draftJson));
        return view(ruleCode);
    }

    /** 页面草稿对应的规则（整理 + 结构校验 + 编译），不落库：保存草稿、校验规则、用草稿试算共用。 */
    public ScoreRule draftRule(JsonNode body, String ruleCode, Set<String> inputFields) {
        ScoreRule rule = normalize(parse(body, ruleCode));
        engine.compile(rule, 0, inputFields);
        return rule;
    }

    /** 结构校验 + 发布前检查，不落库：给页面的「校验规则」用，也是发布时的同一套检查。 */
    public ScoreRuleEngine.PublishCheck check(JsonNode body, String ruleCode, Set<String> inputFields, Map<String, List<Object>> probeDomain) {
        return engine.checkForPublish(engine.compile(draftRule(body, ruleCode, inputFields), 0, inputFields), probeDomain);
    }

    /**
     * 发布草稿。任一项不满足都拒绝，已发布版本不变：
     * 权限（操作人、发布名单）→ 草稿未被他人改动 → 结构与分数合法 → 冲突与覆盖范围（有提示项时须明确确认）→ 生效时间。
     */
    @Transactional
    public PublishedView publish(String ruleCode, PublishRequest request, Set<String> inputFields, Map<String, List<Object>> probeDomain) {
        if (request == null) throw new IllegalArgumentException("发布请求不能为空");
        String who = requireText(request.operator(), "发布必须提供操作人");
        if (!publishers.isEmpty() && Arrays.stream(publishers.split(",")).map(String::trim).noneMatch(who::equals))
            throw new IllegalArgumentException("操作人 " + who + " 不在评分规则发布名单内，不能发布 " + ruleCode);
        ScoreRuleConfig config = configs.findById(ruleCode).orElseThrow(() -> new IllegalArgumentException("评分规则 " + ruleCode + " 没有草稿，不能发布"));
        if (request.expectedDraftVersion() != null && request.expectedDraftVersion() != config.draftVersion)
            throw new IllegalArgumentException("草稿已被他人修改（当前草稿 v" + config.draftVersion + "），请刷新后重新发布");
        ScoreRule draft = normalize(read(config.draftJson));
        ScoreRuleVersion latest = versions.findTopByRuleCodeOrderByVersionDesc(ruleCode).orElse(null);
        int next = latest == null ? 1 : latest.version + 1;
        ScoreRuleEngine.Prepared prepared = engine.compile(draft, next, inputFields);
        ScoreRuleEngine.PublishCheck check = engine.checkForPublish(prepared, probeDomain);
        if (!check.ok())
            throw new IllegalArgumentException("发布校验未通过：\n- " + String.join("\n- ", check.errors()));
        if (!check.warnings().isEmpty() && !Boolean.TRUE.equals(request.acknowledgeCoverageGaps()))
            throw new IllegalArgumentException("发布校验有需要确认的提示，请阅读后勾选「已知悉覆盖范围提示」再发布：\n- " + String.join("\n- ", check.warnings()));
        // 生效时间：默认立即生效；不得早于上一版本的生效时间（否则新版本会被旧版本盖住），也不得早于上一版本的发布时间（否则会改变已发生决策的回放结果）
        LocalDateTime effectiveFrom = request.effectiveFrom() == null ? now() : request.effectiveFrom();
        LocalDateTime latestAllowed = now().plusDays(366);
        if (effectiveFrom.isAfter(latestAllowed)) throw new IllegalArgumentException("生效时间不能晚于一年以后");
        if (latest != null) {
            if (effectiveFrom.isBefore(latest.effectiveFrom))
                throw new IllegalArgumentException("生效时间不能早于上一版本 v" + latest.version + " 的生效时间 " + latest.effectiveFrom);
            if (effectiveFrom.isBefore(latest.publishedAt))
                throw new IllegalArgumentException("生效时间不能早于上一版本 v" + latest.version + " 的发布时间 " + latest.publishedAt + "，否则会改变已发生决策的回放结果");
        }
        List<ScoreRule.Group> groups = draft.groups().stream().map(g -> new ScoreRule.Group(g.groupCode(), g.name(), g.priority(), g.condition(),
                g.result(), g.enabled(), next)).toList();
        ScoreRule snapshot = new ScoreRule(draft.ruleCode(), draft.ruleType(), draft.matchMode(), draft.scoreRange(), groups, draft.enabled());
        String payload = write(snapshot);
        String before = latest == null ? null : latest.payloadJson;
        versions.save(new ScoreRuleVersion(ruleCode, next, snapshot.ruleType(), snapshot.matchMode(), payload, effectiveFrom, now(), who,
                request.note() == null ? null : request.note().trim()));
        config.ruleType = snapshot.ruleType();
        config.matchMode = snapshot.matchMode();
        config.publishedVersion = next;
        config.publishedAt = now();
        config.publishedBy = who;
        configs.save(config);
        audit.save(new RuleAudit(AUDIT_TYPE, ruleCode, "PUBLISH", who, before, payload));
        return version(ruleCode, next);
    }

    /**
     * 默认评分规则的生效起点：默认规则是把原来写死在代码里的评分（V1.10）变成配置，视为自始生效，
     * 这样任何决策时间（含回放历史决策）都能取到默认版本，不会因为「发布时间晚于决策时间」而变成未发布。
     */
    public static final LocalDateTime DEFAULT_EFFECTIVE_FROM = LocalDateTime.of(2000, 1, 1, 0, 0);

    /** 系统初始化用：写入默认草稿和 v1（仅在从未发布过时调用）。 */
    @Transactional
    public void seedDefault(String ruleCode, ScoreRule rule, Set<String> inputFields, Map<String, List<Object>> probeDomain, String operator, String note) {
        if (configs.findById(ruleCode).isPresent() || versions.findTopByRuleCodeOrderByVersionDesc(ruleCode).isPresent()) return;
        ScoreRule draft = normalize(rule);
        ScoreRuleEngine.PublishCheck check = engine.checkForPublish(engine.compile(draft, 1, inputFields), probeDomain);
        if (!check.ok()) throw new IllegalStateException("默认评分规则未通过发布校验：" + String.join("；", check.errors()));
        List<ScoreRule.Group> groups = draft.groups().stream().map(g -> new ScoreRule.Group(g.groupCode(), g.name(), g.priority(), g.condition(),
                g.result(), g.enabled(), 1)).toList();
        ScoreRule snapshot = new ScoreRule(draft.ruleCode(), draft.ruleType(), draft.matchMode(), draft.scoreRange(), groups, draft.enabled());
        String payload = write(snapshot);
        versions.save(new ScoreRuleVersion(ruleCode, 1, snapshot.ruleType(), snapshot.matchMode(), payload, DEFAULT_EFFECTIVE_FROM, now(), operator, note));
        ScoreRuleConfig config = new ScoreRuleConfig(ruleCode);
        config.draftJson = write(draft);
        config.draftUpdatedBy = operator;
        config.draftUpdatedAt = now();
        config.publishedVersion = 1;
        config.publishedAt = now();
        config.publishedBy = operator;
        configs.save(config);
        audit.save(new RuleAudit(AUDIT_TYPE, ruleCode, "PUBLISH", operator, null, payload));
    }

    /** 审计记录（发布与保存草稿），按时间倒序。 */
    public List<RuleAudit> audits(String ruleCode) {
        return audit.findAll().stream().filter(a -> AUDIT_TYPE.equals(a.objectType) && ruleCode.equals(a.objectId))
                .sorted(Comparator.comparing((RuleAudit a) -> a.operatedAt).reversed().thenComparing(a -> a.id, Comparator.reverseOrder())).toList();
    }

    // ---------- 读取与整理 ----------

    /**
     * 解析页面提交的规则 JSON。分数、优先级在 JSON 里必须是整数：Jackson 默认会把 50.5 悄悄截成 50，所以先在树上核对。
     */
    public ScoreRule parse(JsonNode body, String ruleCode) {
        if (body == null || body.isNull()) throw new IllegalArgumentException("评分规则不能为空");
        if (body.hasNonNull("ruleCode") && !ruleCode.equals(body.get("ruleCode").asText()))
            throw new IllegalArgumentException("规则编码须为 " + ruleCode);
        JsonNode groups = body.path("groups");
        if (!groups.isArray()) throw new IllegalArgumentException("groups 必须是数组");
        for (JsonNode group : groups) {
            String code = group.path("groupCode").asText("（未命名）");
            JsonNode score = group.path("result").path("score");
            if (score.isNumber() && !score.isIntegralNumber()) throw new IllegalArgumentException("评分组 " + code + "：分数必须是整数");
            JsonNode priority = group.path("priority");
            if (priority.isNumber() && !priority.isIntegralNumber()) throw new IllegalArgumentException("评分组 " + code + "：优先级必须是整数");
        }
        try {
            ScoreRule rule = json.treeToValue(body, ScoreRule.class);
            if (rule == null) throw new IllegalArgumentException("评分规则不能为空");
            return new ScoreRule(ruleCode, rule.ruleType(), rule.matchMode(), rule.scoreRange(), rule.groups(), rule.enabled());
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("评分规则 JSON 无效： " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), ex);
        }
    }

    /** 草稿的规范形式：评分组按优先级升序，版本号清空（版本号只在发布快照里写入）。 */
    static ScoreRule normalize(ScoreRule rule) {
        if (rule.groups() == null) return rule;
        List<ScoreRule.Group> groups = rule.groups().stream()
                .sorted(Comparator.comparing(ScoreRule.Group::priority, Comparator.nullsLast(Comparator.<Integer>naturalOrder())))
                .map(g -> g.version() == null ? g : new ScoreRule.Group(g.groupCode(), g.name(), g.priority(), g.condition(), g.result(), g.enabled(), null))
                .toList();
        return new ScoreRule(rule.ruleCode(), rule.ruleType(), rule.matchMode(), rule.scoreRange(), groups, rule.enabled());
    }

    /** 两份规则除版本号外是否相同（比较规范 JSON）。 */
    private boolean same(ScoreRule a, ScoreRule b) {
        return write(normalize(a)).equals(write(normalize(b)));
    }

    ScoreRule read(String payload) {
        try {
            return json.readValue(payload, ScoreRule.class);
        } catch (Exception ex) {
            throw new IllegalStateException("评分规则配置不可读", ex);
        }
    }

    private String write(ScoreRule rule) {
        try {
            return json.writeValueAsString(rule);
        } catch (Exception ex) {
            throw new IllegalStateException("评分规则序列化失败", ex);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank() || value.trim().length() > 64) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
