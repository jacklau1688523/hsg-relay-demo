package com.hsg.relay.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.ClientExclusionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 函数库接口：「添加函数」选择器、函数试算、删除保护、使用策略，以及字段 ↔ 函数绑定的保存。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FunctionMetadataApiTest {
    private static final Map<String, Object> EXCLUSION_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");
    /** 已接入函数节点的规则（报错文字里的清单，按编码排序）。 */
    private static final String RULES = "C1 / C2 / C3 / C5 / C7 / C8";
    /** 启动时补齐的 26 个通用函数：27 个集合函数里去掉返回 OBJECT / MAP 的两个（没有后置函数接收，不进选择器），加上封顶函数 numberMin。 */
    private static final List<String> SEEDED = List.of("collectionAllMatch", "collectionAnyMatch", "collectionAvg", "collectionContains",
            "collectionContainsAll", "collectionContainsAny", "collectionCount", "collectionCountMatch", "collectionCountWhere",
            "collectionDifference", "collectionDistinct", "collectionFilter", "collectionFilterRecords", "collectionIntersects",
            "collectionIntersection", "collectionIsEmpty", "collectionMax", "collectionMin", "collectionNoneMatch", "collectionNotContains",
            "collectionSize", "collectionSort", "collectionSum", "collectionSumWhere", "collectionUnion", "numberMin");
    private static final Map<String, Object> SAMPLE = Map.of("providerCode", "PROV-GD", "clientId", "API-CLIENT", "amountUsd", "100");
    private static final String SIMULATE = "/api/function-metadata/clientExcluded/simulate";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AppetiteRepository appetites;
    @Autowired
    ClientExclusionRepository exclusions;

    private MockHttpServletRequestBuilder jsonRequest(String method, String path, Object body) throws Exception {
        return MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path).contentType(MediaType.APPLICATION_JSON)
                .content((body == null ? "{}" : json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode ok(RequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode send(String method, String path, Object body) throws Exception {
        return ok(jsonRequest(method, path, body));
    }

    private String errorOf(RequestBuilder request) throws Exception {
        var response = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().getResponse();
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("error").asText();
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(field == null ? node.asText() : node.path(field).asText()));
        return values;
    }

    private static List<String> plus(List<String> seeded, String... others) {
        List<String> all = new ArrayList<>(seeded);
        all.addAll(List.of(others));
        return all;
    }

    private static JsonNode entry(JsonNode array, String field, String code) {
        for (JsonNode node : array) if (node.path(field).asText().equals(code)) return node;
        throw new AssertionError("未找到 " + code);
    }

    private Appetite rule() {
        return appetites.findAll().stream().filter(a -> a.providerCode.equals("PROV-HO") && a.flowCategory.equals("PSP")).findFirst().orElseThrow();
    }

    /** 保存该规则的 C1 附加条件（enabled = true）。 */
    private JsonNode saveCondition(Map<String, Object> tree) throws Exception {
        return send("PUT", "/api/appetites/" + rule().id + "/condition", Map.of("enabled", true, "tree", tree));
    }

    private static Map<String, Object> functionNode(String functionCode, Map<String, Object> arguments) {
        return Map.of("type", "FUNCTION", "functionCode", functionCode, "arguments", arguments, "operator", "EQ", "value", false);
    }

    private static Map<String, Object> fieldInput(Object... extra) {
        Map<String, Object> input = new java.util.LinkedHashMap<>(Map.of("fieldCode", "apiBoundFlag", "fieldName", "接口绑定标记", "dataType", "BOOLEAN",
                "allowedOperators", "EQ,NE", "allowedRules", "C1", "enabled", true));
        for (int i = 0; i < extra.length; i += 2) input.put((String) extra[i], extra[i + 1]);
        return input;
    }

    @Test
    void pickerListsControlledPathsRequiredChecksAndExecutableFunctionsForC1() throws Exception {
        JsonNode catalog = ok(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", "C1"));
        assertThat(catalog.path("ruleCode").asText()).isEqualTo("C1");
        assertThat(values(catalog.path("paths"), "path")).containsExactly("candidate.providerCode", "candidate.flowCategory",
                "transaction.clientId", "transaction.paymentCurrency", "transaction.beneficiaryBankCountry", "transaction.amountUsd",
                "context.decisionTime", "context.monthKey", "context.ruleCode");
        // clientExcluded 不再由执行器必检：清单里只有执行器在代码里落实的三项
        assertThat(values(catalog.path("required"), "functionCode")).containsExactly("findBestAppetiteRule",
                "hasValidCaseApproval", "getProviderMtd");
        assertThat(catalog.path("required").get(0).path("note").asText()).doesNotContain("CLIENT_EXCLUDED");
        // findBestAppetiteRule 由执行器内部落实，没有独立的 Handler：清单里照常列出（只读），但不能添加为条件节点
        assertThat(catalog.path("required").get(0).path("implemented").asBoolean()).isFalse();

        // collectionContains / collectionIntersects 等通用函数为启动时补齐；返回 OBJECT / MAP 的 collectionFirstMatch / collectionGroupBy 没有后置函数接收，不进选择器
        List<String> listed = values(catalog.path("functions"), "functionCode");
        // collectionContains / collectionIntersects 本身就在 SEEDED 里：总数 = 26 个通用函数 + clientExcluded + 两个 C1 评分函数
        assertThat(listed).hasSize(SEEDED.size() + 3).containsExactlyInAnyOrderElementsOf(plus(SEEDED,
                "clientExcluded", "getProviderMtd", "hasValidCaseApproval"));
        assertThat(listed).doesNotContain("collectionFirstMatch", "collectionGroupBy");
        // 集合函数：分类、参数类型（COLLECTION / PREDICATE / SORT_FIELDS）、返回类型与是否可被后置函数引用
        JsonNode sort = entry(catalog.path("functions"), "functionCode", "collectionSort");
        assertThat(sort.path("category").asText()).isEqualTo("COLLECTION_RECORD");
        assertThat(sort.path("returnType").asText()).isEqualTo("COLLECTION");
        assertThat(sort.path("referable").asBoolean()).isTrue();
        assertThat(values(sort.path("operators"), null)).isEmpty();
        assertThat(values(sort.path("parameters"), "code")).containsExactly("collection", "sortFields");
        assertThat(sort.path("parameters").get(1).path("type").asText()).isEqualTo("SORT_FIELDS");
        assertThat(entry(catalog.path("functions"), "functionCode", "collectionCountMatch").path("parameters").get(1).path("type").asText())
                .isEqualTo("PREDICATE");
        assertThat(entry(catalog.path("functions"), "functionCode", "collectionSize").path("operators").toString()).isEqualTo("[\"EQ\",\"NE\",\"GT\",\"GTE\",\"LT\",\"LTE\"]");
        assertThat(entry(catalog.path("functions"), "functionCode", "collectionSize").path("returnType").asText()).isEqualTo("INTEGER");
        // 可以绑定为函数参数的数据字段与元素结构
        assertThat(values(catalog.path("dataFields"), "fieldCode")).contains("providerExcludedClientIds");
        assertThat(entry(catalog.path("dataFields"), "fieldCode", "providerExcludedClientIds").path("dataType").asText()).isEqualTo("STRING_SET");
        assertThat(values(catalog.path("elementSchemas"), "code")).contains("REACH_ROW", "PROVIDER_RESTRICTION", "COST_COMPONENT", "PREFERENCE_RULE");
        JsonNode clientExcluded = catalog.path("functions").get(0);
        assertThat(clientExcluded.path("functionName").asText()).isEqualTo("客户是否被合作方排除");
        assertThat(clientExcluded.path("returnType").asText()).isEqualTo("BOOLEAN");
        assertThat(clientExcluded.path("dataSource").asText()).isEqualTo("CLIENT_EXCLUSION");
        // 可选函数：由运营在条件编辑器里决定是否使用
        assertThat(clientExcluded.path("usagePolicy").asText()).isEqualTo("OPTIONAL");
        assertThat(clientExcluded.path("requiredNote").isNull()).isTrue();
        assertThat(values(clientExcluded.path("operators"), null)).containsExactly("EQ", "NE");
        assertThat(clientExcluded.path("defaultValue").isBoolean()).isTrue();
        assertThat(values(clientExcluded.path("parameters"), "code")).containsExactly("providerCode", "clientId", "decisionTime");
        assertThat(clientExcluded.path("parameters").get(2).path("defaultBinding").asText()).isEqualTo("$context.decisionTime");
        assertThat(clientExcluded.path("parameters").get(2).path("required").asBoolean()).isFalse();
        assertThat(clientExcluded.path("defaultArguments").path("providerCode").asText()).isEqualTo("$candidate.providerCode");
        assertThat(clientExcluded.path("defaultArguments").path("clientId").asText()).isEqualTo("$transaction.clientId");
    }

    @Test
    void pickerIsAvailableForRulesWithAnExecutionContext() throws Exception {
        // C2 / C3 / C7 / C8 已接入函数节点（受控路径 + 执行上下文）；没有接入的规则（如 C4）仍不能用
        JsonNode c2 = ok(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", "C2"));
        assertThat(c2.path("ruleCode").asText()).isEqualTo("C2");
        assertThat(values(c2.path("paths"), "path")).contains("candidate.providerCode", "transaction.directorNationalities", "context.regulatoryRuleId");
        assertThat(values(c2.path("dataFields"), "fieldCode")).contains("providerCapabilityTags", "requiredCapabilityTags", "providerRestrictions",
                "providerRestrictedNationalities").doesNotContain("providerExcludedClientIds");
        for (String rule : List.of("C3", "C7", "C8"))
            assertThat(ok(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", rule)).path("ruleCode").asText()).isEqualTo(rule);
        assertThat(errorOf(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", "C4")))
                .isEqualTo("目前只有 " + RULES + " 的条件编辑器支持添加函数，不能用于 C4");
        // C5 评分组的条件编辑器可以选函数；OBJECT 返回类型（calculateValueDate）由执行器调用，不能作为条件节点
        mvc.perform(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", "C5")).andExpect(status().isOk());
    }

    @Test
    void simulateReportsArgumentsValueDataSourceDurationAndHitRecord() throws Exception {
        ClientExclusion exclusion = exclusions.save(new ClientExclusion("API-CLIENT", "PROV-GD", "review",
                LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")).minusDays(1), null));
        JsonNode hit = send("POST", SIMULATE, Map.of("arguments", EXCLUSION_ARGUMENTS, "operator", "EQ", "value", true, "sample", SAMPLE));
        assertThat(hit.path("functionCode").asText()).isEqualTo("clientExcluded");
        assertThat(hit.path("expression").asText()).isEqualTo("(clientExcluded(providerCode=$candidate.providerCode, "
                + "clientId=$transaction.clientId, decisionTime=$context.decisionTime) == true)");
        assertThat(hit.path("arguments").path("providerCode").asText()).isEqualTo("PROV-GD");
        assertThat(hit.path("arguments").path("clientId").asText()).isEqualTo("API-CLIENT");
        assertThat(hit.path("arguments").path("decisionTime").isTextual()).isTrue();
        assertThat(hit.path("bindings").path("decisionTime").asText()).isEqualTo("$context.decisionTime");
        assertThat(hit.path("value").asBoolean()).isTrue();
        assertThat(hit.path("matched").asBoolean()).isTrue();
        assertThat(hit.path("recordId").asText()).isEqualTo(exclusion.id.toString());
        assertThat(hit.path("dataSource").asText()).isEqualTo("CLIENT_EXCLUSION");
        assertThat(hit.path("durationMs").isNumber()).isTrue();
        assertThat(hit.path("error").isNull()).isTrue();

        // 换一个客户：未命中，没有记录 ID
        JsonNode miss = send("POST", SIMULATE, Map.of("arguments", Map.of("providerCode", "$candidate.providerCode", "clientId", "API-OTHER"),
                "operator", "EQ", "value", true, "sample", SAMPLE));
        assertThat(miss.path("value").asBoolean()).isFalse();
        assertThat(miss.path("matched").asBoolean()).isFalse();
        assertThat(miss.path("recordId").isNull()).isTrue();
    }

    @Test
    void simulateRejectsInvalidArgumentsWithBadRequest() throws Exception {
        assertThat(errorOf(jsonRequest("POST", SIMULATE, Map.of("arguments", Map.of("providerCode", "$candidate.providerCode"),
                "operator", "EQ", "value", true, "sample", SAMPLE)))).isEqualTo("函数 clientExcluded 缺少必填参数：clientId");
        assertThat(errorOf(jsonRequest("POST", SIMULATE, Map.of("arguments", EXCLUSION_ARGUMENTS, "operator", "GT", "value", true, "sample", SAMPLE))))
                .isEqualTo("函数 clientExcluded 返回类型为 BOOLEAN，不支持运算符 GT，可用：EQ、NE");
        assertThat(errorOf(jsonRequest("POST", SIMULATE, Map.of("arguments", EXCLUSION_ARGUMENTS, "operator", "EQ", "value", true,
                "sample", Map.of("providerCode", "PROV-GD"))))).isEqualTo("试算需要合作方和客户");
        assertThat(errorOf(jsonRequest("POST", SIMULATE, null))).isEqualTo("试算需要合作方和客户");
    }

    @Test
    void requiredFunctionCanOnlyBeDisabledNotDeleted() throws Exception {
        // 系统必检（个案审批）：C1 执行器在代码里强制落实，删除绕不过，只能停用
        Map<String, Object> approvalArguments = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId",
                "flowCategory", "$candidate.flowCategory");
        assertThat(errorOf(MockMvcRequestBuilders.delete("/api/function-metadata/hasValidCaseApproval"))).isEqualTo("C1 正在使用该系统必检函数，请停用而非删除");
        JsonNode disabled = send("POST", "/api/function-metadata/hasValidCaseApproval/toggle", null);
        assertThat(disabled.path("enabled").asBoolean()).isFalse();
        assertThat(disabled.path("usagePolicy").asText()).as("使用策略来自代码，停用后仍是系统必检").isEqualTo("REQUIRED");
        // 停用后不能再添加为条件节点；系统必检清单仍然列出它（只读），但 implemented 为 false（不能作为节点执行）
        JsonNode catalog = ok(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", "C1"));
        assertThat(values(catalog.path("functions"), "functionCode")).hasSize(SEEDED.size() + 2)
                .containsExactlyInAnyOrderElementsOf(plus(SEEDED, "clientExcluded", "getProviderMtd"))
                .doesNotContain("hasValidCaseApproval");
        assertThat(values(catalog.path("required"), "functionCode")).containsExactly("findBestAppetiteRule", "hasValidCaseApproval", "getProviderMtd");
        assertThat(entry(catalog.path("required"), "functionCode", "hasValidCaseApproval").path("implemented").asBoolean()).isFalse();
        // 引用它的条件不能再保存
        assertThat(errorOf(jsonRequest("PUT", "/api/appetites/" + rule().id + "/condition", Map.of("enabled", true,
                "tree", Map.of("logic", "AND", "children", List.of(functionNode("hasValidCaseApproval", approvalArguments)))))))
                .isEqualTo("函数 hasValidCaseApproval 已停用，不能用于条件");
    }

    @Test
    void optionalFunctionCanBeDeletedWhileNothingReferencesIt() throws Exception {
        // clientExcluded 不再是系统必检：没有被字段绑定或已保存条件引用时可以删除，删除后不能再添加为条件节点
        JsonNode catalog = ok(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", "C1"));
        assertThat(entry(catalog.path("functions"), "functionCode", "clientExcluded").path("usagePolicy").asText()).isEqualTo("OPTIONAL");
        assertThat(ok(MockMvcRequestBuilders.delete("/api/function-metadata/clientExcluded")).path("deleted").asText()).isEqualTo("clientExcluded");
        assertThat(values(ok(MockMvcRequestBuilders.get("/api/function-metadata/available").param("ruleCode", "C1")).path("functions"), "functionCode"))
                .hasSize(SEEDED.size() + 2)
                .containsExactlyInAnyOrderElementsOf(plus(SEEDED, "getProviderMtd", "hasValidCaseApproval"))
                .doesNotContain("clientExcluded");
        assertThat(errorOf(jsonRequest("PUT", "/api/appetites/" + rule().id + "/condition", Map.of("enabled", true,
                "tree", Map.of("logic", "AND", "children", List.of(functionNode("clientExcluded", EXCLUSION_ARGUMENTS)))))))
                .isEqualTo("函数不存在：clientExcluded");
    }

    @Test
    void optionalFunctionReferencedByASavedConditionCannotBeDeletedUntilTheReferenceIsGone() throws Exception {
        JsonNode created = send("POST", "/api/function-metadata", new BusinessFunctionController.Input("clientExcludedCopy", "客户排除（副本）",
                "客户与准入", "BOOLEAN", "C1", "CANDIDATE", "REQUEST", "clientExclusionChecker", "CLIENT_EXCLUSION", "[]", "测试用可选函数", true));
        assertThat(created.path("usagePolicy").asText()).isEqualTo("OPTIONAL");
        assertThat(created.path("implemented").asBoolean()).isTrue();

        // 已保存的 C1 条件引用了该函数：不能删除
        saveCondition(Map.of("logic", "AND", "children", List.of(functionNode("clientExcludedCopy", EXCLUSION_ARGUMENTS))));
        assertThat(errorOf(MockMvcRequestBuilders.delete("/api/function-metadata/clientExcludedCopy")))
                .isEqualTo("函数已被引用，不能删除：C1 准入规则 #" + rule().id);

        // 绑定了该函数的字段同样算引用
        send("POST", "/api/field-metadata", Map.of("fieldCode", "apiCopyFlag", "fieldName", "副本标记", "dataType", "BOOLEAN",
                "allowedOperators", "EQ,NE", "allowedRules", "C1", "enabled", true, "valueSource", "FUNCTION", "functionCode", "clientExcludedCopy",
                "argumentBindings", Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId")));
        assertThat(errorOf(MockMvcRequestBuilders.delete("/api/function-metadata/clientExcludedCopy")))
                .isEqualTo("函数已被引用，不能删除：字段绑定 apiCopyFlag、C1 准入规则 #" + rule().id);

        // 条件里的函数节点删掉、字段改回请求输入后可以删除
        saveCondition(Map.of("logic", "AND", "children", List.of(Map.of("type", "FIELD", "field", "paymentCurrency", "operator", "EQ", "value", "USD"))));
        send("PUT", "/api/field-metadata/apiCopyFlag", Map.of("fieldCode", "apiCopyFlag", "fieldName", "副本标记", "dataType", "BOOLEAN",
                "allowedOperators", "EQ,NE", "allowedRules", "C1", "enabled", true, "valueSource", "REQUEST"));
        assertThat(ok(MockMvcRequestBuilders.delete("/api/function-metadata/clientExcludedCopy")).path("deleted").asText()).isEqualTo("clientExcludedCopy");
    }

    @Test
    void functionListCarriesUsagePolicyAndImplementationStatus() throws Exception {
        JsonNode list = ok(MockMvcRequestBuilders.get("/api/function-metadata"));
        assertThat(values(list, "functionCode")).contains("clientExcluded", "hasValidCaseApproval", "getProviderMtd", "findBestAppetiteRule", "restrictionHit");
        assertThat(entry(list, "functionCode", "clientExcluded").path("usagePolicy").asText()).isEqualTo("OPTIONAL");
        assertThat(entry(list, "functionCode", "clientExcluded").path("implemented").asBoolean()).isTrue();
        assertThat(entry(list, "functionCode", "findBestAppetiteRule").path("usagePolicy").asText()).isEqualTo("REQUIRED");
        assertThat(entry(list, "functionCode", "findBestAppetiteRule").path("implemented").asBoolean()).isFalse();
        assertThat(entry(list, "functionCode", "restrictionHit").path("usagePolicy").asText()).isEqualTo("OPTIONAL");
        assertThat(entry(list, "functionCode", "restrictionHit").path("implemented").asBoolean()).isFalse();
        // 可绑定的 Handler：已规划的一批 + 代码里已注册的
        assertThat(values(ok(MockMvcRequestBuilders.get("/api/function-metadata/handlers")), null))
                .contains("clientExclusionChecker", "caseApprovalChecker", "providerMtdReader", "appetiteRuleSelector");
    }

    @Test
    void fieldBindingIsSavedThroughTheApiAndLegacyPayloadsKeepTheExistingBinding() throws Exception {
        // 升级前的七字段请求：不表达取值来源，新增字段的取值来源留空（按请求输入处理）
        JsonNode legacy = send("POST", "/api/field-metadata", fieldInput());
        assertThat(legacy.path("valueSource").isNull()).isTrue();

        JsonNode bound = send("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("valueSource", "FUNCTION", "functionCode", "clientExcluded",
                "argumentBindings", Map.of("clientId", "$transaction.clientId", "providerCode", "$candidate.providerCode")));
        assertThat(bound.path("valueSource").asText()).isEqualTo("FUNCTION");
        assertThat(bound.path("functionCode").asText()).isEqualTo("clientExcluded");
        assertThat(bound.path("argumentBindings").asText()).as("按 Handler 参数顺序落库，不含执行时才补的默认路径")
                .isEqualTo("{\"providerCode\":\"$candidate.providerCode\",\"clientId\":\"$transaction.clientId\"}");
        assertThat(entry(ok(MockMvcRequestBuilders.get("/api/field-metadata")), "fieldCode", "apiBoundFlag").path("functionCode").asText())
                .isEqualTo("clientExcluded");

        // 升级前的七字段请求修改已绑定的字段（例如只改名称）：没有表达取值来源，绑定保持不变
        JsonNode legacyUpdate = send("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("fieldName", "接口绑定标记（改名）"));
        assertThat(legacyUpdate.path("fieldName").asText()).isEqualTo("接口绑定标记（改名）");
        assertThat(legacyUpdate.path("valueSource").asText()).isEqualTo("FUNCTION");
        assertThat(legacyUpdate.path("functionCode").asText()).isEqualTo("clientExcluded");
        // 保留的绑定仍按本次提交复核：clientExcluded 不适用于 C2，把适用规则改成含 C2 会被拦下
        assertThat(errorOf(jsonRequest("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("allowedRules", "C1,C2"))))
                .isEqualTo("函数 clientExcluded 不适用于当前规则 C2");
        // 绑定函数的字段只能适用于已接入函数节点的规则
        assertThat(errorOf(jsonRequest("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("allowedRules", "C1,C4"))))
                .isEqualTo("绑定函数的字段只能适用于 " + RULES + "，不能用于 C1,C4");

        // 改回请求输入：绑定清空
        JsonNode request = send("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("valueSource", "REQUEST"));
        assertThat(request.path("valueSource").asText()).isEqualTo("REQUEST");
        assertThat(request.path("functionCode").isNull()).isTrue();
        assertThat(request.path("argumentBindings").isNull()).isTrue();

        assertThat(errorOf(jsonRequest("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("dataType", "DECIMAL", "allowedOperators", "EQ",
                "valueSource", "FUNCTION", "functionCode", "clientExcluded"))))
                .isEqualTo("函数 clientExcluded 返回类型为 BOOLEAN，与字段类型 DECIMAL 不一致，不能绑定");
        assertThat(errorOf(jsonRequest("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("valueSource", "REQUEST", "functionCode", "clientExcluded"))))
                .isEqualTo("取值来源为请求输入时不能绑定函数");
        // 参数校验的顺序：先查必填参数，再校验取值路径（不受控的路径直接报错，不会当成字面量）
        assertThat(errorOf(jsonRequest("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("valueSource", "FUNCTION", "functionCode", "clientExcluded",
                "argumentBindings", Map.of("clientId", "$env.clientId"))))).isEqualTo("函数 clientExcluded 缺少必填参数：providerCode");
        assertThat(errorOf(jsonRequest("PUT", "/api/field-metadata/apiBoundFlag", fieldInput("valueSource", "FUNCTION", "functionCode", "clientExcluded",
                "argumentBindings", Map.of("providerCode", "$candidate.providerCode", "clientId", "$env.clientId")))))
                .contains("不受控的取值路径：env.clientId");
    }
}
