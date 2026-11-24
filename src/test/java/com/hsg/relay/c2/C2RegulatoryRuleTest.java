package com.hsg.relay.c2;

import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.service.ExpressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

// 监管规则按「交易币种 + 受益行国家」配置：保存即存为标准条件树，求值直接比对；旧版自由条件树仍按原条件执行。
// 附加触发条件（按字段库构建）：范围命中且附加条件命中才触发。
@SpringBootTest
@Transactional
class C2RegulatoryRuleTest {
    @Autowired
    C2Service c2;
    @Autowired
    RegulatoryRuleRepository rules;
    @Autowired
    FieldDefinitionRepository fields;
    @Autowired
    RuleAuditRepository audits;

    private static C2Service.RegulatoryInput input(String id, String currency, String country) {
        return new C2Service.RegulatoryInput(id, "测试监管规则 " + id, "C2", "GLOBAL", currency, country,
                new C2Service.Requirement("PROVIDER_CAPABILITY", List.of("SWIFT")),
                new C2Service.FailureAction("REG_RULE_NOT_MET"), true, null, LocalDateTime.of(2026, 1, 1, 0, 0), null, null);
    }

    private static C2Service.Simulation payment(String provider, String currency, String country, String purpose) {
        return new C2Service.Simulation(provider, currency, currency, country, country, null, "HK", "HK", null, purpose, List.of());
    }

    private static C2Service.Simulation payment(String provider, String currency, String country, String purpose,
                                                Map<String, Object> variables) {
        return new C2Service.Simulation(provider, currency, currency, country, country, null, "HK", "HK", null, purpose, List.of(), variables);
    }

    private static ExpressionService.Node leaf(String field, String operator, Object value) {
        return new ExpressionService.Node(null, null, field, operator, value);
    }

    private static ExpressionService.Node group(String logic, ExpressionService.Node... children) {
        return new ExpressionService.Node(logic, List.of(children), null, null, null);
    }

    private static Map<String, Object> variables(Object... keyValues) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) result.put((String) keyValues[i], keyValues[i + 1]);
        return result;
    }

    /** 只在附加触发条件里使用的两个字段：布尔、数字（取值来自模拟请求的 variables，不是九个固定输入项）。 */
    private void createVariableFields() {
        fields.save(new FieldDefinition("c2TestFlag", "测试标记", "BOOLEAN", "EQ,NE", "C2"));
        fields.save(new FieldDefinition("c2TestScore", "测试评分", "DECIMAL", "EQ,NE,GT,GTE,LT,LTE", "C2"));
    }

    private C2Service.RegulatoryView rule(String ruleId) {
        return c2.regulations().stream().filter(r -> r.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    private static C2Service.RegulatoryCheck check(C2Service.Result result, String ruleId) {
        return result.regulatoryChecks().stream().filter(c -> c.ruleId().equals(ruleId)).findFirst().orElseThrow();
    }

    @BeforeEach
    void startFromAnEmptyRuleSet() {
        // 各用例只看自己建的规则，不受种子数据和同一 JVM 里其他用例遗留数据的影响（事务结束后回滚）
        rules.deleteAll();
    }

    @Test
    void ruleMatchesByCurrencyAndCountryRegardlessOfFieldLibraryState() {
        c2.saveRegulatory(input("REG-T1", " usd", "JP"));
        // 字段库里的「交易币种」被停用，已发布的监管规则照常执行
        FieldDefinition field = fields.findById("paymentCurrency").orElseThrow();
        field.enabled = false;
        fields.save(field);

        C2Service.RegulatoryView saved = rule("REG-T1");
        assertFalse(saved.legacy());
        assertEquals("USD", saved.currency());
        assertEquals("JP", saved.bankCountry());
        assertEquals("交易币种 = USD 且 受益行国家 = JP", saved.conditionText());

        C2Service.Result hit = c2.simulate(payment("PROV-W", "USD", "JP", null));
        assertFalse(hit.passed());
        assertEquals("REG_RULE_NOT_MET", hit.reasonCode());
        assertEquals(List.of("SWIFT"), check(hit, "REG-T1").missingTags());
        assertNull(check(hit, "REG-T1").expression(), "标准规则直接比对币种和国家，不再生成表达式");
        assertTrue(c2.simulate(payment("PROV-T1", "USD", "JP", null)).passed());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", null)), "REG-T1").triggered());
        assertFalse(check(c2.simulate(payment("PROV-W", "HKD", "JP", null)), "REG-T1").triggered());
    }

    @Test
    void allCountryAppliesToEveryBeneficiaryCountry() {
        c2.saveRegulatory(input("REG-T2", "HKD", "ALL"));
        assertEquals("交易币种 = HKD 且 受益行国家 = 不限（ALL）", rule("REG-T2").conditionText());
        assertTrue(check(c2.simulate(payment("PROV-W", "HKD", "SG", null)), "REG-T2").triggered());
        assertTrue(check(c2.simulate(payment("PROV-W", "HKD", "DE", null)), "REG-T2").triggered());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "SG", null)), "REG-T2").triggered());
    }

    @Test
    void legacyFreeFormTreeRunsOnItsOwnConditionUntilResaved() {
        RegulatoryRule legacy = new RegulatoryRule();
        legacy.ruleId = "REG-T3";
        legacy.ruleName = "旧版条件树规则";
        legacy.conditionJson = """
                {"operator":"OR","children":[
                  {"field":"paymentCurrency","operator":"EQ","value":"EUR"},
                  {"field":"purpose","operator":"EQ","value":"INVESTMENT"}
                ]}
                """;
        legacy.requiredTagsJson = "[\"SWIFT\"]";
        legacy.effectiveFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
        rules.save(legacy);

        C2Service.RegulatoryView before = rule("REG-T3");
        assertTrue(before.legacy());
        assertNull(before.currency());
        assertEquals("(paymentCurrency EQ EUR 或 purpose EQ INVESTMENT)", before.conditionText());
        C2Service.Result result = c2.simulate(payment("PROV-W", "USD", "US", "INVESTMENT"));
        assertTrue(check(result, "REG-T3").triggered());
        assertNotNull(check(result, "REG-T3").expression());
        assertEquals(List.of("SWIFT"), check(result, "REG-T3").missingTags());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", "GOODS")), "REG-T3").triggered());

        // 页面重新保存后转为标准形态
        C2Service.RegulatoryView resaved = c2.saveRegulatory(input("REG-T3", "EUR", "ALL"));
        assertFalse(resaved.legacy());
        assertEquals("EUR", resaved.currency());
        assertEquals(2, resaved.versionNo());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", "INVESTMENT")), "REG-T3").triggered());
        assertTrue(check(c2.simulate(payment("PROV-W", "EUR", "US", "GOODS")), "REG-T3").triggered());
    }

    @Test
    void saveRejectsUnknownCurrencyCountryAndLegacyOnlyInput() {
        var badCurrency = assertThrows(IllegalArgumentException.class, () -> c2.saveRegulatory(input("REG-T4", "XXX", "CN")));
        assertTrue(badCurrency.getMessage().startsWith("交易币种必填"), badCurrency.getMessage());
        var badCountry = assertThrows(IllegalArgumentException.class, () -> c2.saveRegulatory(input("REG-T4", "CNY", "UK")));
        assertEquals("受益行国家必填，须为 ISO 3166-1 两位国家代码或 ALL", badCountry.getMessage());
        // 只带旧版 condition、不带币种和国家的请求不再被接受
        var noScope = assertThrows(IllegalArgumentException.class, () -> c2.saveRegulatory(input("REG-T4", null, null)));
        assertTrue(noScope.getMessage().startsWith("交易币种必填"), noScope.getMessage());
        assertTrue(c2.regulations().stream().noneMatch(r -> r.ruleId().equals("REG-T4")));
    }

    @Test
    void optionsFollowTheFieldLibraryAndTheIsoList() {
        FieldDefinition field = fields.findById("paymentCurrency").orElseThrow();
        field.valueOptions = " cny , USD, ";
        fields.save(field);
        C2Service.RegulatoryOptions options = c2.regulatoryOptions();
        assertEquals(List.of("CNY", "USD"), options.currencies());
        assertEquals("ALL", options.countries().get(0).code());
        assertTrue(options.tags().stream().anyMatch(t -> "SWIFT".equals(t.tagCode)));
    }

    @Test
    void additionalConditionNarrowsTheRuleToMatchingTransactions() {
        createVariableFields();
        c2.saveRegulatory(input("REG-T5", "USD", "ALL"));
        C2Service.RegulatoryView saved = c2.saveTriggerCondition("REG-T5", new C2Service.TriggerConditionInput(
                group("AND", leaf("purpose", "EQ", "INVESTMENT"),
                        group("OR", leaf("c2TestFlag", "EQ", "true"), leaf("c2TestScore", "GTE", "1000000"))), null, 1));
        assertEquals("(purpose = INVESTMENT 且 (c2TestFlag = true 或 c2TestScore ≥ 1000000))", saved.triggerText());
        assertNull(saved.triggerProblem());
        assertEquals("USD", saved.currency(), "附加条件不改动规则范围");
        assertEquals(List.of("SWIFT"), saved.requirement().requiredTags());
        assertEquals(2, saved.versionNo());

        // 范围命中且附加条件命中：规则触发，合作方缺 SWIFT 即失败
        C2Service.Result hit = c2.simulate(payment("PROV-W", "USD", "US", "INVESTMENT",
                variables("c2TestFlag", "false", "c2TestScore", "1500000")));
        assertFalse(hit.passed());
        assertEquals("REG_RULE_NOT_MET", hit.reasonCode());
        assertTrue(check(hit, "REG-T5").triggered());
        assertEquals("((purpose == 'INVESTMENT') && ((c2TestFlag == true) || (c2TestScore >= 1000000)))",
                check(hit, "REG-T5").triggerExpression());
        assertEquals("(purpose = INVESTMENT 且 (c2TestFlag = true 或 c2TestScore ≥ 1000000))", check(hit, "REG-T5").triggerCondition());
        assertNull(check(hit, "REG-T5").note());
        assertTrue(c2.simulate(payment("PROV-T1", "USD", "US", "INVESTMENT", variables("c2TestFlag", "true"))).passed());

        // 范围命中但附加条件不命中：规则不触发，合作方缺标签也不报错
        C2Service.Result miss = c2.simulate(payment("PROV-W", "USD", "US", "INVESTMENT",
                variables("c2TestFlag", "false", "c2TestScore", "5")));
        assertTrue(miss.passed());
        assertFalse(check(miss, "REG-T5").triggered());
        assertNotNull(check(miss, "REG-T5").triggerExpression(), "附加条件已求值");
        assertNull(check(miss, "REG-T5").note());
        assertTrue(c2.simulate(payment("PROV-W", "USD", "US", "GOODS", variables("c2TestFlag", "true"))).passed());

        // 范围不命中：规则不触发，也不再求附加条件
        C2Service.Result outside = c2.simulate(payment("PROV-W", "HKD", "US", "INVESTMENT", variables("c2TestFlag", "true")));
        assertFalse(check(outside, "REG-T5").triggered());
        assertNull(check(outside, "REG-T5").triggerExpression());
    }

    @Test
    void missingValueLeavesTheRuleUntriggeredWithANote() {
        createVariableFields();
        c2.saveRegulatory(input("REG-T6", "USD", "ALL"));
        c2.saveTriggerCondition("REG-T6", new C2Service.TriggerConditionInput(group("AND", leaf("c2TestFlag", "EQ", "true")), null, null));

        // 没传 variables（C1–C10 Check 等调用方）、传了别的字段、传了空白：都按未触发处理并说明，不报错
        List<Map<String, Object>> noValue = Arrays.asList(null, variables("c2TestScore", "9"), variables("c2TestFlag", "  "));
        for (Map<String, Object> supplied : noValue) {
            C2Service.RegulatoryCheck check = check(c2.simulate(payment("PROV-W", "USD", "US", null, supplied)), "REG-T6");
            assertFalse(check.triggered());
            assertNull(check.triggerExpression());
            assertEquals("附加触发条件引用的字段 c2TestFlag 本次没有提供取值，按未触发处理", check.note());
        }
        assertTrue(check(c2.simulate(payment("PROV-W", "USD", "US", null, variables("c2TestFlag", true))), "REG-T6").triggered());
    }

    @Test
    void staleConditionStillTriggersAndReportsTheProblem() {
        createVariableFields();
        fields.save(new FieldDefinition("c2TestChannel", "测试渠道", "ENUM", "EQ,NE,IN", "C2", "SWIFT,API"));
        c2.saveRegulatory(input("REG-T7", "USD", "ALL"));
        c2.saveTriggerCondition("REG-T7", new C2Service.TriggerConditionInput(
                group("OR", leaf("c2TestScore", "GTE", "100"), leaf("c2TestChannel", "EQ", "API")), null, null));
        Map<String, Object> noMatch = variables("c2TestScore", "1", "c2TestChannel", "SWIFT");
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", null, noMatch)), "REG-T7").triggered());

        // 保存后字段在元数据中心被停用：条件已失效。监管规则是限制而不是准入，失效时不能放宽——按触发处理并说明
        FieldDefinition score = fields.findById("c2TestScore").orElseThrow();
        score.enabled = false;
        fields.save(score);
        C2Service.RegulatoryCheck disabled = check(c2.simulate(payment("PROV-W", "USD", "US", null, noMatch)), "REG-T7");
        assertTrue(disabled.triggered());
        assertNull(disabled.triggerExpression());
        assertEquals("附加触发条件已失效（字段 c2TestScore 不适用于当前规则或不支持该操作符），按触发处理；请在 C2 模拟页核对后重新保存",
                disabled.note());
        assertEquals("字段 c2TestScore 不适用于当前规则或不支持该操作符", rule("REG-T7").triggerProblem());
        // 失效的条件不求值，所以没有取值时同样按触发处理
        assertTrue(check(c2.simulate(payment("PROV-W", "USD", "US", null, null)), "REG-T7").triggered());

        // 重新启用后恢复按条件执行；运算符被取消、条件值被移出可选值时同样视为失效
        score.enabled = true;
        fields.save(score);
        assertNull(rule("REG-T7").triggerProblem());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", null, noMatch)), "REG-T7").triggered());
        score.allowedOperators = "EQ,NE";
        fields.save(score);
        assertEquals("字段 c2TestScore 不适用于当前规则或不支持该操作符", rule("REG-T7").triggerProblem());
        score.allowedOperators = "EQ,NE,GT,GTE,LT,LTE";
        fields.save(score);
        assertNull(rule("REG-T7").triggerProblem());
        FieldDefinition channel = fields.findById("c2TestChannel").orElseThrow();
        channel.valueOptions = "SWIFT";
        fields.save(channel);
        assertEquals("c2TestChannel 不支持该值：API", rule("REG-T7").triggerProblem());
        assertTrue(check(c2.simulate(payment("PROV-W", "USD", "US", null, noMatch)), "REG-T7").triggered());
    }

    @Test
    void booleanAndDecimalLeavesCompareByFieldType() {
        createVariableFields();
        c2.saveRegulatory(input("REG-T8", "USD", "ALL"));
        c2.saveTriggerCondition("REG-T8", new C2Service.TriggerConditionInput(
                group("AND", leaf("c2TestFlag", "EQ", "true"), leaf("c2TestScore", "GT", "100")), null, null));
        // 页面控件提交的取值都是字符串：按字段类型转换后再比较
        assertTrue(check(c2.simulate(payment("PROV-W", "USD", "US", null,
                variables("c2TestFlag", "TRUE", "c2TestScore", " 100.01 "))), "REG-T8").triggered());
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", null,
                variables("c2TestFlag", "false", "c2TestScore", "100.00"))), "REG-T8").triggered());

        // 取值与字段类型不符：报错并指出字段，而不是静默不命中
        assertEquals("字段 c2TestFlag 的取值只能是 true 或 false：yes", assertThrows(IllegalArgumentException.class,
                () -> c2.simulate(payment("PROV-W", "USD", "US", null, variables("c2TestFlag", "yes", "c2TestScore", "101")))).getMessage());
        assertEquals("字段 c2TestScore 的取值不是有效数字：abc", assertThrows(IllegalArgumentException.class,
                () -> c2.simulate(payment("PROV-W", "USD", "US", null, variables("c2TestFlag", "true", "c2TestScore", "abc")))).getMessage());

        // 条件值同样按类型校验
        assertEquals("布尔值只能为 true 或 false", assertThrows(IllegalArgumentException.class,
                () -> c2.saveTriggerCondition("REG-T8", new C2Service.TriggerConditionInput(group("AND", leaf("c2TestFlag", "EQ", "YES")), null, null)))
                .getMessage());
        assertEquals("字段 c2TestScore 的条件值不是有效数字：abc", assertThrows(IllegalArgumentException.class,
                () -> c2.saveTriggerCondition("REG-T8", new C2Service.TriggerConditionInput(group("AND", leaf("c2TestScore", "GT", "abc")), null, null)))
                .getMessage());
    }

    @Test
    void fixedTransactionInputsAreNotOverriddenByVariables() {
        c2.saveRegulatory(input("REG-T9", "USD", "ALL"));
        c2.saveTriggerCondition("REG-T9", new C2Service.TriggerConditionInput(group("AND", leaf("purpose", "EQ", "INVESTMENT")), null, null));
        // 九个固定交易要素以模拟请求为准：variables 里的同名字段不覆盖
        assertFalse(check(c2.simulate(payment("PROV-W", "USD", "US", "GOODS",
                variables("purpose", "INVESTMENT", "paymentCurrency", "HKD"))), "REG-T9").triggered());
        assertTrue(check(c2.simulate(payment("PROV-W", "USD", "US", "INVESTMENT", variables("purpose", "GOODS"))), "REG-T9").triggered());
    }

    @Test
    void invalidConditionUnknownRuleAndStaleVersionAreRejected() {
        c2.saveRegulatory(input("REG-TA", "USD", "ALL"));
        // 字段库校验保留：只适用 C1 的字段、未知字段不能进入 C2 附加条件
        var otherRule = assertThrows(IllegalArgumentException.class, () -> c2.saveTriggerCondition("REG-TA",
                new C2Service.TriggerConditionInput(group("AND", leaf("flowCategory", "EQ", "STANDARD")), null, null)));
        assertEquals("字段 flowCategory 不适用于当前规则或不支持该操作符", otherRule.getMessage());
        var unknownField = assertThrows(IllegalArgumentException.class, () -> c2.saveTriggerCondition("REG-TA",
                new C2Service.TriggerConditionInput(group("AND", leaf("noSuchField", "EQ", "X")), null, null)));
        assertEquals("非法字段: noSuchField", unknownField.getMessage());
        var unknownRule = assertThrows(IllegalArgumentException.class,
                () -> c2.saveTriggerCondition("REG-NONE", new C2Service.TriggerConditionInput(group("AND", leaf("purpose", "EQ", "GOODS")), null, null)));
        assertEquals("规则不存在", unknownRule.getMessage());
        var staleVersion = assertThrows(IllegalArgumentException.class, () -> c2.saveTriggerCondition("REG-TA",
                new C2Service.TriggerConditionInput(group("AND", leaf("purpose", "EQ", "GOODS")), null, 7)));
        assertEquals("规则版本已变化，请刷新后重试", staleVersion.getMessage());
        var noTree = assertThrows(IllegalArgumentException.class,
                () -> c2.saveTriggerCondition("REG-TA", new C2Service.TriggerConditionInput(null, null, null)));
        assertEquals("条件树不能为空", noTree.getMessage());
        var noBody = assertThrows(IllegalArgumentException.class, () -> c2.saveTriggerCondition("REG-TA", null));
        assertEquals("条件不能为空", noBody.getMessage());

        // 校验不通过时规则保持原样
        C2Service.RegulatoryView untouched = rule("REG-TA");
        assertNull(untouched.triggerCondition());
        assertEquals(1, untouched.versionNo());
    }

    @Test
    void conditionSurvivesScopeEditsAndCanBeCleared() {
        createVariableFields();
        c2.saveRegulatory(input("REG-TB", "USD", "ALL"));
        c2.saveTriggerCondition("REG-TB", new C2Service.TriggerConditionInput(group("AND", leaf("c2TestFlag", "EQ", "true")), null, null));

        // 监管路由规则页重新保存范围 / 标签、启停规则：附加条件保留
        C2Service.RegulatoryView resaved = c2.saveRegulatory(input("REG-TB", "EUR", "DE"));
        assertEquals("EUR", resaved.currency());
        assertEquals("(c2TestFlag = true)", resaved.triggerText());
        assertEquals(3, resaved.versionNo());
        assertEquals("(c2TestFlag = true)", c2.toggleRegulatory("REG-TB").triggerText());
        // 停用再启用：启停同样保留附加条件
        c2.toggleRegulatory("REG-TB");
        assertTrue(rule("REG-TB").enabled());
        assertTrue(check(c2.simulate(payment("PROV-W", "EUR", "DE", null, variables("c2TestFlag", true))), "REG-TB").triggered());

        // enabled=false 清除；清除时也可以带版本号，带上时须一致
        var staleVersion = assertThrows(IllegalArgumentException.class,
                () -> c2.saveTriggerCondition("REG-TB", new C2Service.TriggerConditionInput(null, false, 1)));
        assertEquals("规则版本已变化，请刷新后重试", staleVersion.getMessage());
        C2Service.RegulatoryView cleared = c2.saveTriggerCondition("REG-TB", new C2Service.TriggerConditionInput(null, false, null));
        assertNull(cleared.triggerCondition());
        assertNull(cleared.triggerText());
        assertNull(cleared.triggerProblem());
        assertEquals(6, cleared.versionNo());
        assertNull(rules.findById("REG-TB").orElseThrow().triggerConditionJson);
        // 清除后交易只要落在范围内即触发
        assertTrue(check(c2.simulate(payment("PROV-W", "EUR", "DE", null, null)), "REG-TB").triggered());

        // 每次变更都留审计
        assertEquals(List.of("CREATE", "UPDATE_CONDITION", "UPDATE", "DISABLE", "ENABLE", "UPDATE_CONDITION"),
                audits.findAll().stream().filter(a -> "REG-TB".equals(a.objectId)).sorted(Comparator.comparing(a -> a.id))
                        .map(a -> a.operation).toList());
    }
}
