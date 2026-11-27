package com.hsg.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.api.FieldMetadataController;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.C1PathVersion;
import com.hsg.relay.domain.CaseApproval;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.domain.ClientProfile;
import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.C1PathVersionRepository;
import com.hsg.relay.repo.CaseApprovalRepository;
import com.hsg.relay.repo.ClientExclusionRepository;
import com.hsg.relay.repo.ClientProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.hsg.relay.service.C1PathConfigService.PublishRequest;
import static com.hsg.relay.service.C1PathFixtures.EXCLUSION_ARGUMENTS;
import static com.hsg.relay.service.C1PathFixtures.field;
import static com.hsg.relay.service.C1PathFixtures.function;
import static com.hsg.relay.service.C1PathFixtures.group;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1 执行器按候选合作方选路径、执行这条路径自己的条件树，再执行对每条路径都生效的必检。
 * 覆盖验收场景 1–9：专用路径命中、DEFAULT 兜底、冲突、准入规则四级匹配、必检不可绕过、DEFAULT 不是自动通过、
 * 旧版本回放、新增路径不改代码。草稿 / 发布 / 迁移的管理见 C1PathConfigServiceTest。
 * <p>每个用例在自己的事务里发布需要的配置并回滚，互不影响。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relayc1routing;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class C1PathRoutingTest {
    private static final String OPERATOR = "tester";
    /** 对任何客户都成立的路径条件（只看当前流转类别）。 */
    private static final ExpressionService.Node ALWAYS = group("AND", field("flowCategory", "NE", "NONE"));
    private static final String CONFLICT_MESSAGE = "合作方 PROV-GD 同时绑定在多条启用的路径：P1、P2；一个合作方只能由一条启用的路径负责，请调整绑定或停用其中一条";

    @Autowired
    C1Service c1;
    @Autowired
    C1PathConfigService paths;
    @Autowired
    C1PathVersionRepository versions;
    @Autowired
    AppetiteRepository appetites;
    @Autowired
    CaseApprovalRepository approvals;
    @Autowired
    ClientExclusionRepository exclusions;
    @Autowired
    ClientProfileRepository clients;
    @Autowired
    FieldMetadataController metadata;
    @Autowired
    ExpressionService expressions;
    @Autowired
    ObjectMapper json;

    private final LocalDateTime from = LocalDateTime.now().minusDays(1);
    // PROV-GD 的四条 STANDARD 准入规则（与线上数据相同）
    private Long usdAll, usdHk, allKg, usdKg;

    @BeforeEach
    void seed() {
        // 种子里 PROV-GD 没有准入规则
        usdAll = appetite("PROV-GD", "STANDARD", "USD", "ALL", "ACCEPT", null);
        usdHk = appetite("PROV-GD", "STANDARD", "USD", "HK", "EXCLUDE", null);
        allKg = appetite("PROV-GD", "STANDARD", "ALL", "KG", "EXCLUDE", null);
        usdKg = appetite("PROV-GD", "STANDARD", "USD", "KG", "CASE_BY_CASE", null);
        clients.save(new ClientProfile("CGD01", "COMPLETE", "STANDARD"));
        clients.save(new ClientProfile("CGD02", "COMPLETE", "STANDARD,PSP"));
        clients.save(new ClientProfile("CGD03", "PENDING", "STANDARD"));
    }

    // ---------- 构造 ----------

    private Long appetite(String provider, String flow, String currency, String country, String action, String cap) {
        Appetite rule = new Appetite();
        rule.providerCode = provider;
        rule.flowCategory = flow;
        rule.currency = currency;
        rule.bankCountry = country;
        rule.action = action;
        rule.evidenceDocNo = "DOC-" + provider + "-" + currency + "-" + country;
        rule.nextReviewDate = LocalDate.now().plusYears(1);
        rule.effectiveFrom = from;
        rule.monthlyCapUsd = cap == null ? null : new BigDecimal(cap);
        return appetites.save(rule).id;
    }

    /** 一条启用的专用路径；失败原因码留空，由规范化补成 C1_PATH_编码_NOT_MATCHED。 */
    private static C1PathRule.Path line(String code, String name, List<String> providers, int priority, ExpressionService.Node tree) {
        return new C1PathRule.Path(code, name, providers, priority, true, tree, null, null);
    }

    private static C1PathRule.Path defaultPath(ExpressionService.Node tree) {
        return new C1PathRule.Path("DEFAULT", "默认路径", List.of(), 0, true, tree, null, null);
    }

    private static C1PathRule.Path disabled(C1PathRule.Path path) {
        return new C1PathRule.Path(path.pathCode(), path.pathName(), path.providerCodes(), path.priority(), false,
                path.conditionAst(), path.failureReasonCode(), null);
    }

    private static C1PathRule rule(C1PathRule.Path... list) {
        return new C1PathRule(C1PathRule.RULE_CODE, C1PathRule.BRANCH_MODE, List.of(list));
    }

    /** 基线：P1 = PROV-GD、P2 = PROV-W，其余合作方走 DEFAULT；三条路径的条件对任何客户都成立。 */
    private static C1PathRule baseline() {
        return rule(line("P1", "广东专线", List.of("PROV-GD"), 1, ALWAYS),
                line("P2", "Wise 专线", List.of("PROV-W"), 2, ALWAYS), defaultPath(ALWAYS));
    }

    /** 保存草稿并发布，返回新版本号。 */
    private int publish(C1PathRule config, String note) {
        paths.saveDraft(json.valueToTree(config), null, OPERATOR);
        return paths.publish(new PublishRequest(null, OPERATOR, note)).version();
    }

    /** 绕过发布校验直接写入一个已发布版本：模拟迁移产生的历史版本，或数据被改动过的版本。 */
    private void insertVersion(int version, Object payload) throws Exception {
        versions.save(new C1PathVersion(version, C1PathRule.BRANCH_MODE,
                payload instanceof String text ? text : json.writeValueAsString(payload), LocalDateTime.now(), OPERATOR, "手工写入"));
    }

    private static C1Service.Simulation simulate(String client, String provider, String currency, String country, String amount) {
        return new C1Service.Simulation(client, provider, currency, country, new BigDecimal(amount));
    }

    /** 按指定的已发布版本判定（回放）。 */
    private static C1Service.Simulation replay(String client, String provider, String currency, String country, String amount, int version) {
        return new C1Service.Simulation(client, provider, currency, country, new BigDecimal(amount), Map.of(), version);
    }

    private static String detail(C1Service.Result result, String step) {
        return result.steps().stream().filter(s -> s.step().equals(step)).map(C1Service.Trace::detail).findFirst().orElseThrow();
    }

    private static C1Service.Trace step(C1Service.Result result, String step) {
        return result.steps().stream().filter(s -> s.step().equals(step)).findFirst().orElseThrow();
    }

    // ---------- 1 / 2 / 3：按候选合作方选路径 ----------

    @Test
    void providerCodeSelectsTheDedicatedPathBoundToIt() {
        publish(baseline(), "基线");
        var result = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100"));
        assertThat(result.passed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("PASS");
        assertThat(result.pathCode()).isEqualTo("P1");
        assertThat(result.pathName()).isEqualTo("广东专线");
        assertThat(result.pathPriority()).isEqualTo(1);
        assertThat(result.defaultPathHit()).isFalse();
        assertThat(result.configVersion()).isEqualTo(1);
        assertThat(result.appetiteMatchLevel()).isEqualTo("EXACT_ALL");
        assertThat(result.appetiteDecisions()).containsExactly(new C1Service.AppetiteDecision("STANDARD", "EXACT_ALL", usdAll, "ACCEPT", "USD", "ALL"));
        assertThat(detail(result, "0. 路径配置")).isEqualTo("已发布版本 v1（最新），共 3 条路径，启用 3 条");
        assertThat(detail(result, "2. 路径选择")).isEqualTo("合作方 PROV-GD 命中专用路径 P1（广东专线），优先级 1；规则版本 v1");
        assertThat(detail(result, "2. 路径条件 STANDARD")).isEqualTo("路径 P1 的条件满足；表达式 ((flowCategory != 'NONE'))");
        // 先执行所选路径的条件树，再执行必检
        assertThat(result.steps()).extracting(C1Service.Trace::step).containsExactly("0. 路径配置", "1. 客户档案", "2. 路径选择",
                "2. 路径条件 STANDARD", "3. 客户分类", "3. 客户排除", "3. 规则匹配 STANDARD", "4. MTD STANDARD", "5. C1 汇总");
        assertThat(result.steps()).extracting(C1Service.Trace::status).containsOnly("PASS");
        assertThat(detail(result, "5. C1 汇总")).isEqualTo("全部流转类别通过路径 P1 的条件和 C1 必检；模拟不占用 MTD");

        var wise = c1.evaluate(simulate("C10002", "PROV-W", "USD", "KG", "100"));
        assertThat(wise.reasonCode()).isEqualTo("PASS");
        assertThat(wise.pathCode()).isEqualTo("P2");
        assertThat(wise.pathName()).isEqualTo("Wise 专线");
        assertThat(wise.pathPriority()).isEqualTo(2);
        assertThat(wise.defaultPathHit()).isFalse();
        assertThat(detail(wise, "2. 路径条件 STANDARD")).startsWith("路径 P2 的条件满足");
        assertThat(detail(wise, "2. 路径条件 STANDARD")).doesNotContain("路径 P1");
    }

    @Test
    void providerWithoutADedicatedPathHitsTheDefaultPathAndIsToldSo() {
        publish(baseline(), "基线");
        var result = c1.evaluate(simulate("C10002", "PROV-HO", "USD", "KG", "100"));
        assertThat(result.pathCode()).isEqualTo("DEFAULT");
        assertThat(result.pathName()).isEqualTo("默认路径");
        assertThat(result.pathPriority()).isZero();
        assertThat(result.defaultPathHit()).isTrue();
        assertThat(detail(result, "2. 路径选择")).isEqualTo("合作方 PROV-HO 没有任何启用的专用路径，命中 DEFAULT 路径（默认路径），优先级 0；规则版本 v1");
        // DEFAULT 的条件通过之后必检照常执行：C10002 只有 STANDARD，PROV-HO 没有 STANDARD 的准入规则
        assertThat(detail(result, "2. 路径条件 STANDARD")).isEqualTo("路径 DEFAULT 的条件满足；表达式 ((flowCategory != 'NONE'))");
        assertThat(result.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(result.passed()).isFalse();
        // C10001（PSP）有 PROV-HO 的 CASE_BY_CASE 规则和有效审批：走 DEFAULT 通过
        var pass = c1.evaluate(simulate("C10001", "PROV-HO", "USD", "KG", "100"));
        assertThat(pass.reasonCode()).isEqualTo("PASS");
        assertThat(pass.pathCode()).isEqualTo("DEFAULT");
        assertThat(pass.defaultPathHit()).isTrue();
        assertThat(pass.appetiteMatchLevel()).isEqualTo("EXACT_EXACT");

        // 专用路径停用后，绑定它的合作方也落到 DEFAULT，轨迹里说明是哪条专用路径停用了
        publish(rule(disabled(line("P1", "广东专线", List.of("PROV-GD"), 1, null)),
                line("P2", "Wise 专线", List.of("PROV-W"), 2, ALWAYS), defaultPath(ALWAYS)), "停用 P1");
        var afterDisable = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100"));
        assertThat(afterDisable.pathCode()).isEqualTo("DEFAULT");
        assertThat(afterDisable.defaultPathHit()).isTrue();
        assertThat(afterDisable.configVersion()).isEqualTo(2);
        assertThat(detail(afterDisable, "2. 路径选择"))
                .isEqualTo("合作方 PROV-GD 没有任何启用的专用路径，命中 DEFAULT 路径（默认路径），优先级 0；规则版本 v2；绑定它的专用路径 P1 已停用");
    }

    // ---------- 4：冲突 ----------

    @Test
    void aProviderBoundByTwoEnabledPathsIsAConflictThatPriorityCannotResolve() throws Exception {
        assertThat(publish(baseline(), "基线")).isEqualTo(1);
        var conflict = rule(line("P1", "广东专线", List.of("PROV-GD"), 1, ALWAYS),
                line("P2", "线路二", List.of("PROV-GD", "PROV-W"), 2, ALWAYS), defaultPath(ALWAYS));
        assertThat(paths.check(json.valueToTree(conflict)).errors()).containsExactly(CONFLICT_MESSAGE);
        assertThatThrownBy(() -> publish(conflict, "冲突配置"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("发布校验未通过：\n- " + CONFLICT_MESSAGE);
        // 不写版本：最新版本仍是基线
        assertThat(versions.count()).isEqualTo(1);
        assertThat(c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100")).configVersion()).isEqualTo(1);

        // 绕过发布校验写入的版本（迁移 / 数据改动）：执行时按冲突拒绝，两条路径优先级不同也不会挑一条
        insertVersion(2, conflict);
        var result = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100"));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("C1_PATH_CONFLICT");
        assertThat(result.pathCode()).isNull();
        assertThat(result.configVersion()).isEqualTo(2);
        assertThat(result.functionCalls()).isEmpty();
        assertThat(result.steps()).extracting(C1Service.Trace::step).containsExactly("0. 路径配置", "1. 客户档案", "2. 路径选择", "失败原因");
        assertThat(step(result, "2. 路径选择").status()).isEqualTo("FAIL");
        assertThat(detail(result, "2. 路径选择")).isEqualTo("合作方 PROV-GD 同时绑定在启用的路径 P1、P2：路径冲突，候选合作方被过滤；请修正配置后发布新版本");
        assertThat(detail(result, "失败原因")).isEqualTo("合作方 PROV-GD 同时绑定在启用的路径 P1、P2：路径冲突，候选合作方被过滤；请修正配置后发布新版本");
        // 不在冲突里的合作方不受影响；没有冲突的 v1 仍可回放
        assertThat(c1.evaluate(simulate("C10002", "PROV-W", "USD", "KG", "100")).pathCode()).isEqualTo("P2");
        assertThat(c1.evaluate(replay("C10002", "PROV-GD", "USD", "US", "100", 1)).pathCode()).isEqualTo("P1");
    }

    // ---------- 5：准入规则四级匹配 ----------

    @Test
    void appetiteMatchTakesTheMostSpecificLevelAndNeverFallsBackToALowerOne() {
        publish(baseline(), "基线");
        // 精确币种 + 精确国家：CASE_BY_CASE 盖过 精确币种 + ALL 国家 的 ACCEPT 和 ALL 币种 + 精确国家 的 EXCLUDE
        var needsApproval = c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "KG", "100"));
        assertThat(needsApproval.passed()).isFalse();
        assertThat(needsApproval.reasonCode()).isEqualTo("APPETITE_APPROVAL_MISSING");
        assertThat(needsApproval.matchedRuleId()).isEqualTo(usdKg);
        assertThat(needsApproval.matchedRuleAction()).isEqualTo("CASE_BY_CASE");
        assertThat(needsApproval.appetiteMatchLevel()).isEqualTo("EXACT_EXACT");
        // 路径条件通过，停在必检的个案审批
        assertThat(needsApproval.pathCode()).isEqualTo("P1");
        assertThat(detail(needsApproval, "3. 规则匹配 STANDARD")).isEqualTo("命中 #" + usdKg + "，USD/KG，动作 CASE_BY_CASE，版本 1，匹配级别 EXACT_EXACT（精确币种 + 精确国家）");
        assertThat(step(needsApproval, "4. 个案审批 STANDARD").status()).isEqualTo("FAIL");
        assertThat(detail(needsApproval, "4. 个案审批 STANDARD")).isEqualTo("没有有效的合作方-客户-流转类别审批");
        assertThat(needsApproval.functionCalls()).extracting(FunctionExecutionService.Call::functionCode).contains("hasValidCaseApproval");
        // 有有效审批 → 通过
        approvals.save(new CaseApproval("CGD01", "PROV-GD", "STANDARD", "APR-GD-001", from, LocalDateTime.now().plusDays(30)));
        assertThat(c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "KG", "100")).reasonCode()).isEqualTo("PASS");

        // 精确币种 + 精确国家 的 EXCLUDE 盖过 精确币种 + ALL 国家 的 ACCEPT：不降级去匹配更宽松的 ACCEPT
        var excluded = c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "HK", "100"));
        assertThat(excluded.reasonCode()).isEqualTo("APPETITE_EXCLUDED");
        assertThat(excluded.matchedRuleId()).isEqualTo(usdHk);
        assertThat(excluded.matchedRuleAction()).isEqualTo("EXCLUDE");
        assertThat(excluded.appetiteMatchLevel()).isEqualTo("EXACT_EXACT");
        assertThat(detail(excluded, "3. 规则匹配 STANDARD")).isEqualTo("命中 #" + usdHk + "，USD/HK，动作 EXCLUDE，版本 1，匹配级别 EXACT_EXACT（精确币种 + 精确国家）");
        assertThat(detail(excluded, "失败原因")).isEqualTo("STANDARD 被合作方禁止");

        // 精确币种 + ALL 国家：ACCEPT 且没有月度上限，不查 MTD
        var accept = c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "US", "100"));
        assertThat(accept.reasonCode()).isEqualTo("PASS");
        assertThat(accept.matchedRuleId()).isEqualTo(usdAll);
        assertThat(accept.appetiteMatchLevel()).isEqualTo("EXACT_ALL");
        assertThat(detail(accept, "3. 规则匹配 STANDARD")).isEqualTo("命中 #" + usdAll + "，USD/ALL，动作 ACCEPT，版本 1，匹配级别 EXACT_ALL（精确币种 + ALL 国家）");
        assertThat(detail(accept, "4. MTD STANDARD")).isEqualTo("ACCEPT 未设置月度上限");
        assertThat(accept.functionCalls()).extracting(FunctionExecutionService.Call::functionCode).doesNotContain("getProviderMtd");

        // ALL 币种 + 精确国家
        var country = c1.evaluate(simulate("CGD01", "PROV-GD", "GBP", "KG", "100"));
        assertThat(country.reasonCode()).isEqualTo("APPETITE_EXCLUDED");
        assertThat(country.matchedRuleId()).isEqualTo(allKg);
        assertThat(country.appetiteMatchLevel()).isEqualTo("ALL_EXACT");

        // 四条规则都不覆盖 → 没有有效准入配置，默认拒绝
        var none = c1.evaluate(simulate("CGD01", "PROV-GD", "GBP", "US", "100"));
        assertThat(none.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(none.matchedRuleId()).isNull();
        assertThat(none.appetiteMatchLevel()).isNull();
        assertThat(none.appetiteDecisions()).containsExactly(new C1Service.AppetiteDecision("STANDARD"));

        // 补一条 ALL/ALL 的 ACCEPT：GBP/US 命中它（ALL_ALL）；更高级别仍然优先
        Long allAll = appetite("PROV-GD", "STANDARD", "ALL", "ALL", "ACCEPT", null);
        var lowest = c1.evaluate(simulate("CGD01", "PROV-GD", "GBP", "US", "100"));
        assertThat(lowest.reasonCode()).isEqualTo("PASS");
        assertThat(lowest.matchedRuleId()).isEqualTo(allAll);
        assertThat(lowest.appetiteMatchLevel()).isEqualTo("ALL_ALL");
        assertThat(detail(lowest, "3. 规则匹配 STANDARD")).isEqualTo("命中 #" + allAll + "，ALL/ALL，动作 ACCEPT，版本 1，匹配级别 ALL_ALL（ALL/ALL）");
        assertThat(c1.evaluate(simulate("CGD01", "PROV-GD", "GBP", "KG", "100")).matchedRuleId()).isEqualTo(allKg);
        assertThat(c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "KG", "100")).matchedRuleId()).isEqualTo(usdKg);
        assertThat(c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "HK", "100")).matchedRuleId()).isEqualTo(usdHk);

        // 每个流转类别分别匹配：CGD02 有 STANDARD、PSP 两个类别，PSP 没有规则
        var twoFlows = c1.evaluate(simulate("CGD02", "PROV-GD", "USD", "US", "100"));
        assertThat(twoFlows.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(twoFlows.appetiteDecisions()).containsExactly(
                new C1Service.AppetiteDecision("STANDARD", "EXACT_ALL", usdAll, "ACCEPT", "USD", "ALL"),
                new C1Service.AppetiteDecision("PSP"));
        assertThat(detail(twoFlows, "失败原因")).isEqualTo("PSP 无有效准入配置（默认拒绝）");
        // 路径条件同样按每个流转类别各求值一次
        assertThat(twoFlows.steps()).extracting(C1Service.Trace::step).containsSubsequence("2. 路径条件 STANDARD", "2. 路径条件 PSP",
                "3. 规则匹配 STANDARD", "4. MTD STANDARD", "3. 规则匹配 PSP");
    }

    // ---------- 6：必检不可绕过 ----------

    @Test
    void mandatoryChecksRunAfterEveryPathAndCannotBeBypassedByThePathTree() {
        publish(baseline(), "基线");
        // 路径条件成立，但命中 CASE_BY_CASE 且没有审批
        var noApproval = c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "KG", "100"));
        assertThat(noApproval.pathCode()).isEqualTo("P1");
        assertThat(noApproval.reasonCode()).isEqualTo("APPETITE_APPROVAL_MISSING");

        // 路径条件成立，但客户在合作方的有效排除名单里
        ClientExclusion exclusion = exclusions.save(new ClientExclusion("CGD01", "PROV-GD", "风控要求", from, null));
        var excluded = c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "US", "100"));
        assertThat(excluded.reasonCode()).isEqualTo("CLIENT_EXCLUDED");
        assertThat(excluded.exclusionRecordId()).isEqualTo(String.valueOf(exclusion.id));
        assertThat(excluded.pathCode()).isEqualTo("P1");
        assertThat(detail(excluded, "2. 路径条件 STANDARD")).startsWith("路径 P1 的条件满足");
        assertThat(detail(excluded, "3. 客户排除")).isEqualTo("客户 CGD01 在合作方 PROV-GD 的有效排除记录 #" + exclusion.id + "，候选合作方被过滤");
        // 排除在准入匹配之前：不会给出准入类的原因码
        assertThat(excluded.steps()).extracting(C1Service.Trace::step).doesNotContain("3. 规则匹配 STANDARD");
        assertThat(excluded.matchedRuleId()).isNull();

        // 路径条件成立，但客户没有完成分类
        var pending = c1.evaluate(simulate("CGD03", "PROV-GD", "USD", "US", "100"));
        assertThat(pending.reasonCode()).isEqualTo("CLIENT_NOT_CLASSIFIED");
        assertThat(pending.classificationStatus()).isEqualTo("PENDING");
        assertThat(pending.pathCode()).isEqualTo("P1");
        assertThat(detail(pending, "3. 客户分类")).isEqualTo("客户 CGD03 的分类状态为 PENDING，仅 COMPLETE 可继续");

        // 路径条件成立，但 MTD + 本笔金额超过 ACCEPT 规则的月度上限
        var capped = c1.evaluate(simulate("C10002", "PROV-W", "USD", "KG", "4500000"));
        assertThat(capped.reasonCode()).isEqualTo("APPETITE_CAP_REACHED");
        assertThat(capped.pathCode()).isEqualTo("P2");
        assertThat(step(capped, "4. MTD STANDARD").status()).isEqualTo("FAIL");
        assertThat(detail(capped, "4. MTD STANDARD")).startsWith("MTD 800000").contains("+ 本笔 4500000 > 月上限 5000000");
        assertThat(capped.functionCalls()).extracting(FunctionExecutionService.Call::functionCode).contains("getProviderMtd");
        // 未超限的金额通过
        assertThat(c1.evaluate(simulate("C10002", "PROV-W", "USD", "KG", "4000000")).reasonCode()).isEqualTo("PASS");

        // 路径条件树里自己写了 clientExcluded：被排除的客户不满足 P1 的条件 → 按路径的失败原因码过滤，不伪装成 CLIENT_EXCLUDED
        var excludedInTree = group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", false), field("flowCategory", "NE", "NONE"));
        assertThat(publish(rule(line("P1", "广东专线", List.of("PROV-GD"), 1, excludedInTree),
                line("P2", "Wise 专线", List.of("PROV-W"), 2, ALWAYS), defaultPath(ALWAYS)), "P1 条件树含 clientExcluded")).isEqualTo(2);
        var pathRejected = c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "US", "100"));
        assertThat(pathRejected.reasonCode()).isEqualTo("C1_PATH_P1_NOT_MATCHED");
        assertThat(pathRejected.configVersion()).isEqualTo(2);
        assertThat(pathRejected.exclusionRecordId()).isNull();
        assertThat(step(pathRejected, "2. 路径条件 STANDARD").status()).isEqualTo("FAIL");
        assertThat(detail(pathRejected, "2. 路径条件 STANDARD")).startsWith("路径 P1 的表达式不满足：").contains("clientExcluded(").contains("== false");
        assertThat(detail(pathRejected, "失败原因")).isEqualTo("STANDARD 不满足路径 P1 的条件，候选合作方被过滤");
        assertThat(pathRejected.steps()).extracting(C1Service.Trace::step).doesNotContain("3. 客户排除");
        // 被排除的客户不满足路径条件；没被排除的客户路径条件成立，再过必检
        assertThat(c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100")).reasonCode()).isEqualTo("PASS");

        // 反过来把条件写成 clientExcluded == true：路径条件对被排除的客户成立，必检照样按 CLIENT_EXCLUDED 拒绝
        publish(rule(line("P1", "广东专线", List.of("PROV-GD"), 1, group("AND", function("clientExcluded", EXCLUSION_ARGUMENTS, "EQ", true), field("flowCategory", "NE", "NONE"))),
                line("P2", "Wise 专线", List.of("PROV-W"), 2, ALWAYS), defaultPath(ALWAYS)), "P1 只收被排除的客户");
        var bypass = c1.evaluate(simulate("CGD01", "PROV-GD", "USD", "US", "100"));
        assertThat(detail(bypass, "2. 路径条件 STANDARD")).startsWith("路径 P1 的条件满足");
        assertThat(bypass.reasonCode()).isEqualTo("CLIENT_EXCLUDED");
        assertThat(bypass.exclusionRecordId()).isEqualTo(String.valueOf(exclusion.id));
    }

    // ---------- 7：DEFAULT 不是自动通过 ----------

    @Test
    void defaultPathIsNotAnAutomaticPassAndBrokenConfigIsRejected() throws Exception {
        C1PathRule.Path p1 = line("P1", "广东专线", List.of("PROV-GD"), 1, ALWAYS);
        C1PathRule.Path p2 = line("P2", "Wise 专线", List.of("PROV-W"), 2, ALWAYS);

        // DEFAULT 路径自己的条件不满足：按它的失败原因码过滤，不会落到别的路径，更不会自动通过
        publish(rule(p1, p2, defaultPath(group("AND", field("flowCategory", "EQ", "PSP")))), "DEFAULT 只收 PSP");
        var rejected = c1.evaluate(simulate("C10002", "PROV-HO", "USD", "KG", "100"));
        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.reasonCode()).isEqualTo("C1_PATH_DEFAULT_NOT_MATCHED");
        assertThat(rejected.pathCode()).isEqualTo("DEFAULT");
        assertThat(rejected.defaultPathHit()).isTrue();
        assertThat(step(rejected, "2. 路径条件 STANDARD").status()).isEqualTo("FAIL");
        assertThat(detail(rejected, "2. 路径条件 STANDARD")).isEqualTo("路径 DEFAULT 的表达式不满足：((flowCategory == 'PSP'))");
        assertThat(detail(rejected, "失败原因")).isEqualTo("STANDARD 不满足路径 DEFAULT 的条件，候选合作方被过滤");
        assertThat(rejected.steps()).extracting(C1Service.Trace::step).doesNotContain("3. 客户分类");
        assertThat(rejected.matchedRuleId()).isNull();
        // 专用路径不受 DEFAULT 条件的影响
        var dedicated = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100"));
        assertThat(dedicated.reasonCode()).isEqualTo("PASS");
        assertThat(detail(dedicated, "2. 路径条件 STANDARD")).contains("路径 P1");

        // DEFAULT 停用：没有专用路径的合作方找不到路径
        publish(rule(p1, p2, disabled(defaultPath(ALWAYS))), "停用 DEFAULT");
        var notFound = c1.evaluate(simulate("C10002", "PROV-HO", "USD", "KG", "100"));
        assertThat(notFound.reasonCode()).isEqualTo("C1_PATH_NOT_FOUND");
        assertThat(notFound.pathCode()).isNull();
        assertThat(notFound.configVersion()).isEqualTo(2);
        assertThat(step(notFound, "2. 路径选择").status()).isEqualTo("FAIL");
        assertThat(detail(notFound, "2. 路径选择")).isEqualTo("合作方 PROV-HO 没有启用的专用路径，DEFAULT 路径也未启用");
        assertThat(c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100")).pathCode()).isEqualTo("P1");

        // 配置损坏（绕过发布校验写入）：启用的路径没有条件树 → 拒绝，不等于通过
        insertVersion(3, rule(new C1PathRule.Path("P1", "广东专线", List.of("PROV-GD"), 1, true, null, null, null), p2, defaultPath(ALWAYS)));
        var noTree = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100"));
        assertThat(noTree.passed()).isFalse();
        assertThat(noTree.reasonCode()).isEqualTo("C1_CONFIG_BROKEN");
        assertThat(noTree.pathCode()).isEqualTo("P1");
        assertThat(noTree.configVersion()).isEqualTo(3);
        assertThat(noTree.functionCalls()).isEmpty();
        assertThat(step(noTree, "2. 路径条件 STANDARD").status()).isEqualTo("FAIL");
        assertThat(detail(noTree, "2. 路径条件 STANDARD")).isEqualTo("路径 P1 已启用但没有条件树");
        assertThat(detail(noTree, "失败原因")).isEqualTo("路径 P1 已启用但没有条件树 v3：启用的路径必须有自己的条件");

        // 配置损坏：两条启用的 DEFAULT 无法判定
        insertVersion(4, rule(p1, p2, defaultPath(ALWAYS),
                new C1PathRule.Path("DEFAULT", "默认路径", List.of(), 0, true, ALWAYS, "ANOTHER_DEFAULT", null)));
        var twoDefaults = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100"));
        assertThat(twoDefaults.reasonCode()).isEqualTo("C1_CONFIG_BROKEN");
        assertThat(twoDefaults.pathCode()).isNull();
        assertThat(twoDefaults.configVersion()).isEqualTo(4);
        assertThat(detail(twoDefaults, "2. 路径选择")).isEqualTo("C1 路径配置 v4 有 2 条启用的 DEFAULT 路径，无法判定");
        // 没有损坏的版本仍可回放
        assertThat(c1.evaluate(replay("C10002", "PROV-GD", "USD", "US", "100", 1)).reasonCode()).isEqualTo("PASS");
    }

    // ---------- 8：旧版本兼容 ----------

    @Test
    void legacyVersionsWithTheOldBooleanConditionsStillReplayAndUnreadableConfigIsRejected() throws Exception {
        // 旧版全局准入条件用到的字段：客户分类状态只存在于旧版配置里；两个旧版布尔字段不在字段库里，回放 v1 用的是内置定义
        metadata.create(new FieldMetadataController.Input("clientClassificationStatus", "客户分类状态", "ENUM", "EQ,NE", "C1", "COMPLETE,PENDING", true));
        // 升级前的全局准入条件（旧 JSON 不带 type，含两个旧版布尔条件），迁入后作为 v1 原样保留
        ObjectNode legacyPath = json.createObjectNode().put("pathCode", "DEFAULT").put("pathName", "默认路径（旧版全局准入条件）")
                .put("priority", 0).put("enabled", true).put("failureReasonCode", "C1_CONDITION_NOT_MATCHED").put("version", 1);
        legacyPath.putArray("providerCodes");
        legacyPath.set("conditionAst", json.readTree("{\"logic\":\"AND\",\"children\":[{\"field\":\"clientClassificationStatus\",\"operator\":\"EQ\",\"value\":\"COMPLETE\"},"
                + "{\"logic\":\"AND\",\"children\":[{\"field\":\"appetiteCurrencyEnabled\",\"operator\":\"EQ\",\"value\":true},"
                + "{\"field\":\"appetiteCountryEnabled\",\"operator\":\"EQ\",\"value\":true}]}]}"));
        insertVersion(1, json.createObjectNode().put("ruleCode", "C1").put("branchMode", "FIRST_MATCH").set("paths", json.createArrayNode().add(legacyPath)));

        // 旧语义：PROV-W 的准入规则只覆盖 USD，EUR 时 appetiteCurrencyEnabled = false → 不满足，沿用旧版原因码
        var old = c1.evaluate(simulate("C10002", "PROV-W", "EUR", "KG", "100"));
        assertThat(old.reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(old.configVersion()).isEqualTo(1);
        assertThat(old.pathCode()).isEqualTo("DEFAULT");
        assertThat(step(old, "2. 路径条件 STANDARD").status()).isEqualTo("FAIL");
        assertThat(detail(old, "2. 路径条件 STANDARD")).startsWith("路径 DEFAULT 的表达式不满足：").contains("appetiteCurrencyEnabled == true");
        assertThat(c1.evaluate(simulate("C10002", "PROV-W", "USD", "KG", "100")).reasonCode()).isEqualTo("PASS");

        // 发布新版（DEFAULT 路径不再有布尔条件）：最新版本里 EUR 停在必检的准入匹配；同一笔按 v1 回放结果不变
        assertThat(publish(baseline(), "新版路径配置")).isEqualTo(2);
        var latest = c1.evaluate(simulate("C10002", "PROV-W", "EUR", "KG", "100"));
        assertThat(latest.reasonCode()).isEqualTo("APPETITE_NOT_SET");
        assertThat(latest.configVersion()).isEqualTo(2);
        var replayed = c1.evaluate(replay("C10002", "PROV-W", "EUR", "KG", "100", 1));
        assertThat(replayed.reasonCode()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(replayed.configVersion()).isEqualTo(1);
        assertThat(detail(replayed, "0. 路径配置")).isEqualTo("已发布版本 v1（按指定版本回放），共 1 条路径，启用 1 条");

        // 已发布的版本读不出来：按配置损坏拒绝（不是通过，也不是「没有配置」）；更早的版本仍可回放
        insertVersion(3, "{not json");
        var broken = c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100"));
        assertThat(broken.passed()).isFalse();
        assertThat(broken.reasonCode()).isEqualTo("C1_CONFIG_BROKEN");
        assertThat(broken.configVersion()).isNull();
        assertThat(broken.functionCalls()).isEmpty();
        assertThat(broken.steps()).extracting(C1Service.Trace::step).containsExactly("0. 路径配置", "失败原因");
        assertThat(detail(broken, "0. 路径配置")).startsWith("C1 路径配置无法读取（最新版本）：");
        assertThat(c1.evaluate(replay("C10002", "PROV-GD", "USD", "US", "100", 2)).pathCode()).isEqualTo("P1");
    }

    @Test
    void legacyVersionReplaysTheSameWhetherOrNotTheFieldLibraryStillHasTheTwoBooleans() throws Exception {
        // v1 只有两个旧版布尔条件；字段库里并没有这两个字段（迁移不要求它们还在）
        ExpressionService.Node booleans = group("AND", field("appetiteCurrencyEnabled", "EQ", true), field("appetiteCountryEnabled", "EQ", true));
        insertVersion(1, rule(new C1PathRule.Path("DEFAULT", "默认路径（旧版全局准入条件）", List.of(), 0, true, booleans,
                "C1_CONDITION_NOT_MATCHED", 1)));

        // 内置定义只服务规则 C1：其它规则照旧只认字段库，这时字段库里没有它 → 非法字段
        assertThat(expressions.compile(booleans, "C1")).isEqualTo("((appetiteCurrencyEnabled == true) && (appetiteCountryEnabled == true))");
        assertThatThrownBy(() -> expressions.compile(booleans, "C2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("非法字段: appetiteCurrencyEnabled");

        // 字段库里没有这两个字段
        assertLegacyBooleanReplay();
        // 字段库里有、但已停用
        metadata.create(new FieldMetadataController.Input("appetiteCurrencyEnabled", "准入币种已启用", "BOOLEAN", "EQ,NE", "C1", null, true));
        metadata.create(new FieldMetadataController.Input("appetiteCountryEnabled", "准入国家已启用", "BOOLEAN", "EQ,NE", "C1", null, true));
        metadata.toggle("appetiteCurrencyEnabled");
        metadata.toggle("appetiteCountryEnabled");
        assertLegacyBooleanReplay();
        // 字段库里有且启用：仍按内置定义，不读字段库
        metadata.toggle("appetiteCurrencyEnabled");
        metadata.toggle("appetiteCountryEnabled");
        assertLegacyBooleanReplay();

        // 其它规则仍按字段库的定义校验（适用规则只有 C1），新草稿引用它们也仍被结构校验拦下：内置定义只是为了回放，不是给新配置用的
        assertThatThrownBy(() -> expressions.compile(booleans, "C2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("字段 appetiteCurrencyEnabled 不适用于当前规则或不支持该操作符");
        var draft = paths.compile(json.valueToTree(rule(defaultPath(group("AND", booleans, field("flowCategory", "NE", "NONE"))))));
        assertThat(draft.ok()).isFalse();
        // 两个旧版布尔条件各报一条
        assertThat(draft.errors()).hasSize(2).allSatisfy(error -> assertThat(error)
                .contains("路径 DEFAULT：条件树仍引用旧版布尔字段 appetite").contains("APPETITE_MATCH").contains("请删除这个条件"));
        assertThat(draft.errors().get(0)).contains("appetiteCurrencyEnabled");
        assertThat(draft.errors().get(1)).contains("appetiteCountryEnabled");
    }

    /** v1 回放的旧语义：布尔字段 = 该合作方在该流转类别下有覆盖这个币种 / 国家的有效准入规则（PROV-T1 是 ALL/ALL，PROV-W 只覆盖 USD）。 */
    private void assertLegacyBooleanReplay() {
        var pass = c1.evaluate(replay("C10002", "PROV-T1", "USD", "KG", "100", 1));
        assertThat(pass.reasonCode()).as("steps: %s", pass.steps()).isEqualTo("PASS");
        assertThat(pass.configVersion()).isEqualTo(1);
        assertThat(pass.pathCode()).isEqualTo("DEFAULT");
        assertThat(detail(pass, "2. 路径条件 STANDARD"))
                .isEqualTo("路径 DEFAULT 的条件满足；表达式 ((appetiteCurrencyEnabled == true) && (appetiteCountryEnabled == true))");
        var eur = c1.evaluate(replay("C10002", "PROV-W", "EUR", "KG", "100", 1));
        assertThat(eur.reasonCode()).as("steps: %s", eur.steps()).isEqualTo("C1_CONDITION_NOT_MATCHED");
        assertThat(step(eur, "2. 路径条件 STANDARD").status()).isEqualTo("FAIL");
        assertThat(detail(eur, "失败原因")).isEqualTo("STANDARD 不满足路径 DEFAULT 的条件，候选合作方被过滤");
        assertThat(c1.evaluate(replay("C10002", "PROV-W", "USD", "KG", "100", 1)).reasonCode()).isEqualTo("PASS");
        // 旧条件树里没有客户分类条件，必检仍对回放的版本生效：PENDING 客户的路径条件满足，停在必检的客户分类
        var pending = c1.evaluate(replay("CGD03", "PROV-T1", "USD", "KG", "100", 1));
        assertThat(pending.reasonCode()).as("steps: %s", pending.steps()).isEqualTo("CLIENT_NOT_CLASSIFIED");
        assertThat(step(pending, "2. 路径条件 STANDARD").status()).isEqualTo("PASS");
        assertThat(step(pending, "3. 客户分类").status()).isEqualTo("FAIL");
    }

    // ---------- 9：新增路径不改代码 ----------

    @Test
    void addingAPathForAnotherProviderIsConfigurationOnly() {
        publish(baseline(), "基线");
        assertThat(c1.evaluate(simulate("C10002", "PROV-T1", "USD", "KG", "100")).pathCode()).isEqualTo("DEFAULT");

        // 页面上新增一条绑定 PROV-T1 的路径并发布：执行器不用改代码
        assertThat(publish(rule(line("P1", "广东专线", List.of("PROV-GD"), 1, ALWAYS), line("P2", "Wise 专线", List.of("PROV-W"), 2, ALWAYS),
                line("P3", "T1 专线", List.of("PROV-T1"), 3, ALWAYS), defaultPath(ALWAYS)), "新增 T1 专线")).isEqualTo(2);
        var result = c1.evaluate(simulate("C10002", "PROV-T1", "USD", "KG", "100"));
        assertThat(result.reasonCode()).isEqualTo("PASS");
        assertThat(result.pathCode()).isEqualTo("P3");
        assertThat(result.pathName()).isEqualTo("T1 专线");
        assertThat(result.pathPriority()).isEqualTo(3);
        assertThat(result.defaultPathHit()).isFalse();
        assertThat(result.configVersion()).isEqualTo(2);
        assertThat(detail(result, "0. 路径配置")).isEqualTo("已发布版本 v2（最新），共 4 条路径，启用 4 条");
        // 已发生的决策按它当时的版本回放：那时 PROV-T1 还走 DEFAULT
        assertThat(c1.evaluate(replay("C10002", "PROV-T1", "USD", "KG", "100", 1)).pathCode()).isEqualTo("DEFAULT");
        // 其它合作方不受影响
        assertThat(c1.evaluate(simulate("C10002", "PROV-GD", "USD", "US", "100")).pathCode()).isEqualTo("P1");
    }

    // ---------- 请求校验 ----------

    @Test
    void invalidRequestsAreRequestErrorsAndAMissingClientProfileIsRejectedBeforePathSelection() {
        publish(baseline(), "基线");
        assertThatThrownBy(() -> c1.evaluate(new C1Service.Simulation(null, "PROV-GD", "USD", "US", BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("模拟请求字段缺失或金额无效");
        assertThatThrownBy(() -> c1.evaluate(new C1Service.Simulation("C10002", "PROV-GD", "USD", "US", new BigDecimal("-1"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("模拟请求字段缺失或金额无效");
        assertThatThrownBy(() -> c1.evaluate(simulate("C10002", "PROV-XX", "USD", "US", "1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("合作方不存在");
        assertThatThrownBy(() -> c1.evaluate(replay("C10002", "PROV-GD", "USD", "US", "1", 99)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("C1 路径配置没有版本 v99");

        var missing = c1.evaluate(simulate("NOBODY", "PROV-GD", "USD", "US", "1"));
        assertThat(missing.passed()).isFalse();
        assertThat(missing.reasonCode()).isEqualTo("CLIENT_NOT_FOUND");
        assertThat(missing.pathCode()).isNull();
        assertThat(missing.classificationStatus()).isNull();
        assertThat(missing.functionCalls()).isEmpty();
        assertThat(missing.steps()).extracting(C1Service.Trace::step).containsExactly("0. 路径配置", "1. 客户档案", "失败原因");
        assertThat(step(missing, "1. 客户档案").status()).isEqualTo("FAIL");
        assertThat(detail(missing, "1. 客户档案")).isEqualTo("客户 NOBODY 的档案不存在");
    }
}
