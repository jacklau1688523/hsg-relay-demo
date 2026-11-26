package com.hsg.relay.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.api.FieldMetadataController;
import com.hsg.relay.api.RelayController;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.ClientProfileRepository;
import java.util.List;
import java.util.Map;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relaytest;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class C1ServiceTest {
    @Autowired
    C1Service service;
    @Autowired
    RelayController controller;
    @Autowired
    AppetiteRepository appetites;
    @Autowired
    ClientProfileRepository clients;
    @Autowired
    FieldMetadataController metadata;
    @Autowired
    ObjectMapper json;
    @Autowired
    C1ConditionService globalConditions;

    // PROV-HO / PSP / USD / KG 为 CASE_BY_CASE，客户 C10001 有有效审批：命中即 PASS
    private Appetite headOfficePspRule() {
        return appetites.findAll().stream().filter(a -> a.providerCode.equals("PROV-HO") && a.flowCategory.equals("PSP")).findFirst().orElseThrow();
    }

    @Test
    @Transactional
    void clientStatusAndProviderExclusionConditionsUseStoredDataWithoutSimulationInputs() throws Exception {
        metadata.create(new FieldMetadataController.Input("clientClassificationStatus", "客户分类状态", "ENUM", "EQ,NE", "C1", "COMPLETE,PENDING", true));
        // 执行器不再为 clientProviderExcluded 注入必检结果：字段绑定到 clientExcluded，取值来自规则执行，仍不需要模拟入参
        metadata.create(new FieldMetadataController.Input("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1", null, true, "FUNCTION", "clientExcluded",
                json.readTree("{\"providerCode\":\"$candidate.providerCode\",\"clientId\":\"$transaction.clientId\"}")));
        var rule = headOfficePspRule();
        var tree = new ExpressionService.Node("AND", List.of(
                new ExpressionService.Node(null, null, "clientClassificationStatus", "EQ", "COMPLETE"),
                new ExpressionService.Node(null, null, "clientProviderExcluded", "EQ", "false")), null, null, null);
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true, tree));

        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("PASS");
        var spoofed = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"),
                Map.of("clientClassificationStatus", "PENDING", "clientProviderExcluded", true));
        assertThat(service.evaluate(spoofed).reasonCode()).isEqualTo("PASS");
    }

    @Test
    @Transactional
    void conditionAcceptsEveryFieldEnabledForC1InMetadataCenter() {
        // 元数据中心新增的 C1 字段不再被写死的四个字段挡住：可以编译、保存并参与模拟
        metadata.create(new FieldMetadataController.Input("testRiskRating", "风险等级", "ENUM", "EQ,NE,IN", "C1", "LOW,HIGH", true));
        var rule = headOfficePspRule();
        var tree = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "testRiskRating", "EQ", "HIGH")), null, null, null);
        assertThat(controller.compileC1(new RelayController.C1ConditionInput(tree)).get("expression")).isEqualTo("((testRiskRating == 'HIGH'))");
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true, tree));

        // 没有提供该字段的取值：规则按不命中处理（默认拒绝），并在校验过程里说明
        var result = service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100")));
        assertThat(result.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("SKIP");
            assertThat(step.detail()).isEqualTo("规则 #" + rule.id + " 的附加条件引用了字段 testRiskRating，本次没有提供取值，按不命中处理");
        });
        assertThat(service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testRiskRating", "HIGH"))).reasonCode()).isEqualTo("PASS");
        assertThat(service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testRiskRating", "LOW"))).reasonCode()).isEqualTo("APPETITE_NOT_SET");
    }

    @Test
    @Transactional
    void conditionStillRejectsFieldsOutsideTheC1FieldLibrary() {
        // 字段库校验保留：不适用 C1 的字段、未知字段仍不能进入 C1 条件（种子里 clientSegment 仅适用 C8）
        var otherRule = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "clientSegment", "EQ", "VIP")), null, null, null);
        assertThatThrownBy(() -> controller.compileC1(new RelayController.C1ConditionInput(otherRule))).hasMessageContaining("不适用于当前规则");
        var unknown = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "noSuchField", "EQ", "X")), null, null, null);
        assertThatThrownBy(() -> controller.compileC1(new RelayController.C1ConditionInput(unknown))).hasMessageContaining("非法字段");
    }

    @Test
    @Transactional
    void conditionThatOutlivesItsFieldLibraryEntrySkipsRuleInsteadOfFailing() {
        // 规则保存后字段在元数据中心被停用、或可选值被删：条件已失效，规则按不命中处理并指出字段，
        // 而不是让整次校验（含 C1–C10 Check）报错
        metadata.create(new FieldMetadataController.Input("testRiskRating", "风险等级", "ENUM", "EQ,NE,IN", "C1", "LOW,HIGH", true));
        var rule = headOfficePspRule();
        var tree = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "testRiskRating", "EQ", "HIGH")), null, null, null);
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true, tree));
        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testRiskRating", "HIGH"));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("PASS");

        metadata.toggle("testRiskRating");
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(service.evaluate(request).steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("SKIP");
            assertThat(step.detail()).isEqualTo("规则 #" + rule.id + " 的附加条件已失效（字段 testRiskRating 不适用于当前规则或不支持该操作符），按不命中处理，请在 C1 业务准入页核对后重新保存");
        });

        metadata.toggle("testRiskRating");
        metadata.update("testRiskRating", new FieldMetadataController.Input("testRiskRating", "风险等级", "ENUM", "EQ,NE,IN", "C1", "LOW,MEDIUM", true));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(service.evaluate(request).steps()).anySatisfy(step -> {
            assertThat(step.status()).isEqualTo("SKIP");
            assertThat(step.detail()).isEqualTo("规则 #" + rule.id + " 的附加条件已失效（testRiskRating 不支持该值：HIGH），按不命中处理，请在 C1 业务准入页核对后重新保存");
        });
    }

    @Test
    void simulationVariablesAreOptionalAndNeverOverrideTradeFields() throws Exception {
        // C1–C10 Check 等调用方不传 variables；页面通过 variables 提交新增字段的取值
        String body = "{\"clientId\":\"C10001\",\"providerCode\":\"PROV-HO\",\"paymentCurrency\":\"USD\",\"beneficiaryBankCountry\":\"KG\",\"amountUsd\":100";
        assertThat(json.readValue(body + "}", C1Service.Simulation.class).variables()).isNull();
        var supplied = json.readValue(body + ",\"variables\":{\"clientSegment\":\"VIP\",\"paymentCurrency\":\"CNY\"}}", C1Service.Simulation.class);
        assertThat(supplied.variables()).containsEntry("clientSegment", "VIP");
        // 四个交易要素以表单为准：variables 里的同名字段不覆盖，仍按 USD/KG 命中
        assertThat(service.evaluate(supplied).reasonCode()).isEqualTo("PASS");
    }

    @Test
    @Transactional
    void simulationValuesSubmittedByThePageAreComparedByFieldType() {
        // 模拟面板的下拉和输入框提交的都是字符串：布尔、数字字段须按类型转换后再比较，
        // 否则 "true" == true 静默不命中（且 != 反向命中）
        metadata.create(new FieldMetadataController.Input("testHighRisk", "高风险标记", "BOOLEAN", "EQ,NE", "C1", null, true));
        metadata.create(new FieldMetadataController.Input("testRiskScore", "风险评分", "DECIMAL", "EQ,NE,GT,GTE,LT,LTE", "C1", null, true));
        var rule = headOfficePspRule();
        var tree = new ExpressionService.Node("AND", List.of(
                new ExpressionService.Node(null, null, "testHighRisk", "EQ", "true"),
                new ExpressionService.Node(null, null, "testRiskScore", "GTE", "5")), null, null, null);
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true, tree));

        assertThat(service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testHighRisk", "true", "testRiskScore", "7"))).reasonCode()).isEqualTo("PASS");
        assertThat(service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testHighRisk", true, "testRiskScore", new BigDecimal("7.5")))).reasonCode()).isEqualTo("PASS");
        // 条件不满足：规则不命中，但不是 SKIP（取值已提供，只是不满足）
        var notMatched = service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testHighRisk", "false", "testRiskScore", "7")));
        assertThat(notMatched.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(notMatched.steps()).noneMatch(step -> step.status().equals("SKIP"));
        // 取值与字段类型不符：报错并指出字段
        assertThatThrownBy(() -> service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testHighRisk", "YES", "testRiskScore", "7"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("字段 testHighRisk 的取值只能是 true 或 false：YES");
        assertThatThrownBy(() -> service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"), Map.of("testHighRisk", "true", "testRiskScore", "abc"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("字段 testRiskScore 的取值不是有效数字：abc");
    }

    @Test
    @Transactional
    void conditionValueThatDoesNotFitTheFieldIsRejectedWithTheFieldName() {
        // 数字字段的条件值为空或不是数字：返回带字段名的校验错误（空值原来会变成 500）
        metadata.create(new FieldMetadataController.Input("testRiskScore", "风险评分", "DECIMAL", "EQ,NE,GT,GTE,LT,LTE", "C1", null, true));
        var leaf = new ExpressionService.Node(null, null, "testRiskScore", "GTE", "");
        var blank = new ExpressionService.Node("AND", List.of(leaf), null, null, null);
        assertThatThrownBy(() -> controller.compileC1(new RelayController.C1ConditionInput(blank)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("字段 testRiskScore 的条件值不是有效数字：");
        assertThatThrownBy(() -> controller.saveC1Condition(headOfficePspRule().id, new RelayController.C1ConditionUpdate(true, blank)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("字段 testRiskScore 的条件值不是有效数字：");
        var text = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "testRiskScore", "GTE", "abc")), null, null, null);
        assertThatThrownBy(() -> controller.compileC1(new RelayController.C1ConditionInput(text))).hasMessage("字段 testRiskScore 的条件值不是有效数字：abc");
        var valid = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "testRiskScore", "GTE", " 5.50 ")), null, null, null);
        assertThat(controller.compileC1(new RelayController.C1ConditionInput(valid)).get("expression")).isEqualTo("((testRiskScore >= 5.50))");
    }

    @Test
    void caseByCaseWithApprovalPasses() {
        var result = service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("150000")));
        assertThat(result.passed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("PASS");
    }

    @Test
    void pspRejectedByTierOneBank() {
        var result = service.evaluate(new C1Service.Simulation("C10001", "PROV-T1", "USD", "KG", new BigDecimal("150000")));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("APPETITE_EXCLUDED");
    }

    @Test
    void incompleteClientIsEvaluatedByConfiguredAppetiteRules() {
        var result = service.evaluate(new C1Service.Simulation("C10003", "PROV-T1", "USD", "KG", new BigDecimal("150000")));
        // 分类状态不再由 C1 执行器硬编码拦截；C10003 的 WEB3 规则是配置的 EXCLUDE。
        assertThat(result.reasonCode()).isEqualTo("APPETITE_EXCLUDED");
    }

    @Test
    @Transactional
    void pendingClassificationCanPassWhenRuleConditionAllowsIt() {
        metadata.create(new FieldMetadataController.Input("clientClassificationStatus", "客户分类状态", "ENUM", "EQ,NE", "C1", "COMPLETE,PENDING", true));
        var rule = appetites.findAll().stream()
                .filter(a -> a.providerCode.equals("PROV-HO") && a.flowCategory.equals("PSP"))
                .findFirst().orElseThrow();
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true,
                new ExpressionService.Node("AND", List.of(
                        new ExpressionService.Node(null, null, "clientClassificationStatus", "EQ", "PENDING"),
                        new ExpressionService.Node(null, null, "paymentCurrency", "EQ", "USD")), null, null, null)));
        // C10003 没有 PSP 档案类别，先改用已有 PSP 客户的档案状态进行验证；规则条件本身由配置决定。
        clients.save(new com.hsg.relay.domain.ClientProfile("C1-PENDING", "PENDING", "PSP"));
        var result = service.evaluate(new C1Service.Simulation("C1-PENDING", "PROV-HO", "USD", "KG", new BigDecimal("100")));
        assertThat(result.reasonCode()).isEqualTo("APPETITE_APPROVAL_MISSING");
    }

    @Test
    @Transactional
    void globalConditionMustMatchForCandidateToContinue() {
        metadata.create(new FieldMetadataController.Input("clientClassificationStatus", "客户分类状态", "ENUM", "EQ,NE", "C1", "COMPLETE,PENDING", true));
        var tree = new ExpressionService.Node("AND", List.of(
                new ExpressionService.Node(null, null, "clientClassificationStatus", "EQ", "PENDING")), null, null, null);
        globalConditions.save(new C1ConditionService.Input(true, tree, null));

        var complete = service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100")));
        assertThat(complete.reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(complete.steps()).anySatisfy(step -> {
            assertThat(step.step()).contains("C1 准入条件");
            assertThat(step.status()).isEqualTo("FAIL");
            assertThat(step.detail()).contains("准入条件不满足");
        });

        var pending = service.evaluate(new C1Service.Simulation("C10003", "PROV-T1", "USD", "KG", new BigDecimal("100")));
        assertThat(pending.reasonCode()).isEqualTo("APPETITE_EXCLUDED");
        assertThat(pending.steps()).anySatisfy(step -> {
            assertThat(step.step()).contains("C1 准入条件");
            assertThat(step.status()).isEqualTo("PASS");
            assertThat(step.detail()).contains("准入条件满足");
        });
    }

    @Test
    void missingRuleDefaultsToDeny() {
        var result = service.evaluate(new C1Service.Simulation("C10002", "PROV-HO", "USD", "CN", new BigDecimal("100")));
        assertThat(result.reasonCode()).isEqualTo("APPETITE_NOT_SET");
    }

    // 客户排除不再由执行器强制检查：条件树里没有 clientExcluded 节点时不查询排除记录；有节点时由准入条件过滤，解除记录后恢复
    @Test
    @Transactional
    void exclusionIsAppliedByTheGlobalConditionAndCanBeReleasedWithoutDeletingHistory() {
        var exclusion = controller.addExclusion(new ClientExclusion("C10002", "PROV-T1", "review", java.time.LocalDateTime.now().minusDays(1), null));
        assertThat(controller.exclusions("PROV-T1")).anyMatch(x -> x.id.equals(exclusion.id));
        assertThat(controller.exclusions("PROV-HO")).noneMatch(x -> x.id.equals(exclusion.id));
        var request = new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal("100"));
        // 全局准入条件里没有节点：排除记录不参与判定，也不查询
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("PASS");
        assertThat(service.evaluate(request).functionCalls()).noneMatch(call -> call.functionCode().equals("clientExcluded"));

        // 全局准入条件里有 clientExcluded 节点：命中排除记录即不满足
        globalConditions.save(new C1ConditionService.Input(true, new ExpressionService.Node("AND", List.of(
                new ExpressionService.Node("FUNCTION", null, null, null, "EQ", false, "clientExcluded",
                        Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId"))), null, null, null), null));
        var rejected = service.evaluate(request);
        assertThat(rejected.reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(rejected.functionCalls()).singleElement().satisfies(call -> {
            assertThat(call.functionCode()).isEqualTo("clientExcluded");
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isEqualTo(exclusion.id.toString());
        });

        // 解除（补上失效时间）而不是删除历史：条件恢复满足
        controller.releaseExclusion(exclusion.id);
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("PASS");
    }

    @Test
    @Transactional
    void configuredConditionControlsWhichAppetiteRowMatches() {
        var rule = appetites.findAll().stream().filter(a -> a.providerCode.equals("PROV-HO") && a.flowCategory.equals("PSP")).findFirst().orElseThrow();
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true,
                new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "paymentCurrency", "EQ", "CNY")), null, null, null)));
        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("APPETITE_NOT_SET");
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true,
                new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "paymentCurrency", "EQ", "USD")), null, null, null)));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("PASS");
    }
}
