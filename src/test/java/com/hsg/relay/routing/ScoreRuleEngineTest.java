package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.FieldResolver;
import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.service.ExpressionService;
import com.hsg.relay.service.ScoreRuleEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 评分引擎（SCORE / FIRST_MATCH）单元测试：条件走现有 ExpressionService，字段库用内存元数据替代。 */
class ScoreRuleEngineTest {
    private ScoreRuleEngine engine;
    private FunctionExecutionService functions;
    private FieldValueService fieldValues;

    @BeforeEach
    void setUp() {
        Map<String, FieldDefinition> definitions = new LinkedHashMap<>();
        for (String[] f : new String[][]{
                {"requestedValueDate", "DATE", "EQ,NE,GT,GTE,LT,LTE"}, {"providerBusinessDay", "BOOLEAN", "EQ,NE"},
                {"beforeCutoff", "BOOLEAN", "EQ,NE"}, {"valueDateOffsetDays", "INTEGER", "EQ,NE,GT,GTE,LT,LTE"},
                {"earliestValueDate", "DATE", "EQ,NE,GT,GTE,LT,LTE"}, {"candidateValueDate", "DATE", "EQ,NE,GT,GTE,LT,LTE"},
                {"delayBusinessDays", "INTEGER", "EQ,NE,GT,GTE,LT,LTE"}, {"c5SpeedScore", "INTEGER", "EQ,NE,GT,GTE,LT,LTE"}})
            definitions.put(f[0], new FieldDefinition(f[0], f[0], f[1], f[2], "C5"));
        FieldDefinitionRepository fields = mock(FieldDefinitionRepository.class);
        when(fields.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(definitions.get(inv.<String>getArgument(0))));
        when(fields.findAll()).thenAnswer(inv -> new ArrayList<>(definitions.values()));
        functions = mock(FunctionExecutionService.class);
        fieldValues = mock(FieldValueService.class);
        ExpressionService expressions = new ExpressionService(fields, functions, new FieldResolver(new ObjectMapper()), fieldValues);
        engine = new ScoreRuleEngine(expressions);
    }

    private static final Set<String> INPUTS = C5ScoreService.INPUT_FIELDS;

    private ExpressionService.Node leaf(String field, String operator, Object value) {
        return new ExpressionService.Node(null, "AND", null, field, operator, value, null, null);
    }

    private ScoreRule.Group group(String code, int priority, int score, ExpressionService.Node condition) {
        return new ScoreRule.Group(code, code, priority, condition, new ScoreRule.Result("SCORE", score), true, null);
    }

    private ScoreRule rule(ScoreRule.Group... groups) {
        return new ScoreRule("C5", "SCORE", "FIRST_MATCH", new ScoreRule.ScoreRange(0, 100), List.of(groups), true);
    }

    private ScoreRule defaultRule() {
        return rule(group("C5-ON-TIME", 1, 100, leaf("delayBusinessDays", "EQ", 0)),
                group("C5-ONE-DAY", 2, 50, leaf("delayBusinessDays", "EQ", 1)),
                group("C5-TWO-PLUS", 3, 0, leaf("delayBusinessDays", "GTE", 2)));
    }

    private ScoreResult evaluate(ScoreRule rule, Object delay) {
        return engine.evaluate(engine.compile(rule, 1, INPUTS), Map.of("delayBusinessDays", delay), null);
    }

    private ScoreRuleEngine.PublishCheck check(ScoreRule rule) {
        return engine.checkForPublish(engine.compile(rule, 1, INPUTS), C5ScoreService.PROBE_DOMAIN);
    }

    // ---------- FIRST_MATCH ----------

    @Test
    void defaultRuleGives10050And0() {
        assertThat(evaluate(defaultRule(), 0).score()).isEqualTo(100);
        assertThat(evaluate(defaultRule(), 1).score()).isEqualTo(50);
        assertThat(evaluate(defaultRule(), 2).score()).isZero();
        assertThat(evaluate(defaultRule(), 45).score()).isZero();
    }

    @Test
    void firstMatchIsTheHighestPriorityRegardlessOfListOrder() {
        // 列表顺序故意打乱：优先级 10 的组排在最前，但优先级 1 的先判断
        var rule = rule(group("ANY", 10, 60, leaf("delayBusinessDays", "GTE", 0)),
                group("EXACT", 1, 100, leaf("delayBusinessDays", "EQ", 5)),
                group("FIVE_PLUS", 5, 20, leaf("delayBusinessDays", "GTE", 5)));
        var exact = evaluate(rule, 5);
        assertThat(exact.matchedGroupCode()).isEqualTo("EXACT");
        assertThat(exact.score()).isEqualTo(100);
        assertThat(exact.groups()).hasSize(1);                                  // 命中后不再判断后面的组
        var seven = evaluate(rule, 7);
        assertThat(seven.matchedGroupCode()).isEqualTo("FIVE_PLUS");
        assertThat(seven.groups()).extracting(ScoreResult.GroupOutcome::matched).containsExactly(false, true);
        assertThat(evaluate(rule, 1).score()).isEqualTo(60);
    }

    @Test
    void disabledGroupsAreSkipped() {
        var off = new ScoreRule.Group("OFF", "停用", 1, leaf("delayBusinessDays", "GTE", 0), new ScoreRule.Result("SCORE", 1), false, null);
        var result = evaluate(rule(off, group("ON", 2, 77, leaf("delayBusinessDays", "GTE", 0))), 3);
        assertThat(result.score()).isEqualTo(77);
        assertThat(result.groups()).extracting(ScoreResult.GroupOutcome::groupCode).containsExactly("ON");
    }

    @Test
    void nestedAndOrGroupsAreSupported() {
        var threeToFive = new ExpressionService.Node("AND", List.of(leaf("delayBusinessDays", "GTE", 3), leaf("delayBusinessDays", "LTE", 5)), null, null, null);
        var nested = new ExpressionService.Node("OR", List.of(threeToFive, leaf("valueDateOffsetDays", "GTE", 4)), null, null, null);
        var rule = rule(group("SPECIAL", 1, 10, nested), group("REST", 2, 90, leaf("delayBusinessDays", "GTE", 0)));
        assertThat(evaluate(rule, 4).score()).isEqualTo(10);                  // 延迟 3–5 或偏移 ≥ 4
        var offsetFive = engine.evaluate(engine.compile(rule, 1, INPUTS), Map.of("delayBusinessDays", 1, "valueDateOffsetDays", 5), null);
        assertThat(offsetFive.score()).isEqualTo(10);
        assertThat(evaluate(rule, 1).score()).isEqualTo(90);
    }

    @Test
    void dateAndBooleanConditionsCompareWithTheirOwnTypes() {
        var rule = rule(group("HOLIDAY", 1, 0, leaf("candidateValueDate", "EQ", "2026-12-25")),
                group("NOT_WORKING_DAY", 2, 30, leaf("providerBusinessDay", "EQ", false)),
                group("REST", 3, 100, leaf("beforeCutoff", "EQ", "true")));
        var vars = new HashMap<String, Object>(Map.of("candidateValueDate", "2026-12-25", "providerBusinessDay", "true", "beforeCutoff", "true"));
        assertThat(engine.evaluate(engine.compile(rule, 1, INPUTS), vars, null).score()).isZero();
        vars.put("candidateValueDate", "2026-12-24");
        assertThat(engine.evaluate(engine.compile(rule, 1, INPUTS), vars, null).score()).isEqualTo(100);
        vars.put("providerBusinessDay", "false");
        assertThat(engine.evaluate(engine.compile(rule, 1, INPUTS), vars, null).score()).isEqualTo(30);
    }

    // ---------- 无匹配不默认满分 ----------

    @Test
    void noMatchIsReportedAndHasNoScore() {
        var result = evaluate(rule(group("ONLY-ZERO", 1, 100, leaf("delayBusinessDays", "EQ", 0))), 3);
        assertThat(result.status()).isEqualTo("NOT_MATCHED");
        assertThat(result.score()).isNull();
        assertThat(result.matchedGroupCode()).isNull();
        assertThat(result.scored()).isFalse();
        assertThat(result.message()).contains("不默认满分");
    }

    @Test
    void disabledRuleHasNoScore() {
        var prepared = engine.compile(new ScoreRule("C5", "SCORE", "FIRST_MATCH", new ScoreRule.ScoreRange(0, 100),
                List.of(group("A", 1, 100, leaf("delayBusinessDays", "GTE", 0))), false), 1, INPUTS);
        assertThat(engine.evaluate(prepared, Map.of("delayBusinessDays", 0), null).status()).isEqualTo("RULE_DISABLED");
    }

    // ---------- 结构校验 ----------

    @Test
    void scoreOutsideZeroToHundredIsRejected() {
        assertThatThrownBy(() -> engine.compile(rule(group("TOO-HIGH", 1, 101, leaf("delayBusinessDays", "EQ", 0))), 1, INPUTS))
                .hasMessageContaining("101").hasMessageContaining("超出合法范围");
        assertThatThrownBy(() -> engine.compile(rule(group("NEGATIVE", 1, -1, leaf("delayBusinessDays", "EQ", 0))), 1, INPUTS))
                .hasMessageContaining("-1").hasMessageContaining("超出合法范围");
    }

    @Test
    void duplicateCodeOrPriorityAndWrongTypesAreRejected() {
        assertThatThrownBy(() -> engine.compile(rule(group("A", 1, 1, leaf("delayBusinessDays", "EQ", 0)), group("A", 2, 2, leaf("delayBusinessDays", "EQ", 1))), 1, INPUTS))
                .hasMessageContaining("ID 重复");
        assertThatThrownBy(() -> engine.compile(rule(group("A", 1, 1, leaf("delayBusinessDays", "EQ", 0)), group("B", 1, 2, leaf("delayBusinessDays", "EQ", 1))), 1, INPUTS))
                .hasMessageContaining("优先级重复");
        assertThatThrownBy(() -> engine.compile(new ScoreRule("C5", "FILTER", "FIRST_MATCH", new ScoreRule.ScoreRange(0, 100),
                List.of(group("A", 1, 1, leaf("delayBusinessDays", "EQ", 0))), true), 1, INPUTS)).hasMessageContaining("SCORE");
        assertThatThrownBy(() -> engine.compile(new ScoreRule("C5", "SCORE", "ALL_MATCH", new ScoreRule.ScoreRange(0, 100),
                List.of(group("A", 1, 1, leaf("delayBusinessDays", "EQ", 0))), true), 1, INPUTS)).hasMessageContaining("FIRST_MATCH");
    }

    @Test
    void conditionsAreLimitedToTheScoreInputs() {
        assertThatThrownBy(() -> engine.compile(rule(group("A", 1, 1, leaf("c5SpeedScore", "EQ", 0))), 1, INPUTS))
                .hasMessageContaining("c5SpeedScore").hasMessageContaining("不能作为评分条件");
        assertThatThrownBy(() -> engine.compile(rule(group("A", 1, 1, leaf("amountUsd", "GT", 1))), 1, INPUTS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void operatorMustBeAllowedByTheFieldMetadata() {
        assertThatThrownBy(() -> engine.compile(rule(group("A", 1, 1, leaf("delayBusinessDays", "IN", "0,1"))), 1, INPUTS))
                .hasMessageContaining("不支持该操作符");
        // 任务书里的 GE / LE 在字段库里对应现有编码 GTE / LTE
        assertThat(evaluate(rule(group("A", 1, 0, leaf("delayBusinessDays", "LTE", 0))), 0).score()).isZero();
        assertThatThrownBy(() -> engine.compile(rule(group("A", 1, 1, leaf("providerBusinessDay", "GT", "true"))), 1, INPUTS))
                .hasMessageContaining("不支持该操作符");
    }

    // ---------- 发布前检查 ----------

    @Test
    void defaultRulePassesThePublishCheck() {
        var check = check(defaultRule());
        assertThat(check.ok()).isTrue();
        assertThat(check.warnings()).isEmpty();
        assertThat(check.summary()).containsEntry("probeCombinations", 63L);
        assertThat(check.summary().get("groupHits")).isEqualTo(Map.of("C5-ON-TIME", 1L, "C5-ONE-DAY", 1L, "C5-TWO-PLUS", 61L));
    }

    @Test
    void coverageGapIsAnError() {
        var check = check(rule(group("ZERO", 1, 100, leaf("delayBusinessDays", "EQ", 0)), group("ONE", 2, 50, leaf("delayBusinessDays", "EQ", 1))));
        assertThat(check.ok()).isFalse();
        assertThat(String.join("\n", check.errors())).contains("覆盖缺口").contains("delayBusinessDays=2");
        assertThat(check.warnings()).isEmpty();
    }

    @Test
    void identicalConditionsAreAConflictAndShadowedGroupsNeverHit() {
        var same = check(rule(group("A", 1, 1, leaf("delayBusinessDays", "GTE", 0)), group("B", 2, 2, leaf("delayBusinessDays", "GTE", 0)),
                group("C", 3, 3, leaf("delayBusinessDays", "LT", 0))));
        assertThat(String.join("\n", same.errors())).contains("条件完全相同").contains("B 永远不会命中");
        var shadowed = check(rule(group("ANY", 1, 60, leaf("delayBusinessDays", "GTE", 0)), group("ZERO", 2, 100, leaf("delayBusinessDays", "EQ", 0))));
        assertThat(shadowed.ok()).isFalse();
        assertThat(String.join("\n", shadowed.errors())).contains("ZERO").contains("永不命中");
    }

    @Test
    void noEnabledGroupIsAnError() {
        var off = new ScoreRule.Group("OFF", "停用", 1, leaf("delayBusinessDays", "GTE", 0), new ScoreRule.Result("SCORE", 1), false, null);
        assertThat(String.join("\n", check(rule(off)).errors())).contains("没有已启用的评分组");
    }

    @Test
    void dateConditionsCannotBeVerifiedAndNeedAcknowledgement() {
        var check = check(rule(group("CHRISTMAS", 1, 5, leaf("candidateValueDate", "EQ", "2026-12-25")),
                group("ZERO", 2, 100, leaf("delayBusinessDays", "EQ", 0)), group("REST", 3, 0, leaf("delayBusinessDays", "GTE", 1))));
        assertThat(check.ok()).isTrue();
        assertThat(check.warnings()).isNotEmpty();
        assertThat(String.join("\n", check.warnings())).contains("无法穷举");
        assertThat(check.summary()).containsEntry("unverifiableGroups", List.of("CHRISTMAS"));
    }

    @Test
    void functionConditionsAreSupportedButCannotBeVerified() {
        BusinessFunction handler = mock(BusinessFunction.class);
        when(handler.handlerCode()).thenReturn("clientExclusionChecker");
        when(handler.returnType()).thenReturn("BOOLEAN");
        when(handler.dataSource()).thenReturn("CLIENT_EXCLUSION");
        when(handler.parameters()).thenReturn(List.of());
        when(functions.validate(eq("clientExcluded"), any(), eq("C5")))
                .thenReturn(new FunctionExecutionService.Validated("clientExcluded", "客户是否被排除", handler, Map.of()));
        when(functions.invoke(any(), any())).thenAnswer(inv -> new FunctionExecutionService.Call("clientExcluded", "clientExclusionChecker",
                Map.of(), true, "EX-1", "CLIENT_EXCLUSION", 0, false, null));
        var node = new ExpressionService.Node("FUNCTION", null, null, null, "EQ", true, "clientExcluded",
                Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId"));
        var rule = rule(group("EXCLUDED", 1, 1, node), group("ZERO", 2, 100, leaf("delayBusinessDays", "EQ", 0)),
                group("REST", 3, 0, leaf("delayBusinessDays", "GTE", 1)));
        var check = check(rule);
        assertThat(check.ok()).isTrue();
        assertThat(String.join("\n", check.warnings())).contains("函数条件");
        // 执行：函数节点带上下文求值，返回 true → 命中第一组
        var context = RuleExecutionContext.c5("PROV-T1", "C10002", "USD", LocalDate.parse("2026-11-20"), java.time.LocalDateTime.parse("2026-11-20T10:00:00"));
        var result = engine.evaluate(engine.compile(rule, 1, INPUTS), Map.of("delayBusinessDays", 0), context);
        assertThat(result.matchedGroupCode()).isEqualTo("EXCLUDED");
        verify(functions, times(1)).invoke(any(), any());
        verifyNoInteractions(fieldValues);
    }
}
