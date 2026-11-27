package com.hsg.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.api.BusinessFunctionController;
import com.hsg.relay.api.FieldMetadataController;
import com.hsg.relay.api.RelayController;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.domain.ClientProfile;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.FunctionExecutionService.Call;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.ClientExclusionRepository;
import com.hsg.relay.repo.ClientProfileRepository;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 函数库接入 C1 规则条件：FUNCTION 节点、可选 / 必检使用策略、函数绑定字段、参数解析和调用复用。
 * 客户排除是 C1 必检：执行器在代码里直接调用 Java Handler，不读函数库元数据。函数库里的 clientExcluded 是可选函数，
 * 停用或删除只影响条件树里的节点和绑定它的字段，绕不过必检；必检与节点入参相同，共用请求级缓存。
 * 路径配置用只有 DEFAULT 路径、条件恒成立的基线（{@link C1PathFixtures#publishDefaultOnly}）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relayfunction;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class C1FunctionTest {
    static final AtomicInteger OPTIONAL_CALLS = new AtomicInteger();

    /** 测试用的可选函数：没有任何执行器把它声明为系统必检，所以使用策略是 OPTIONAL。 */
    @TestConfiguration
    static class OptionalHandler {
        @Bean
        BusinessFunction testHighRiskClientChecker(ClientProfileRepository clients) {
            return new BusinessFunction() {
                @Override
                public String handlerCode() {
                    return "testHighRiskClientChecker";
                }

                @Override
                public String returnType() {
                    return "BOOLEAN";
                }

                @Override
                public String dataSource() {
                    return "CLIENT_PROFILE";
                }

                @Override
                public List<Parameter> parameters() {
                    return List.of(new Parameter("clientId", "STRING", "TRANSACTION", true));
                }

                @Override
                public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
                    OPTIONAL_CALLS.incrementAndGet();
                    ClientProfile client = clients.findById((String) arguments.get("clientId")).orElse(null);
                    return new Outcome(client != null && client.flowCategories != null && client.flowCategories.contains("WEB3"),
                            client == null ? null : client.clientId);
                }
            };
        }
    }

    private static final Map<String, Object> EXCLUSION_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");
    /** 已接入函数节点的规则（报错文字里的清单，按编码排序）。 */
    private static final String FUNCTION_RULE_TEXT = "C1 / C2 / C3 / C5 / C7 / C8";

    @Autowired
    C1Service c1;
    @Autowired
    ExpressionService expressions;
    @Autowired
    RelayController relay;
    @Autowired
    BusinessFunctionController functionsApi;
    @Autowired
    FieldMetadataController fieldsApi;
    @Autowired
    FunctionMetadataService functionMetadata;
    @Autowired
    ClientExclusionRepository exclusions;
    @Autowired
    AppetiteRepository appetites;
    @Autowired
    FieldDefinitionRepository fields;
    @Autowired
    RuleAuditRepository audit;
    @Autowired
    ObjectMapper json;
    @Autowired
    C1PathConfigService pathConfig;

    @BeforeEach
    void publishBaselinePaths() {
        C1PathFixtures.publishDefaultOnly(pathConfig, json);
    }

    /** 发布一份路径配置（测试里临时改路径条件树时用）。 */
    private void publishPaths(C1PathRule.Path... paths) {
        pathConfig.saveDraft(json.valueToTree(new C1PathRule("C1", "FIRST_MATCH", List.of(paths))), null, "tester");
        pathConfig.publish(new C1PathConfigService.PublishRequest(null, "tester", null));
    }

    private static ExpressionService.Node function(String code, Map<String, Object> arguments, String operator, Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, operator, value, code, arguments);
    }

    private static ExpressionService.Node field(String code, String operator, Object value) {
        return new ExpressionService.Node(null, null, code, operator, value);
    }

    private static ExpressionService.Node group(String logic, ExpressionService.Node... children) {
        return new ExpressionService.Node(logic, List.of(children), null, null, null);
    }

    private Appetite rule(String provider, String flow) {
        return appetites.findAll().stream().filter(a -> a.providerCode.equals(provider) && a.flowCategory.equals(flow)).findFirst().orElseThrow();
    }

    private void saveCondition(Long id, ExpressionService.Node tree) {
        relay.saveC1Condition(id, new RelayController.C1ConditionUpdate(true, tree));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
    }

    // 1. 没有有效排除记录：函数返回 false
    @Test
    void clientExcludedReturnsFalseWithoutAValidRecord() {
        var tree = group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false));
        var compiled = expressions.compileDetailed(tree, "C1");
        assertThat(compiled.readable()).isEqualTo("((clientExcluded(providerCode=$candidate.providerCode, clientId=$transaction.clientId, "
                + "decisionTime=$context.decisionTime) == false))");
        var context = RuleExecutionContext.c1("PROV-GD", "C10001", "USD", "KG", new BigDecimal("100"), LocalDateTime.of(2026, 11, 25, 10, 0));
        assertThat(expressions.evaluate(compiled, Map.of(), context).matched()).isTrue();
        assertThat(context.calls()).singleElement().satisfies(call -> {
            assertThat(call.functionCode()).isEqualTo("clientExcluded");
            assertThat(call.value()).isEqualTo(false);
            assertThat(call.recordId()).isNull();
            assertThat(call.cached()).isFalse();
            assertThat(call.dataSource()).isEqualTo("CLIENT_EXCLUSION");
            assertThat(call.arguments()).containsEntry("providerCode", "PROV-GD")
                    .containsEntry("clientId", "C10001")
                    .containsEntry("decisionTime", LocalDateTime.of(2026, 11, 25, 10, 0));
        });
    }

    // 2. 客户排除是必检：条件树里没有节点也会被过滤；路径条件树和准入规则条件里的 clientExcluded 节点与必检共用请求级缓存
    @Test
    @Transactional
    void exclusionIsAMandatoryCheckAndTreeNodesShareItsResult() {
        ClientExclusion sanction = exclusions.save(new ClientExclusion("C10001", "PROV-GD", "sanction", now().minusDays(1), null));
        var request = new C1Service.Simulation("C10001", "PROV-GD", "USD", "KG", new BigDecimal("100"));
        // 路径条件树里没有任何节点：有有效的排除记录同样被过滤，必检在到达准入规则之前结束
        var mandatory = c1.evaluate(request);
        assertThat(mandatory.reasonCode()).isEqualTo("CLIENT_EXCLUDED");
        assertThat(mandatory.exclusionRecordId()).isEqualTo(sanction.id.toString());
        assertThat(mandatory.functionCalls()).singleElement().satisfies(call -> {
            assertThat(call.functionCode()).isEqualTo("clientExcluded");
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isEqualTo(sanction.id.toString());
            assertThat(call.cached()).isFalse();
        });
        assertThat(mandatory.steps()).extracting(C1Service.Trace::step)
                .containsSubsequence("1. 客户档案", "2. 路径选择", "2. 路径条件 PSP", "3. 客户分类", "3. 客户排除", "失败原因");

        // 路径条件树里有 clientExcluded 节点：节点先求值，客户被排除即不满足这条路径的条件，按路径自己的失败原因码过滤
        var node = function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false);
        publishPaths(C1PathFixtures.path("DEFAULT", "默认路径", List.of(), 0, true, group("AND", node)));
        var byPath = c1.evaluate(request);
        assertThat(byPath.reasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(byPath.functionCalls()).singleElement().satisfies(call -> {
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isEqualTo(sanction.id.toString());
            assertThat(call.cached()).isFalse();
        });
        assertThat(byPath.steps()).extracting(C1Service.Trace::step)
                .containsSubsequence("1. 客户档案", "2. 路径条件 PSP", "失败原因").doesNotContain("3. 客户排除");
        publishPaths(C1PathFixtures.path("DEFAULT", "默认路径", List.of(), 0, true, group("AND", field("flowCategory", "NE", "NONE"))));

        // 规则条件里有 clientExcluded 节点（AND）：未被排除时命中规则；节点与必检入参相同，复用必检的查询结果
        Appetite rule = rule("PROV-HO", "PSP");
        var headOffice = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        saveCondition(rule.id, group("AND", node));
        var withNode = c1.evaluate(headOffice);
        assertThat(withNode.reasonCode()).isEqualTo("PASS");
        assertThat(withNode.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "clientExcluded", "hasValidCaseApproval");
        assertThat(withNode.functionCalls().get(0).value()).isEqualTo(false);
        assertThat(withNode.functionCalls().get(0).cached()).isFalse();
        assertThat(withNode.functionCalls().get(1).cached()).isTrue();
        assertThat(withNode.functionCalls().get(1).value()).isEqualTo(false);

        // 被排除：必检先于准入匹配，按 CLIENT_EXCLUDED 过滤，准入规则的条件不再求值
        ClientExclusion active = exclusions.save(new ClientExclusion("C10001", "PROV-HO", "review", now().minusHours(1), null));
        var excluded = c1.evaluate(headOffice);
        assertThat(excluded.reasonCode()).isEqualTo("CLIENT_EXCLUDED");
        assertThat(excluded.steps()).noneMatch(step -> step.status().equals("SKIP"));
        assertThat(excluded.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded");
        assertThat(excluded.functionCalls().get(0).recordId()).isEqualTo(active.id.toString());

        // 解除后运营删除节点并保存：规则条件里不再出现排除检查（必检照常执行）
        relay.releaseExclusion(active.id);
        saveCondition(rule.id, group("AND", field("paymentCurrency", "EQ", "USD")));
        var withoutNode = c1.evaluate(headOffice);
        assertThat(withoutNode.reasonCode()).isEqualTo("PASS");
        assertThat(withoutNode.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "hasValidCaseApproval");

        // 路径条件树和规则条件里都有节点，加上必检：入参相同，整个判定只查询一次（后两次复用）
        saveCondition(rule.id, group("AND", node));
        publishPaths(C1PathFixtures.path("DEFAULT", "默认路径", List.of(), 0, true, group("AND", node)));
        var twice = c1.evaluate(headOffice);
        assertThat(twice.reasonCode()).isEqualTo("PASS");
        assertThat(twice.functionCalls()).extracting(Call::functionCode)
                .containsExactly("clientExcluded", "clientExcluded", "clientExcluded", "hasValidCaseApproval");
        assertThat(twice.functionCalls().get(0).cached()).isFalse();
        assertThat(twice.functionCalls().get(1).cached()).isTrue();
        assertThat(twice.functionCalls().get(2).cached()).isTrue();
        assertThat(twice.functionCalls()).filteredOn(call -> call.functionCode().equals("clientExcluded"))
                .allMatch(call -> Boolean.FALSE.equals(call.value()));
    }

    // 3. 已过期、未生效、已解除的记录不算有效（按必检验证）
    @Test
    @Transactional
    void expiredFutureAndReleasedRecordsAreNotValid() {
        LocalDateTime time = now();
        exclusions.save(new ClientExclusion("C10001", "PROV-GD", "expired", time.minusDays(10), time.minusDays(1)));
        exclusions.save(new ClientExclusion("C10001", "PROV-GD", "not yet effective", time.plusDays(1), null));
        exclusions.save(new ClientExclusion("C10002", "PROV-GD", "other client", time.minusDays(1), null));
        exclusions.save(new ClientExclusion("C10001", "PROV-T1", "other provider", time.minusDays(1), null));
        var request = new C1Service.Simulation("C10001", "PROV-GD", "USD", "KG", new BigDecimal("100"));
        // 没有有效的排除记录：必检通过，继续匹配准入规则；PROV-GD 没有准入规则 → 默认拒绝
        var result = c1.evaluate(request);
        assertThat(result.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("3. 客户排除");
            assertThat(step.status()).isEqualTo("PASS");
        });
        assertThat(result.functionCalls()).singleElement().satisfies(call -> assertThat(call.value()).isEqualTo(false));

        ClientExclusion active = exclusions.save(new ClientExclusion("C10001", "PROV-GD", "review", time.minusDays(1), null));
        var excluded = c1.evaluate(request);
        assertThat(excluded.reasonCode()).isEqualTo("CLIENT_EXCLUDED");
        assertThat(excluded.functionCalls()).singleElement().satisfies(call -> {
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isEqualTo(active.id.toString());
        });
        // 解除（补上失效时间）而不是删除历史
        relay.releaseExclusion(active.id);
        var released = c1.evaluate(request);
        assertThat(released.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(released.functionCalls()).singleElement().satisfies(call -> assertThat(call.value()).isEqualTo(false));
    }

    // 5. 可选函数：加到条件里才执行，从条件里删除后不再执行
    @Test
    @Transactional
    void optionalFunctionRunsOnlyWhileItIsInTheConditionTree() {
        functionsApi.create(new BusinessFunctionController.Input("testHighRiskClient", "高风险客户", "客户与准入", "BOOLEAN", "C1",
                "CANDIDATE", "REQUEST", "testHighRiskClientChecker", "CLIENT_PROFILE",
                "[{\"code\":\"clientId\",\"type\":\"STRING\",\"source\":\"TRANSACTION\"}]", "测试用可选函数", true));
        assertThat(functionMetadata.isRequired("testHighRiskClient")).isFalse();
        assertThat(functionMetadata.usagePolicy("C1", "testHighRiskClient")).isEqualTo("OPTIONAL");
        assertThat(functionMetadata.available("C1").functions()).anySatisfy(f -> {
            assertThat(f.functionCode()).isEqualTo("testHighRiskClient");
            assertThat(f.usagePolicy()).isEqualTo("OPTIONAL");
            assertThat(f.dataSource()).isEqualTo("CLIENT_PROFILE");
        });

        Appetite rule = rule("PROV-HO", "PSP");
        var node = function("testHighRiskClient", Map.of("clientId", "$transaction.clientId"), "EQ", false);
        saveCondition(rule.id, group("AND", node));
        int before = OPTIONAL_CALLS.get();
        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        var result = c1.evaluate(request);
        assertThat(result.reasonCode()).isEqualTo("PASS");
        assertThat(OPTIONAL_CALLS.get()).isEqualTo(before + 1);
        assertThat(result.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "testHighRiskClient", "hasValidCaseApproval");

        // 运营从条件里删除这个可选函数节点：不再执行，判定不受影响
        saveCondition(rule.id, group("AND", field("paymentCurrency", "EQ", "USD")));
        assertThat(c1.evaluate(request).reasonCode()).isEqualTo("PASS");
        assertThat(OPTIONAL_CALLS.get()).isEqualTo(before + 1);

        // 可选函数停用后，引用它的条件失效：规则按不命中处理并说明
        saveCondition(rule.id, group("AND", node));
        functionsApi.toggle("testHighRiskClient");
        var disabled = c1.evaluate(request);
        assertThat(disabled.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(disabled.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("SKIP");
            assertThat(step.detail()).contains("函数 testHighRiskClient 已停用");
        });
    }

    // 6. clientExcluded 在函数库里是可选函数：被条件引用时不能删除、只能停用；停用或删除只让引用它的条件失效，绕不过执行器的必检
    @Test
    @Transactional
    void clientExcludedIsOptionalAndControlledByTheFunctionLibrary() {
        assertThat(functionMetadata.isRequired("clientExcluded")).isFalse();
        assertThat(functionMetadata.usagePolicy("C1", "clientExcluded")).isEqualTo("OPTIONAL");
        Appetite rule = rule("PROV-HO", "PSP");
        saveCondition(rule.id, group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false)));
        assertThat(functionMetadata.references("clientExcluded")).containsExactly("C1 准入规则 #" + rule.id);
        // 已被条件引用：不能删除
        assertThatThrownBy(() -> functionsApi.delete("clientExcluded")).hasMessage("函数已被引用，不能删除：C1 准入规则 #" + rule.id);
        var headOffice = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        assertThat(c1.evaluate(headOffice).reasonCode()).isEqualTo("PASS");

        // 停用：条件里的节点不能执行，引用它的规则条件失效（按不命中处理并说明）；执行器的必检照常执行
        functionsApi.toggle("clientExcluded");
        assertThat(functionMetadata.isExecutable("clientExcluded", "C1")).isFalse();
        assertThat(functionMetadata.available("C1").functions()).noneMatch(f -> f.functionCode().equals("clientExcluded"));
        var skipped = c1.evaluate(headOffice);
        assertThat(skipped.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(skipped.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded");
        assertThat(skipped.functionCalls().get(0).value()).isEqualTo(false);
        assertThat(skipped.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("SKIP");
            assertThat(step.detail()).contains("函数 clientExcluded 已停用");
        });

        // 运营删掉节点：不再有引用，可以从函数库删除；必检不读函数库元数据，判定里仍有排除检查
        saveCondition(rule.id, group("AND", field("paymentCurrency", "EQ", "USD")));
        assertThat(functionMetadata.references("clientExcluded")).isEmpty();
        functionsApi.delete("clientExcluded");
        var afterDelete = c1.evaluate(headOffice);
        assertThat(afterDelete.reasonCode()).isEqualTo("PASS");
        assertThat(afterDelete.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "hasValidCaseApproval");
        exclusions.save(new ClientExclusion("C10001", "PROV-HO", "review", now().minusHours(1), null));
        assertThat(c1.evaluate(headOffice).reasonCode()).isEqualTo("CLIENT_EXCLUDED");
    }

    // 7. 函数节点可以放在 AND / OR 子条件组里
    @Test
    @Transactional
    void functionNodesWorkInsideAndOrSubGroups() {
        Appetite rule = rule("PROV-HO", "PSP");
        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        // paymentCurrency == USD 且（clientExcluded == false 或 paymentCurrency == CNY）
        var matched = group("AND", field("paymentCurrency", "EQ", "USD"),
                group("OR", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false), field("paymentCurrency", "EQ", "CNY")));
        assertThat(relay.compileC1(new RelayController.C1ConditionInput(matched)).get("expression")).isEqualTo(
                "((paymentCurrency == 'USD') && ((clientExcluded(providerCode=$candidate.providerCode, clientId=$transaction.clientId, decisionTime=$context.decisionTime) == false) || (paymentCurrency == 'CNY')))");
        saveCondition(rule.id, matched);
        var pass = c1.evaluate(request);
        assertThat(pass.reasonCode()).isEqualTo("PASS");
        assertThat(pass.steps()).noneMatch(step -> step.status().equals("SKIP"));
        // 条件里的 clientExcluded 节点与必检入参相同：复用必检的查询结果，整个判定只查询一次
        assertThat(pass.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "clientExcluded", "hasValidCaseApproval");
        assertThat(pass.functionCalls().get(0).cached()).isFalse();
        assertThat(pass.functionCalls().get(0).value()).isEqualTo(false);
        assertThat(pass.functionCalls().get(1).cached()).isTrue();

        // （clientExcluded == true 且 paymentCurrency == USD）或 paymentCurrency == CNY：函数返回 false，整个条件不命中（不是 SKIP）
        var notMatched = group("OR", group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", true), field("paymentCurrency", "EQ", "USD")),
                field("paymentCurrency", "EQ", "CNY"));
        saveCondition(rule.id, notMatched);
        var miss = c1.evaluate(request);
        assertThat(miss.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(miss.steps()).noneMatch(step -> step.status().equals("SKIP"));
        assertThat(miss.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "clientExcluded");
        assertThat(miss.functionCalls().get(1).cached()).isTrue();
    }

    // 8. 参数缺失、类型或来源不匹配、路径不受控：保存和编译时就报错
    @Test
    @Transactional
    void missingOrWronglyTypedArgumentsAreRejected() {
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", Map.of("providerCode", "$candidate.providerCode"), "EQ", false)), "C1"))
                .hasMessage("函数 clientExcluded 缺少必填参数：clientId");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.amountUsd"), "EQ", false)), "C1"))
                .hasMessage("函数 clientExcluded 的参数 clientId 类型应为 STRING，不能绑定 transaction.amountUsd（DECIMAL）");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", Map.of("providerCode", "$candidate.providerCode", "clientId", "$candidate.providerCode"), "EQ", false)), "C1"))
                .hasMessage("函数 clientExcluded 的参数 clientId 数据来源应为 TRANSACTION，不能绑定 candidate.providerCode（CANDIDATE）");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", Map.of("providerCode", "$candidate.providerCode", "clientId", "$env.clientId"), "EQ", false)), "C1"))
                .hasMessageContaining("不受控的取值路径：env.clientId");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId", "decisionTime", "$context.monthKey"), "EQ", false)), "C1"))
                .hasMessageContaining("参数 decisionTime 类型应为 DATE");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId", "extra", "x"), "EQ", false)), "C1"))
                .hasMessage("函数 clientExcluded 不支持参数：extra");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "GT", false)), "C1"))
                .hasMessage("函数 clientExcluded 返回类型为 BOOLEAN，不支持运算符 GT，可用：EQ、NE");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", "YES")), "C1"))
                .hasMessage("布尔值只能为 true 或 false");
        assertThatThrownBy(() -> expressions.compileDetailed(group("AND", function("noSuchFunction", Map.of(), "EQ", false)), "C1"))
                .hasMessage("函数不存在：noSuchFunction");
        // 运行时路径取值为空：报错，不用默认值顶替
        var context = RuleExecutionContext.c1("PROV-GD", "C10001", "USD", "KG", new BigDecimal("100"), LocalDateTime.of(2026, 11, 25, 10, 0));
        var compiled = expressions.compileDetailed(group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false)), "C1");
        context.set("transaction.clientId", null);
        assertThatThrownBy(() -> expressions.evaluate(compiled, Map.of(), context)).hasMessageContaining("参数 clientId 取值为空（transaction.clientId）");

        // 保存条件时同样拦截；C2 条件不能使用函数；没有执行上下文不能求值函数节点
        Appetite rule = rule("PROV-HO", "PSP");
        var broken = group("AND", function("clientExcluded", Map.of(), "EQ", false));
        assertThatThrownBy(() -> saveCondition(rule.id, broken)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("缺少必填参数");
        var valid = group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false));
        // 函数节点只能用于已接入的规则；clientExcluded 本身不适用于 C2
        assertThatThrownBy(() -> expressions.compile(valid, "C2")).hasMessage("函数 clientExcluded 不适用于当前规则 C2");
        assertThatThrownBy(() -> expressions.compile(valid, "C4")).hasMessage("函数节点目前仅支持 " + FUNCTION_RULE_TEXT + " 的条件，不能用于 C4");
        assertThatThrownBy(() -> expressions.preview(new ExpressionService.Preview(valid, Map.of(), "C1"))).hasMessage("条件引用了业务函数，只能在规则执行中求值");
    }

    // DECIMAL 返回值按数值比较，MTD 校验不受影响
    @Test
    @Transactional
    void decimalFunctionResultIsComparedAsANumber() {
        Appetite rule = rule("PROV-T1", "STANDARD");
        Map<String, Object> arguments = Map.of("providerCode", "$candidate.providerCode", "flowCategory", "$candidate.flowCategory");
        saveCondition(rule.id, group("AND", function("getProviderMtd", arguments, "LTE", 1800000)));
        var request = new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal("150000"));
        var pass = c1.evaluate(request);
        assertThat(pass.reasonCode()).isEqualTo("PASS");
        // 条件节点里的 getProviderMtd 与执行器的 MTD 校验入参相同：节点先查询，执行器复用结果，不再查第二次
        // （第一个 clientExcluded 是必检的客户排除检查）
        assertThat(pass.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "getProviderMtd", "getProviderMtd");
        assertThat(pass.functionCalls().get(1).cached()).isFalse();
        assertThat(pass.functionCalls().get(2).cached()).isTrue();
        assertThat((BigDecimal) pass.functionCalls().get(1).value()).isEqualByComparingTo("1800000");

        saveCondition(rule.id, group("AND", function("getProviderMtd", arguments, "GT", 1800000)));
        var miss = c1.evaluate(request);
        assertThat(miss.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(miss.steps()).noneMatch(step -> step.status().equals("SKIP"));
    }

    // 9. 旧版保存的 FIELD / GROUP JSON（没有 type）照常执行；FUNCTION JSON 按约定格式解析
    @Test
    @Transactional
    void legacyFieldAndGroupJsonStillRuns() throws Exception {
        String legacy = "{\"logic\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"USD\"},"
                + "{\"logic\":\"OR\",\"children\":[{\"field\":\"beneficiaryBankCountry\",\"operator\":\"EQ\",\"value\":\"KG\"},{\"field\":\"beneficiaryBankCountry\",\"operator\":\"IN\",\"value\":\"CN,HK\"}]}]}";
        var tree = json.readValue(legacy, ExpressionService.Node.class);
        assertThat(tree.type()).isEqualTo("GROUP");
        assertThat(tree.children()).extracting(ExpressionService.Node::type).containsExactly("FIELD", "GROUP");
        assertThat(tree.children().get(0).isFunction()).isFalse();
        Appetite rule = rule("PROV-HO", "PSP");
        saveCondition(rule.id, tree);
        var result = c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100")));
        assertThat(result.reasonCode()).isEqualTo("PASS");
        assertThat(result.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "hasValidCaseApproval");

        String functionJson = "{\"type\":\"FUNCTION\",\"functionCode\":\"clientExcluded\",\"arguments\":{\"providerCode\":\"$candidate.providerCode\","
                + "\"clientId\":\"$transaction.clientId\",\"decisionTime\":\"$context.decisionTime\"},\"operator\":\"EQ\",\"value\":false}";
        var node = json.readValue(functionJson, ExpressionService.Node.class);
        assertThat(node.type()).isEqualTo("FUNCTION");
        assertThat(node.isFunction()).isTrue();
        assertThat(node.arguments()).containsEntry("decisionTime", "$context.decisionTime");
        assertThat(node.value()).isEqualTo(false);
        saveCondition(rule.id, group("AND", node, json.readValue(legacy, ExpressionService.Node.class)));
        assertThat(c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"))).reasonCode()).isEqualTo("PASS");
    }

    // 11. 四级匹配、个案审批、MTD 校验保持原有语义，并以函数调用记录返回
    @Test
    void approvalAndMtdChecksAreUnaffectedAndRecordedAsFunctionCalls() {
        var approval = c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("150000")));
        assertThat(approval.reasonCode()).isEqualTo("PASS");
        // 每次判定先做必检的客户排除检查，再按命中的准入动作做审批 / MTD 校验
        assertThat(approval.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "hasValidCaseApproval");
        assertThat(approval.functionCalls().get(1).recordId()).isNotBlank();
        assertThat(approval.functionCalls().get(1).arguments()).containsEntry("flowCategory", "PSP");
        assertThat(approval.appetiteMatchLevel()).isEqualTo("EXACT_EXACT");
        assertThat(approval.appetiteDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision.flowCategory()).isEqualTo("PSP");
            assertThat(decision.ruleId()).isEqualTo(rule("PROV-HO", "PSP").id);
            assertThat(decision.action()).isEqualTo("CASE_BY_CASE");
        });

        // 精确币种 + ALL 国家（PROV-W USD）与 ALL + ALL（PROV-T1 STANDARD）
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-W", "USD", "KG", new BigDecimal("150000"))).reasonCode()).isEqualTo("PASS");
        var mtd = c1.evaluate(new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal("150000")));
        assertThat(mtd.reasonCode()).isEqualTo("PASS");
        assertThat(mtd.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "getProviderMtd");
        assertThat(mtd.appetiteMatchLevel()).isEqualTo("ALL_ALL");
        assertThat(c1.evaluate(new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal("9000000"))).reasonCode()).isEqualTo("APPETITE_CAP_REACHED");
        assertThat(c1.evaluate(new C1Service.Simulation("C10001", "PROV-T1", "USD", "KG", new BigDecimal("150000"))).reasonCode()).isEqualTo("APPETITE_EXCLUDED");
    }

    // 字段 ↔ 函数绑定：clientProviderExcluded 的取值由 clientExcluded 计算，请求里提交的同名字段取值不采用
    @Test
    @Transactional
    void fieldBoundToAFunctionTakesItsValueFromTheFunction() throws Exception {
        // 升级前的数据：字段存在，但没有取值来源（列为空）
        FieldDefinition legacy = fields.save(new FieldDefinition("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1"));
        assertThat(legacy.valueSource).isNull();

        var bindings = json.readTree("{\"providerCode\":\"$candidate.providerCode\",\"clientId\":\"$transaction.clientId\"}");
        fieldsApi.update("clientProviderExcluded", new FieldMetadataController.Input("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1", null,
                true, "FUNCTION", "clientExcluded", bindings));
        FieldDefinition bound = field("clientProviderExcluded");
        assertThat(bound.valueSource).isEqualTo("FUNCTION");
        assertThat(bound.functionCode).isEqualTo("clientExcluded");
        assertThat(bound.argumentBindings).isEqualTo("{\"providerCode\":\"$candidate.providerCode\",\"clientId\":\"$transaction.clientId\"}");

        Appetite rule = rule("PROV-HO", "PSP");
        var tree = group("AND", field("clientProviderExcluded", "EQ", "false"));
        saveCondition(rule.id, tree);
        // 请求伪造 clientProviderExcluded = true：不采用，仍按函数结果 false 命中规则
        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("clientProviderExcluded", true));
        var result = c1.evaluate(request);
        assertThat(result.reasonCode()).isEqualTo("PASS");
        assertThat(result.steps()).noneMatch(step -> step.status().equals("SKIP"));
        // 绑定字段不需要请求提供取值：取值来自 clientExcluded，与必检入参相同，本次判定只查询这一次
        assertThat(result.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "clientExcluded", "hasValidCaseApproval");
        assertThat(result.functionCalls().get(0).cached()).isFalse();
        assertThat(result.functionCalls().get(0).value()).isEqualTo(false);
        assertThat(result.functionCalls().get(1).cached()).isTrue();
        assertThat(result.functionCalls().get(1).value()).isEqualTo(false);

        // 绑定校验：返回类型、适用规则、函数是否存在
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("testBoundAmount", "金额", "DECIMAL", "EQ", "C1", null, true, "FUNCTION", "clientExcluded", null)))
                .hasMessage("函数 clientExcluded 返回类型为 BOOLEAN，与字段类型 DECIMAL 不一致，不能绑定");
        // 适用规则里含未接入函数节点的规则：拦下；只含已接入的规则时再核对函数本身是否适用
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("testBoundFlag", "标记", "BOOLEAN", "EQ", "C1,C4", null, true, "FUNCTION", "clientExcluded", null)))
                .hasMessage("绑定函数的字段只能适用于 " + FUNCTION_RULE_TEXT + "，不能用于 C1,C4");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("testBoundFlag", "标记", "BOOLEAN", "EQ", "C1,C2", null, true, "FUNCTION", "clientExcluded", null)))
                .hasMessage("函数 clientExcluded 不适用于当前规则 C2");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("testBoundFlag", "标记", "BOOLEAN", "EQ", "C1", null, true, "FUNCTION", "noSuchFunction", null)))
                .hasMessage("函数不存在：noSuchFunction");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("testBoundFlag", "标记", "BOOLEAN", "EQ", "C1", null, true, "FUNCTION", " ", null)))
                .hasMessage("请选择字段要绑定的业务函数");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("testBoundFlag", "标记", "BOOLEAN", "EQ", "C1", null, true, "REQUEST", "clientExcluded", null)))
                .hasMessage("取值来源为请求输入时不能绑定函数");
        assertThatThrownBy(() -> fieldsApi.create(new FieldMetadataController.Input("testBoundFlag", "标记", "BOOLEAN", "EQ", "C1", null, true, "SCRIPT", "clientExcluded", null)))
                .hasMessage("取值来源只能是 REQUEST（请求输入）、FUNCTION（函数计算）或 DATA_RESOLVER（数据解析）");
        // 页面的表达式预览没有执行上下文，不能求值函数绑定字段
        assertThatThrownBy(() -> expressions.preview(new ExpressionService.Preview(tree, Map.of("clientProviderExcluded", false), "C1")))
                .hasMessage("条件引用了函数绑定字段 clientProviderExcluded，只能在规则执行中取值");
        // 绑定的函数停用后，引用该字段的条件失效
        functionsApi.toggle("clientExcluded");
        var skipped = c1.evaluate(request);
        assertThat(skipped.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(skipped.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("SKIP");
            assertThat(step.detail()).contains("函数 clientExcluded 已停用");
        });
    }

    // 函数试算：入参、返回值、条件结果、来源、耗时、命中记录
    @Test
    @Transactional
    void simulateReportsArgumentsValueAndHitRecord() {
        ClientExclusion exclusion = exclusions.save(new ClientExclusion("C10001", "PROV-GD", "review", now().minusDays(1), null));
        Map<String, Object> sample = Map.of("clientId", "C10001", "providerCode", "PROV-GD", "paymentCurrency", "USD", "beneficiaryBankCountry", "KG", "amountUsd", "100");
        var hit = functionMetadata.simulate("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", true, sample);
        assertThat(hit.functionName()).isEqualTo("客户是否被合作方排除");
        assertThat(hit.expression()).startsWith("(clientExcluded(providerCode=$candidate.providerCode");
        assertThat(hit.arguments()).containsEntry("providerCode", "PROV-GD").containsEntry("clientId", "C10001");
        assertThat(hit.bindings()).containsEntry("decisionTime", "$context.decisionTime");
        assertThat(hit.value()).isEqualTo(true);
        assertThat(hit.recordId()).isEqualTo(exclusion.id.toString());
        assertThat(hit.dataSource()).isEqualTo("CLIENT_EXCLUSION");
        assertThat(hit.matched()).isTrue();
        assertThat(hit.error()).isNull();

        var miss = functionMetadata.simulate("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", true,
                Map.of("clientId", "C10004", "providerCode", "PROV-GD", "amountUsd", "0"));
        assertThat(miss.value()).isEqualTo(false);
        assertThat(miss.recordId()).isNull();
        assertThat(miss.matched()).isFalse();

        assertThatThrownBy(() -> functionMetadata.simulate("clientExcluded", Map.of(), "EQ", true, sample)).hasMessageContaining("缺少必填参数：providerCode");
        assertThatThrownBy(() -> functionMetadata.simulate("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", true, Map.of("clientId", "C10001"))).hasMessage("试算需要合作方和客户");
    }

    // 启动时为内置字段补上函数绑定：只在取值来源从未配置过时执行（写审计），重复执行不覆盖运营的配置
    @Test
    @Transactional
    void builtInFieldIsBoundOnceAndNeverOverridesAnExplicitChoice() {
        // 升级前的数据：字段存在，取值来源为空
        fields.save(new FieldDefinition("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1"));
        functionMetadata.bindBuiltInField();
        FieldDefinition bound = field("clientProviderExcluded");
        assertThat(bound.valueSource).isEqualTo("FUNCTION");
        assertThat(bound.functionCode).isEqualTo("clientExcluded");
        assertThat(bound.argumentBindings).isEqualTo("{\"providerCode\":\"$candidate.providerCode\",\"clientId\":\"$transaction.clientId\"}");
        assertThat(audit.findAll()).anySatisfy(row -> {
            assertThat(row.objectType).isEqualTo("FIELD_METADATA");
            assertThat(row.objectId).isEqualTo("clientProviderExcluded");
            assertThat(row.operation).isEqualTo("BIND_FUNCTION");
        });

        // 再次启动：已配置过，不再处理
        long audits = audit.count();
        functionMetadata.bindBuiltInField();
        assertThat(audit.count()).isEqualTo(audits);

        // 运营改回「请求输入」后再启动：保持运营的配置
        fieldsApi.update("clientProviderExcluded", new FieldMetadataController.Input("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1", null, true, "REQUEST", null, null));
        functionMetadata.bindBuiltInField();
        assertThat(field("clientProviderExcluded").valueSource).isEqualTo("REQUEST");
        assertThat(field("clientProviderExcluded").functionCode).isNull();

        // 适用规则不是仅 C1 时不自动绑定（与保存接口的校验一致）：字段照旧按请求输入取值，不报错
        fields.save(new FieldDefinition("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1,C2"));
        functionMetadata.bindBuiltInField();
        assertThat(field("clientProviderExcluded").valueSource).isNull();
        // 函数不可用（已停用）时同样跳过
        fields.save(new FieldDefinition("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1"));
        functionsApi.toggle("clientExcluded");
        functionMetadata.bindBuiltInField();
        assertThat(field("clientProviderExcluded").valueSource).isNull();
        assertThat(field("clientProviderExcluded").functionCode).isNull();
    }

    private FieldDefinition field(String code) {
        return fieldsApi.list().stream().filter(f -> f.fieldCode.equals(code)).findFirst().orElseThrow();
    }
}
