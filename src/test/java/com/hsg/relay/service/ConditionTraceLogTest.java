package com.hsg.relay.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.api.FieldMetadataController;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.function.RuleExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 条件求值过程日志：{@link ExpressionService#evaluate} 在 DEBUG 级别输出 CANDIDATE / TRANSACTION / CONTEXT 三组上下文、
 * 函数调用、代入变量和逐项结果；DEBUG 关闭时不输出。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relaytrace;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class ConditionTraceLogTest {
    private static final Map<String, Object> EXCLUSION_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");
    private static final String CLIENT_EXCLUDED_READABLE =
            "clientExcluded(providerCode=$candidate.providerCode, clientId=$transaction.clientId, decisionTime=$context.decisionTime)";

    @Autowired
    ExpressionService expressions;
    @Autowired
    FieldMetadataController fieldsApi;
    @Autowired
    C1ConditionService globalConditions;
    @Autowired
    FunctionMetadataService functionMetadata;
    @Autowired
    ObjectMapper json;

    private final Logger logger = (Logger) LoggerFactory.getLogger(ExpressionService.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level level;
    private boolean additive;

    @BeforeEach
    void captureTrace() {
        level = logger.getLevel();
        additive = logger.isAdditive();
        logger.addAppender(events);
        logger.setAdditive(false);
        logger.setLevel(Level.DEBUG);
        events.start();
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(events);
        logger.setLevel(level);
        logger.setAdditive(additive);
        events.stop();
    }

    private List<String> traces() {
        return events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ExpressionService.Node field(String code, String operator, Object value) {
        return new ExpressionService.Node(null, null, code, operator, value);
    }

    private static ExpressionService.Node function(String code, Map<String, Object> arguments, String operator, Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, operator, value, code, arguments);
    }

    private static ExpressionService.Node group(String logic, ExpressionService.Node... children) {
        return new ExpressionService.Node(logic, List.of(children), null, null, null);
    }

    private static RuleExecutionContext context() {
        var context = RuleExecutionContext.c1("PROV-GD", "C10001", "USD", "KG", new BigDecimal("100.5"), LocalDateTime.of(2026, 11, 25, 10, 0));
        return context.set("candidate.flowCategory", "PSP");
    }

    // 1. 一次求值一个 DEBUG 块：上下文三组、函数调用、代入变量及来源、逐项结果
    @Test
    void traceShowsContextFunctionCallsVariablesAndLeaves() throws Exception {
        // 函数绑定字段：取值来自 clientExcluded，请求里提交的同名取值不采用
        fieldsApi.create(new FieldMetadataController.Input("traceProviderExcluded", "测试：客户是否被合作方排除", "BOOLEAN", "EQ,NE", "C1",
                null, true, "FUNCTION", "clientExcluded",
                json.readTree("{\"providerCode\":\"$candidate.providerCode\",\"clientId\":\"$transaction.clientId\"}")));
        var tree = group("AND", field("paymentCurrency", "EQ", "USD"),
                group("OR", field("traceProviderExcluded", "EQ", "true"), function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", true)));
        var compiled = expressions.compileDetailed(tree, "C1");

        var result = expressions.evaluate(compiled, Map.of("paymentCurrency", "USD", "traceProviderExcluded", true), context(), "单元测试条件");

        assertThat(result.matched()).isFalse();
        assertThat(traces()).singleElement().asString()
                .startsWith("条件求值｜单元测试条件｜规则 C1\n    表达式：" + compiled.readable()
                        + "\n    可执行表达式：" + compiled.expression() + "\n")
                .contains("\n    [CANDIDATE 当前正在评估的候选路由]\n        candidate.providerCode = PROV-GD\n        candidate.flowCategory = PSP\n")
                .contains("\n    [TRANSACTION 当前支付交易的数据]\n        transaction.clientId = C10001\n        transaction.paymentCurrency = USD\n"
                        + "        transaction.beneficiaryBankCountry = KG\n        transaction.amountUsd = 100.5\n")
                .contains("\n    [CONTEXT 规则引擎执行过程中的上下文数据]\n        context.decisionTime = 2026-11-25T10:00:00\n"
                        + "        context.monthKey = 2026-11\n        context.ruleCode = C1\n")
                // 函数绑定字段先执行；函数节点入参相同，复用这次查询
                .contains("\n    [函数调用]\n        traceProviderExcluded ← clientExcluded（客户是否被合作方排除，Handler clientExclusionChecker）"
                        + "｜入参 {providerCode=PROV-GD, clientId=C10001, decisionTime=2026-11-25T10:00:00}｜返回 false｜耗时 ")
                .contains("ms｜数据来源 CLIENT_EXCLUSION\n        __fn0 ← clientExcluded（客户是否被合作方排除，Handler clientExclusionChecker）"
                        + "｜入参 {providerCode=PROV-GD, clientId=C10001, decisionTime=2026-11-25T10:00:00}｜返回 false｜复用本次判定内的结果"
                        + "｜数据来源 CLIENT_EXCLUSION\n")
                // 请求里的 traceProviderExcluded = true 没有被采用：取值来自函数
                .contains("\n    [代入变量]\n        paymentCurrency = USD（请求取值）\n        traceProviderExcluded = false（函数 clientExcluded 计算）\n"
                        + "        __fn0 = false（函数 clientExcluded 返回值）\n")
                .contains("\n    [逐项求值]\n        1. (paymentCurrency == 'USD') → true\n        2. (traceProviderExcluded == true) → false\n"
                        + "        3. (" + CLIENT_EXCLUDED_READABLE + " == true) → false\n")
                .contains("\n    结果：未命中｜耗时 ")
                .endsWith("ms");
    }

    // 2. 表达式引用了但没有取值的变量：Aviator 按 nil 比较（== 得 false、不报错），日志必须点名
    @Test
    void traceNamesVariablesThatHadNoValue() {
        var compiled = expressions.compileDetailed(
                group("AND", field("paymentCurrency", "EQ", "USD"), field("beneficiaryBankCountry", "EQ", "KG")), "C1");

        assertThat(expressions.evaluate(compiled, Map.of("paymentCurrency", " "), null).matched()).isFalse();

        assertThat(traces()).singleElement().asString()
                .startsWith("条件求值｜规则 C1\n    表达式：" + compiled.readable() + "\n")
                .doesNotContain("可执行表达式")
                .contains("\n    [执行上下文] 无（")
                .contains("\n    [函数调用] 无\n")
                .contains("\n    [代入变量]\n        paymentCurrency = —（请求取值为空）\n"
                        + "        beneficiaryBankCountry = —（请求变量里没有提供取值，Aviator 按 nil 比较）\n")
                .contains("\n    [逐项求值]\n        1. (paymentCurrency == 'USD') → false（paymentCurrency 没有取值，按 nil 比较）\n"
                        + "        2. (beneficiaryBankCountry == 'KG') → false（beneficiaryBankCountry 没有取值，按 nil 比较）\n")
                .contains("\n    结果：未命中｜耗时 ")
                .endsWith("ms");
    }

    // 3. 函数取值失败：日志记下失败的调用（参数绑定 + 原因）、哪条路径取值为空，异常照常抛给调用方
    @Test
    void failedFunctionCallIsTracedAndStillRethrown() {
        var compiled = expressions.compileDetailed(group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false)), "C1");
        var context = context();
        context.set("transaction.clientId", null);

        assertThatThrownBy(() -> expressions.evaluate(compiled, Map.of(), context, "失败场景"))
                .hasMessageContaining("参数 clientId 取值为空（transaction.clientId）");

        assertThat(traces()).singleElement().asString()
                .startsWith("条件求值｜失败场景｜规则 C1\n")
                .contains("\n        transaction.clientId = —\n")
                .contains("\n    [函数调用]\n        __fn0 ← clientExcluded（客户是否被合作方排除，Handler clientExclusionChecker）"
                        + "｜参数绑定 providerCode=$candidate.providerCode, clientId=$transaction.clientId, decisionTime=$context.decisionTime"
                        + "｜执行失败：IllegalArgumentException：函数 clientExcluded 的参数 clientId 取值为空（transaction.clientId）\n")
                .contains("\n    [代入变量]\n        __fn0 = —（函数 clientExcluded 调用失败，没有取到值）\n")
                .contains("\n    [逐项求值]\n        1. (" + CLIENT_EXCLUDED_READABLE + " == false) → false（__fn0 没有取值，按 nil 比较）\n")
                .contains("\n    结果：执行失败｜IllegalArgumentException：函数 clientExcluded 的参数 clientId 取值为空（transaction.clientId）｜耗时 ")
                .endsWith("ms");
    }

    // 4. DEBUG 关闭：不输出，也不影响求值
    @Test
    void nothingIsLoggedWhenDebugIsOff() {
        logger.setLevel(Level.INFO);
        var compiled = expressions.compileDetailed(group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false)), "C1");

        assertThat(expressions.evaluate(compiled, Map.of(), context()).matched()).isTrue();
        assertThat(events.list).isEmpty();
    }

    // 5. 调用方带来源说明：C1 全局准入条件、函数试算
    @Test
    void callersAreNamedInTheHeader() {
        globalConditions.save(new C1ConditionService.Input(true,
                group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false)), null));
        assertThat(globalConditions.evaluate(Map.of(), context()).matched()).isTrue();
        functionMetadata.simulate("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false,
                Map.of("clientId", "C10001", "providerCode", "PROV-GD"));

        assertThat(traces()).satisfiesExactly(
                trace -> assertThat(trace).startsWith("条件求值｜C1 全局准入条件｜规则 C1\n").contains("\n    结果：命中｜耗时 "),
                trace -> assertThat(trace).startsWith("条件求值｜函数试算 clientExcluded｜规则 C1\n").contains("\n    结果：命中｜耗时 "));
    }
}
