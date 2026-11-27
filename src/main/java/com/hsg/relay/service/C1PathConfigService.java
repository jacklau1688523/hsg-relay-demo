package com.hsg.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.C1ConditionConfig;
import com.hsg.relay.domain.C1PathConfig;
import com.hsg.relay.domain.C1PathVersion;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.C1ConditionConfigRepository;
import com.hsg.relay.repo.C1PathConfigRepository;
import com.hsg.relay.repo.C1PathVersionRepository;
import com.hsg.relay.repo.ProviderRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * C1 合作方动态规则路径配置：草稿、发布、历史版本，以及从旧版「全局准入条件」迁入。
 * <ul>
 *   <li>草稿（{@link C1PathConfig}，全局一行）：可反复保存，带草稿版本号做并发校验；草稿不参与执行。</li>
 *   <li>发布版本（{@link C1PathVersion}）：只插入、不更新、不删除。发布 = 重新校验已保存的草稿（结构、编译、规则、发布前检查）
 *       → 写入新版本 → 移动「最新已发布版本」指针 → 写审计。执行只用已发布版本；回放历史决策按决策记录里的 configVersion 取版本。</li>
 *   <li>校验分三层：结构（JSON 形状、整数优先级、空条件组、旧版布尔字段）→ 编译（每条非空条件树都要能编译，含停用的路径）
 *       → 规则（路径编码、DEFAULT 唯一、失败原因码唯一且不占用系统原因码）→ 发布前检查（合作方存在、启用路径不冲突、启用路径必须有条件树）。
 *       任一层不满足都不能发布；已发布的路径不能删除（历史版本和决策记录仍引用它），可以停用。</li>
 * </ul>
 * 发布权限与评分规则一致：必须提供操作人；配置 relay.c1-path.publishers（逗号分隔）后只有名单内可以发布，留空不限制。
 */
@Service
public class C1PathConfigService {
    public static final String AUDIT_TYPE = "C1_PATH";
    /** 旧版全局准入条件的原因码：迁入的 v1 沿用它，新配置不能再用。 */
    public static final String LEGACY_FAILURE_CODE = "C1_CONDITION_NOT_MATCHED";
    /** 路径没有填失败原因码时的缺省值（DEFAULT 路径）。 */
    public static final String DEFAULT_FAILURE_CODE = "C1_PATH_DEFAULT_NOT_MATCHED";
    /**
     * 旧版全局准入条件里由执行器注入取值的两个布尔字段；已由 APPETITE_MATCH 取代，新配置不能再引用。
     * 清单只有一份（{@link ExpressionService#LEGACY_BOOLEAN_FIELDS}）：规则 C1 编译时按内置字段处理，迁移产生的 v1 回放不依赖字段库。
     */
    public static final List<String> LEGACY_BOOLEAN_FIELDS = ExpressionService.LEGACY_BOOLEAN_FIELDS;
    public static final String MIGRATED_OPERATOR = "system";
    private static final Pattern PATH_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final List<String> RESERVED_REASON_CODES = List.of("PASS", "C1_PATH_NOT_FOUND", "C1_PATH_CONFLICT", LEGACY_FAILURE_CODE);
    private static final List<String> RESERVED_REASON_PREFIXES = List.of("CLIENT_", "APPETITE_", "C1_CONFIG_");

    public record Hint(String code, String message) {
    }

    public record PathSummary(String pathCode, String pathName, List<String> providerCodes, Integer priority, Boolean enabled,
                              String failureReasonCode, String condition) {
    }

    public record VersionSummary(int version, LocalDateTime publishedAt, String publishedBy, String note, List<PathSummary> paths) {
    }

    public record DraftView(C1PathRule rule, int version, String updatedBy, LocalDateTime updatedAt) {
    }

    public record PublishedView(int version, C1PathRule rule, String branchMode, LocalDateTime publishedAt, String publishedBy, String note) {
    }

    /** 页面一次取齐的数据：草稿、最新已发布版本、草稿是否与已发布不同、历史版本（新 → 旧）、迁移提示。 */
    public record View(String ruleCode, String branchMode, DraftView draft, PublishedView published, boolean draftChanged,
                       List<VersionSummary> history, List<Hint> migrationHints) {
    }

    /** 校验结果：errors 任一项不满足都不能发布；warnings 不阻止发布，但要读；paths 是每条非空条件树的可读表达式。 */
    public record CheckResult(boolean ok, List<String> errors, List<String> warnings, List<PathCompile> paths) {
        public CheckResult {
            errors = errors == null ? List.of() : List.copyOf(errors);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }

    public record PathCompile(String pathCode, String pathName, String expression) {
    }

    public record DraftRequest(JsonNode rule, Integer expectedDraftVersion, String operator) {
    }

    public record PublishRequest(Integer expectedDraftVersion, String operator, String note) {
    }

    /** 执行时读到的已发布版本。 */
    public record Loaded(int version, C1PathRule rule) {
    }

    public record MigrationView(boolean migrated, boolean legacyConfigured, Integer legacyVersionNo, String legacyConditionJson,
                                boolean pendingReview, List<Hint> hints) {
    }

    /** 一条路径的条件树（原始 JSON，供引用检查扫描）；只有非空的条件树。 */
    public record PathNode(String pathCode, JsonNode conditionAst) {
    }

    private final C1PathConfigRepository configs;
    private final C1PathVersionRepository versions;
    private final C1ConditionConfigRepository legacyConditions;
    private final ProviderRepository providers;
    private final AppetiteRepository appetites;
    private final RuleAuditRepository audit;
    private final ObjectMapper json;
    private final Clock clock;
    private final ExpressionService expressions;
    private final String publishers;

    public C1PathConfigService(C1PathConfigRepository configs, C1PathVersionRepository versions,
                               C1ConditionConfigRepository legacyConditions, ProviderRepository providers,
                               AppetiteRepository appetites, RuleAuditRepository audit, ObjectMapper json, Clock clock,
                               ExpressionService expressions, @Value("${relay.c1-path.publishers:}") String publishers) {
        this.configs = configs;
        this.versions = versions;
        this.legacyConditions = legacyConditions;
        this.providers = providers;
        this.appetites = appetites;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.expressions = expressions;
        this.publishers = publishers == null ? "" : publishers.trim();
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    // ---------- 读取 ----------

    public View view() {
        C1PathConfig config = configs.findById(1L).orElse(null);
        PublishedView published = latest();
        C1PathRule draft = config == null || config.draftJson == null || config.draftJson.isBlank() ? null : read(config.draftJson);
        boolean changed = published == null ? draft != null : draft == null || !same(draft, published.rule());
        return new View(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE,
                draft == null ? null : new DraftView(draft, config.draftVersion, config.draftUpdatedBy, config.draftUpdatedAt),
                published, changed, history(), hints(config));
    }

    /** 历史版本，新 → 旧。 */
    public List<VersionSummary> history() {
        return versions.findAllByOrderByVersionDesc().stream()
                .map(v -> summarize(v.version, v.publishedAt, v.publishedBy, v.note, read(v.payloadJson))).toList();
    }

    public PublishedView version(int version) {
        return versions.findByVersion(version).map(this::toView)
                .orElseThrow(() -> new IllegalArgumentException("C1 路径配置没有版本 v" + version));
    }

    /** 当前已保存的草稿 JSON；没有草稿时为 null。 */
    public JsonNode storedDraft() {
        C1PathConfig config = configs.findById(1L).orElse(null);
        if (config == null || config.draftJson == null || config.draftJson.isBlank()) return null;
        return readTree(config.draftJson);
    }

    /**
     * 执行时读取已发布版本：version 为空取最新，否则取指定版本（回放）。没有时返回 null；
     * 存下来的 JSON 读不出来时抛 IllegalStateException——不会当成「没有配置」以外的任何结果，更不会默认通过。
     * 已发布版本原样读取，不做整理（版本号、顺序、停用状态都是发布时的快照）。
     */
    public Loaded load(Integer version) {
        C1PathVersion row = version == null ? versions.findTopByOrderByVersionDesc().orElse(null) : versions.findByVersion(version).orElse(null);
        if (row == null) return null;
        return new Loaded(row.version, read(row.payloadJson));
    }

    /** 最新已发布版本的规则；从未发布时为 null。 */
    public C1PathRule latestPublishedRule() {
        C1PathVersion row = versions.findTopByOrderByVersionDesc().orElse(null);
        return row == null ? null : read(row.payloadJson);
    }

    /** 审计记录（保存草稿、发布、迁移），新 → 旧。 */
    public List<RuleAudit> audits() {
        return audit.findTop500ByObjectTypeOrderByIdDesc(AUDIT_TYPE).stream()
                .filter(a -> C1PathRule.RULE_CODE.equals(a.objectId)).toList();
    }

    /** 迁移情况：是否已迁移、旧版是否配置过条件、待人工确认的提示。 */
    public MigrationView migrationView() {
        C1ConditionConfig legacy = legacyConditions.findById(1L).orElse(null);
        List<Hint> hints = hints(configs.findById(1L).orElse(null));
        boolean migrated = audits().stream().anyMatch(a -> "MIGRATE".equals(a.operation));
        boolean configured = legacy != null && legacy.conditionJson != null && !legacy.conditionJson.isBlank();
        return new MigrationView(migrated, configured, legacy == null ? null : legacy.versionNo,
                legacy == null ? null : legacy.conditionJson, !hints.isEmpty(), hints);
    }

    // ---------- 校验 ----------

    /** 结构校验 + 编译：给页面的「编译」用，每条非空条件树给出可读表达式。不落库。 */
    public CheckResult compile(JsonNode body) {
        List<String> errors = new ArrayList<>(structuralErrors(body));
        if (!errors.isEmpty()) return new CheckResult(false, errors, List.of(), List.of());
        C1PathRule rule = normalize(parse(body));
        List<PathCompile> compiled = compileAll(rule, errors);
        return new CheckResult(errors.isEmpty(), errors, List.of(), compiled);
    }

    /** 全部校验（结构 → 编译 → 规则 → 删除保护 → 发布前检查与提示），不落库：给页面的「校验」用，结果与发布时的检查一致。 */
    public CheckResult check(JsonNode body) {
        List<String> errors = new ArrayList<>(structuralErrors(body));
        if (!errors.isEmpty()) return new CheckResult(false, errors, List.of(), List.of());
        C1PathRule rule = normalize(parse(body));
        List<PathCompile> compiled = compileAll(rule, errors);
        errors.addAll(ruleErrors(rule));
        deletionErrors(rule, errors);
        List<String> warnings = new ArrayList<>();
        publishChecks(rule, errors, warnings);
        return new CheckResult(errors.isEmpty(), errors, warnings, compiled);
    }

    /**
     * 页面草稿对应的规则（结构校验 + 整理 + 编译），不落库：保存草稿、校验、用草稿试算共用。
     * 任一条件不满足抛参数错误，消息逐条列出全部问题。
     */
    public C1PathRule draftRule(JsonNode body) {
        List<String> errors = new ArrayList<>(structuralErrors(body));
        if (!errors.isEmpty()) throw new IllegalArgumentException("C1 路径配置结构无效：\n- " + String.join("\n- ", errors));
        C1PathRule rule = normalize(parse(body));
        compileAll(rule, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException("C1 路径配置无法编译：\n- " + String.join("\n- ", errors));
        return rule;
    }

    /**
     * 用草稿试算时的规则：在 {@link #draftRule} 之上再做规则级校验。缺 DEFAULT 路径、原因码不合规的草稿不能发布，
     * 试算时直接说明问题，不给出误导性的「找不到路径」。
     */
    public C1PathRule simulationRule(JsonNode body) {
        C1PathRule rule = draftRule(body);
        List<String> errors = new ArrayList<>(ruleErrors(rule));
        if (!errors.isEmpty()) throw new IllegalArgumentException("C1 路径配置不符合规则：\n- " + String.join("\n- ", errors));
        return rule;
    }

    /**
     * JSON 形状校验，不解析成对象就能报出的问题：
     * ruleCode / branchMode 固定值、paths 数组、优先级必须是整数（Jackson 会把 1.5 悄悄截成 1）、providerCodes 必须是字符串数组、
     * 条件树里不能有空条件组、不能引用旧版布尔字段（附迁移说明）。
     */
    public List<String> structuralErrors(JsonNode body) {
        List<String> errors = new ArrayList<>();
        if (body == null || body.isNull()) {
            errors.add("路径配置不能为空");
            return errors;
        }
        if (!body.isObject()) {
            errors.add("路径配置必须是 JSON 对象");
            return errors;
        }
        String ruleCode = text(body, "ruleCode");
        if (!ruleCode.isEmpty() && !C1PathRule.RULE_CODE.equals(ruleCode))
            errors.add("ruleCode 须为 " + C1PathRule.RULE_CODE);
        String branchMode = text(body, "branchMode");
        if (!branchMode.isEmpty() && !C1PathRule.BRANCH_MODE.equals(branchMode))
            errors.add("branchMode 须为 " + C1PathRule.BRANCH_MODE + "（按候选合作方选路径，没有其它分支模式）");
        JsonNode paths = body.get("paths");
        if (paths == null || !paths.isArray()) {
            errors.add("paths 必须是数组");
            return errors;
        }
        int index = 0;
        for (JsonNode path : paths) {
            index++;
            String owner = "路径 " + label(path, index) + "：";
            if (!path.isObject()) {
                errors.add("第 " + index + " 条路径必须是 JSON 对象");
                continue;
            }
            JsonNode code = path.get("pathCode");
            if (code != null && !code.isNull() && !code.isTextual()) errors.add(owner + "路径编码必须是字符串");
            JsonNode name = path.get("pathName");
            if (name != null && !name.isNull() && !name.isTextual()) errors.add(owner + "路径名称必须是字符串");
            JsonNode priority = path.get("priority");
            if (priority != null && !priority.isNull() && (!priority.isNumber() || !priority.isIntegralNumber()))
                errors.add(owner + "优先级必须是整数");
            JsonNode providerCodes = path.get("providerCodes");
            if (providerCodes != null && !providerCodes.isNull()) {
                if (!providerCodes.isArray()) errors.add(owner + "providerCodes 必须是数组");
                else for (JsonNode item : providerCodes)
                    if (!item.isTextual()) errors.add(owner + "绑定的合作方编码必须是字符串：" + item);
            }
            JsonNode reason = path.get("failureReasonCode");
            if (reason != null && !reason.isNull() && !reason.isTextual()) errors.add(owner + "失败原因码必须是字符串");
            JsonNode tree = path.get("conditionAst");
            if (tree != null && !tree.isNull()) treeErrors(owner, tree, true, errors);
        }
        return errors;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private static String label(JsonNode path, int index) {
        String code = text(path, "pathCode");
        return code.isEmpty() ? "（第 " + index + " 条，未命名）" : code;
    }

    /** 条件树的结构检查：空条件组、旧版布尔字段。根条件组为空视为「没有条件树」（草稿允许），嵌套的空条件组是错误。 */
    private static void treeErrors(String owner, JsonNode node, boolean root, List<String> errors) {
        if (node == null || node.isNull()) return;
        if (!node.isObject()) {
            errors.add(owner + "条件树节点必须是 JSON 对象");
            return;
        }
        if (isGroup(node)) {
            JsonNode children = node.get("children");
            if (children == null || !children.isArray() || children.isEmpty()) {
                if (!root) errors.add(owner + "条件树存在空的条件组，请删除它或添加条件");
                return;
            }
            for (JsonNode child : children) treeErrors(owner, child, false, errors);
            return;
        }
        String field = node.path("field").asText("");
        if (LEGACY_BOOLEAN_FIELDS.contains(field))
            errors.add(owner + "条件树仍引用旧版布尔字段 " + field + "：合作方准入是否覆盖该币种 / 国家已改由 APPETITE_MATCH 四级匹配判定"
                    + "（APPETITE_NOT_SET），请删除这个条件");
    }

    /** 规则级校验（整理之后）：路径编码、名称、DEFAULT 唯一且不绑定合作方、失败原因码唯一且不占用系统原因码。 */
    private static List<String> ruleErrors(C1PathRule rule) {
        List<String> errors = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        Set<String> reasons = new HashSet<>();
        int defaults = 0;
        for (C1PathRule.Path path : rule.paths()) {
            String code = path.pathCode();
            String owner = "路径 " + (code == null || code.isBlank() ? "（未命名）" : code) + "：";
            if (code == null || code.isBlank()) errors.add("路径编码不能为空");
            else if (!PATH_CODE.matcher(code).matches())
                errors.add(owner + "路径编码须以大写字母开头，仅含大写字母、数字、下划线，最多 32 位");
            else if (!codes.add(code)) errors.add(owner + "路径编码重复");
            if (path.pathName() == null || path.pathName().isBlank()) errors.add(owner + "路径名称不能为空");
            else if (path.pathName().length() > 80) errors.add(owner + "路径名称最多 80 位");
            if (path.priority() != null && path.priority() < 0) errors.add(owner + "优先级不可小于 0");
            if (C1PathRule.DEFAULT_PATH.equals(code)) {
                defaults++;
                if (!path.providerCodes().isEmpty()) errors.add(owner + "DEFAULT 路径不绑定合作方");
            }
            String reason = path.failureReasonCode();
            if (reason == null || reason.isBlank()) errors.add(owner + "失败原因码不能为空");
            else if (!REASON_CODE.matcher(reason).matches())
                errors.add(owner + "失败原因码须以大写字母开头，仅含大写字母、数字、下划线，最多 64 位");
            else if (!reasons.add(reason)) errors.add(owner + "失败原因码 " + reason + " 与其它路径重复");
            else if (isReserved(reason)) errors.add(owner + "失败原因码 " + reason + " 是系统原因码，请换一个（系统在路径找不到、路径冲突、"
                    + "客户分类 / 排除、准入匹配和配置缺失时使用这些编码）");
        }
        if (defaults == 0) errors.add("必须有一条 DEFAULT 路径：它不绑定合作方，没有专用路径的合作方走它");
        else if (defaults > 1) errors.add("只能有一条 DEFAULT 路径，现在有 " + defaults + " 条");
        return errors;
    }

    private static boolean isReserved(String reason) {
        return RESERVED_REASON_CODES.contains(reason) || RESERVED_REASON_PREFIXES.stream().anyMatch(reason::startsWith);
    }

    /** 每条非空条件树都要能编译（含停用的路径：停用的路径恢复启用时必须可用）。 */
    private List<PathCompile> compileAll(C1PathRule rule, List<String> errors) {
        List<PathCompile> compiled = new ArrayList<>();
        for (C1PathRule.Path path : rule.paths()) {
            if (isEmptyTree(path.conditionAst())) continue;
            String owner = "路径 " + path.pathCode() + "：";
            try {
                compiled.add(new PathCompile(path.pathCode(), path.pathName(), expressions.compileDetailed(path.conditionAst(), C1PathRule.RULE_CODE).readable()));
            } catch (RuntimeException ex) {
                errors.add(owner + "条件树无法编译：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        }
        return compiled;
    }

    /** 删除保护：最新已发布版本里的路径，草稿里不能没有（历史版本和决策记录仍引用它）；停用可以。 */
    private void deletionErrors(C1PathRule draft, List<String> errors) {
        C1PathRule published = latestPublishedRule();
        if (published == null) return;
        Set<String> current = new HashSet<>();
        draft.paths().forEach(path -> current.add(path.pathCode()));
        for (C1PathRule.Path path : published.paths())
            if (!current.contains(path.pathCode()))
                errors.add("路径 " + path.pathCode() + " 已发布过，不能删除（历史版本和决策记录仍引用它）；不需要时请停用");
    }

    /** 发布前检查：合作方存在、启用路径互不冲突、启用路径（含 DEFAULT）必须有条件树；提示：空绑定的启用路径、DEFAULT 停用。 */
    private void publishChecks(C1PathRule rule, List<String> errors, List<String> warnings) {
        Map<String, List<String>> bindings = new TreeMap<>();
        for (C1PathRule.Path path : rule.paths()) {
            String owner = "路径 " + path.pathCode() + "：";
            for (String provider : path.providerCodes())
                if (!providers.existsById(provider)) errors.add(owner + "绑定的合作方 " + provider + " 不存在");
            if (path.on() && isEmptyTree(path.conditionAst()))
                errors.add(owner + "已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）");
            if (C1PathRule.DEFAULT_PATH.equals(path.pathCode())) {
                if (!path.on()) warnings.add("路径 DEFAULT 已停用：没有启用专用路径的合作方将按 C1_PATH_NOT_FOUND 过滤");
                continue;
            }
            if (!path.on()) continue;
            if (path.providerCodes().isEmpty()) warnings.add(owner + "已启用但未绑定任何合作方，不会命中");
            for (String provider : path.providerCodes())
                bindings.computeIfAbsent(provider, key -> new ArrayList<>()).add(path.pathCode());
        }
        bindings.forEach((provider, paths) -> {
            if (paths.size() > 1)
                errors.add("合作方 " + provider + " 同时绑定在多条启用的路径：" + String.join("、", paths)
                        + "；一个合作方只能由一条启用的路径负责，请调整绑定或停用其中一条");
        });
    }

    // ---------- 草稿与发布 ----------

    /**
     * 保存草稿：结构 + 编译 + 删除保护。规则级问题和发布前检查留给「校验」与发布。
     * 配置行只在启动迁移里创建；应用刚启动、迁移还没执行时就有保存请求，先补做迁移（见方法内注释）。
     */
    @Transactional
    public View saveDraft(JsonNode body, Integer expectedDraftVersion, String operator) {
        C1PathRule rule = draftRule(body);
        String who = requireText(operator, "操作人不能为空");
        C1PathConfig config = configs.findById(1L).orElse(null);
        if (config == null) {
            // 启动迁移在 ApplicationReadyEvent 里执行，端口先于它开放：迁移还没跑就有保存请求时先补做同一段迁移（幂等），
            // 免得这次保存抢先建行、旧版全局准入条件永远迁不进来。本请求随后的校验不通过时整体回滚，迁移留给启动时做。
            migrateFromLegacy();
            config = configs.findById(1L).orElseThrow();
        }
        List<String> errors = new ArrayList<>();
        deletionErrors(rule, errors);
        if (!errors.isEmpty()) throw new IllegalArgumentException("草稿不能保存：\n- " + String.join("\n- ", errors));
        if (expectedDraftVersion != null && expectedDraftVersion != config.draftVersion)
            throw new IllegalArgumentException("草稿已被他人修改（当前草稿 v" + config.draftVersion + "），请刷新后重新编辑");
        String before = config.draftJson;
        config.draftJson = write(rule);
        config.draftUpdatedBy = who;
        config.draftUpdatedAt = now();
        config.draftVersion++;
        configs.save(config);
        audit.save(new RuleAudit(AUDIT_TYPE, C1PathRule.RULE_CODE, "SAVE_DRAFT", who, before, config.draftJson));
        return view();
    }

    /** 发布已保存的草稿。权限 → 草稿未被他人改动 → 重新校验（结构、编译、规则、发布前检查）；任一项不满足都拒绝，已发布版本不变。 */
    @Transactional
    public PublishedView publish(PublishRequest request) {
        if (request == null) throw new IllegalArgumentException("发布请求不能为空");
        String who = requireText(request.operator(), "发布必须提供操作人");
        if (!publishers.isEmpty() && Arrays.stream(publishers.split(",")).map(String::trim).noneMatch(who::equals))
            throw new IllegalArgumentException("操作人 " + who + " 不在 C1 路径配置发布名单内，不能发布");
        C1PathConfig config = configs.findById(1L).orElse(null);
        if (config == null || config.draftJson == null || config.draftJson.isBlank())
            throw new IllegalArgumentException("没有可发布的草稿：请先保存路径配置草稿");
        if (request.expectedDraftVersion() != null && request.expectedDraftVersion() != config.draftVersion)
            throw new IllegalArgumentException("草稿已被他人修改（当前草稿 v" + config.draftVersion + "），请刷新后重新发布");
        JsonNode body = readTree(config.draftJson);
        List<String> errors = new ArrayList<>(structuralErrors(body));
        C1PathRule draft = null;
        if (errors.isEmpty()) {
            draft = normalize(parse(body));
            compileAll(draft, errors);
            errors.addAll(ruleErrors(draft));
            deletionErrors(draft, errors);
            publishChecks(draft, errors, new ArrayList<>());
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException("发布校验未通过：\n- " + String.join("\n- ", errors));
        C1PathVersion latest = versions.findTopByOrderByVersionDesc().orElse(null);
        int next = latest == null ? 1 : latest.version + 1;
        // 快照：每条路径带上发布版本号
        List<C1PathRule.Path> paths = draft.paths().stream().map(path -> new C1PathRule.Path(path.pathCode(), path.pathName(),
                path.providerCodes(), path.priority(), path.enabled(), path.conditionAst(), path.failureReasonCode(), next)).toList();
        String payload = write(new C1PathRule(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE, paths));
        String before = latest == null ? null : latest.payloadJson;
        versions.save(new C1PathVersion(next, C1PathRule.BRANCH_MODE, payload, now(), who,
                request.note() == null ? null : request.note().trim()));
        config.publishedVersion = next;
        config.publishedAt = now();
        config.publishedBy = who;
        // 迁移提示只说明「迁移产生了什么」；第一次发布后草稿已由人工确认，提示随之清空
        config.migrationHintsJson = null;
        configs.save(config);
        audit.save(new RuleAudit(AUDIT_TYPE, C1PathRule.RULE_CODE, "PUBLISH", who, before, payload));
        return version(next);
    }

    // ---------- 解析与整理 ----------

    /** 解析页面提交的配置 JSON。根条件组为空的路径视为没有条件树（草稿允许；发布时启用的路径必须有条件树）。 */
    public C1PathRule parse(JsonNode body) {
        if (body == null || body.isNull()) throw new IllegalArgumentException("路径配置不能为空");
        try {
            C1PathRule rule = json.treeToValue(body, C1PathRule.class);
            if (rule == null) throw new IllegalArgumentException("路径配置不能为空");
            JsonNode trees = body.get("paths");
            List<C1PathRule.Path> paths = new ArrayList<>();
            // 「没有条件树」按 JSON 判断：根节点是空的条件组（带或不带 type）都算没有，不留下一个没有字段的空节点
            for (int i = 0; i < rule.paths().size(); i++) {
                C1PathRule.Path path = rule.paths().get(i);
                JsonNode tree = trees == null || !trees.isArray() || !trees.get(i).isObject() ? null : trees.get(i).get("conditionAst");
                paths.add(!isEmptyTree(tree) ? path
                        : new C1PathRule.Path(path.pathCode(), path.pathName(), path.providerCodes(), path.priority(), path.enabled(), null,
                        path.failureReasonCode(), path.version()));
            }
            return new C1PathRule(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE, paths);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("路径配置 JSON 无效： " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()), ex);
        }
    }

    /**
     * 草稿的规范形式：版本号清空（只在发布快照里写入），编码 / 名称去空格，合作方去重排序，启用的缺省值 true、优先级缺省 0，
     * 没填失败原因码的路径补 C1_PATH_编码_NOT_MATCHED，路径按（优先级，编码）排序、DEFAULT 排在最后。只用于草稿，不用于已发布版本。
     */
    public static C1PathRule normalize(C1PathRule rule) {
        List<C1PathRule.Path> paths = rule.paths().stream().map(path -> {
            String code = path.pathCode() == null ? null : path.pathCode().trim();
            String reason = path.failureReasonCode() == null ? "" : path.failureReasonCode().trim();
            // 缺省原因码由路径编码派生；编码本身不合规时（如小写）路径编码那一条会报错，这里不连带报原因码
            if (reason.isEmpty() && code != null && !code.isEmpty()) reason = "C1_PATH_" + code.toUpperCase(Locale.ROOT) + "_NOT_MATCHED";
            List<String> providerCodes = path.providerCodes().stream().filter(Objects::nonNull).map(String::trim)
                    .filter(provider -> !provider.isEmpty()).distinct().sorted().toList();
            return new C1PathRule.Path(code, path.pathName() == null ? null : path.pathName().trim(), providerCodes,
                    path.priority() == null ? 0 : path.priority(), path.enabled() == null ? Boolean.TRUE : path.enabled(),
                    path.conditionAst(), reason, null);
        }).sorted(Comparator.comparing((C1PathRule.Path path) -> C1PathRule.DEFAULT_PATH.equals(path.pathCode()))
                .thenComparing(C1PathRule.Path::priority, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(C1PathRule.Path::pathCode, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        return new C1PathRule(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE, paths);
    }

    /** 两份配置除版本号外是否相同（比较规范 JSON）。 */
    private boolean same(C1PathRule a, C1PathRule b) {
        return write(normalize(a)).equals(write(normalize(b)));
    }

    /** 没有条件树：null，或根节点是没有子条件的条件组。 */
    public static boolean isEmptyTree(JsonNode tree) {
        return tree == null || tree.isNull() || tree.isMissingNode() || isGroup(tree) && !hasChildren(tree);
    }

    /** 同 {@link #isEmptyTree(JsonNode)}，用于已解析的条件树。 */
    public static boolean isEmptyTree(ExpressionService.Node tree) {
        return tree == null || "GROUP".equals(tree.type()) && (tree.children() == null || tree.children().isEmpty());
    }

    /**
     * 与 ExpressionService.Node 的识别规则一致：有 type 按 type；旧 JSON 没有 type 时，有子条件的是条件组。
     * 没写 type、没有子条件、没有字段和函数，但写了 logic 的节点（旧版保存过的空条件组，如 {"logic":"AND","children":[]}）也按条件组处理：
     * 根上是「没有条件树」，嵌套在条件树里是空条件组（结构校验报错），不会当成一个没有字段的字段条件。
     */
    private static boolean isGroup(JsonNode node) {
        String type = node.path("type").asText("");
        if (!type.isBlank()) return "GROUP".equals(type);
        return hasChildren(node) || (node.path("field").asText("").isBlank() && node.path("functionCode").asText("").isBlank()
                && !node.path("logic").asText("").isBlank());
    }

    private static boolean hasChildren(JsonNode node) {
        JsonNode children = node.get("children");
        return children != null && children.isArray() && !children.isEmpty();
    }

    /**
     * 一份已保存的配置 JSON 里全部非空的条件树（草稿、已发布版本都适用）。JSON 读不出来时抛 IllegalStateException：
     * 引用检查不能因为配置读不出来就当成「没有被引用」。
     */
    public static List<PathNode> pathTrees(String payloadJson, ObjectMapper json) {
        List<PathNode> trees = new ArrayList<>();
        if (payloadJson == null || payloadJson.isBlank()) return trees;
        JsonNode root;
        try {
            root = json.readTree(payloadJson);
        } catch (Exception ex) {
            throw new IllegalStateException("C1 路径配置不可读，请先修复配置", ex);
        }
        for (JsonNode path : root.path("paths")) {
            JsonNode tree = path.get("conditionAst");
            if (!isEmptyTree(tree)) trees.add(new PathNode(path.path("pathCode").asText(null), tree));
        }
        return trees;
    }

    /** 条件树的可读表达式（如 (clientClassificationStatus EQ COMPLETE AND clientExcluded(...) EQ false)）；空树返回 null。不抛异常。 */
    public static String describe(JsonNode tree) {
        if (isEmptyTree(tree)) return null;
        StringBuilder out = new StringBuilder();
        describe(tree, out);
        return out.toString();
    }

    private static void describe(JsonNode node, StringBuilder out) {
        if (isGroup(node)) {
            out.append('(');
            String logic = "OR".equals(node.path("logic").asText()) ? " OR " : " AND ";
            boolean first = true;
            for (JsonNode child : node.get("children")) {
                if (!first) out.append(logic);
                first = false;
                describe(child, out);
            }
            out.append(')');
            return;
        }
        String function = node.path("functionCode").asText("");
        if (!function.isBlank()) {
            out.append(function).append('(');
            JsonNode arguments = node.path("arguments");
            boolean first = true;
            if (arguments.isObject())
                for (Iterator<Map.Entry<String, JsonNode>> it = arguments.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> argument = it.next();
                    if (!first) out.append(", ");
                    first = false;
                    out.append(argument.getKey()).append('=').append(argument.getValue().isTextual() ? argument.getValue().asText() : argument.getValue().toString());
                }
            out.append(')');
        } else out.append(node.path("field").asText(""));
        String operator = node.path("operator").asText("");
        if (!operator.isBlank()) {
            JsonNode value = node.get("value");
            out.append(' ').append(operator).append(' ')
                    .append(value == null || value.isNull() ? "（空）" : value.isTextual() ? value.asText() : value.toString());
        }
    }

    // ---------- 从旧版全局准入条件迁入 ----------

    /**
     * 启动时把旧版「C1 全局准入条件」迁入路径配置（只在配置行不存在时执行一次，幂等）。
     * <ul>
     *   <li>旧版配置过条件：v1 = 旧条件树原样作为 DEFAULT 路径（失败原因码沿用 C1_CONDITION_NOT_MATCHED），已发生的决策按它回放；
     *       草稿 = 去掉两个旧版布尔字段的条件树（空掉的条件组一并去掉），其余条件原样保留，等人工确认后发布；
     *       布尔条件挂在「或」条件组里时另行提示（去掉后其余条件从「可选」变成「必须满足」，见 {@link #pruneLegacyBooleans}）。</li>
     *   <li>旧版没有配置条件（或保存过的条件是空的）：只有一条 DEFAULT 路径的空白草稿，没有已发布版本（执行时按 C1_CONFIG_MISSING 拒绝）。</li>
     *   <li>旧版条件读不出来：同空白草稿，并留下说明。</li>
     * </ul>
     * 旧版配置行原样保留（只读），不删除、不改写。迁移说明写入 migrationHintsJson，第一次发布后清空。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrateFromLegacy() {
        if (configs.existsById(1L)) return;
        C1ConditionConfig legacy = legacyConditions.findById(1L).orElse(null);
        String legacyJson = legacy == null || legacy.conditionJson == null || legacy.conditionJson.isBlank() ? null : legacy.conditionJson;
        List<Hint> hints = new ArrayList<>();
        // 草稿的 DEFAULT 路径：迁入后被编辑的那一条
        C1PathRule.Path draftDefault = new C1PathRule.Path(C1PathRule.DEFAULT_PATH, "默认路径", List.of(), 0, true, null, DEFAULT_FAILURE_CODE, null);
        LocalDateTime at = now();
        if (legacyJson == null) {
            hints.add(new Hint("NO_LEGACY_CONDITION", "旧版没有配置全局准入条件：路径配置从空白草稿开始。请给 DEFAULT 路径配置条件并发布，"
                    + "发布前 C1 按 C1_CONFIG_MISSING 拒绝全部候选合作方"));
        } else {
            JsonNode legacyTree = null;
            try {
                legacyTree = json.readTree(legacyJson);
            } catch (Exception ignored) {
                // 读不出来按未迁入处理，见下
            }
            ExpressionService.Node legacyNode = legacyTree != null && legacyTree.isObject() && !isEmptyTree(legacyTree) ? toNode(legacyTree) : null;
            if (legacyTree != null && legacyTree.isObject() && isEmptyTree(legacyTree)) {
                // 旧版保存过但条件是空的：等同没有配置条件
                hints.add(new Hint("NO_LEGACY_CONDITION", "旧版全局准入条件是空的（没有任何条件）：路径配置从空白草稿开始。请给 DEFAULT 路径配置条件并发布，"
                        + "发布前 C1 按 C1_CONFIG_MISSING 拒绝全部候选合作方"));
            } else if (legacyNode == null) {
                hints.add(new Hint("LEGACY_UNREADABLE", "旧版全局准入条件无法读取，未能迁入：请对照旧配置重新编辑 DEFAULT 路径并发布"));
            } else {
                draftDefault = migrateLegacyTree(legacyTree, legacyNode, legacy.versionNo, at, hints);
            }
        }
        C1PathConfig config = new C1PathConfig(1L);
        config.draftJson = write(new C1PathRule(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE, List.of(draftDefault)));
        config.draftUpdatedBy = MIGRATED_OPERATOR;
        config.draftUpdatedAt = at;
        config.migrationHintsJson = write(hints);
        configs.save(config);
        audit.save(new RuleAudit(AUDIT_TYPE, C1PathRule.RULE_CODE, "MIGRATE", MIGRATED_OPERATOR, legacyJson, config.draftJson));
    }

    /** 旧版条件树可用时：写入 v1（原样），并返回去掉旧版布尔字段后的草稿 DEFAULT 路径。 */
    private C1PathRule.Path migrateLegacyTree(JsonNode legacyTree, ExpressionService.Node legacyNode, int legacyVersionNo,
                                              LocalDateTime at, List<Hint> hints) {
        C1PathRule.Path verbatim = new C1PathRule.Path(C1PathRule.DEFAULT_PATH, "默认路径（旧版全局准入条件）", List.of(), 0, true,
                legacyNode, LEGACY_FAILURE_CODE, 1);
        String payload = write(new C1PathRule(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE, List.of(verbatim)));
        versions.save(new C1PathVersion(1, C1PathRule.BRANCH_MODE, payload, at, MIGRATED_OPERATOR,
                "系统迁移：旧版全局准入条件（版本 " + legacyVersionNo + "）原样转为 DEFAULT 路径"));
        int[] removed = {0};
        Pruning pruning = pruneLegacyBooleans(legacyTree);
        JsonNode pruned = pruning.tree();
        hints.add(new Hint("V1_SNAPSHOT", "v1 = 旧版全局准入条件原样（失败原因码 " + LEGACY_FAILURE_CODE + "），已发生的决策按它回放；"
                + "草稿是新版配置，确认后发布为 v2"));
        if (pruning.removed() > 0)
            hints.add(new Hint("LEGACY_BOOLEANS_REMOVED", "草稿已移除 " + pruning.removed() + " 个旧版布尔条件（"
                    + String.join(" / ", LEGACY_BOOLEAN_FIELDS) + "）：合作方准入是否覆盖该币种 / 国家改由 APPETITE_MATCH 四级匹配判定"
                    + "（不覆盖时 APPETITE_NOT_SET）"));
        if (pruned != null && pruning.removedFromOr() > 0)
            hints.add(new Hint("LEGACY_BOOLEAN_IN_OR", "有 " + pruning.removedFromOr() + " 个旧版布尔条件在「或」条件组里：去掉后该组只剩其余条件，"
                    + "旧版靠这个布尔条件放行的请求会不满足路径（APPETITE_MATCH 是必检，与路径条件是与的关系，补不回「或」的关系）。"
                    + "请重点核对 DEFAULT 路径的条件树"));
        hints.add(new Hint("OTHER_CONDITIONS_PRESERVED", pruned == null
                ? "旧条件只有这两个布尔条件：草稿的 DEFAULT 路径没有条件树，请补充条件后发布（启用的路径必须有条件树）"
                : "其余条件原样保留在草稿的 DEFAULT 路径里，请核对后发布"));
        List<String> ruleIds = appetites.findAll().stream()
                .filter(rule -> rule.conditionJson != null && !rule.conditionJson.isBlank() && referencesAnyLegacyField(rule.conditionJson))
                .map(rule -> "#" + rule.id).sorted().toList();
        if (!ruleIds.isEmpty())
            hints.add(new Hint("APPETITE_RULES_WITH_BOOLEANS", "准入规则 " + String.join("、", ruleIds)
                    + " 的附加条件仍引用旧版布尔字段：这些规则在 C1 判定里按「没有取值、不命中」跳过，请到合作方详情重新保存它们的附加条件"));
        return new C1PathRule.Path(C1PathRule.DEFAULT_PATH, "默认路径", List.of(), 0, true,
                pruned == null ? null : toNode(pruned), DEFAULT_FAILURE_CODE, null);
    }

    private boolean referencesAnyLegacyField(String conditionJson) {
        try {
            JsonNode tree = json.readTree(conditionJson);
            return LEGACY_BOOLEAN_FIELDS.stream().anyMatch(field -> usesField(tree, field));
        } catch (Exception ex) {
            return false;
        }
    }

    /** 条件树 JSON → 节点对象；解析失败返回 null。 */
    private ExpressionService.Node toNode(JsonNode tree) {
        try {
            return json.treeToValue(tree, ExpressionService.Node.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean usesField(JsonNode node, String field) {
        if (node == null) return false;
        if (node.isObject()) {
            if (field.equals(node.path("field").asText(null))) return true;
            for (JsonNode child : node) if (usesField(child, field)) return true;
        } else if (node.isArray()) for (JsonNode child : node) if (usesField(child, field)) return true;
        return false;
    }

    /** 去掉旧版布尔字段后的条件树：tree 为 null 表示整棵树都去掉了；removed 是去掉的条件数；removedFromOr 是其中位于「或」关系里的个数。 */
    record Pruning(JsonNode tree, int removed, int removedFromOr) {
    }

    /**
     * 去掉引用旧版布尔字段的字段条件，空掉的条件组一并去掉。
     * <p>布尔条件挂在「与」关系里时，去掉它只是少了一道准入门槛（已由必检的 APPETITE_MATCH 接管）；
     * 挂在「或」关系里时（同组还有别的条件），去掉后剩下的条件反而成了必须满足，需要单独提示人工核对。
     * 只有一个条件的条件组不改变与或关系，按上层关系处理。
     */
    static Pruning pruneLegacyBooleans(JsonNode node) {
        int[] removed = {0}, fromOr = {0};
        JsonNode tree = prune(node, new HashSet<>(LEGACY_BOOLEAN_FIELDS), false, removed, fromOr);
        return new Pruning(tree, removed[0], fromOr[0]);
    }

    private static JsonNode prune(JsonNode node, Set<String> legacyFields, boolean orContext, int[] removed, int[] fromOr) {
        if (node == null || node.isNull()) return null;
        if (!isGroup(node)) {
            if (legacyFields.contains(node.path("field").asText(""))) {
                removed[0]++;
                if (orContext) fromOr[0]++;
                return null;
            }
            return node;
        }
        ArrayNode children = JsonNodeFactory.instance.arrayNode();
        for (JsonNode child : node.path("children")) {
            JsonNode kept = prune(child, legacyFields, childContext(node, orContext), removed, fromOr);
            if (kept != null) children.add(kept);
        }
        if (children.isEmpty()) return null;
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.set("children", children);
        return copy;
    }

    /** 条件组的子条件所处的与或关系：多个条件的组按自己的 logic，单个条件的组沿用上层的关系。 */
    private static boolean childContext(JsonNode group, boolean orContext) {
        if (group.path("children").size() <= 1) return orContext;
        return "OR".equalsIgnoreCase(group.path("logic").asText(""));
    }

    // ---------- 内部读取 ----------

    private PublishedView latest() {
        return versions.findTopByOrderByVersionDesc().map(this::toView).orElse(null);
    }

    private PublishedView toView(C1PathVersion row) {
        return new PublishedView(row.version, read(row.payloadJson), row.branchMode, row.publishedAt, row.publishedBy, row.note);
    }

    private VersionSummary summarize(int version, LocalDateTime publishedAt, String publishedBy, String note, C1PathRule rule) {
        List<PathSummary> paths = rule.paths().stream().map(path -> new PathSummary(path.pathCode(), path.pathName(), path.providerCodes(),
                path.priority(), path.enabled(), path.failureReasonCode(), describe(json.valueToTree(path.conditionAst())))).toList();
        return new VersionSummary(version, publishedAt, publishedBy, note, paths);
    }

    private List<Hint> hints(C1PathConfig config) {
        if (config == null || config.migrationHintsJson == null || config.migrationHintsJson.isBlank()) return List.of();
        try {
            return List.of(json.readValue(config.migrationHintsJson, Hint[].class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    C1PathRule read(String payload) {
        try {
            C1PathRule rule = json.readValue(payload, C1PathRule.class);
            if (rule == null) throw new IllegalStateException("C1 路径配置为空");
            return rule;
        } catch (Exception ex) {
            throw new IllegalStateException("C1 路径配置不可读", ex);
        }
    }

    private JsonNode readTree(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("C1 路径配置不可读", ex);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("C1 路径配置序列化失败", ex);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank() || value.trim().length() > 64) throw new IllegalArgumentException(message);
        return value.trim();
    }
}
