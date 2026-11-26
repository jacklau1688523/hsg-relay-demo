package com.hsg.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.api.BusinessFunctionController;
import com.hsg.relay.api.FieldMetadataController;
import com.hsg.relay.api.RelayController;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.function.CollectionIntersectsFunction;
import com.hsg.relay.function.FunctionExecutionService.Call;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.repo.ClientExclusionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 元数据集合字段 + 通用集合函数：providerExcludedClientIds 由字段解析器取数，collectionContains / collectionIntersects 只做计算，
 * 函数参数用显式来源映射（FIELD / TRANSACTION）；客户排除由条件树里的节点决定，执行器不再单独必检。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relaycollection;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class CollectionFunctionTest {
    private static final String SET = "providerExcludedClientIds";
    private static final Map<String, Object> COLLECTION = Map.of("source", "FIELD", "fieldCode", SET);
    private static final Map<String, Object> CLIENT = Map.of("source", "TRANSACTION", "fieldCode", "clientId");
    private static final Map<String, Object> CONTAINS_ARGUMENTS = Map.of("collection", COLLECTION, "element", CLIENT);
    private static final String LEGACY_JSON = "{\"logic\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"USD\"},"
            + "{\"type\":\"FUNCTION\",\"functionCode\":\"clientExcluded\",\"arguments\":{\"providerCode\":\"$candidate.providerCode\","
            + "\"clientId\":\"$transaction.clientId\",\"decisionTime\":\"$context.decisionTime\"},\"operator\":\"EQ\",\"value\":false}]}";

    @Autowired
    ExpressionService expressions;
    @Autowired
    C1Service c1;
    @Autowired
    C1ConditionService globalConditions;
    @Autowired
    ClientExclusionChecker checker;
    @Autowired
    ClientExclusionRepository exclusions;
    @Autowired
    RuleAuditRepository audit;
    @Autowired
    FieldMetadataController fieldsApi;
    @Autowired
    FunctionMetadataService functionMetadata;
    @Autowired
    RelayController relay;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    private static ExpressionService.Node function(String code, Map<String, Object> arguments, String operator, Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, operator, value, code, arguments);
    }

    private static ExpressionService.Node field(String code, String operator, Object value) {
        return new ExpressionService.Node(null, null, code, operator, value);
    }

    private static ExpressionService.Node group(String logic, ExpressionService.Node... children) {
        return new ExpressionService.Node(logic, List.of(children), null, null, null);
    }

    private static ExpressionService.Node contains(String operator, Object value) {
        return function("collectionContains", CONTAINS_ARGUMENTS, operator, value);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
    }

    private static RuleExecutionContext context(String provider, String client) {
        return RuleExecutionContext.c1(provider, client, "USD", "KG", new BigDecimal("100"), now());
    }

    private ClientExclusion exclude(String client, String provider) {
        return exclusions.save(new ClientExclusion(client, provider, "test", now().minusDays(1), null));
    }

    private boolean matched(ExpressionService.Node tree, RuleExecutionContext context) {
        return matched(tree, Map.of(), context);
    }

    private boolean matched(ExpressionService.Node tree, Map<String, Object> variables, RuleExecutionContext context) {
        return expressions.evaluate(expressions.compileDetailed(tree, "C1"), variables, context).matched();
    }

    // ---------- 元数据 ----------

    @Test
    void collectionFieldAndFunctionsAreSeededAndQueryableFromTheMetadataCenter() {
        var field = fieldsApi.list().stream().filter(f -> f.fieldCode.equals(SET)).findFirst().orElseThrow();
        assertThat(field.dataType).isEqualTo("STRING_SET");
        assertThat(field.elementType).isEqualTo("STRING");
        assertThat(field.allowedRules).isEqualTo("C1");
        assertThat(field.allowedOperators).isEqualTo("CONTAINS,NOT_CONTAINS");
        assertThat(field.valueSource).isEqualTo("DATA_RESOLVER");
        assertThat(field.resolverCode).isEqualTo("providerExclusionListResolver");
        assertThat(field.executionScope).isEqualTo("CANDIDATE");
        assertThat(field.cachePolicy).isEqualTo("REQUEST");
        assertThat(field.enabled).isTrue();
        assertThat(fieldsApi.resolvers()).contains("providerExclusionListResolver");
        // 旧字段不受影响：仍是请求输入
        var legacy = fieldsApi.list().stream().filter(f -> f.fieldCode.equals("paymentCurrency")).findFirst().orElseThrow();
        assertThat(legacy.dataType).isEqualTo("ENUM");
        assertThat(legacy.valueSource == null || legacy.valueSource.isBlank() || "REQUEST".equals(legacy.valueSource)).isTrue();
        assertThat(legacy.resolverCode).isNull();

        var catalog = functionMetadata.available("C1");
        assertThat(catalog.functions()).extracting(f -> f.functionCode())
                .contains("collectionContains", "collectionIntersects", "clientExcluded");
        var containsView = catalog.functions().stream().filter(f -> f.functionCode().equals("collectionContains")).findFirst().orElseThrow();
        assertThat(containsView.functionName()).isEqualTo("集合是否包含指定值");
        assertThat(containsView.returnType()).isEqualTo("BOOLEAN");
        assertThat(containsView.parameters()).extracting(FunctionMetadataService.ParameterView::code).containsExactly("collection", "element");
        assertThat(containsView.parameters()).extracting(FunctionMetadataService.ParameterView::type).containsExactly("STRING_SET", "STRING");
        assertThat(containsView.usagePolicy()).isEqualTo("OPTIONAL");
        // clientExcluded 保持兼容：仍是可选条件里的函数，但不再由执行器列为系统必检
        assertThat(catalog.functions()).filteredOn(f -> f.functionCode().equals("clientExcluded")).singleElement()
                .extracting(FunctionMetadataService.FunctionView::usagePolicy).isEqualTo("OPTIONAL");
        assertThat(catalog.required()).extracting(FunctionMetadataService.RequiredView::functionCode)
                .containsExactly("findBestAppetiteRule", "hasValidCaseApproval", "getProviderMtd");
    }

    @Test
    void collectionFieldDefinitionIsValidatedOnSave() {
        var valid = new FieldMetadataController.Input("providerBlockedCurrencies", "合作方屏蔽币种集合", "STRING_SET", "CONTAINS,NOT_CONTAINS",
                "C1", null, true, "DATA_RESOLVER", null, null, "providerExclusionListResolver", "STRING", "CANDIDATE", "REQUEST");
        assertThat(fieldsApi.create(valid).dataType).isEqualTo("STRING_SET");
        // 元素类型只能是 STRING；解析器必须已注册；只能适用于已接入函数节点的规则（C1 / C2 / C3 / C5 / C7 / C8）；不能同时绑定函数
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("badElement", "坏", "STRING_SET", "CONTAINS", "C1", null, true,
                "DATA_RESOLVER", null, null, "providerExclusionListResolver", "INTEGER", "CANDIDATE", "REQUEST")))
                .hasMessageContaining("元素类型");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("badResolver", "坏", "STRING_SET", "CONTAINS", "C1", null, true,
                "DATA_RESOLVER", null, null, "noSuchResolver", "STRING", "CANDIDATE", "REQUEST")))
                .hasMessageContaining("尚未注册");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("badRules", "坏", "STRING_SET", "CONTAINS", "C1,C4", null, true,
                "DATA_RESOLVER", null, null, "providerExclusionListResolver", "STRING", "CANDIDATE", "REQUEST")))
                .hasMessage("数据解析字段只能适用于 C1 / C2 / C3 / C5 / C7 / C8，不能用于 C1,C4");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("badOperator", "坏", "STRING_SET", "EQ", "C1", null, true,
                "DATA_RESOLVER", null, null, "providerExclusionListResolver", "STRING", "CANDIDATE", "REQUEST")))
                .hasMessageContaining("运算符与数据类型不匹配");
        // 升级前的七参构造（请求输入）照常可用
        assertThat(fieldsApi.create(new FieldMetadataController.Input("legacyScalar", "旧字段", "STRING", "EQ,NE", "C1", null, true)).resolverCode).isNull();
    }

    // ---------- 通用函数：包含 / 不包含 / 空集合 ----------

    // 1. 集合包含值，返回 true
    @Test
    void collectionContainsReturnsTrueWhenTheElementIsInTheCollection() {
        ClientExclusion record = exclude("C10001", "PROV-GD");
        var compiled = expressions.compileDetailed(contains("EQ", true), "C1");
        assertThat(compiled.readable()).isEqualTo("(collectionContains(collection=" + SET + ", element=$transaction.clientId) == true)");
        var context = context("PROV-GD", "C10001");
        assertThat(expressions.evaluate(compiled, Map.of(), context).matched()).isTrue();
        assertThat(context.calls()).singleElement().satisfies(call -> {
            assertThat(call.functionCode()).isEqualTo("collectionContains");
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isEqualTo(record.id.toString());
            assertThat(call.dataSource()).isEqualTo("ARGUMENT");
            // 调用记录里的集合是展示形态：来源、个数、前几个元素，不是解析器对象
            @SuppressWarnings("unchecked")
            var collection = (Map<String, Object>) call.arguments().get("collection");
            assertThat(collection).containsEntry("source", "CLIENT_EXCLUSION").containsEntry("size", 1).containsEntry("materialized", true);
            assertThat(collection.get("sample")).isEqualTo(List.of("C10001"));
        });
    }

    // 2. 集合不包含值，返回 false
    @Test
    void collectionContainsReturnsFalseWhenTheElementIsNotInTheCollection() {
        exclude("C10008", "PROV-GD");
        var context = context("PROV-GD", "C10001");
        assertThat(matched(contains("EQ", false), context)).isTrue();
        assertThat(context.calls()).singleElement().satisfies(call -> {
            assertThat(call.value()).isEqualTo(false);
            assertThat(call.recordId()).isNull();
        });
    }

    // 3. 空集合正常判断：包含为 false，不包含为 true
    @Test
    void emptyCollectionIsLoadedAndJudgedNormally() {
        var context = context("PROV-HO", "C10001");
        assertThat(checker.exclusionSet(context).elements()).isEmpty();
        assertThat(matched(contains("EQ", false), context)).isTrue();
        assertThat(matched(group("AND", contains("NE", true), field(SET, "NOT_CONTAINS", "C10001")), context)).isTrue();
        // 集合字段直接做字段条件（CONTAINS，条件值写死元素）同样是 false
        assertThat(matched(field(SET, "CONTAINS", "C10001"), context)).isFalse();
        assertThatThrownBy(() -> expressions.compileDetailed(field(SET, "EQ", "C10001"), "C1"))
                .hasMessageContaining("不适用于当前规则或不支持该操作符");
    }

    // 5. 客户排除记录过期后不再命中
    @Test
    void expiredExclusionIsNoLongerInTheCollection() {
        LocalDateTime time = now();
        exclusions.save(new ClientExclusion("C10001", "PROV-GD", "expired", time.minusDays(10), time.minusDays(1)));
        exclusions.save(new ClientExclusion("C10001", "PROV-GD", "not yet effective", time.plusDays(1), null));
        var context = context("PROV-GD", "C10001");
        assertThat(checker.exclusionSet(context).elements()).isEmpty();
        assertThat(matched(contains("EQ", true), context)).isFalse();
    }

    // 6. 客户排除记录解除后不再命中（解除只补失效时间，不删除历史）
    @Test
    void releasedExclusionIsNoLongerInTheCollection() {
        ClientExclusion record = exclude("C10001", "PROV-GD");
        var context = context("PROV-GD", "C10001");
        assertThat(matched(contains("EQ", true), context)).isTrue();
        relay.releaseExclusion(record.id);
        assertThat(exclusions.findById(record.id).orElseThrow().effectiveTo).isNotNull();
        // 新的判定（新的上下文）里不再命中
        var later = context("PROV-GD", "C10001");
        assertThat(matched(contains("EQ", true), later)).isFalse();
        assertThat(c1.evaluate(new C1Service.Simulation("C10001", "PROV-GD", "USD", "KG", new BigDecimal("100"))).reasonCode())
                .isEqualTo("APPETITE_NOT_SET");
    }

    // 7. 不同合作方拥有不同排除集合
    @Test
    void eachProviderHasItsOwnCollection() {
        exclude("C10001", "PROV-GD");
        exclude("C10002", "PROV-HO");
        var gd = checker.exclusionSet(context("PROV-GD", "C10001")).elements();
        var ho = checker.exclusionSet(context("PROV-HO", "C10001")).elements();
        assertThat(gd).containsExactly("C10001");
        assertThat(ho).containsExactly("C10002");
        assertThat(matched(contains("EQ", true), context("PROV-GD", "C10001"))).isTrue();
        assertThat(matched(contains("EQ", true), context("PROV-HO", "C10001"))).isFalse();
        assertThat(matched(contains("EQ", true), context("PROV-HO", "C10002"))).isTrue();
    }

    // 8. FIELD 参数正确解析：取到的是字段解析器的集合，请求里提交的同名取值不采用
    @Test
    void fieldArgumentIsResolvedByTheFieldResolver() {
        exclude("C10001", "PROV-GD");
        var context = context("PROV-GD", "C10001");
        var compiled = expressions.compileDetailed(contains("EQ", true), "C1");
        // 请求伪造一个同名取值：不采用
        assertThat(expressions.evaluate(compiled, Map.of(SET, List.of("C99999")), context).matched()).isTrue();
        assertThat(compiled.resolvedFields()).isEmpty();   // 集合是函数实参，不是字段条件
        assertThat(context.resolutions()).singleElement().satisfies(resolution -> {
            assertThat(resolution.fieldCode()).isEqualTo(SET);
            assertThat(resolution.resolverCode()).isEqualTo("providerExclusionListResolver");
            assertThat(resolution.cached()).isFalse();
        });
        assertThat(context.calls().get(0).arguments().get("element")).isEqualTo("C10001");
        // 字段直接做条件时记为数据解析字段，且必须带执行上下文
        var leaf = expressions.compileDetailed(field(SET, "CONTAINS", "C10001"), "C1");
        assertThat(leaf.resolvedFields()).containsExactly(SET);
        assertThat(leaf.needsContext()).isTrue();
        assertThatThrownBy(() -> expressions.evaluate(leaf, Map.of(), null))
                .hasMessageContaining("条件引用了数据解析字段 " + SET + "，只能在规则执行中取值");
    }

    // 9. TRANSACTION 参数正确解析：来源 TRANSACTION + 字段 clientId 即受控路径 transaction.clientId
    @Test
    void transactionArgumentIsResolvedThroughTheControlledPath() {
        exclude("C10001", "PROV-GD");
        var context = context("PROV-GD", "C10001");
        assertThat(matched(contains("EQ", true), context)).isTrue();
        assertThat(context.calls().get(0).arguments().get("element")).isEqualTo("C10001");
        // 显式写成 path 与 fieldCode 等价；显式写全路径同样可以
        var byPath = function("collectionContains", Map.of("collection", COLLECTION, "element", Map.of("source", "TRANSACTION", "path", "clientId")), "EQ", true);
        assertThat(matched(byPath, context)).isTrue();
        var byName = function("collectionContains", Map.of("collection", COLLECTION, "element", Map.of("source", "TRANSACTION", "fieldCode", "transaction.clientId")), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(byName, "C1")).hasMessageContaining("不受控的取值路径：transaction.transaction.clientId");
        // 没有登记的路径不能借参数名混进来
        var unknown = function("collectionContains", Map.of("collection", COLLECTION, "element", Map.of("source", "TRANSACTION", "fieldCode", "providerCode")), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(unknown, "C1")).hasMessageContaining("不受控的取值路径：transaction.providerCode");
    }

    // 10. 错误数据类型被拒绝（保存 / 编译条件时就报出）
    @Test
    void wrongTypesAreRejectedWithExplicitErrors() {
        var fieldAsElement = function("collectionContains", Map.of("collection", COLLECTION, "element", COLLECTION), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(fieldAsElement, "C1"))
                .hasMessageContaining("类型为 STRING_SET，与参数要求的 STRING 不一致");
        var requestField = function("collectionContains", Map.of("collection", Map.of("source", "FIELD", "fieldCode", "paymentCurrency"), "element", CLIENT), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(requestField, "C1"))
                .hasMessageContaining("字段 paymentCurrency 的取值来源不是数据解析（DATA_RESOLVER）");
        var pathAsCollection = function("collectionContains", Map.of("collection", CLIENT, "element", CLIENT), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(pathAsCollection, "C1"))
                .hasMessageContaining("类型应为 STRING_SET，不能绑定 transaction.clientId（STRING）");
        var scalarAsSet = function("collectionContains", Map.of("collection", COLLECTION, "element", Map.of("source", "CONTEXT", "fieldCode", "decisionTime")), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(scalarAsSet, "C1"))
                .hasMessageContaining("类型应为 STRING，不能绑定 context.decisionTime（DATE）");
        var missing = function("collectionContains", Map.of("collection", COLLECTION), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(missing, "C1")).hasMessageContaining("缺少必填参数：element");
        var literalSet = function("collectionContains", Map.of("collection", "C10001", "element", CLIENT), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(literalSet, "C1")).hasMessageContaining("类型应为 STRING_SET，字面量无效：C10001");
        var noSuchField = function("collectionContains", Map.of("collection", Map.of("source", "FIELD", "fieldCode", "noSuchSet"), "element", CLIENT), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(noSuchField, "C1")).hasMessageContaining("字段 noSuchSet 不存在");
        // 前置函数输出：必须写明 nodeId；引用的函数节点须在同一棵条件树里
        var noNode = function("collectionContains", Map.of("collection", Map.of("source", "FUNCTION_OUTPUT", "fieldCode", "x"), "element", CLIENT), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(noNode, "C1")).hasMessage("函数 collectionContains 的参数 collection 的来源 FUNCTION_OUTPUT 缺少 nodeId");
        var future = function("collectionContains", Map.of("collection", Map.of("source", "FUNCTION_OUTPUT", "nodeId", "node-missing"), "element", CLIENT), "EQ", true);
        assertThatThrownBy(() -> expressions.compileDetailed(future, "C1")).hasMessage("引用的前置函数节点不存在：条件树里没有 id 为 node-missing 的函数节点");
        // 数据解析字段只能用于已接入函数节点的规则
        assertThatThrownBy(() -> functionMetadata.binding("c4Set", "STRING_SET", "C4", "DATA_RESOLVER", null, null, "providerExclusionListResolver", "CANDIDATE", "REQUEST"))
                .hasMessageContaining("C1 / C2 / C3 / C5 / C7 / C8").hasMessageContaining("C4");
        // C2 已接入函数节点；但这个函数和这个数据字段都只适用于 C1
        assertThatThrownBy(() -> expressions.compileDetailed(contains("EQ", false), "C2")).hasMessageContaining("不适用于当前规则 C2");
        // Handler 层的第二道检查：运行时实参类型不对时直接报错，不返回 false
        var handler = new com.hsg.relay.function.CollectionContainsFunction();
        assertThatThrownBy(() -> handler.execute(Map.of("collection", RuleCollection.of("T", List.of("A")), "element", 42), null))
                .hasMessageContaining("参数 element 应为非空字符串").hasMessageContaining("Integer 42");
        assertThatThrownBy(() -> handler.execute(Map.of("collection", "not-a-collection", "element", "A"), null))
                .hasMessageContaining("参数 collection 应为集合").hasMessageContaining("String not-a-collection");
        Map<String, Object> unloaded = new java.util.HashMap<>();
        unloaded.put("collection", null);
        unloaded.put("element", "A");
        assertThatThrownBy(() -> handler.execute(unloaded, null)).hasMessageContaining("数据未加载");
    }

    // 11. FUNCTION 节点参与 AND / OR
    @Test
    void functionNodeTakesPartInAndOrGroups() {
        exclude("C10001", "PROV-GD");
        var context = context("PROV-GD", "C10001");
        Map<String, Object> usd = Map.of("paymentCurrency", "USD");
        var usdAndNotContains = group("AND", field("paymentCurrency", "EQ", "USD"), contains("EQ", false));
        var usdAndContains = group("AND", field("paymentCurrency", "EQ", "USD"), group("AND", contains("EQ", true)));
        var usdOrCnyAndContains = group("AND", field("paymentCurrency", "EQ", "USD"), group("OR", field("paymentCurrency", "EQ", "CNY"), contains("EQ", true)));
        // (币种是 CNY) OR (集合包含当前客户)：后者为 true → 整体成立
        assertThat(matched(group("OR", field("paymentCurrency", "EQ", "CNY"), contains("EQ", true)), usd, context)).isTrue();
        // (币种是 USD) AND (集合不包含当前客户)：后者为 false → 整体不成立
        assertThat(matched(usdAndNotContains, usd, context)).isFalse();
        // AND ( (币种是 USD) AND (包含) )
        assertThat(matched(usdAndContains, usd, context)).isTrue();
        assertThat(matched(usdOrCnyAndContains, usd, context)).isTrue();
        // 字段条件不成立时，AND 整体不成立（函数节点为 true 也一样）
        assertThat(matched(usdAndContains, Map.of("paymentCurrency", "CNY"), context)).isFalse();
    }

    // 12. 旧版规则 JSON（无 type 的字段节点 + 字符串参数的函数节点）仍能执行
    @Test
    void legacyRuleJsonStillRuns() throws Exception {
        ExpressionService.Node tree = json.readValue(LEGACY_JSON, ExpressionService.Node.class);
        var compiled = expressions.compileDetailed(tree, "C1");
        assertThat(compiled.resolvedFields()).isEmpty();
        assertThat(expressions.evaluate(compiled, Map.of("paymentCurrency", "USD"), context("PROV-HO", "C10001")).matched()).isTrue();
        assertThat(expressions.evaluate(compiled, Map.of("paymentCurrency", "CNY"), context("PROV-HO", "C10001")).matched()).isFalse();
        exclude("C10001", "PROV-HO");
        assertThat(expressions.evaluate(compiled, Map.of("paymentCurrency", "USD"), context("PROV-HO", "C10001")).matched()).isFalse();
    }

    // 13. 客户排除不再由执行器必检：由全局准入条件里的集合条件决定是否过滤，命中记录随函数调用返回
    @Test
    void c1ExclusionIsDecidedByTheConditionTreeNotByAMandatoryCheck() throws Exception {
        ClientExclusion record = exclude("C10001", "PROV-GD");
        var request = new C1Service.Simulation("C10001", "PROV-GD", "USD", "KG", new BigDecimal("100"));
        // 条件树里没有任何排除相关的节点：有有效的排除记录也不会被过滤，也不查询排除记录；
        // PROV-GD 没有准入规则，按默认拒绝返回
        globalConditions.save(new C1ConditionService.Input(true, group("AND", field("paymentCurrency", "EQ", "USD")), null));
        var withoutNode = c1.evaluate(request);
        assertThat(withoutNode.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(withoutNode.functionCalls()).isEmpty();
        assertThat(withoutNode.steps()).extracting(C1Service.Trace::step).containsSubsequence("1. 客户档案", "2. C1 准入条件 PSP", "失败原因");

        // 条件树里就是目标表达式 collectionContains(providerExcludedClientIds, clientId) == false：
        // 客户在集合里，条件不满足，C1 返回 C1_CONDITION_NOT_MATCHED
        var version = globalConditions.get().versionNo;
        globalConditions.save(new C1ConditionService.Input(true, contains("EQ", false), version));
        var result = c1.evaluate(request);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(result.functionCalls()).extracting(Call::functionCode).containsExactly("collectionContains");
        assertThat(result.functionCalls().get(0).value()).isEqualTo(true);
        assertThat(result.functionCalls().get(0).recordId()).isEqualTo(record.id.toString());
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("2. C1 准入条件 PSP");
            assertThat(step.status()).isEqualTo("FAIL");
            assertThat(step.detail()).contains("准入条件不满足");
        });
        // 原必检的「排除命中」审计随必检一起去掉
        assertThat(audit.findAll()).noneMatch(row -> row.objectType.equals("C1_DECISION") && row.operation.equals("EXCLUSION_HIT"));

        // 运营改成不引用集合的条件：排除记录不再参与判定
        globalConditions.save(new C1ConditionService.Input(true, field("paymentCurrency", "EQ", "USD"), null));
        assertThat(c1.evaluate(request).reasonCode()).isEqualTo("APPETITE_NOT_SET");
    }

    // 条件树里的多个节点共用请求内的同一份数据：字段解析一次执行 + 两次复用，第二个相同函数节点复用结果
    @Test
    void conditionNodesShareTheRequestLevelCollection() {
        // 被排除的是另一个客户：当前客户 C10001 未被排除，条件树照常求值
        ClientExclusion record = exclude("C10009", "PROV-HO");
        // (不包含 C10001) AND ( (包含 C10001) OR (不包含 C10001) )：第三个节点与第一个实参相同，复用其结果
        var twice = group("AND", contains("EQ", false), group("OR", contains("EQ", true), contains("EQ", false)));
        globalConditions.save(new C1ConditionService.Input(true, twice, null));
        var result = c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100")));
        assertThat(result.reasonCode()).isEqualTo("PASS");
        assertThat(result.functionCalls()).extracting(Call::functionCode)
                .containsExactly("collectionContains", "collectionContains", "collectionContains", "hasValidCaseApproval");
        // 当前客户未被排除：三个节点的函数结果都是 false；第三个节点与第一个实参相同，复用其结果
        assertThat(result.functionCalls().get(0).value()).isEqualTo(false);
        assertThat(result.functionCalls().get(1).value()).isEqualTo(false);
        assertThat(result.functionCalls().get(2).value()).isEqualTo(false);
        assertThat(result.functionCalls().get(2).cached()).isTrue();

        // 直接求值同一个上下文：三个节点各做一次字段解析（后两次复用请求级结果，不再查库），第三个节点的函数结果为复用
        var context = context("PROV-HO", "C10001");
        assertThat(matched(twice, context)).isTrue();
        assertThat(context.resolutions()).extracting(FieldValueService.Resolution::fieldCode).containsExactly(SET, SET, SET);
        assertThat(context.resolutions()).extracting(FieldValueService.Resolution::cached).containsExactly(false, true, true);
        assertThat(context.calls().get(2).cached()).isTrue();

        // 同一上下文里再引用集合字段做条件：仍是同一份（复用），不再解析
        assertThat(matched(field(SET, "CONTAINS", "C10009"), context)).isTrue();
        assertThat(context.resolutions()).hasSize(4);
        assertThat(context.resolutions().get(3).cached()).isTrue();
        // 未被排除的客户做集合字段条件：不包含
        assertThat(matched(field(SET, "NOT_CONTAINS", "C10001"), context)).isTrue();
    }

    // ---------- C2 / C3 不受影响 ----------

    // 14. C2 / C3 原有规则不受影响：字段条件照常编译求值，函数节点和集合字段仍只属于 C1
    @Test
    void c2AndC3FieldRulesAreUnaffected() {
        var tree = group("AND", field("beneficiaryBankCountry", "EQ", "KG"), field("paymentCurrency", "EQ", "USD"));
        assertThat(expressions.compile(tree, "C2")).isEqualTo("((beneficiaryBankCountry == 'KG') && (paymentCurrency == 'USD'))");
        var reach = group("AND", field("beneficiaryBankCountry", "EQ", "KG"));
        assertThat(expressions.compile(reach, "C3")).isEqualTo("((beneficiaryBankCountry == 'KG'))");
        assertThat(expressions.preview(new ExpressionService.Preview(tree, Map.of("beneficiaryBankCountry", "KG", "paymentCurrency", "USD"), "C2")).matched()).isTrue();
        assertThat(expressions.preview(new ExpressionService.Preview(tree, Map.of("beneficiaryBankCountry", "KG", "paymentCurrency", "CNY"), "C2")).matched()).isFalse();
        assertThat(expressions.preview(new ExpressionService.Preview(reach, Map.of("beneficiaryBankCountry", "KG"), "C3")).matched()).isTrue();
        assertThat(expressions.preview(new ExpressionService.Preview(reach, Map.of("beneficiaryBankCountry", "CN"), "C3")).matched()).isFalse();
        var c2 = expressions.compileDetailed(tree, "C2");
        assertThat(c2.needsContext()).isFalse();
        assertThat(c2.resolvedFields()).isEmpty();
        // 这个数据字段 / 函数只适用于 C1：不能进入 C2、C3 的条件（C2 / C3 自己的函数与数据字段另见集合函数测试）
        assertThatThrownBy(() -> expressions.compileDetailed(field(SET, "CONTAINS", "C10001"), "C2")).hasMessage("字段 providerExcludedClientIds 不适用于当前规则或不支持该操作符");
        assertThatThrownBy(() -> expressions.compileDetailed(contains("EQ", false), "C3")).hasMessageContaining("不适用于当前规则 C3");
    }

    // ---------- 求交集（Handler 只做计算） ----------

    @Test
    void collectionIntersectsComparesTwoCollections() {
        var handler = new CollectionIntersectsFunction();
        var cnRu = RuleCollection.of("NATIONALITY", List.of("CN", "RU"));
        var kgRu = RuleCollection.of("NATIONALITY", Map.of("KG", "11", "RU", "12"));
        assertThat(handler.execute(Map.of("leftCollection", cnRu, "rightCollection", kgRu), null).value()).isEqualTo(true);
        assertThat(handler.execute(Map.of("leftCollection", cnRu, "rightCollection", kgRu), null).recordId()).isEqualTo("12");
        assertThat(handler.execute(Map.of("leftCollection", cnRu, "rightCollection", RuleCollection.of("NATIONALITY", List.of("KG", "US"))), null).value()).isEqualTo(false);
        assertThat(handler.execute(Map.of("leftCollection", cnRu, "rightCollection", RuleCollection.of("NATIONALITY", List.of())), null).value()).isEqualTo(false);
        assertThatThrownBy(() -> handler.execute(Map.of("leftCollection", "CN", "rightCollection", kgRu), null)).hasMessageContaining("参数 leftCollection 应为集合");
        // 内存集合的缓存键含元素：不同集合的键不同
        assertThat(cnRu.cacheKey()).isNotEqualTo(RuleCollection.of("NATIONALITY", List.of("CN", "KG")).cacheKey());
        assertThat(cnRu.summary()).isEqualTo("NATIONALITY（2 个元素）");
        assertThat(cnRu.view(5).sample()).containsExactly("CN", "RU");
    }

    // ---------- 接口：从元数据中心查询并试算 ----------

    @Test
    void simulateEndpointResolvesFieldAndTransactionArguments() throws Exception {
        ClientExclusion record = exclude("C10001", "PROV-GD");
        String body = json.writeValueAsString(Map.of("arguments", CONTAINS_ARGUMENTS, "operator", "EQ", "value", true,
                "sample", Map.of("providerCode", "PROV-GD", "clientId", "C10001", "amountUsd", "100")));
        String response = mvc.perform(post("/api/function-metadata/collectionContains/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode out = json.readTree(response);
        assertThat(out.path("value").asBoolean()).isTrue();
        assertThat(out.path("matched").asBoolean()).isTrue();
        assertThat(out.path("recordId").asText()).isEqualTo(record.id.toString());
        assertThat(out.path("expression").asText()).isEqualTo("(collectionContains(collection=" + SET + ", element=$transaction.clientId) == true)");
        assertThat(out.path("bindings").path("collection").asText()).isEqualTo(SET);
        assertThat(out.path("bindings").path("element").asText()).isEqualTo("$transaction.clientId");
        assertThat(out.path("arguments").path("element").asText()).isEqualTo("C10001");
        assertThat(out.path("arguments").path("collection").path("size").asInt()).isEqualTo(1);
        assertThat(response).doesNotContain("elements").doesNotContain("cacheKey");
    }
}
