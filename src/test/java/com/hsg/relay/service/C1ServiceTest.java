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
    FieldMetadataController metadata;
    @Autowired
    ObjectMapper json;

    // PROV-HO / PSP / USD / KG 为 CASE_BY_CASE，客户 C10001 有有效审批：命中即 PASS
    private Appetite headOfficePspRule() {
        return appetites.findAll().stream().filter(a -> a.providerCode.equals("PROV-HO") && a.flowCategory.equals("PSP")).findFirst().orElseThrow();
    }

    @Test
    @Transactional
    void clientStatusAndProviderExclusionConditionsUseStoredDataWithoutSimulationInputs() {
        metadata.create(new FieldMetadataController.Input("clientClassificationStatus", "客户分类状态", "ENUM", "EQ,NE", "C1", "COMPLETE,PENDING", true));
        metadata.create(new FieldMetadataController.Input("clientProviderExcluded", "客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1", null, true));
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
    void incompleteClientRejected() {
        var result = service.evaluate(new C1Service.Simulation("C10003", "PROV-T1", "USD", "KG", new BigDecimal("150000")));
        assertThat(result.reasonCode()).isEqualTo("CLIENT_NOT_CLASSIFIED");
    }

    @Test
    void missingRuleDefaultsToDeny() {
        var result = service.evaluate(new C1Service.Simulation("C10002", "PROV-HO", "USD", "CN", new BigDecimal("100")));
        assertThat(result.reasonCode()).isEqualTo("APPETITE_NOT_SET");
    }

    @Test
    @Transactional
    void exclusionCanBeReleasedWithoutDeletingHistory() {
        var exclusion = controller.addExclusion(new ClientExclusion("C10002", "PROV-T1", "review", java.time.LocalDateTime.now().minusDays(1), null));
        assertThat(controller.exclusions("PROV-T1")).anyMatch(x -> x.id.equals(exclusion.id));
        assertThat(controller.exclusions("PROV-HO")).noneMatch(x -> x.id.equals(exclusion.id));
        var request = new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal("100"));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("CLIENT_EXCLUDED");
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
