package com.hsg.relay.service;

import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.repo.ClientExclusionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;

/**
 * 排除记录数量超过加载上限（relay.exclusion.materialize-limit）时的成员查询模式：
 * 集合不全量加载，CONTAINS 语义不变；需要枚举的操作明确报错，不会隐式全量加载。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relaymembership;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "relay.exclusion.materialize-limit=1"
})
@Transactional
class CollectionMembershipQueryTest {
    private static final String SET = "providerExcludedClientIds";
    private static final Map<String, Object> CONTAINS = Map.of("collection", Map.of("source", "FIELD", "fieldCode", SET),
            "element", Map.of("source", "TRANSACTION", "fieldCode", "clientId"));

    @Autowired
    ExpressionService expressions;
    @Autowired
    C1Service c1;
    @Autowired
    ClientExclusionChecker checker;
    @Autowired
    ClientExclusionRepository exclusions;
    @Autowired
    C1ConditionService c1Conditions;
    @Autowired
    C1PathConfigService pathConfig;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void publishBaselinePaths() {
        C1PathFixtures.publishDefaultOnly(pathConfig, json);
    }

    private static RuleExecutionContext context(String provider, String client) {
        return RuleExecutionContext.c1(provider, client, "USD", "KG", new BigDecimal("100"), LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")));
    }

    private static ExpressionService.Node contains(Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, "EQ", value, "collectionContains", CONTAINS);
    }

    private boolean matched(ExpressionService.Node tree, RuleExecutionContext context) {
        return expressions.evaluate(expressions.compileDetailed(tree, "C1"), Map.of(), context).matched();
    }

    private void twoRecords(String provider) {
        LocalDateTime time = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
        exclusions.save(new ClientExclusion("C10001", provider, "test", time.minusDays(1), null));
        exclusions.save(new ClientExclusion("C10002", provider, "test", time.minusDays(1), null));
    }

    @Test
    void oversizedCollectionAnswersMembershipWithoutLoadingEverything() {
        twoRecords("PROV-GD");
        // 仓库代理不能直接 spy：用委托到真实仓库的 mock 统计查询次数
        ClientExclusionRepository spy = Mockito.mock(ClientExclusionRepository.class, AdditionalAnswers.delegatesTo(exclusions));
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
        RuleCollection set = new ClientExclusionChecker(spy, 1).exclusionSet("PROV-GD", now, null);
        assertThat(set.materialized()).isFalse();
        assertThat(set.view(5).size()).isEqualTo(-1);
        assertThat(set.summary()).contains("成员查询模式");
        // 成员查询：命中带记录 ID，未命中为 false；同一客户在本次求值内只查一次
        assertThat(set.lookup("C10001").hit()).isTrue();
        assertThat(set.lookup("C10001").recordId()).isNotBlank();
        assertThat(set.contains("C99999")).isFalse();
        assertThat(set.lookup(null).hit()).isFalse();
        Mockito.verify(spy, times(1)).countValidForProvider("PROV-GD", now);
        Mockito.verify(spy, times(2)).findValidForMember(any(), any(), any());
        Mockito.verify(spy, Mockito.never()).findValidForProvider(any(), any());
        // 枚举类操作明确报错，不会隐式全量加载
        assertThatThrownBy(set::elements).hasMessageContaining("未全量加载").hasMessageContaining("collectionContains");
        Mockito.verify(spy, Mockito.never()).findValidForProvider(any(), any());
    }

    @Test
    void collectionContainsKeepsTheSameSemanticsInMembershipQueryMode() {
        twoRecords("PROV-GD");
        var hit = context("PROV-GD", "C10001");
        assertThat(matched(contains(true), hit)).isTrue();
        assertThat(hit.calls()).singleElement().satisfies(call -> {
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isNotBlank();
            @SuppressWarnings("unchecked")
            var view = (Map<String, Object>) call.arguments().get("collection");
            assertThat(view).containsEntry("materialized", false).containsEntry("size", -1);
        });
        assertThat(matched(contains(false), context("PROV-GD", "C99999"))).isTrue();
        // 另一个合作方只有一条记录：已加载，按内存判断
        exclusions.save(new ClientExclusion("C10008", "PROV-HO", "test", LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")).minusDays(1), null));
        assertThat(matched(contains(true), context("PROV-HO", "C10008"))).isTrue();
        assertThat(matched(contains(true), context("PROV-HO", "C10001"))).isFalse();
    }

    @Test
    void enumeratingOperationsFailExplicitlyInsteadOfLoadingTheWholeList() {
        twoRecords("PROV-GD");
        // 集合字段的字段条件要枚举集合：报错
        var leaf = expressions.compileDetailed(new ExpressionService.Node(null, null, SET, "CONTAINS", "C10001"), "C1");
        assertThatThrownBy(() -> expressions.evaluate(leaf, Map.of(), context("PROV-GD", "C10001"))).hasMessageContaining("未全量加载");
        // 经 C1 全局条件执行时包装成条件执行失败，不会被当成「不包含」
        var configured = new C1ConditionService.Input(true, new ExpressionService.Node(null, null, SET, "NOT_CONTAINS", "C10001"), null);
        c1Conditions.save(configured);
        assertThatThrownBy(() -> c1Conditions.evaluate(Map.of(), context("PROV-GD", "C10001"))).hasMessageContaining("C1 全局过滤条件执行失败").hasMessageContaining("未全量加载");

        // 两个集合都未加载时求交集：报错
        twoRecords("PROV-HO");
        var handler = new com.hsg.relay.function.CollectionIntersectsFunction();
        RuleExecutionContext context = context("PROV-GD", "C10001");
        RuleCollection left = checker.exclusionSet(context), right = checker.exclusionSet("PROV-HO", context.decisionTime(), context);
        assertThatThrownBy(() -> handler.execute(Map.of("leftCollection", left, "rightCollection", right), context))
                .hasMessageContaining("都未全量加载");
        // 一边已加载、一边成员查询：枚举已加载的一边，对另一边做成员查询
        RuleCollection loaded = RuleCollection.of("T", List.of("C10002"));
        assertThat(handler.execute(Map.of("leftCollection", left, "rightCollection", loaded), context).value()).isEqualTo(true);
    }

    // C1 的客户排除检查在成员查询模式下照常工作：必检与路径条件树里的集合条件都走成员查询，命中记录随函数调用返回
    @Test
    void c1UsesMembershipQueriesForTheMandatoryCheckAndThePathCondition() {
        twoRecords("PROV-GD");
        var request = new C1Service.Simulation("C10001", "PROV-GD", "USD", "KG", new BigDecimal("100"));
        // 必检：客户在排除名单里 → CLIENT_EXCLUDED
        var mandatory = c1.evaluate(request);
        assertThat(mandatory.reasonCode()).isEqualTo("CLIENT_EXCLUDED");
        assertThat(mandatory.exclusionRecordId()).isNotBlank();
        assertThat(mandatory.functionCalls()).singleElement().satisfies(call -> {
            assertThat(call.functionCode()).isEqualTo("clientExcluded");
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isNotBlank();
        });

        // 路径条件树里的集合条件：同样走成员查询，客户在集合里 → 路径条件不满足
        pathConfig.saveDraft(json.valueToTree(new C1PathRule("C1", "FIRST_MATCH", List.of(
                C1PathFixtures.path("DEFAULT", "默认路径", List.of(), 0, true, contains(false))))), null, "tester");
        pathConfig.publish(new C1PathConfigService.PublishRequest(null, "tester", null));
        var result = c1.evaluate(request);
        assertThat(result.reasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(result.functionCalls()).singleElement().satisfies(call -> {
            assertThat(call.functionCode()).isEqualTo("collectionContains");
            assertThat(call.value()).isEqualTo(true);
            assertThat(call.recordId()).isNotBlank();
        });
    }
}
