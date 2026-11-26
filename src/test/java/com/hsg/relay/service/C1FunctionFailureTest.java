package com.hsg.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.api.RelayController;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.function.FunctionExecutionService.Call;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.ClientExclusionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 客户排除查询失败和调用复用：用 Mock 替换排除记录仓库，模拟数据库故障并统计查询次数。
 * 排除记录按「合作方 + 决策时间」成集合加载（countValidForProvider / findValidForProvider，量大时按成员 findValidForMember 查询）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relayfunctionfailure;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class C1FunctionFailureTest {
    private static final Map<String, Object> EXCLUSION_ARGUMENTS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");
    private static final Map<String, Object> CONTAINS_ARGUMENTS = Map.of("collection", Map.of("source", "FIELD", "fieldCode", "providerExcludedClientIds"),
            "element", Map.of("source", "TRANSACTION", "fieldCode", "clientId"));

    @Autowired
    MockMvc mvc;
    @Autowired
    C1Service c1;
    @Autowired
    RelayController relay;
    @Autowired
    FunctionMetadataService functionMetadata;
    @Autowired
    AppetiteRepository appetites;
    @Autowired
    C1ConditionService globalConditions;
    @Autowired
    ObjectMapper json;
    // 应用里所有读取排除记录的地方（函数节点、函数绑定字段、集合字段、函数试算）都走这个仓库
    @MockBean
    ClientExclusionRepository exclusions;

    private static ExpressionService.Node function(Map<String, Object> arguments, String operator, Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, operator, value, "clientExcluded", arguments);
    }

    private static ExpressionService.Node contains(Map<String, Object> arguments, String operator, Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, operator, value, "collectionContains", arguments);
    }

    private static ExpressionService.Node field(String code, String operator, Object value) {
        return new ExpressionService.Node(null, null, code, operator, value);
    }

    private static ExpressionService.Node group(String logic, ExpressionService.Node... children) {
        return new ExpressionService.Node(logic, List.of(children), null, null, null);
    }

    private static ClientExclusion record(long id, String clientId, String providerCode, LocalDateTime from, LocalDateTime to) {
        ClientExclusion record = new ClientExclusion(clientId, providerCode, "test", from, to);
        record.id = id;
        return record;
    }

    // 4. 排除记录查询失败：条件树里有引用它的节点时不能当成「未排除」放行；没有节点时执行器不查询
    @Test
    void databaseFailureIsNotTreatedAsNotExcluded() throws Exception {
        when(exclusions.countValidForProvider(any(), any())).thenThrow(new DataAccessResourceFailureException("connection refused"));
        String body = "{\"clientId\":\"C10001\",\"providerCode\":\"PROV-HO\",\"paymentCurrency\":\"USD\",\"beneficiaryBankCountry\":\"KG\",\"amountUsd\":100}";
        // 条件树里没有排除相关的节点：执行器不查询排除记录，PROV-HO / PSP / USD / KG 照常 PASS（C10001 有有效审批）
        mvc.perform(post("/api/c1/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(true))
                .andExpect(jsonPath("$.reasonCode").value("PASS"))
                .andExpect(jsonPath("$.functionCalls[0].functionCode").value("hasValidCaseApproval"));
        verify(exclusions, Mockito.never()).countValidForProvider(any(), any());

        // 函数试算同样报出异常，不返回 false
        var simulation = functionMetadata.simulate("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false,
                Map.of("clientId", "C10001", "providerCode", "PROV-HO", "amountUsd", "100"));
        assertThat(simulation.value()).isNull();
        assertThat(simulation.matched()).isNull();
        assertThat(simulation.error()).contains("执行失败").contains("connection refused");

        // 集合字段 + 通用函数同样读失败：试算不返回 false
        var contains = functionMetadata.simulate("collectionContains", CONTAINS_ARGUMENTS, "EQ", false,
                Map.of("clientId", "C10001", "providerCode", "PROV-HO", "amountUsd", "100"));
        assertThat(contains.value()).isNull();
        assertThat(contains.matched()).isNull();
        assertThat(contains.error()).contains("connection refused");

        // 全局准入条件引用了集合：读失败时整次判定报错，不会当成「不包含」放行
        globalConditions.save(new C1ConditionService.Input(true, contains(CONTAINS_ARGUMENTS, "EQ", false), null));
        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        assertThatThrownBy(() -> c1.evaluate(request)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("C1 全局过滤条件执行失败").hasMessageContaining("connection refused");
        var response = mvc.perform(post("/api/c1/simulate").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("error").asText())
                .contains("C1 全局过滤条件执行失败").contains("connection refused");
    }

    // 条件树里的 collectionContains 使用请求级的同一份排除集合：整个判定只加载一次，命中记录随函数调用返回
    @Test
    void collectionConditionLoadsTheExclusionSetOnce() {
        LocalDateTime time = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
        when(exclusions.findValidForProvider(any(), any()))
                .thenReturn(List.of(record(7, "C10001", "PROV-HO", time.minusDays(1), null)));
        globalConditions.save(new C1ConditionService.Input(true, contains(CONTAINS_ARGUMENTS, "EQ", false), null));
        var result = c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100")));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(result.functionCalls()).extracting(Call::functionCode).containsExactly("collectionContains");
        assertThat(result.functionCalls().get(0).value()).isEqualTo(true);
        assertThat(result.functionCalls().get(0).recordId()).isEqualTo("7");
        verify(exclusions, times(1)).countValidForProvider(any(), any());
        verify(exclusions, times(1)).findValidForProvider(any(), any());
        verify(exclusions, Mockito.never()).findValidForMember(any(), any(), any());
    }

    // 10. 同一判定里相同函数 + 相同入参只查询一次；入参不同才再查
    @Test
    void sameFunctionWithSameArgumentsIsQueriedOncePerEvaluation() {
        when(exclusions.findValidForProvider(any(), any())).thenReturn(List.of());
        Appetite rule = appetites.findAll().stream().filter(a -> a.providerCode.equals("PROV-HO") && a.flowCategory.equals("PSP")).findFirst().orElseThrow();
        relay.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true, group("AND",
                function(EXCLUSION_ARGUMENTS, "EQ", false),
                group("OR", function(EXCLUSION_ARGUMENTS, "NE", true), field("paymentCurrency", "EQ", "USD")))));
        var result = c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100")));

        assertThat(result.reasonCode()).isEqualTo("PASS");
        // 两个函数节点入参相同：只真正查询一次（第二个节点复用结果；第 3 条是个案审批）
        verify(exclusions, times(1)).countValidForProvider(any(), any());
        verify(exclusions, times(1)).findValidForProvider(any(), any());
        assertThat(result.functionCalls()).extracting(Call::functionCode)
                .containsExactly("clientExcluded", "clientExcluded", "hasValidCaseApproval");
        assertThat(result.functionCalls().get(0).cached()).isFalse();
        assertThat(result.functionCalls().get(1).cached()).isTrue();
        assertThat(result.functionCalls()).filteredOn(call -> call.functionCode().equals("clientExcluded"))
                .allMatch(call -> Boolean.FALSE.equals(call.value()));

        // 节点用字面量指定了另一个客户：入参不同，函数单独求值
        Mockito.clearInvocations(exclusions);
        when(exclusions.findValidForProvider(any(), any())).thenReturn(List.of(record(7, "C10002", "PROV-HO", LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")).minusDays(1), null)));
        relay.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true, group("AND",
                function(Map.of("providerCode", "$candidate.providerCode", "clientId", "C10002"), "EQ", true))));
        var other = c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100")));
        assertThat(other.reasonCode()).isEqualTo("PASS");
        // 新判定里节点加载一次集合；指定的另一个客户在同一份集合里判断，不再访问数据库
        verify(exclusions, times(1)).countValidForProvider(any(), any());
        verify(exclusions, times(1)).findValidForProvider(any(), any());
        verify(exclusions, Mockito.never()).findValidForMember(any(), any(), any());
        assertThat(other.functionCalls()).extracting(Call::functionCode).containsExactly("clientExcluded", "hasValidCaseApproval");
        assertThat(other.functionCalls().get(0).value()).isEqualTo(true);
        assertThat(other.functionCalls().get(0).cached()).isFalse();
        assertThat(other.functionCalls().get(0).recordId()).isEqualTo("7");
        assertThat(other.functionCalls().get(0).arguments()).containsEntry("clientId", "C10002");
    }
}
