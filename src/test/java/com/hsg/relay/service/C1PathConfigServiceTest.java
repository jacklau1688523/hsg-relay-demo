package com.hsg.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.api.BusinessFunctionController;
import com.hsg.relay.api.FieldMetadataController;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.C1PathVersion;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.C1ConditionConfigRepository;
import com.hsg.relay.repo.C1PathConfigRepository;
import com.hsg.relay.repo.C1PathVersionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.hsg.relay.service.C1PathConfigService.Hint;
import static com.hsg.relay.service.C1PathConfigService.PathSummary;
import static com.hsg.relay.service.C1PathConfigService.PublishRequest;
import static com.hsg.relay.service.C1PathConfigService.VersionSummary;
import static com.hsg.relay.service.C1PathFixtures.EXCLUSION_ARGUMENTS;
import static com.hsg.relay.service.C1PathFixtures.field;
import static com.hsg.relay.service.C1PathFixtures.function;
import static com.hsg.relay.service.C1PathFixtures.group;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C1 合作方动态规则路径配置的草稿、校验、发布、历史版本、迁移和引用保护。执行器怎么按路径判定见 C1PathRoutingTest。
 * <p>类上不加 {@code @Transactional}：全新数据库「只有空白草稿、没有已发布版本」的初始状态要在用例里直接断言；
 * 改动配置的用例各自带事务，结束后回滚，不污染后面的用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relayc1path;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class C1PathConfigServiceTest {
    private static final String OPS = "ops.path";
    /** 与线上旧版全局准入条件（v69）相同的形状：客户分类 COMPLETE、两个旧版布尔条件、客户不在排除名单。 */
    private static final String COLLECTION_CONTAINS = "collectionContains";
    /** 与页面保存的形态一致：集合来自元数据字段 providerExcludedClientIds（字段解析器取数），比较元素是本笔交易的客户编号。 */
    private static final Map<String, Object> CONTAINS_ARGUMENTS = Map.of("collection", Map.of("source", "FIELD", "fieldCode", "providerExcludedClientIds"),
            "element", "$transaction.clientId");

    @Autowired
    C1PathConfigService paths;
    @Autowired
    C1Service c1;
    @Autowired
    C1PathConfigRepository configs;
    @Autowired
    C1PathVersionRepository versions;
    @Autowired
    C1ConditionConfigRepository legacy;
    @Autowired
    RuleAuditRepository audits;
    @Autowired
    FieldMetadataController metadata;
    @Autowired
    BusinessFunctionController functions;
    @Autowired
    FunctionMetadataService functionMetadata;
    @Autowired
    AppetiteRepository appetites;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    // ---------- 构造 ----------

    /** 一条路径：失败原因码原样给出（不再走规范化补缺省值），版本号留空。 */
    private static C1PathRule.Path path(String code, String name, List<String> providers, Integer priority, Boolean enabled,
                                        String reason, ExpressionService.Node tree) {
        return new C1PathRule.Path(code, name, providers, priority, enabled, tree, reason, null);
    }

    private ObjectNode body(C1PathRule.Path... pathList) {
        return json.valueToTree(new C1PathRule("C1", "FIRST_MATCH", List.of(pathList)));
    }

    /** 一条启用的专用路径，条件 flowCategory != NONE（对任何客户都成立）。 */
    private static C1PathRule.Path line(String code, List<String> providers) {
        return path(code, "线路" + code, providers, 1, true, null, group("AND", field("flowCategory", "NE", "NONE")));
    }

    /** DEFAULT 路径，条件 flowCategory != NONE。 */
    private static C1PathRule.Path defaultPath() {
        return path("DEFAULT", "默认路径", List.of(), 0, true, null, group("AND", field("flowCategory", "NE", "NONE")));
    }

    /** 一个空的条件组（type = GROUP、没有子条件）：在根上表示「没有条件树」，嵌套在条件树里是结构错误。 */
    private static ExpressionService.Node emptyGroup(String logic) {
        return new ExpressionService.Node("GROUP", logic, List.of(), null, null, null, null, null);
    }

    private static C1Service.Simulation simulate(String client, String provider, String currency, String country, String amount) {
        return new C1Service.Simulation(client, provider, currency, country, new BigDecimal(amount));
    }

    private int draftVersion() {
        return paths.view().draft().version();
    }

    /** 保存草稿并发布，返回新版本。 */
    private C1PathConfigService.PublishedView saveAndPublish(ObjectNode rule, String note) {
        paths.saveDraft(rule, null, OPS);
        return paths.publish(new PublishRequest(null, OPS, note));
    }

    private MockHttpServletRequestBuilder request(String method, String path, Object body) throws Exception {
        return MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
                .content((body == null ? "{}" : json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode ok(RequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode send(String method, String path, Object body) throws Exception {
        return ok(request(method, path, body));
    }

    private String errorOf(RequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("error").asText();
    }

    // ---------- 初始状态 ----------

    @Test
    void freshDatabaseHasOnlyASkeletonDraftAndEveryCandidateIsRejectedUntilSomethingIsPublished() {
        var view = paths.view();
        assertThat(view.ruleCode()).isEqualTo("C1");
        assertThat(view.branchMode()).isEqualTo("FIRST_MATCH");
        assertThat(view.published()).isNull();
        assertThat(view.history()).isEmpty();
        assertThat(view.draftChanged()).isTrue();
        // 草稿只有一条 DEFAULT 路径，没有条件树，由系统迁移写入
        assertThat(view.draft().updatedBy()).isEqualTo("system");
        assertThat(view.draft().rule().paths()).singleElement().satisfies(path -> {
            assertThat(path.pathCode()).isEqualTo("DEFAULT");
            assertThat(path.on()).isTrue();
            assertThat(path.providerCodes()).isEmpty();
            assertThat(path.conditionAst()).isNull();
        });
        assertThat(view.migrationHints()).extracting(Hint::code).containsExactly("NO_LEGACY_CONDITION");

        var migration = paths.migrationView();
        assertThat(migration.migrated()).isTrue();
        assertThat(migration.legacyConfigured()).isFalse();
        assertThat(migration.legacyVersionNo()).isEqualTo(1);
        assertThat(migration.pendingReview()).isTrue();
        assertThat(migration.hints()).extracting(Hint::code).containsExactly("NO_LEGACY_CONDITION");
        // 旧版配置行原样保留（只读）
        assertThat(legacy.findById(1L).orElseThrow().conditionJson).isBlank();
        // 审计：一条系统迁移记录，操作人 system
        assertThat(paths.audits()).singleElement().satisfies(audit -> {
            assertThat(audit.objectType).isEqualTo("C1_PATH");
            assertThat(audit.objectId).isEqualTo("C1");
            assertThat(audit.operation).isEqualTo("MIGRATE");
            assertThat(audit.operatorName).isEqualTo("system");
            assertThat(audit.beforeJson).isNull();
        });

        // 没有已发布版本：每个候选合作方都被拒绝（HTTP 200 + passed=false），不会默认通过
        var result = c1.evaluate(simulate("C10002", "PROV-T1", "USD", "KG", "100"));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("C1_CONFIG_MISSING");
        assertThat(result.configVersion()).isNull();
        assertThat(result.pathCode()).isNull();
        assertThat(result.functionCalls()).isEmpty();
        assertThat(result.steps()).extracting(C1Service.Trace::step).containsExactly("0. 路径配置", "失败原因");
        assertThat(result.steps().get(0).status()).isEqualTo("FAIL");

        // 空白草稿不能发布：启用的路径必须有自己的条件
        assertThatThrownBy(() -> paths.publish(new PublishRequest(null, OPS, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("发布校验未通过")
                .hasMessageContaining("路径 DEFAULT：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）");
        assertThat(versions.count()).isZero();
    }

    // ---------- 结构校验 ----------

    @Test
    void structureErrorsAreReportedBeforeTheRuleIsParsedOrCompiled() throws Exception {
        assertThat(paths.compile(null).errors()).containsExactly("路径配置不能为空");
        assertThat(paths.compile(json.readTree("[]")).errors()).containsExactly("路径配置必须是 JSON 对象");
        assertThat(paths.compile(json.readTree("{\"ruleCode\":\"C2\",\"branchMode\":\"PRIORITY\"}")).errors())
                .containsExactlyInAnyOrder("ruleCode 须为 C1", "branchMode 须为 FIRST_MATCH（按候选合作方选路径，没有其它分支模式）", "paths 必须是数组");

        ObjectNode notArray = body(defaultPath());
        notArray.putObject("paths");
        assertThat(paths.check(notArray).errors()).containsExactly("paths 必须是数组");
        ObjectNode notObject = body(defaultPath());
        notObject.withArray("paths").removeAll().add("P1");
        assertThat(paths.check(notObject).errors()).containsExactly("第 1 条路径必须是 JSON 对象");

        // 优先级必须是整数：Jackson 会把 1.5 悄悄截成 1，所以要在 JSON 层拦下
        ObjectNode fraction = body(defaultPath());
        ((ObjectNode) fraction.withArray("paths").get(0)).put("priority", 1.5);
        assertThat(paths.compile(fraction).errors()).containsExactly("路径 DEFAULT：优先级必须是整数");

        ObjectNode providers = body(defaultPath());
        ((ObjectNode) providers.withArray("paths").get(0)).put("providerCodes", "PROV-GD");
        assertThat(paths.compile(providers).errors()).containsExactly("路径 DEFAULT：providerCodes 必须是数组");
        ((ObjectNode) providers.withArray("paths").get(0)).putArray("providerCodes").add(1);
        assertThat(paths.compile(providers).errors()).containsExactly("路径 DEFAULT：绑定的合作方编码必须是字符串：1");

        ObjectNode types = body(line("P1", List.of("PROV-GD")));
        ObjectNode first = (ObjectNode) types.withArray("paths").get(0);
        first.put("pathCode", 1).put("pathName", 2).put("failureReasonCode", 3).put("conditionAst", "flowCategory");
        assertThat(paths.compile(types).errors()).containsExactlyInAnyOrder(
                "路径 1：路径编码必须是字符串", "路径 1：路径名称必须是字符串", "路径 1：失败原因码必须是字符串", "路径 1：条件树节点必须是 JSON 对象");

        // 嵌套的空条件组是错误（根条件组为空视为「没有条件树」，草稿允许）
        ObjectNode nested = body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null,
                group("AND", field("flowCategory", "NE", "NONE"), emptyGroup("OR"))));
        var check = paths.check(nested);
        assertThat(check.ok()).isFalse();
        assertThat(check.errors()).containsExactly("路径 P1：条件树存在空的条件组，请删除它或添加条件");
        assertThat(check.warnings()).isEmpty();
        assertThat(check.paths()).isEmpty();
        assertThatThrownBy(() -> paths.saveDraft(nested, null, OPS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("C1 路径配置结构无效：\n- 路径 P1：条件树存在空的条件组，请删除它或添加条件");
        // 旧版保存的 JSON 不带 type：没有子条件、没有字段和函数、只写了 logic 的节点同样按空条件组处理
        ObjectNode untyped = body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null,
                group("AND", field("flowCategory", "NE", "NONE"))));
        ObjectNode untypedEmpty = json.createObjectNode().put("logic", "OR");
        untypedEmpty.putArray("children");
        ((ObjectNode) untyped.withArray("paths").get(0).get("conditionAst")).withArray("children").add(untypedEmpty);
        assertThat(paths.check(untyped).errors()).containsExactly("路径 P1：条件树存在空的条件组，请删除它或添加条件");

        // 旧版布尔字段不能再出现在路径条件里，报错里说明已由 APPETITE_MATCH 取代
        ObjectNode legacyBoolean = body(path("DEFAULT", "默认路径", List.of(), 0, true, null,
                group("AND", field("appetiteCurrencyEnabled", "EQ", true), field("flowCategory", "NE", "NONE"))));
        assertThat(paths.compile(legacyBoolean).errors()).singleElement().asString()
                .contains("appetiteCurrencyEnabled").contains("APPETITE_MATCH").contains("APPETITE_NOT_SET").contains("请删除这个条件");
    }

    @Test
    @Transactional
    void pathCodesNamesAndFailureReasonCodesFollowTheRulesAndReservedCodesStayWithTheSystem() {
        var check = paths.check(body(
                path("p1", "小写编码", List.of("PROV-GD"), 0, true, null, null),
                path("P2", " ", List.of("PROV-W"), -1, true, null, null),
                path("P2", "重复编码", List.of("PROV-W"), 0, true, null, null),
                path("DEFAULT", "默认路径", List.of("PROV-GD"), 0, true, null, null)));
        // 小写编码、空名称、负优先级、重复编码、DEFAULT 绑定合作方；没有条件树的启用路径在发布前检查里报错
        assertThat(check.errors()).containsExactlyInAnyOrder(
                "路径 p1：路径编码须以大写字母开头，仅含大写字母、数字、下划线，最多 32 位",
                "路径 P2：优先级不可小于 0",
                "路径 P2：路径名称不能为空",
                "路径 P2：路径编码重复",
                "路径 P2：失败原因码 C1_PATH_P2_NOT_MATCHED 与其它路径重复",
                "路径 DEFAULT：DEFAULT 路径不绑定合作方",
                "路径 p1：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）",
                "路径 P2：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）",
                "路径 P2：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）",
                "路径 DEFAULT：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）",
                "合作方 PROV-W 同时绑定在多条启用的路径：P2、P2；一个合作方只能由一条启用的路径负责，请调整绑定或停用其中一条");

        // 没有专用路径时的兜底：必须恰好一条 DEFAULT
        assertThat(paths.check(body(line("P1", List.of("PROV-GD")))).errors())
                .containsExactly("必须有一条 DEFAULT 路径：它不绑定合作方，没有专用路径的合作方走它");
        assertThat(paths.check(body(defaultPath(), path("DEFAULT", "又一个默认", List.of("PROV-GD"), 0, true, "ANOTHER_DEFAULT", null))).errors())
                .containsExactly("路径 DEFAULT：路径编码重复", "路径 DEFAULT：DEFAULT 路径不绑定合作方", "只能有一条 DEFAULT 路径，现在有 2 条",
                        "路径 DEFAULT：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）");

        // 系统原因码不能给路径用：PASS、路径找不到 / 冲突、客户 / 准入 / 配置类原因码、旧版原因码
        var reserved = paths.check(body(
                path("DEFAULT", "默认路径", List.of(), 0, true, null, group("AND", field("flowCategory", "NE", "NONE"))),
                path("P3", "通过", List.of("PROV-GD"), 1, true, "PASS", group("AND", field("flowCategory", "NE", "NONE"))),
                path("P4", "路径找不到", List.of(), 1, true, "C1_PATH_NOT_FOUND", null),
                path("P5", "路径冲突", List.of(), 1, true, "C1_PATH_CONFLICT", null),
                path("P6", "旧版原因码", List.of(), 1, true, "C1_CONDITION_NOT_MATCHED", null),
                path("P7", "客户分类", List.of(), 1, true, "CLIENT_NOT_CLASSIFIED", null),
                path("P8", "准入", List.of(), 1, true, "APPETITE_NOT_SET", null),
                path("P9", "配置", List.of(), 1, true, "C1_CONFIG_MISSING", null)));
        assertThat(reserved.ok()).isFalse();
        assertThat(reserved.errors()).filteredOn(error -> error.contains("系统原因码")).containsExactlyInAnyOrder(
                "路径 P3：失败原因码 PASS 是系统原因码，请换一个（系统在路径找不到、路径冲突、客户分类 / 排除、准入匹配和配置缺失时使用这些编码）",
                "路径 P4：失败原因码 C1_PATH_NOT_FOUND 是系统原因码，请换一个（系统在路径找不到、路径冲突、客户分类 / 排除、准入匹配和配置缺失时使用这些编码）",
                "路径 P5：失败原因码 C1_PATH_CONFLICT 是系统原因码，请换一个（系统在路径找不到、路径冲突、客户分类 / 排除、准入匹配和配置缺失时使用这些编码）",
                "路径 P6：失败原因码 C1_CONDITION_NOT_MATCHED 是系统原因码，请换一个（系统在路径找不到、路径冲突、客户分类 / 排除、准入匹配和配置缺失时使用这些编码）",
                "路径 P7：失败原因码 CLIENT_NOT_CLASSIFIED 是系统原因码，请换一个（系统在路径找不到、路径冲突、客户分类 / 排除、准入匹配和配置缺失时使用这些编码）",
                "路径 P8：失败原因码 APPETITE_NOT_SET 是系统原因码，请换一个（系统在路径找不到、路径冲突、客户分类 / 排除、准入匹配和配置缺失时使用这些编码）",
                "路径 P9：失败原因码 C1_CONFIG_MISSING 是系统原因码，请换一个（系统在路径找不到、路径冲突、客户分类 / 排除、准入匹配和配置缺失时使用这些编码）");
        // 格式不对的原因码
        assertThat(paths.check(body(path("DEFAULT", "默认路径", List.of(), 0, true, "gd blocked", null))).errors())
                .contains("路径 DEFAULT：失败原因码须以大写字母开头，仅含大写字母、数字、下划线，最多 64 位");

        // 结构和编译都通过，所以草稿可以先存下来；发布留给「校验」和发布时的检查
        var saved = paths.saveDraft(body(path("p1", "小写编码", List.of("PROV-GD"), 0, true, null, null)), null, OPS);
        assertThat(saved.draft().rule().paths().get(0).pathCode()).isEqualTo("p1");
        assertThat(paths.compile(body(path("p1", "小写编码", List.of("PROV-GD"), 0, true, null, null))).ok()).isTrue();
        assertThatThrownBy(() -> paths.publish(new PublishRequest(null, OPS, null)))
                .hasMessageContaining("发布校验未通过")
                .hasMessageContaining("路径 p1：路径编码须以大写字母开头");
    }

    @Test
    @Transactional
    void publishChecksProvidersConflictsAndTreesWhileWarningsOnlyNeedToBeRead() throws Exception {
        // 不存在的合作方
        assertThat(paths.check(body(line("P1", List.of("PROV-XX")), defaultPath())).errors())
                .containsExactly("路径 P1：绑定的合作方 PROV-XX 不存在");

        // 冲突：同一个合作方绑定在多条启用的路径上。优先级只用来排序，化解不了冲突；停用的路径不参与
        assertThat(paths.check(body(
                path("P1", "线路一", List.of("PROV-GD", "PROV-W"), 1, true, null, group("AND", field("flowCategory", "NE", "NONE"))),
                path("P2", "线路二", List.of("PROV-GD"), 2, true, null, group("AND", field("flowCategory", "NE", "NONE"))),
                path("P3", "线路三", List.of("PROV-GD"), 3, false, null, null),
                defaultPath())).errors())
                .containsExactly("合作方 PROV-GD 同时绑定在多条启用的路径：P1、P2；一个合作方只能由一条启用的路径负责，请调整绑定或停用其中一条");

        // 启用的路径必须有条件树：空条件树不等于通过
        assertThat(paths.check(body(line("P1", List.of("PROV-GD")), defaultPath())).ok()).isTrue();
        assertThat(paths.check(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null, null), defaultPath())).errors())
                .containsExactly("路径 P1：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）");
        // 根条件组为空的 JSON 形状同样算没有条件树：带 type 的空组，以及旧版不带 type 的空组
        assertThat(paths.check(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null, emptyGroup("AND")), defaultPath())).errors())
                .containsExactly("路径 P1：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）");
        ObjectNode legacyEmpty = body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null, null), defaultPath());
        ((ObjectNode) legacyEmpty.withArray("paths").get(0)).set("conditionAst", json.readTree("{\"logic\":\"AND\",\"children\":[]}"));
        assertThat(paths.check(legacyEmpty).errors())
                .containsExactly("路径 P1：已启用但没有条件树；启用的路径必须有自己的条件（空条件树不等于通过）");

        // 停用的路径可以没有条件树，但它的条件树同样要能编译（恢复启用时必须可用）
        var disabled = paths.check(body(path("P1", "线路一", List.of("PROV-GD"), 1, false, null, null), defaultPath()));
        assertThat(disabled.ok()).isTrue();
        assertThat(disabled.warnings()).isEmpty();
        assertThat(paths.check(body(path("P1", "线路一", List.of("PROV-GD"), 1, false, null,
                group("AND", field("noSuchField", "EQ", "X"))), defaultPath())).errors())
                .containsExactly("路径 P1：条件树无法编译：非法字段: noSuchField");
        assertThat(paths.check(body(path("P1", "线路一", List.of("PROV-GD"), 1, false, null,
                group("AND", field("flowCategory", "GT", "NONE"))), defaultPath())).errors())
                .containsExactly("路径 P1：条件树无法编译：字段 flowCategory 不适用于当前规则或不支持该操作符");

        // 提示不阻止发布，但要读：启用的专用路径没绑定任何合作方；DEFAULT 停用后没有专用路径的合作方会被过滤
        var warnings = paths.check(body(
                path("P1", "线路一", List.of(), 1, true, null, group("AND", field("flowCategory", "NE", "NONE"))),
                path("DEFAULT", "默认路径", List.of(), 0, false, null, group("AND", field("flowCategory", "NE", "NONE")))));
        assertThat(warnings.ok()).isTrue();
        assertThat(warnings.errors()).isEmpty();
        assertThat(warnings.warnings()).containsExactly(
                "路径 P1：已启用但未绑定任何合作方，不会命中",
                "路径 DEFAULT 已停用：没有启用专用路径的合作方将按 C1_PATH_NOT_FOUND 过滤");

        // 发布时任一项不满足都拒绝，不写版本、不移动指针
        assertThatThrownBy(() -> saveAndPublish(body(
                path("P1", "线路一", List.of("PROV-GD"), 1, true, null, group("AND", field("flowCategory", "NE", "NONE"))),
                path("P2", "线路二", List.of("PROV-GD"), 2, true, null, group("AND", field("flowCategory", "NE", "NONE"))),
                defaultPath()), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("发布校验未通过：\n- 合作方 PROV-GD 同时绑定在多条启用的路径：P1、P2；一个合作方只能由一条启用的路径负责，请调整绑定或停用其中一条");
        assertThat(versions.count()).isZero();
        assertThat(configs.findById(1L).orElseThrow().publishedVersion).isNull();
        assertThat(paths.audits()).extracting(audit -> audit.operation).containsExactly("SAVE_DRAFT", "MIGRATE");
    }

    // ---------- 草稿 ----------

    @Test
    @Transactional
    void savingADraftNormalizesItAndTheDraftNeverTakesPartInExecution() {
        saveAndPublish(body(defaultPath()), "基线");
        C1PathRule draft = new C1PathRule("C1", "FIRST_MATCH", List.of(
                path("DEFAULT", " 默认路径 ", List.of(), null, null, null, group("AND", field("flowCategory", "EQ", "NONE"))),
                new C1PathRule.Path("P2", "线路二", List.of(" PROV-W ", "PROV-GD", "PROV-W", ""), 5, null, null, "  ", 7),
                path("P1", "线路一", List.of("PROV-GD"), 5, false, "C1_GD_BLOCKED", null)));
        var view = paths.saveDraft(json.valueToTree(draft), draftVersion(), OPS);
        // 规范形式：编码 / 名称去空格，合作方去重排序，启用与优先级的缺省值，没填的原因码补 C1_PATH_编码_NOT_MATCHED，版本号清空，
        // 路径按（优先级，编码）排序、DEFAULT 排最后
        assertThat(view.draft().rule().paths()).containsExactly(
                path("P1", "线路一", List.of("PROV-GD"), 5, false, "C1_GD_BLOCKED", null),
                path("P2", "线路二", List.of("PROV-GD", "PROV-W"), 5, true, "C1_PATH_P2_NOT_MATCHED", null),
                path("DEFAULT", "默认路径", List.of(), 0, true, "C1_PATH_DEFAULT_NOT_MATCHED", group("AND", field("flowCategory", "EQ", "NONE"))));
        assertThat(view.draft().updatedBy()).isEqualTo(OPS);
        assertThat(view.draftChanged()).isTrue();
        assertThat(view.published().version()).isEqualTo(1);

        // 执行只认已发布版本：草稿里 DEFAULT 的条件恒不成立，执行仍按 v1 通过
        var result = c1.evaluate(simulate("C10002", "PROV-T1", "USD", "KG", "100"));
        assertThat(result.reasonCode()).isEqualTo("PASS");
        assertThat(result.configVersion()).isEqualTo(1);

        // 草稿试算按草稿来：结果里的版本号为空（草稿不是已发布版本）
        var simulated = c1.evaluateDraft(simulate("C10002", "PROV-T1", "USD", "KG", "100"), json.valueToTree(draft));
        assertThat(simulated.reasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(simulated.configVersion()).isNull();
        assertThat(simulated.pathCode()).isEqualTo("DEFAULT");
        // 不用自己拼 JSON：直接用已保存的草稿试算，结论相同
        assertThat(c1.evaluateDraft(simulate("C10002", "PROV-T1", "USD", "KG", "100"), paths.storedDraft()).reasonCode())
                .isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(c1.evaluateDraft(simulate("C10002", "PROV-T1", "USD", "KG", "100"), paths.view().draft().rule()).reasonCode())
                .isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
    }

    @Test
    @Transactional
    void savingAndPublishingNeedAnOperatorAndTheCurrentDraftVersion() {
        assertThatThrownBy(() -> paths.saveDraft(body(defaultPath()), null, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("操作人不能为空");
        assertThatThrownBy(() -> paths.publish(null)).hasMessage("发布请求不能为空");
        assertThatThrownBy(() -> paths.publish(new PublishRequest(null, null, null))).hasMessage("发布必须提供操作人");

        // 没有可发布的草稿
        var config = configs.findById(1L).orElseThrow();
        String skeleton = config.draftJson;
        config.draftJson = " ";
        assertThatThrownBy(() -> paths.publish(new PublishRequest(null, OPS, null))).hasMessage("没有可发布的草稿：请先保存路径配置草稿");
        config.draftJson = skeleton;

        // 草稿被他人改过：带旧版本号保存、发布都被拒绝
        paths.saveDraft(body(defaultPath()), null, OPS);
        int current = draftVersion();
        assertThat(current).isEqualTo(2);
        assertThatThrownBy(() -> paths.saveDraft(body(defaultPath()), current - 1, OPS))
                .hasMessage("草稿已被他人修改（当前草稿 v" + current + "），请刷新后重新编辑");
        assertThatThrownBy(() -> paths.publish(new PublishRequest(current - 1, OPS, null)))
                .hasMessage("草稿已被他人修改（当前草稿 v" + current + "），请刷新后重新发布");
        assertThat(draftVersion()).isEqualTo(current);

        // 发布后：迁移提示清空，审计依次是迁移、保存草稿、发布
        var published = paths.publish(new PublishRequest(current, OPS, "  第一版  "));
        assertThat(published.publishedBy()).isEqualTo(OPS);
        assertThat(published.note()).isEqualTo("第一版");
        assertThat(configs.findById(1L).orElseThrow().migrationHintsJson).isNull();
        assertThat(paths.view().migrationHints()).isEmpty();
        var history = paths.audits();
        assertThat(history).extracting(audit -> audit.operation).containsExactly("PUBLISH", "SAVE_DRAFT", "MIGRATE");
        assertThat(history.get(0).beforeJson).isNull();
        assertThat(history.get(0).afterJson).isEqualTo(versions.findByVersion(1).orElseThrow().payloadJson);
        assertThat(history.get(1).beforeJson).isEqualTo(skeleton);
        assertThat(history.get(1).afterJson).contains("\"pathCode\":\"DEFAULT\"").contains("\"conditionAst\":{");
        // 草稿与已发布版本的快照是同一份规范化内容（草稿不带路径版本号）
        assertThat(history.get(1).afterJson).isEqualTo(versions.findByVersion(1).orElseThrow().payloadJson.replace("\"version\":1", "\"version\":null"));
    }

    // ---------- 发布与版本 ----------

    @Test
    @Transactional
    void publishedVersionsAreImmutableAndOldDecisionsReplayAgainstTheirOwnVersion() {
        var first = saveAndPublish(body(path("DEFAULT", "默认路径", List.of(), 0, true, null,
                group("AND", field("paymentCurrency", "EQ", "USD")))), "v1：只收美元");
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.branchMode()).isEqualTo("FIRST_MATCH");
        String payload = versions.findByVersion(1).orElseThrow().payloadJson;
        // 快照：每条路径带上发布版本号；草稿里的版本号仍为空
        assertThat(first.rule().paths().get(0).version()).isEqualTo(1);
        assertThat(paths.view().draft().rule().paths().get(0).version()).isNull();
        assertThat(paths.view().draftChanged()).isFalse();

        var second = saveAndPublish(body(path("DEFAULT", "默认路径", List.of(), 0, true, null,
                group("AND", field("paymentCurrency", "EQ", "HKD")))), "v2：只收港币");
        assertThat(second.version()).isEqualTo(2);
        assertThat(versions.count()).isEqualTo(2);
        assertThat(versions.findByVersion(1).orElseThrow().payloadJson).isEqualTo(payload);
        var config = configs.findById(1L).orElseThrow();
        assertThat(config.publishedVersion).isEqualTo(2);
        assertThat(config.publishedBy).isEqualTo(OPS);

        // 历史：新 → 旧，带每条路径的可读条件
        List<VersionSummary> history = paths.history();
        assertThat(history).extracting(VersionSummary::version).containsExactly(2, 1);
        assertThat(history).extracting(VersionSummary::note).containsExactly("v2：只收港币", "v1：只收美元");
        assertThat(history.get(1).paths()).extracting(PathSummary::condition).containsExactly("(paymentCurrency EQ USD)");
        assertThat(history.get(1).paths().get(0).enabled()).isTrue();
        assertThat(paths.version(1).note()).isEqualTo("v1：只收美元");

        // 默认按最新版本 v2：港币路径条件不满足，按路径自己的失败原因码过滤
        var usd = simulate("C10002", "PROV-T1", "USD", "KG", "100");
        var latest = c1.evaluate(usd);
        assertThat(latest.reasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(latest.configVersion()).isEqualTo(2);
        assertThat(latest.steps().get(0).detail()).isEqualTo("已发布版本 v2（最新），共 1 条路径，启用 1 条");
        // 回放：同一笔按它当时的版本 v1 判定，结果可重现
        var replayed = c1.evaluate(new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal("100"), Map.of(), 1));
        assertThat(replayed.reasonCode()).isEqualTo("PASS");
        assertThat(replayed.configVersion()).isEqualTo(1);
        assertThat(replayed.steps().get(0).detail()).isEqualTo("已发布版本 v1（按指定版本回放），共 1 条路径，启用 1 条");
        // 不存在的版本是请求错误，不是「没有配置」
        assertThatThrownBy(() -> c1.evaluate(new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", BigDecimal.TEN, null, 3)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("C1 路径配置没有版本 v3");
        assertThatThrownBy(() -> paths.version(3)).hasMessage("C1 路径配置没有版本 v3");
    }

    @Test
    @Transactional
    void aPublishedPathCanBeDisabledButNotDeleted() {
        saveAndPublish(body(line("P1", List.of("PROV-GD")), defaultPath()), "基线");
        int version = draftVersion();
        String protectedMessage = "路径 P1 已发布过，不能删除（历史版本和决策记录仍引用它）；不需要时请停用";

        // 草稿里去掉已发布过的路径：不能保存，校验也报错
        assertThatThrownBy(() -> paths.saveDraft(body(defaultPath()), version, OPS))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("草稿不能保存：\n- " + protectedMessage);
        assertThat(paths.check(body(defaultPath())).errors()).containsExactly(protectedMessage);
        assertThat(draftVersion()).isEqualTo(version);

        // 停用可以
        var view = paths.saveDraft(body(path("P1", "线路一", List.of("PROV-GD"), 1, false, null, null), defaultPath()), version, OPS);
        assertThat(view.draft().rule().paths().get(0).on()).isFalse();
        paths.publish(new PublishRequest(view.draft().version(), OPS, "停用线路一"));
        // P1 停用后 PROV-GD 没有启用的专用路径：落到 DEFAULT，轨迹里说明原因
        var result = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "KG", "100"));
        assertThat(result.pathCode()).isEqualTo("DEFAULT");
        assertThat(result.defaultPathHit()).isTrue();
        assertThat(result.configVersion()).isEqualTo(2);
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("2. 路径选择");
            assertThat(step.detail()).isEqualTo("合作方 PROV-GD 没有任何启用的专用路径，命中 DEFAULT 路径（默认路径），优先级 0；规则版本 v2；绑定它的专用路径 P1 已停用");
        });
        // v1 仍按当时的配置回放：那时 P1 还启用着
        var replayed = c1.evaluate(new C1Service.Simulation("C10002", "PROV-GD", "USD", "KG", new BigDecimal("100"), Map.of(), 1));
        assertThat(replayed.pathCode()).isEqualTo("P1");
        assertThat(replayed.pathPriority()).isEqualTo(1);
        assertThat(replayed.defaultPathHit()).isFalse();
    }

    @Test
    @Transactional
    void aDisabledFunctionBlocksNewRulesAndPublishedTreesThatUseItNoLongerCompile() {
        var excluded = function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false);
        var usesExclusion = group("AND", excluded, field("flowCategory", "NE", "NONE"));
        saveAndPublish(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, "C1_GD_EXCLUDED_CLIENT", usesExclusion), defaultPath()), "基线");
        assertThat(c1.evaluate(simulate("C10002", "PROV-GD", "USD", "KG", "100")).steps())
                .anySatisfy(step -> assertThat(step.detail()).contains("clientExcluded(providerCode=$candidate.providerCode"));

        // 函数停用后：引用它的条件不能再保存，已保存的草稿也不能发布
        functions.toggle("clientExcluded");
        assertThatThrownBy(() -> paths.saveDraft(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null, usesExclusion), defaultPath()), null, OPS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("C1 路径配置无法编译").hasMessageContaining("函数 clientExcluded 已停用，不能用于条件");
        assertThatThrownBy(() -> paths.publish(new PublishRequest(null, OPS, null)))
                .hasMessageContaining("发布校验未通过").hasMessageContaining("函数 clientExcluded 已停用，不能用于条件");
        assertThat(paths.check(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null, usesExclusion), defaultPath())).ok()).isFalse();
        // 停用的路径同样要能编译
        assertThat(paths.compile(body(path("P1", "线路一", List.of("PROV-GD"), 1, false, null, usesExclusion), defaultPath())).ok()).isFalse();
        // 未注册的函数编码：只有函数库里登记过的才能用
        assertThat(paths.compile(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null,
                group("AND", function("noSuchFunction", Map.of(), "EQ", true))))).errors())
                .singleElement().asString().contains("noSuchFunction");

        // 已发布的版本在函数停用后执行：按配置损坏拒绝（不是「条件不满足」，更不会通过），没有任何函数被调用
        var broken = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "KG", "100"));
        assertThat(broken.passed()).isFalse();
        assertThat(broken.reasonCode()).isEqualTo("C1_CONFIG_BROKEN");
        assertThat(broken.pathCode()).isEqualTo("P1");
        assertThat(broken.functionCalls()).isEmpty();
        assertThat(broken.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("2. 路径条件 STANDARD");
            assertThat(step.status()).isEqualTo("FAIL");
            assertThat(step.detail()).contains("路径 P1 的条件树无法编译").contains("clientExcluded 已停用");
        });

        // 恢复启用后，同一个版本又能执行
        functions.toggle("clientExcluded");
        assertThat(c1.evaluate(simulate("C10002", "PROV-GD", "USD", "KG", "100")).reasonCode()).isEqualTo("APPETITE_NOT_SET");
    }

    // ---------- 引用保护 ----------

    @Test
    @Transactional
    void fieldsAndFunctionsUsedByPathConditionsCannotBeDeletedWhileAnyVersionUsesThem() {
        var usesExclusion = group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false), field("flowCategory", "NE", "NONE"));
        saveAndPublish(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null, usesExclusion), defaultPath()), "基线");
        // 草稿：P1 改成不再引用 clientExcluded（已发布过，不能删，只能改）
        paths.saveDraft(body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null, group("AND", field("paymentCurrency", "EQ", "USD"))),
                path("P2", "线路二", List.of("PROV-W"), 2, true, null, group("AND", field("paymentCurrency", "EQ", "USD"))),
                defaultPath()), null, OPS);

        // 草稿已不引用 clientExcluded，但 v1 还引用：函数不能删
        assertThat(functionMetadata.references("clientExcluded")).containsExactly("C1 路径已发布 v1 P1");
        assertThatThrownBy(() -> functions.delete("clientExcluded"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("函数已被引用，不能删除：C1 路径已发布 v1 P1");
        // 字段：草稿（P2、DEFAULT）和已发布版本（P1、DEFAULT）都算引用，与其它规则的引用一起列出
        assertThatThrownBy(() -> metadata.delete("flowCategory"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字段已被引用，不能删除：C1 路径草稿 DEFAULT、C1 路径已发布 v1 P1、C1 路径已发布 v1 DEFAULT");
        assertThatThrownBy(() -> metadata.delete("paymentCurrency"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("字段已被引用，不能删除：C1 路径草稿 P1、C1 路径草稿 P2");
        // 没有任何路径引用的字段照常可以删
        metadata.create(new FieldMetadataController.Input("c1PathUnused", "未被路径引用", "STRING", "EQ,NE", "C1", null, true));
        assertThat(metadata.delete("c1PathUnused")).containsEntry("deleted", "c1PathUnused");

        // 已发布的配置读不出来：引用检查直接报错，不会当成「没有被引用」
        C1PathVersion row = versions.findByVersion(1).orElseThrow();
        String payload = row.payloadJson;
        row.payloadJson = "{not json";
        assertThatThrownBy(() -> functionMetadata.references("clientExcluded"))
                .isInstanceOf(IllegalStateException.class).hasMessage("C1 路径配置不可读，请先修复配置");
        assertThatThrownBy(() -> metadata.delete("paymentCurrency")).isInstanceOf(IllegalStateException.class);
        row.payloadJson = payload;
        assertThat(functionMetadata.references("clientExcluded")).containsExactly("C1 路径已发布 v1 P1");
    }

    // ---------- 从旧版迁入 ----------

    /** 回到升级前的状态：没有路径配置，只有旧版全局准入条件（给定内容与版本号）。 */
    private void backToLegacy(String conditionJson, int versionNo) {
        audits.deleteAll(audits.findTop500ByObjectTypeOrderByIdDesc(C1PathConfigService.AUDIT_TYPE));
        versions.deleteAll();
        configs.deleteAll();
        var row = legacy.findById(1L).orElseThrow();
        row.conditionJson = conditionJson;
        row.versionNo = versionNo;
        legacy.save(row);
        configs.flush();
        versions.flush();
    }

    private static ExpressionService.Node notExcluded() {
        return function(COLLECTION_CONTAINS, CONTAINS_ARGUMENTS, "EQ", false);
    }

    @Test
    @Transactional
    void legacyGlobalConditionBecomesVersionOneVerbatimAndTheDraftDropsOnlyTheTwoBooleans() throws Exception {
        metadata.create(new FieldMetadataController.Input("clientClassificationStatus", "客户分类状态", "ENUM", "EQ,NE", "C1", "COMPLETE,PENDING", true));
        // 字段库里没有两个旧版布尔字段：迁移与 v1 回放都不依赖它们
        ExpressionService.Node legacyTree = group("AND",
                field("clientClassificationStatus", "EQ", "COMPLETE"),
                group("AND", field("appetiteCurrencyEnabled", "EQ", true), field("appetiteCountryEnabled", "EQ", true)),
                notExcluded());
        String legacyJson = json.writeValueAsString(legacyTree);
        // 一条准入规则的附加条件仍引用旧版布尔字段
        Appetite rule = appetites.findAll().get(0);
        rule.conditionJson = json.writeValueAsString(field("appetiteCurrencyEnabled", "EQ", true));
        appetites.save(rule);
        backToLegacy(legacyJson, 69);

        paths.migrateFromLegacy();

        // v1 = 旧版全局准入条件原样作为 DEFAULT 路径，沿用旧版失败原因码，操作人 system
        var v1 = paths.version(1);
        assertThat(v1.publishedBy()).isEqualTo("system");
        assertThat(v1.note()).isEqualTo("系统迁移：旧版全局准入条件（版本 69）原样转为 DEFAULT 路径");
        assertThat(v1.rule().paths()).containsExactly(new C1PathRule.Path("DEFAULT", "默认路径（旧版全局准入条件）", List.of(), 0, true,
                legacyTree, "C1_CONDITION_NOT_MATCHED", 1));
        assertThat(v1.rule().paths().get(0).version()).isEqualTo(1);
        // 草稿 = 去掉两个旧版布尔条件后的 DEFAULT 路径，空掉的内层条件组一并去掉，其余条件原样保留
        C1PathRule.Path draft = paths.view().draft().rule().paths().get(0);
        assertThat(paths.view().draft().rule().paths()).hasSize(1);
        assertThat(draft.pathCode()).isEqualTo("DEFAULT");
        assertThat(draft.pathName()).isEqualTo("默认路径");
        assertThat(draft.failureReasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(draft.version()).isNull();
        assertThat(draft.conditionAst()).isEqualTo(group("AND", field("clientClassificationStatus", "EQ", "COMPLETE"), notExcluded()));
        assertThat(paths.view().draftChanged()).isTrue();

        // 迁移说明：v1 是快照、移除了几个布尔条件、其余条件已保留、哪些准入规则的附加条件还在引用布尔字段
        List<Hint> hints = paths.view().migrationHints();
        assertThat(hints).extracting(Hint::code)
                .containsExactly("V1_SNAPSHOT", "LEGACY_BOOLEANS_REMOVED", "OTHER_CONDITIONS_PRESERVED", "APPETITE_RULES_WITH_BOOLEANS");
        assertThat(hints.get(1).message()).contains("移除 2 个旧版布尔条件").contains("appetiteCurrencyEnabled / appetiteCountryEnabled");
        assertThat(hints.get(3).message()).contains("#" + rule.id);
        var migration = paths.migrationView();
        assertThat(migration.migrated()).isTrue();
        assertThat(migration.legacyConfigured()).isTrue();
        assertThat(migration.legacyVersionNo()).isEqualTo(69);
        assertThat(migration.legacyConditionJson()).isEqualTo(legacyJson);
        assertThat(migration.pendingReview()).isTrue();

        // 审计记录迁移前后的内容；旧版配置行原样保留，不改写、不删除
        RuleAudit migrate = paths.audits().get(0);
        assertThat(migrate.operation).isEqualTo("MIGRATE");
        assertThat(migrate.operatorName).isEqualTo("system");
        assertThat(migrate.beforeJson).isEqualTo(legacyJson);
        assertThat(migrate.afterJson).isEqualTo(configs.findById(1L).orElseThrow().draftJson);
        var legacyRow = legacy.findById(1L).orElseThrow();
        assertThat(legacyRow.conditionJson).isEqualTo(legacyJson);
        assertThat(legacyRow.versionNo).isEqualTo(69);
        assertThat(appetites.findById(rule.id).orElseThrow().conditionJson).contains("appetiteCurrencyEnabled");

        // 幂等：再跑一次不产生新版本、新审计
        paths.migrateFromLegacy();
        assertThat(versions.count()).isEqualTo(1);
        assertThat(paths.audits()).hasSize(1);

        // 回放 v1：旧版布尔字段按旧语义取值（PROV-W 的准入规则只覆盖 USD，EUR 不覆盖）→ 路径不满足，原因是旧版的 C1_CONDITION_NOT_MATCHED
        var eurOnW = new C1Service.Simulation("C10002", "PROV-W", "EUR", "KG", new BigDecimal("100"), Map.of(), 1);
        var replay = c1.evaluate(eurOnW);
        assertThat(replay.reasonCode()).as("steps: %s", replay.steps()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(replay.configVersion()).isEqualTo(1);
        assertThat(replay.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("2. 路径条件 STANDARD");
            assertThat(step.status()).isEqualTo("FAIL");
            assertThat(step.detail()).contains("appetiteCurrencyEnabled == true");
        });
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-W", "USD", "KG", new BigDecimal("100"), Map.of(), 1)).reasonCode()).isEqualTo("PASS");
        // 同一笔按草稿（新版）试算：路径条件满足，停在必检的准入匹配。原因码是准入的，不会被伪装成路径原因
        var drafted = c1.evaluateDraft(new C1Service.Simulation("C10002", "PROV-W", "EUR", "KG", new BigDecimal("100")), paths.storedDraft());
        assertThat(drafted.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(drafted.configVersion()).isNull();

        // 人工确认后发布为 v2：迁移说明随之清空；新版路径用自己的失败原因码，v1 仍可回放
        var v2 = paths.publish(new PublishRequest(null, "ops", "确认新版默认路径"));
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.rule().paths().get(0).failureReasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(paths.view().migrationHints()).isEmpty();
        assertThat(paths.migrationView().pendingReview()).isFalse();
        var pending = new C1Service.Simulation("C10003", "PROV-T1", "USD", "KG", new BigDecimal("100"));
        var latest = c1.evaluate(pending);
        assertThat(latest.reasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(latest.configVersion()).isEqualTo(2);
        var old = c1.evaluate(new C1Service.Simulation("C10003", "PROV-T1", "USD", "KG", new BigDecimal("100"), Map.of(), 1));
        assertThat(old.reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        // 迁移说明清空后再跑迁移也不会重来（配置行已存在）
        paths.migrateFromLegacy();
        assertThat(paths.history()).hasSize(2);
        assertThat(paths.audits()).extracting(audit -> audit.operation).containsExactly("PUBLISH", "MIGRATE");
    }

    @Test
    @Transactional
    void legacyConditionMadeOnlyOfTheTwoBooleansMigratesToADraftWithoutATree() throws Exception {
        ExpressionService.Node booleans = group("AND", field("appetiteCurrencyEnabled", "EQ", true), field("appetiteCountryEnabled", "EQ", true));
        backToLegacy(json.writeValueAsString(booleans), 3);

        paths.migrateFromLegacy();

        assertThat(paths.version(1).rule().paths().get(0).conditionAst()).isEqualTo(booleans);
        var view = paths.view();
        assertThat(view.draft().rule().paths().get(0).conditionAst()).isNull();
        assertThat(view.migrationHints()).extracting(Hint::code)
                .containsExactly("V1_SNAPSHOT", "LEGACY_BOOLEANS_REMOVED", "OTHER_CONDITIONS_PRESERVED");
        assertThat(view.migrationHints().get(2).message()).contains("草稿的 DEFAULT 路径没有条件树，请补充条件后发布");
        // 没有补充条件就不能发布：旧条件已被 APPETITE_MATCH 取代，但启用的路径必须有自己的条件
        assertThatThrownBy(() -> paths.publish(new PublishRequest(null, "ops", null)))
                .hasMessageContaining("路径 DEFAULT：已启用但没有条件树");
        // v1 照常可回放：布尔条件按旧语义取值
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", BigDecimal.TEN, Map.of(), 1)).reasonCode()).isEqualTo("PASS");
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-W", "EUR", "KG", BigDecimal.TEN, Map.of(), 1)).reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
    }

    @Test
    void pruningALegacyBooleanIsFlaggedOnlyWhenItSatInAnOrGroup() {
        String currency = "appetiteCurrencyEnabled", country = "appetiteCountryEnabled";
        ExpressionService.Node x = field("flowCategory", "EQ", "PSP"), y = field("amountUsd", "GT", 100);

        // 与关系里：去掉后其余条件原样保留
        assertPruned(group("AND", field(currency, "EQ", true), x), group("AND", x), 1, 0);
        assertPruned(group("AND", field(currency, "EQ", true), group("OR", x, y)), group("AND", group("OR", x, y)), 1, 0);
        // 或关系里（同组还有别的条件）：去掉后其余条件从「二选一」变成「必须满足」，要提示
        assertPruned(group("AND", x, group("OR", field(country, "EQ", true), y)), group("AND", x, group("OR", y)), 1, 1);
        // 只有一个条件的「或」组不改变与或关系：按上层的关系算
        assertPruned(group("OR", x, group("OR", field(country, "EQ", true))), group("OR", x), 1, 1);
        assertPruned(group("AND", x, group("OR", field(country, "EQ", true))), group("AND", x), 1, 0);
        // 或组里面的与组：布尔条件只是这个与组里的一道门槛，去掉后该与组仍在或关系里
        assertPruned(group("OR", group("AND", field(country, "EQ", true), y), x), group("OR", group("AND", y), x), 1, 0);
        // 整棵树都去掉：没有保留下来的条件，也就不存在「其余条件变成必须满足」
        assertPruned(group("OR", field(currency, "EQ", true), field(country, "EQ", true)), null, 2, 2);
        assertPruned(group("AND", field(currency, "EQ", true)), null, 1, 0);
    }

    private void assertPruned(ExpressionService.Node legacyTree, ExpressionService.Node expected, int removed, int removedFromOr) {
        C1PathConfigService.Pruning pruning = C1PathConfigService.pruneLegacyBooleans(json.valueToTree(legacyTree));
        assertThat(pruning.removed()).isEqualTo(removed);
        assertThat(pruning.removedFromOr()).as("or-flagged conditions of %s", legacyTree).isEqualTo(removedFromOr);
        assertThat(pruning.tree()).isEqualTo(expected == null ? null : json.valueToTree(expected));
    }

    @Test
    @Transactional
    void legacyBooleanInsideAnOrGroupKeepsTheOtherConditionsAndAsksForAReview() throws Exception {
        // 旧版：币种覆盖，且（国家覆盖 或 PSP），且流转类别不为空
        ExpressionService.Node legacyTree = group("AND",
                field("appetiteCurrencyEnabled", "EQ", true),
                group("OR", field("appetiteCountryEnabled", "EQ", true), field("flowCategory", "EQ", "PSP")),
                field("flowCategory", "NE", "NONE"));
        backToLegacy(json.writeValueAsString(legacyTree), 7);

        paths.migrateFromLegacy();

        // 草稿：布尔条件去掉，「或」组里剩下的 flowCategory = PSP 原样保留（不替用户改写），并单独提示这一组的含义变了
        C1PathRule.Path draft = paths.view().draft().rule().paths().get(0);
        assertThat(draft.conditionAst()).isEqualTo(group("AND", group("OR", field("flowCategory", "EQ", "PSP")), field("flowCategory", "NE", "NONE")));
        assertThat(paths.view().migrationHints()).extracting(Hint::code)
                .containsExactly("V1_SNAPSHOT", "LEGACY_BOOLEANS_REMOVED", "LEGACY_BOOLEAN_IN_OR", "OTHER_CONDITIONS_PRESERVED");
        assertThat(paths.view().migrationHints().get(2).message()).contains("1 个旧版布尔条件在「或」条件组里").contains("请重点核对 DEFAULT 路径的条件树");
        assertThat(paths.version(1).rule().paths().get(0).conditionAst()).isEqualTo(legacyTree);

        // v1 回放（旧语义）：PROV-W 的 STANDARD 准入规则 #5 是 USD / ALL —— USD 通过，EUR 停在旧版布尔条件
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-W", "USD", "KG", new BigDecimal("100"), Map.of(), 1)).reasonCode()).isEqualTo("PASS");
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-W", "EUR", "KG", new BigDecimal("100"), Map.of(), 1)).reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        // 草稿（新语义）：同一笔 USD 不再靠布尔条件放行 —— STANDARD 客户不满足剩下的 flowCategory = PSP，原因是路径自己的失败原因码
        var standard = c1.evaluateDraft(new C1Service.Simulation("C10002", "PROV-W", "USD", "KG", new BigDecimal("100")), paths.storedDraft());
        assertThat(standard.reasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(standard.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("2. 路径条件 STANDARD");
            assertThat(step.status()).isEqualTo("FAIL");
            assertThat(step.detail()).contains("flowCategory == 'PSP'").doesNotContain("appetite");
        });
        // PSP 客户满足路径条件，改由必检的准入匹配判定：PROV-W 没有 PSP 的准入规则
        var psp = c1.evaluateDraft(new C1Service.Simulation("C10001", "PROV-W", "USD", "KG", new BigDecimal("100")), paths.storedDraft());
        assertThat(psp.reasonCode()).as("steps: %s", psp.steps()).isEqualTo("APPETITE_NOT_SET");
    }

    @Test
    @Transactional
    void unreadableOrEmptyLegacyConditionLeavesASkeletonDraftAndNothingIsInvented() {
        // 读不出来：不迁入、不猜测，留下说明，由人工重新配置
        backToLegacy("{not json", 5);
        paths.migrateFromLegacy();
        assertThat(versions.count()).isZero();
        assertThat(paths.view().published()).isNull();
        assertThat(paths.view().draft().rule().paths().get(0).conditionAst()).isNull();
        assertThat(paths.view().migrationHints()).extracting(Hint::code).containsExactly("LEGACY_UNREADABLE");
        var rejected = c1.evaluate(simulate("C10002", "PROV-T1", "USD", "KG", "100"));
        assertThat(rejected.reasonCode()).isEqualTo("C1_CONFIG_MISSING");

        // 保存过但是空的：等同没有配置条件
        backToLegacy("{\"logic\":\"AND\",\"children\":[]}", 6);
        paths.migrateFromLegacy();
        assertThat(versions.count()).isZero();
        assertThat(paths.view().migrationHints()).extracting(Hint::code).containsExactly("NO_LEGACY_CONDITION");
        assertThat(paths.view().migrationHints().get(0).message()).contains("旧版全局准入条件是空的");
        assertThat(legacy.findById(1L).orElseThrow().versionNo).isEqualTo(6);
        assertThat(legacy.findById(1L).orElseThrow().conditionJson).isEqualTo("{\"logic\":\"AND\",\"children\":[]}");
    }

    @Test
    @Transactional
    void aDraftSavedBeforeTheStartupMigrationRanStillBringsTheLegacyConditionIn() throws Exception {
        // 端口先于 ApplicationReadyEvent 里的迁移开放：迁移还没执行（配置行不存在）时就收到保存草稿请求
        ExpressionService.Node legacyTree = group("AND", field("flowCategory", "NE", "NONE"), field("appetiteCurrencyEnabled", "EQ", true));
        String legacyJson = json.writeValueAsString(legacyTree);
        backToLegacy(legacyJson, 69);
        assertThat(configs.existsById(1L)).isFalse();
        C1PathRule.Path p1 = path("P1", "线路P1", List.of("PROV-GD"), 1, true, "C1_PATH_P1_NOT_MATCHED", group("AND", field("flowCategory", "NE", "NONE")));
        C1PathRule.Path def = path("DEFAULT", "默认路径", List.of(), 0, true, "C1_PATH_DEFAULT_NOT_MATCHED", group("AND", field("flowCategory", "NE", "NONE")));

        var view = paths.saveDraft(body(line("P1", List.of("PROV-GD")), defaultPath()), null, OPS);

        // 这次保存的草稿原样保存（操作人的内容优先于迁移生成的那份草稿），迁移提示留着等人工核对
        assertThat(view.draft().rule().paths()).containsExactly(p1, def);
        assertThat(view.draft().updatedBy()).isEqualTo(OPS);
        assertThat(view.draft().version()).isEqualTo(2);
        assertThat(view.migrationHints()).extracting(Hint::code)
                .containsExactly("V1_SNAPSHOT", "LEGACY_BOOLEANS_REMOVED", "OTHER_CONDITIONS_PRESERVED");
        // 旧版条件没有漏迁：原样成为 v1，已发生的决策照常按它回放
        assertThat(view.published().version()).isEqualTo(1);
        assertThat(view.published().publishedBy()).isEqualTo("system");
        assertThat(view.published().rule().paths()).containsExactly(new C1PathRule.Path("DEFAULT", "默认路径（旧版全局准入条件）", List.of(), 0, true,
                legacyTree, "C1_CONDITION_NOT_MATCHED", 1));
        assertThat(view.draftChanged()).isTrue();
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-W", "USD", "KG", new BigDecimal("100"), Map.of(), 1)).reasonCode()).isEqualTo("PASS");
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-W", "EUR", "KG", new BigDecimal("100"), Map.of(), 1)).reasonCode())
                .isEqualTo("C1_CONDITION_NOT_MATCHED");
        // 审计（新 → 旧）：保存草稿、系统迁移；旧版配置行原样保留
        List<RuleAudit> audits = paths.audits();
        assertThat(audits).extracting(audit -> audit.operation).containsExactly("SAVE_DRAFT", "MIGRATE");
        assertThat(audits.get(1).operatorName).isEqualTo("system");
        assertThat(audits.get(1).beforeJson).isEqualTo(legacyJson);
        assertThat(audits.get(0).operatorName).isEqualTo(OPS);
        assertThat(audits.get(0).afterJson).isEqualTo(configs.findById(1L).orElseThrow().draftJson);
        assertThat(legacy.findById(1L).orElseThrow().conditionJson).isEqualTo(legacyJson);

        // 稍后启动迁移真正执行（或人工再触发）：配置行已存在，不重复迁入
        paths.migrateFromLegacy();
        assertThat(versions.count()).isEqualTo(1);
        assertThat(paths.audits()).hasSize(2);
        assertThat(paths.view().draft().rule().paths()).containsExactly(p1, def);
    }

    @Test
    void aRejectedDraftSavedBeforeTheStartupMigrationRanLeavesTheMigrationToStartup() throws Exception {
        // 不带事务：补做的迁移发生在这次保存自己的事务里，保存被拒绝时它要随之回滚
        String legacyJson = json.writeValueAsString(group("AND", field("flowCategory", "NE", "NONE")));
        backToLegacy(legacyJson, 69);
        try {
            // 草稿里没有迁移刚发布的 DEFAULT 路径：删除保护拒绝保存
            assertThatThrownBy(() -> paths.saveDraft(body(line("P1", List.of("PROV-GD"))), null, OPS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("草稿不能保存：\n- 路径 DEFAULT 已发布过，不能删除");
            assertThat(configs.existsById(1L)).isFalse();
            assertThat(versions.count()).isZero();
            assertThat(paths.audits()).isEmpty();
            // 启动迁移随后照常执行，旧版条件原样成为 v1
            paths.migrateFromLegacy();
            assertThat(paths.version(1).rule().paths().get(0).conditionAst()).isEqualTo(group("AND", field("flowCategory", "NE", "NONE")));
            assertThat(paths.view().draft().updatedBy()).isEqualTo("system");
        } finally {
            // 恢复全新库的初始状态（旧版没有配置条件），不干扰其它用例
            backToLegacy(" ", 1);
            paths.migrateFromLegacy();
        }
    }

    // ---------- 页面接口 ----------

    @Test
    @Transactional
    void pageApiServesTheDraftCheckPublishSimulateHistoryAndMigration() throws Exception {
        JsonNode view = send("GET", "/api/c1/paths", null);
        assertThat(view.path("ruleCode").asText()).isEqualTo("C1");
        assertThat(view.path("branchMode").asText()).isEqualTo("FIRST_MATCH");
        assertThat(view.path("published").isNull()).isTrue();
        assertThat(view.path("draftChanged").asBoolean()).isTrue();
        assertThat(view.path("draft").path("updatedBy").asText()).isEqualTo("system");
        assertThat(view.path("draft").path("rule").path("paths").get(0).path("pathCode").asText()).isEqualTo("DEFAULT");
        assertThat(view.path("draft").path("rule").path("paths").get(0).path("conditionAst").isNull()).isTrue();
        assertThat(view.path("history").isArray()).isTrue();
        assertThat(view.path("history")).isEmpty();
        assertThat(view.path("migrationHints").get(0).path("code").asText()).isEqualTo("NO_LEGACY_CONDITION");

        JsonNode migration = send("GET", "/api/c1/paths/migration", null);
        assertThat(migration.path("migrated").asBoolean()).isTrue();
        assertThat(migration.path("legacyConfigured").asBoolean()).isFalse();
        assertThat(migration.path("pendingReview").asBoolean()).isTrue();
        assertThat(migration.path("hints")).hasSize(1);

        // 保存草稿 → 校验 → 发布
        ObjectNode rule = body(line("P1", List.of("PROV-GD")), defaultPath());
        JsonNode saved = send("PUT", "/api/c1/paths/draft", Map.of("rule", rule, "operator", OPS));
        assertThat(saved.path("draft").path("updatedBy").asText()).isEqualTo(OPS);
        assertThat(saved.path("draftChanged").asBoolean()).isTrue();
        JsonNode checked = send("POST", "/api/c1/paths/check", Map.of("rule", rule));
        assertThat(checked.path("ok").asBoolean()).isTrue();
        assertThat(checked.path("errors")).isEmpty();
        assertThat(checked.path("warnings")).isEmpty();
        assertThat(checked.path("paths")).hasSize(2);
        assertThat(checked.path("paths").get(0).path("pathCode").asText()).isEqualTo("P1");
        assertThat(checked.path("paths").get(0).path("expression").asText()).isEqualTo("((flowCategory != 'NONE'))");
        JsonNode published = send("POST", "/api/c1/paths/publish", new PublishRequest(null, OPS, "第一版"));
        assertThat(published.path("version").asInt()).isEqualTo(1);
        assertThat(published.path("publishedBy").asText()).isEqualTo(OPS);
        assertThat(published.path("note").asText()).isEqualTo("第一版");
        assertThat(published.path("rule").path("paths")).hasSize(2);
        assertThat(published.path("rule").path("paths").get(0).path("version").asInt()).isEqualTo(1);

        // 试算（已发布版本）：结果里带命中的路径、版本、必检明细
        Map<String, Object> usd = Map.of("clientId", "C10002", "providerCode", "PROV-T1", "paymentCurrency", "USD",
                "beneficiaryBankCountry", "KG", "amountUsd", 100);
        JsonNode result = send("POST", "/api/c1/simulate", usd);
        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.path("reasonCode").asText()).isEqualTo("PASS");
        assertThat(result.path("pathCode").asText()).isEqualTo("DEFAULT");
        assertThat(result.path("pathName").asText()).isEqualTo("默认路径");
        assertThat(result.path("pathPriority").asInt()).isZero();
        assertThat(result.path("configVersion").asInt()).isEqualTo(1);
        assertThat(result.path("defaultPathHit").asBoolean()).isTrue();
        assertThat(result.path("classificationStatus").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("appetiteMatchLevel").asText()).isEqualTo("ALL_ALL");
        assertThat(result.path("appetiteDecisions").get(0).path("flowCategory").asText()).isEqualTo("STANDARD");
        assertThat(result.path("appetiteDecisions").get(0).path("matchLevel").asText()).isEqualTo("ALL_ALL");
        assertThat(result.path("appetiteDecisions").get(0).path("action").asText()).isEqualTo("ACCEPT");
        assertThat(result.path("steps")).extracting(step -> step.path("status").asText()).containsOnly("PASS");

        // 试算（页面草稿）：rule 为空时用已保存的草稿；PROV-T1 走 DEFAULT，PROV-GD 走 P1
        JsonNode stored = send("POST", "/api/c1/paths/simulate", Map.of("simulation", usd));
        assertThat(stored.path("pathCode").asText()).isEqualTo("DEFAULT");
        assertThat(stored.path("configVersion").isNull()).isTrue();
        ObjectNode unpublished = body(line("P1", List.of("PROV-T1")), defaultPath());
        JsonNode draft = send("POST", "/api/c1/paths/simulate", Map.of("rule", unpublished, "simulation", usd));
        assertThat(draft.path("pathCode").asText()).isEqualTo("P1");
        assertThat(draft.path("defaultPathHit").asBoolean()).isFalse();
        assertThat(draft.path("configVersion").isNull()).isTrue();
        assertThat(draft.path("steps").get(0).path("detail").asText()).isEqualTo("草稿（未发布），共 2 条路径，启用 2 条");
        // 草稿结构无效：拒绝而不是通过
        JsonNode invalidDraft = send("POST", "/api/c1/paths/simulate", Map.of("rule", Map.of("ruleCode", "C1", "branchMode", "FIRST_MATCH", "paths", List.of()),
                "simulation", usd));
        assertThat(invalidDraft.path("passed").asBoolean()).isFalse();
        assertThat(invalidDraft.path("reasonCode").asText()).isEqualTo("C1_CONFIG_BROKEN");
        assertThat(invalidDraft.path("steps").get(0).path("detail").asText()).contains("草稿不是有效的路径配置");

        // 历史、单个版本、审计
        JsonNode history = send("GET", "/api/c1/paths/history", null);
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("version").asInt()).isEqualTo(1);
        assertThat(history.get(0).path("note").asText()).isEqualTo("第一版");
        assertThat(history.get(0).path("paths").get(1).path("condition").asText()).isEqualTo("(flowCategory NE NONE)");
        assertThat(send("GET", "/api/c1/paths/versions/1", null).path("version").asInt()).isEqualTo(1);
        assertThat(send("GET", "/api/c1/paths/audits", null)).extracting(audit -> audit.path("operation").asText())
                .containsExactly("PUBLISH", "SAVE_DRAFT", "MIGRATE");
        assertThat(send("GET", "/api/c1/paths", null).path("draftChanged").asBoolean()).isFalse();

        // 请求错误统一 400 + {error}
        assertThat(errorOf(request("GET", "/api/c1/paths/versions/9", null))).isEqualTo("C1 路径配置没有版本 v9");
        assertThat(errorOf(request("PUT", "/api/c1/paths/draft", Map.of("rule", body(path("P1", "线路一", List.of("PROV-GD"), 1, true, null,
                group("AND", field("flowCategory", "NE", "NONE"), emptyGroup("OR")))), "operator", OPS))))
                .isEqualTo("C1 路径配置结构无效：\n- 路径 P1：条件树存在空的条件组，请删除它或添加条件");
        assertThat(errorOf(request("PUT", "/api/c1/paths/draft", Map.of("rule", rule, "operator", " "))).isEmpty()).isFalse();
        assertThat(errorOf(request("PUT", "/api/c1/paths/draft", Map.of("rule", rule, "operator", " ")))).isEqualTo("操作人不能为空");
        assertThat(errorOf(request("POST", "/api/c1/paths/publish", Map.of("operator", " ")))).isEqualTo("发布必须提供操作人");
        assertThat(errorOf(request("POST", "/api/c1/paths/simulate", Map.of("simulation", Map.of("clientId", "C10002", "providerCode", "PROV-T1")))))
                .isEqualTo("模拟请求字段缺失或金额无效");
        assertThat(errorOf(request("POST", "/api/c1/paths/simulate", Map.of("simulation", Map.of("clientId", "C10002", "providerCode", "PROV-XX",
                "paymentCurrency", "USD", "beneficiaryBankCountry", "KG", "amountUsd", 100))))).isEqualTo("合作方不存在");
        // 按指定已发布版本判定：版本不存在是请求错误（HTTP 400），不是「没有配置」
        assertThat(errorOf(request("POST", "/api/c1/simulate", Map.of("clientId", "C10002", "providerCode", "PROV-T1",
                "paymentCurrency", "USD", "beneficiaryBankCountry", "KG", "amountUsd", 100, "configVersion", 9))))
                .isEqualTo("C1 路径配置没有版本 v9");
        mvc.perform(request("POST", "/api/c1/paths/simulate", null)).andExpect(status().isBadRequest());
    }
}
