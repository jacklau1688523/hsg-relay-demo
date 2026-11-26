package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.domain.ScoreRuleConfig;
import com.hsg.relay.repo.BusinessFunctionRepository;
import com.hsg.relay.repo.ClientExclusionRepository;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.ScoreRuleConfigRepository;
import com.hsg.relay.repo.ScoreRuleVersionRepository;
import com.hsg.relay.service.ScoreRuleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static com.hsg.relay.routing.RoutingConfig.Reach;

/**
 * C5 评分端到端：C3 匹配行 → calculateValueDate → 已发布评分规则 → FIRST_MATCH → 分数。
 * 决策时间全部显式传入（2026-11-20 是周五，2026-11-21 周六，2026-11-23 周一），不依赖系统时间。
 */
@SpringBootTest
@Transactional
class C5ScoreServiceTest {
    private static final String RULE = "C5";
    private static final LocalDateTime FRI = LocalDateTime.parse("2026-11-20T10:00:00");            // 周五，早于截单 17:00
    private static final LocalDateTime FRI_AT_CUTOFF = LocalDateTime.parse("2026-11-20T17:00:00");
    private static final LocalDate NOV_20 = LocalDate.parse("2026-11-20");
    private static final LocalDate NOV_23 = LocalDate.parse("2026-11-23");
    // 新发布的版本默认「发布当时」生效，所以要用新版本评分，决策时间不能早于发布时间。
    // 用一个足够远的固定周五（2050-01-07），结果不随测试的运行日期变化；而过去的决策时间（2026-11-20）按回放语义仍用 v1。
    private static final LocalDateTime FRI_2050 = LocalDateTime.parse("2050-01-07T10:00:00");
    private static final LocalDateTime FRI_2050_AT_CUTOFF = LocalDateTime.parse("2050-01-07T17:00:00");
    private static final LocalDate JAN_7_2050 = LocalDate.of(2050, 1, 7);

    @Autowired C5ScoreService scoring;
    @Autowired ScoreRuleService rules;
    @Autowired C5Service c5;
    @Autowired C3Service c3;
    @Autowired C3ConditionService c3Conditions;
    @Autowired RoutingService routing;
    @Autowired ObjectMapper json;
    @Autowired FieldDefinitionRepository fields;
    @Autowired BusinessFunctionRepository functions;
    @Autowired ScoreRuleConfigRepository configs;
    @Autowired ScoreRuleVersionRepository versions;
    @Autowired ClientExclusionRepository exclusions;

    private static Reach reach(int offset, LocalDate... holidays) {
        return reach("Asia/Hong_Kong", offset, holidays);
    }

    private static Reach reach(String zone, int offset, LocalDate... holidays) {
        return new Reach("PROV-T1", "USD", "ALL", "ALL", List.of(), List.of("SHA"), zone, LocalTime.of(17, 0), offset,
                BigDecimal.TEN, BigDecimal.TEN, List.of(holidays), true);
    }

    private static ReachMatch match(Reach reach) {
        return new ReachMatch(0, reach, 3, 7);
    }

    private C5ScoreService.Outcome score(Reach reach, LocalDateTime at, String requested) {
        return scoring.evaluate(match(reach), "PROV-T1", null, "USD", requested == null ? null : LocalDate.parse(requested), at);
    }

    /** 已发布的默认规则（v1）的 JSON，测试在它基础上改动。 */
    private ObjectNode defaultTree() {
        return (ObjectNode) json.valueToTree(rules.version(RULE, 1).rule());
    }

    private ObjectNode ruleWithOneGroup(String operator, int value) {
        var tree = json.createObjectNode();
        tree.put("ruleCode", RULE).put("ruleType", "SCORE").put("matchMode", "FIRST_MATCH").put("enabled", true);
        tree.putObject("scoreRange").put("min", 0).put("max", 100);
        group(tree.putArray("groups"), "ONLY-ZERO", "只认按时", 1, operator, value, 100);
        return tree;
    }

    private static ObjectNode group(ArrayNode groups, String code, String name, int priority, String operator, int value, int score) {
        ObjectNode node = groups.addObject();
        node.put("groupCode", code).put("name", name).put("priority", priority).put("enabled", true);
        node.putObject("condition").put("type", "FIELD").put("field", "delayBusinessDays").put("operator", operator).put("value", value);
        node.putObject("result").put("type", "SCORE").put("score", score);
        return node;
    }

    private static void setScore(ObjectNode rule, String groupCode, int score) {
        for (JsonNode g : rule.get("groups"))
            if (g.get("groupCode").asText().equals(groupCode)) ((ObjectNode) g.get("result")).put("score", score);
    }

    private ScoreRuleService.PublishedView publish(ObjectNode rule, Boolean acknowledge) {
        rules.saveDraft(RULE, rule, null, "tester", C5ScoreService.INPUT_FIELDS);
        return rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", "test", null, acknowledge),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN);
    }

    /** 不经过发布、直接指定一个版本号来执行（用于验证 FIRST_MATCH 行为本身）。 */
    private ScoreRuleService.PublishedView manual(ObjectNode rule) {
        return new ScoreRuleService.PublishedView(99, rules.parse(rule, RULE), FRI, FRI, "tester", null);
    }

    private C5Service.Input simInput(String requested, String at) {
        return new C5Service.Input("PROV-T1", "USD", "US", null, "CHASUS33", "SHA", LocalDate.parse(requested), ZonedDateTime.parse(at));
    }

    private RoutingService.Candidate candidate(String region, String at) throws Exception {
        var request = json.readValue("""
                {"paymentId":"C5-SCORE","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                 "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryRegion":"%s","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                 "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                 "requestedValueDate":"2026-11-20","at":"%s"}
                """.formatted(region, at), RoutingService.Request.class);
        return routing.simulate(request).candidates().stream().filter(c -> c.providerCode.equals("PROV-T1")).findFirst().orElseThrow();
    }

    // ---------- 初始化 ----------

    @Test
    void seedPublishesTheDefaultRuleAndMetadata() {
        var view = rules.view(RULE);
        assertThat(view.ruleType()).isEqualTo("SCORE");
        assertThat(view.matchMode()).isEqualTo("FIRST_MATCH");
        assertThat(view.published().version()).isEqualTo(1);
        assertThat(view.published().rule().groups()).extracting(ScoreRule.Group::groupCode).containsExactly("C5-ON-TIME", "C5-ONE-DAY", "C5-TWO-PLUS");
        assertThat(view.published().rule().groups()).extracting(g -> g.result().score()).containsExactly(100, 50, 0);
        assertThat(view.draftChanged()).isFalse();
        assertThat(fields.findById("delayBusinessDays").orElseThrow().allowedOperators).isEqualTo("EQ,NE,GT,GTE,LT,LTE");
        for (String code : C5ScoreService.INPUT_FIELDS) assertThat(fields.findById(code)).as(code).isPresent();
        assertThat(fields.findById(C5ScoreService.OUTPUT_FIELD).orElseThrow().dataType).isEqualTo("INTEGER");
        assertThat(scoring.requiredFunctions()).containsKey("calculateValueDate");
    }

    // ---------- 默认评分：0 → 100，1 → 50，≥2 → 0 ----------

    @Test
    void futureRequestThatTheProviderCanMeetScores100() {
        var outcome = score(reach(5), FRI, "2026-12-10");
        assertThat(outcome.calculation().earliestValueDate()).isEqualTo(LocalDate.parse("2026-11-27"));
        assertThat(outcome.calculation().candidateValueDate()).isEqualTo(LocalDate.parse("2026-12-10"));
        assertThat(outcome.calculation().delayBusinessDays()).isZero();
        assertThat(outcome.score().status()).isEqualTo("SCORED");
        assertThat(outcome.score().score()).isEqualTo(100);
        assertThat(outcome.score().matchedGroupCode()).isEqualTo("C5-ON-TIME");
        assertThat(outcome.score().ruleVersion()).isEqualTo(1);
    }

    @Test
    void oneBusinessDayLateScores50() {
        // 周五恰好等于截单时间（不算早于）→ 下一工作日周一；偏移 0 → 最早起息日 11-23，比请求日晚 1 个工作日
        var outcome = score(reach(0), FRI_AT_CUTOFF, "2026-11-20");
        assertThat(outcome.calculation().beforeCutoff()).isFalse();
        assertThat(outcome.calculation().earliestValueDate()).isEqualTo(NOV_23);
        assertThat(outcome.calculation().candidateValueDate()).isEqualTo(NOV_23);
        assertThat(outcome.calculation().delayBusinessDays()).isEqualTo(1);
        assertThat(outcome.score().score()).isEqualTo(50);
        assertThat(outcome.score().matchedGroupCode()).isEqualTo("C5-ONE-DAY");
    }

    @Test
    void twoOrMoreBusinessDaysLateScores0() {
        var two = score(reach(1), FRI_AT_CUTOFF, "2026-11-20");
        assertThat(two.calculation().earliestValueDate()).isEqualTo(LocalDate.parse("2026-11-24"));
        assertThat(two.calculation().delayBusinessDays()).isEqualTo(2);
        assertThat(two.score().score()).isZero();
        assertThat(two.score().matchedGroupCode()).isEqualTo("C5-TWO-PLUS");
        var six = score(reach(5), FRI_AT_CUTOFF, "2026-11-20");
        assertThat(six.calculation().delayBusinessDays()).isEqualTo(6);
        assertThat(six.score().score()).isZero();
    }

    @Test
    void beforeAtAndAfterCutoffAndWeekendAreRecordedAsScoreInputs() {
        var before = score(reach(0), FRI, "2026-11-20");
        assertThat(before.calculation().currentBusinessDay()).isTrue();
        assertThat(before.calculation().beforeCutoff()).isTrue();
        assertThat(before.calculation().earliestValueDate()).isEqualTo(NOV_20);
        assertThat(before.score().score()).isEqualTo(100);
        var at = score(reach(0), FRI_AT_CUTOFF, "2026-11-20");
        assertThat(at.calculation().currentBusinessDay()).isTrue();
        assertThat(at.calculation().beforeCutoff()).isFalse();
        assertThat(at.calculation().rollReason()).isEqualTo("AT_OR_AFTER_CUTOFF");
        var after = score(reach(0), LocalDateTime.parse("2026-11-20T18:00:00"), "2026-11-20");
        assertThat(after.calculation().earliestValueDate()).isEqualTo(NOV_23);
        assertThat(after.score().score()).isEqualTo(50);
        // 周六：不是工作日，与截单时间无关
        var saturday = score(reach(0), LocalDateTime.parse("2026-11-21T10:00:00"), "2026-11-21");
        assertThat(saturday.calculation().currentBusinessDay()).isFalse();
        assertThat(saturday.calculation().rollReason()).isEqualTo("NOT_BUSINESS_DAY");
        assertThat(saturday.calculation().earliestValueDate()).isEqualTo(NOV_23);
        assertThat(saturday.score().score()).isEqualTo(50);
        // 合作方节假日落在基准日之后：起息日继续顺延
        var holiday = score(reach(0, LocalDate.parse("2026-11-23")), FRI_AT_CUTOFF, "2026-11-20");
        assertThat(holiday.calculation().earliestValueDate()).isEqualTo(LocalDate.parse("2026-11-24"));
        assertThat(holiday.calculation().delayBusinessDays()).isEqualTo(1);        // 周一是节假日，不计
        assertThat(holiday.score().score()).isEqualTo(50);
    }

    @Test
    void cutoffIsJudgedInTheReachRowTimeZone() {
        // 香港周五 20:00 = 纽约周五 07:00，早于纽约截单 17:00：当日起息（若误用香港时间判断则已过截单）
        var morningInNewYork = score(reach("America/New_York", 0), LocalDateTime.parse("2026-11-20T20:00:00"), "2026-11-20");
        assertThat(morningInNewYork.calculation().beforeCutoff()).isTrue();
        assertThat(morningInNewYork.calculation().earliestValueDate()).isEqualTo(NOV_20);
        assertThat(morningInNewYork.score().score()).isEqualTo(100);
        // 香港周五 08:00 = 纽约周四 19:00，已过截单；偏移 1 → 纽约下周一；默认请求日取香港日期 11-20 → 延迟 1 个工作日
        var eveningInNewYork = scoring.evaluate(match(reach("America/New_York", 1)), "PROV-T1", null, "USD", null, LocalDateTime.parse("2026-11-20T08:00:00"));
        assertThat(eveningInNewYork.calculation().beforeCutoff()).isFalse();
        assertThat(eveningInNewYork.calculation().requestedValueDate()).isEqualTo(NOV_20);
        assertThat(eveningInNewYork.calculation().earliestValueDate()).isEqualTo(NOV_23);
        assertThat(eveningInNewYork.score().score()).isEqualTo(50);
    }

    @Test
    void yearEndSkipsTheNewYearHoliday() {
        // 2026-12-31 周四已过截单；2027-01-01 周五是节假日；偏移 1 → 2027-01-05，比请求日晚 2 个工作日
        var outcome = score(reach(1, LocalDate.parse("2027-01-01")), LocalDateTime.parse("2026-12-31T18:00:00"), "2026-12-31");
        assertThat(outcome.calculation().earliestValueDate()).isEqualTo(LocalDate.parse("2027-01-05"));
        assertThat(outcome.calculation().delayBusinessDays()).isEqualTo(2);
        assertThat(outcome.score().matchedGroupCode()).isEqualTo("C5-TWO-PLUS");
        assertThat(outcome.score().score()).isZero();
    }

    @Test
    void missingRequestedDateDefaultsToTheHongKongDateAndIsRecorded() {
        var outcome = score(reach(0), FRI, null);
        assertThat(outcome.requestedValueDateDefaulted()).isTrue();
        assertThat(outcome.calculation().requestedValueDate()).isEqualTo(NOV_20);
        assertThat(outcome.score().score()).isEqualTo(100);
        assertThat(outcome.score().details().get("function")).isNotNull();
        assertThat(outcome.score().details().get("reach")).isNotNull();
        assertThat(score(reach(0), FRI, "2026-11-20").requestedValueDateDefaulted()).isFalse();
    }

    // ---------- FIRST_MATCH 与无匹配 ----------

    @Test
    void firstMatchFollowsPriorityNotListOrder() {
        var tree = json.createObjectNode();
        tree.put("ruleCode", RULE).put("ruleType", "SCORE").put("matchMode", "FIRST_MATCH").put("enabled", true);
        var groups = tree.putArray("groups");
        group(groups, "EXACT-FIVE", "恰好 5 个工作日", 1, "EQ", 5, 100);      // 优先级最高，排在前面
        group(groups, "ANY", "任何延迟", 10, "GTE", 0, 60);
        var prepared = manual(tree);
        // 11-13 周五早于截单、偏移 0 → 最早 11-13；默认请求日 11-13 → 延迟 0：EXACT-FIVE 未命中，ANY 命中
        var onTime = scoring.evaluate(match(reach(0)), "PROV-T1", null, "USD", null, LocalDateTime.parse("2026-11-13T10:00:00"), prepared);
        assertThat(onTime.score().matchedGroupCode()).isEqualTo("ANY");
        assertThat(onTime.score().groups()).extracting(ScoreResult.GroupOutcome::matched).containsExactly(false, true);
        // 11-13 周五早于截单、偏移 5 → 最早 11-20；请求 11-13 → 延迟 5：EXACT-FIVE 先命中，ANY 不再判断
        var five = scoring.evaluate(match(reach(5)), "PROV-T1", null, "USD", LocalDate.parse("2026-11-13"), LocalDateTime.parse("2026-11-13T10:00:00"), prepared);
        assertThat(five.calculation().delayBusinessDays()).isEqualTo(5);
        assertThat(five.score().matchedGroupCode()).isEqualTo("EXACT-FIVE");
        assertThat(five.score().score()).isEqualTo(100);
        assertThat(five.score().groups()).hasSize(1);
    }

    @Test
    void noMatchingGroupIsReportedInsteadOfFullMarks() {
        var outcome = scoring.evaluate(match(reach(0)), "PROV-T1", null, "USD", NOV_20, FRI_AT_CUTOFF, manual(ruleWithOneGroup("EQ", 0)));
        assertThat(outcome.calculation().delayBusinessDays()).isEqualTo(1);
        assertThat(outcome.score().status()).isEqualTo("NOT_MATCHED");
        assertThat(outcome.score().score()).isNull();
        assertThat(outcome.score().scored()).isFalse();
        assertThat(outcome.score().message()).contains("不默认满分");
        // 这种规则也发布不了：发布校验报覆盖缺口
        assertThat(String.join("\n", scoring.check(ruleWithOneGroup("EQ", 0)).errors())).contains("覆盖缺口");
    }

    @Test
    void unpublishedDraftDoesNotAffectScoring() {
        var tree = defaultTree();
        setScore(tree, "C5-ON-TIME", 10);
        var view = rules.saveDraft(RULE, tree, null, "tester", C5ScoreService.INPUT_FIELDS);
        assertThat(view.draftChanged()).isTrue();
        assertThat(score(reach(0), FRI_2050, "2050-01-07").score().score()).isEqualTo(100);
        rules.publish(RULE, new ScoreRuleService.PublishRequest(view.draft().version(), "tester", "降分", null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN);
        assertThat(rules.view(RULE).published().version()).isEqualTo(2);
        assertThat(score(reach(0), FRI_2050, "2050-01-07").score().score()).isEqualTo(10);   // 发布之后的决策用 v2
        assertThat(score(reach(0), FRI_2050, "2050-01-07").score().ruleVersion()).isEqualTo(2);
        // 发布之前的决策时间按回放语义仍用 v1：新版本不改变已发生决策的结果
        assertThat(score(reach(0), FRI, "2026-11-20").score().ruleVersion()).isEqualTo(1);
        assertThat(score(reach(0), FRI, "2026-11-20").score().score()).isEqualTo(100);
        assertThat(rules.view(RULE).draftChanged()).isFalse();
    }

    // ---------- 发布校验与版本 ----------

    @Test
    void scoreAbove100OrBelow0IsRejectedAndNothingIsPublished() {
        var high = defaultTree();
        setScore(high, "C5-ONE-DAY", 101);
        assertThatThrownBy(() -> rules.saveDraft(RULE, high, null, "tester", C5ScoreService.INPUT_FIELDS)).hasMessageContaining("101").hasMessageContaining("超出合法范围");
        // 绕过页面直接把 -1 写进草稿，发布同样被拒
        ScoreRuleConfig config = configs.findById(RULE).orElseThrow();
        var low = defaultTree();
        setScore(low, "C5-ONE-DAY", -1);
        config.draftJson = low.toString();
        configs.save(config);
        assertThatThrownBy(() -> rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", null, null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("-1").hasMessageContaining("超出合法范围");
        assertThat(versions.count()).isEqualTo(1);
        assertThat(rules.view(RULE).published().version()).isEqualTo(1);
        // 小数分数同样不接受（Jackson 默认会把 50.5 截成 50，所以先在 JSON 树上核对）
        var fraction = defaultTree().toString().replace("\"score\":50", "\"score\":50.5");
        assertThatThrownBy(() -> scoring.check(json.readTree(new StringReader(fraction)))).hasMessageContaining("必须是整数");
    }

    @Test
    void coverageGapAndConflictsBlockPublishing() {
        var gap = defaultTree();
        ((ArrayNode) gap.get("groups")).remove(2);
        rules.saveDraft(RULE, gap, null, "tester", C5ScoreService.INPUT_FIELDS);
        assertThatThrownBy(() -> rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", null, null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("覆盖缺口");
        // 两个评分组条件完全相同：后者永远不会命中
        var identical = defaultTree();
        ((ObjectNode) identical.get("groups").get(2)).putObject("condition").put("type", "FIELD").put("field", "delayBusinessDays").put("operator", "EQ").put("value", 1);
        assertThat(String.join("\n", scoring.check(identical).errors())).contains("条件完全相同");
        // 优先级必须唯一，否则命中顺序不确定
        var samePriority = defaultTree();
        ((ObjectNode) samePriority.get("groups").get(1)).put("priority", 1);
        assertThatThrownBy(() -> rules.saveDraft(RULE, samePriority, null, "tester", C5ScoreService.INPUT_FIELDS)).hasMessageContaining("优先级重复");
        assertThat(versions.count()).isEqualTo(1);
    }

    @Test
    void publishingRequiresAnOperatorAndWritesAudit() {
        var tree = defaultTree();
        setScore(tree, "C5-ONE-DAY", 45);
        assertThatThrownBy(() -> rules.saveDraft(RULE, tree, null, " ", C5ScoreService.INPUT_FIELDS)).hasMessageContaining("操作人");
        rules.saveDraft(RULE, tree, null, "tester", C5ScoreService.INPUT_FIELDS);
        assertThatThrownBy(() -> rules.publish(RULE, new ScoreRuleService.PublishRequest(null, null, null, null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("操作人");
        // 草稿已被他人修改（草稿版本号不对）时不能发布
        assertThatThrownBy(() -> rules.publish(RULE, new ScoreRuleService.PublishRequest(999, "tester", null, null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("已被他人修改");
        rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "auditor.lau", "延迟一天调整为 45 分", null, true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN);
        var audits = rules.audits(RULE);
        assertThat(audits).extracting(a -> a.operation).contains("PUBLISH", "SAVE_DRAFT");
        assertThat(audits.get(0).operatorName).isEqualTo("auditor.lau");
        assertThat(audits.get(0).afterJson).contains("\"score\":45");
        assertThat(score(reach(0), FRI_2050_AT_CUTOFF, "2050-01-07").score().score()).isEqualTo(45);
    }

    @Test
    void publishedVersionsAreImmutableAndCanBeReplayed() {
        var tree = defaultTree();
        setScore(tree, "C5-ON-TIME", 90);
        assertThat(publish(tree, true).version()).isEqualTo(2);
        assertThat(rules.version(RULE, 1).rule().groups().get(0).result().score()).isEqualTo(100);
        assertThat(rules.version(RULE, 2).rule().groups().get(0).result().score()).isEqualTo(90);
        assertThat(rules.version(RULE, 1).rule().groups().get(0).version()).isEqualTo(1);
        assertThat(score(reach(0), FRI_2050, "2050-01-07").score().score()).isEqualTo(90);
        assertThat(rules.history(RULE)).extracting(ScoreRuleService.VersionSummary::version).containsExactly(2, 1);
        // 演示覆盖行（PROV-T1 USD）偏移 1：周五决策、请求下周一 → 延迟 0
        var input = simInput("2050-01-10", "2050-01-07T10:00:00+08:00");
        var replay = c5.simulate(input, 1);
        assertThat(replay.replay()).isTrue();
        assertThat(replay.score()).isEqualTo(100);
        assertThat(replay.scoreRuleVersion()).isEqualTo(1);
        assertThat(c5.simulate(input).score()).isEqualTo(90);
        assertThatThrownBy(() -> c5.simulate(input, 3)).hasMessageContaining("没有版本");
        // 审计保留了发布前的版本内容
        assertThat(rules.audits(RULE).get(0).beforeJson).contains("\"score\":100");
    }

    @Test
    void effectiveTimeDecidesWhichVersionAppliesAndIsValidated() {
        // 生效时间早于上一版本（默认版本自始生效）：拒绝
        var tree = defaultTree();
        setScore(tree, "C5-ON-TIME", 91);
        setScore(tree, "C5-ONE-DAY", 92);
        setScore(tree, "C5-TWO-PLUS", 93);
        rules.saveDraft(RULE, tree, null, "tester", C5ScoreService.INPUT_FIELDS);
        assertThatThrownBy(() -> rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", null, rules.now().minusDays(30), true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("生效时间不能早于");
        assertThatThrownBy(() -> rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", null, rules.now().plusYears(2), true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("一年");
        // 一小时后生效：生效前仍用 v1，生效后用 v2
        var later = rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", "一小时后生效", rules.now().plusHours(1), true),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN);
        assertThat(later.version()).isEqualTo(2);
        assertThat(rules.view(RULE).publishedAhead()).isTrue();
        var now = rules.now();
        var before = scoring.evaluate(match(reach(0)), "PROV-T1", null, "USD", null, now);
        var after = scoring.evaluate(match(reach(0)), "PROV-T1", null, "USD", null, now.plusHours(2));
        // 真实「现在」是星期几、是否过截单都不定，所以 v2 的三组分数都改成独有的数字，用分数范围反证用的是哪个版本
        assertThat(before.score().ruleVersion()).isEqualTo(1);
        assertThat(before.score().score()).isIn(100, 50, 0);
        assertThat(after.score().ruleVersion()).isEqualTo(2);
        assertThat(after.score().score()).isIn(91, 92, 93);
        // 决策时间早于任何版本的生效时间：没有可用版本，不给分数，但日期照常计算
        var early = scoring.evaluate(match(reach(0)), "PROV-T1", null, "USD", NOV_20, LocalDateTime.of(1999, 12, 31, 10, 0));
        assertThat(early.score().status()).isEqualTo("NOT_PUBLISHED");
        assertThat(early.score().score()).isNull();
        assertThat(early.calculation().earliestValueDate()).isEqualTo(LocalDate.of(1999, 12, 31));   // 1999-12-31 是周五
    }

    // ---------- FIELD 以外的条件：DATE 与 FUNCTION ----------

    @Test
    void dateConditionInAGroupScoresChristmasAndNeedsAcknowledgementToPublish() {
        var tree = defaultTree();
        var christmas = ((ArrayNode) tree.get("groups")).insertObject(0);
        christmas.put("groupCode", "C5-CHRISTMAS").put("name", "圣诞节起息").put("priority", 0).put("enabled", true);
        christmas.putObject("condition").put("type", "FIELD").put("field", "candidateValueDate").put("operator", "EQ").put("value", "2050-12-25");
        christmas.putObject("result").put("type", "SCORE").put("score", 5);
        var check = scoring.check(tree);
        assertThat(check.ok()).isTrue();
        assertThat(check.warnings()).isNotEmpty();
        rules.saveDraft(RULE, tree, null, "tester", C5ScoreService.INPUT_FIELDS);
        assertThatThrownBy(() -> rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", null, null, false),
                C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN)).hasMessageContaining("知悉");
        rules.publish(RULE, new ScoreRuleService.PublishRequest(null, "tester", null, null, true), C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN);
        assertThat(score(reach(0), FRI_2050, "2050-12-25").score().matchedGroupCode()).isEqualTo("C5-CHRISTMAS");
        assertThat(score(reach(0), FRI_2050, "2050-12-25").score().score()).isEqualTo(5);
        assertThat(score(reach(0), FRI_2050, "2050-12-24").score().matchedGroupCode()).isEqualTo("C5-ON-TIME");
    }

    @Test
    void functionConditionInAGroupRunsThroughTheFunctionLibrary() {
        // 函数库里把 clientExcluded 的适用规则加上 C5（只在本测试事务内，回滚后恢复），它就能进入 C5 评分组的条件
        var function = functions.findById("clientExcluded").orElseThrow();
        function.allowedRules = "C1,C5";
        functions.save(function);
        exclusions.save(new ClientExclusion("C10002", "PROV-T1", "TEST", FRI_2050.minusDays(1), FRI_2050.plusDays(1)));
        var tree = defaultTree();
        var excluded = ((ArrayNode) tree.get("groups")).insertObject(0);
        excluded.put("groupCode", "C5-EXCLUDED-CLIENT").put("name", "客户在合作方排除名单").put("priority", 0).put("enabled", true);
        var condition = excluded.putObject("condition");
        condition.put("type", "GROUP").put("logic", "AND");
        var children = condition.putArray("children");
        var call = children.addObject();
        call.put("type", "FUNCTION").put("functionCode", "clientExcluded").put("operator", "EQ").put("value", true);
        call.putObject("arguments").put("providerCode", "$candidate.providerCode").put("clientId", "$transaction.clientId");
        children.addObject().put("type", "FIELD").put("field", "delayBusinessDays").put("operator", "GTE").put("value", 0);
        excluded.putObject("result").put("type", "SCORE").put("score", 1);
        assertThat(publish(tree, true).version()).isEqualTo(2);
        var listed = scoring.evaluate(match(reach(0)), "PROV-T1", "C10002", "USD", JAN_7_2050, FRI_2050);
        assertThat(listed.score().matchedGroupCode()).isEqualTo("C5-EXCLUDED-CLIENT");
        assertThat(listed.score().score()).isEqualTo(1);
        assertThat(listed.score().groups()).hasSize(1);
        var notListed = scoring.evaluate(match(reach(0)), "PROV-T1", "C10003", "USD", JAN_7_2050, FRI_2050);
        assertThat(notListed.score().groups()).extracting(ScoreResult.GroupOutcome::matched).containsExactly(false, true);
        assertThat(notListed.score().matchedGroupCode()).isEqualTo("C5-ON-TIME");
        // 函数没有登记 C5 时不能进入评分组
        function.allowedRules = "C1";
        functions.save(function);
        assertThatThrownBy(() -> rules.saveDraft(RULE, tree, null, "tester", C5ScoreService.INPUT_FIELDS)).hasMessageContaining("不适用于当前规则");
    }

    // ---------- 草稿试算 ----------

    @Test
    void draftSimulationUsesTheUnpublishedDraftAndWritesNothing() {
        var tree = defaultTree();
        setScore(tree, "C5-ONE-DAY", 77);
        var input = simInput("2026-11-23", "2026-11-20T17:00:00+08:00");            // 演示覆盖行偏移 1 → 延迟 1 个工作日
        var byDraft = c5.simulateDraft(input, tree);
        assertThat(byDraft.draft()).isTrue();
        assertThat(byDraft.replay()).isFalse();
        assertThat(byDraft.scoreRuleVersion()).isNull();
        assertThat(byDraft.score()).isEqualTo(77);
        assertThat(byDraft.groupResults()).extracting(ScoreResult.GroupOutcome::matched).containsExactly(false, true);
        assertThat(byDraft.note()).contains("草稿未发布");
        // 正式试算只认已发布版本：仍是 50 分
        var byPublished = c5.simulate(input);
        assertThat(byPublished.draft()).isFalse();
        assertThat(byPublished.score()).isEqualTo(50);
        assertThat(byPublished.scoreRuleVersion()).isEqualTo(1);
        // 什么都不写：没有新版本，草稿也没变
        assertThat(versions.count()).isEqualTo(1);
        assertThat(rules.view(RULE).draftChanged()).isFalse();
    }

    @Test
    void draftSimulationStillChecksTheRuleStructure() {
        var input = simInput("2026-11-23", "2026-11-20T17:00:00+08:00");
        // 有覆盖缺口的草稿也能试算：延迟 1 个工作日，没有任何评分组命中 → NOT_MATCHED，不给分
        var gap = c5.simulateDraft(input, ruleWithOneGroup("EQ", 0));
        assertThat(gap.scoreStatus()).isEqualTo("NOT_MATCHED");
        assertThat(gap.score()).isNull();
        assertThat(gap.note()).contains("不默认满分");
        // 分数超出范围：结构校验拒绝
        var bad = ruleWithOneGroup("EQ", 0);
        ((ObjectNode) bad.get("groups").get(0).get("result")).put("score", 101);
        assertThatThrownBy(() -> c5.simulateDraft(input, bad)).hasMessageContaining("101").hasMessageContaining("超出合法范围");
        assertThatThrownBy(() -> c5.simulateDraft(input, null)).hasMessageContaining("必填");
    }

    // ---------- C3 → C5 衔接、路由输出 ----------

    @Test
    void c3MostPreciseRowIsHandedToC5() throws Exception {
        // 同一合作方、币种：通用行（截单 17:00、偏移 1）与指定地区行（截单 23:59、偏移 0）的起息结果不同
        var specific = new Reach("PROV-T1", "USD", "US", "US-NY", List.of(), List.of("SHA"), "Asia/Hong_Kong", LocalTime.of(23, 59), 0,
                BigDecimal.TEN, BigDecimal.TEN, List.of(), true);
        var saved = c3.save(new C3Service.Save(c3.list().revision(), null, "ALL", specific));
        int rowIndex = saved.rows().size() - 1;
        var matched = candidate("US-NY", "2026-11-20T11:00:00+08:00");
        assertThat(matched.c5.earliestValueDate()).isEqualTo(NOV_20);              // 指定行：11:00 早于 23:59
        assertThat(matched.c5Status).isEqualTo("SCORED");
        assertThat(matched.c5Score).isEqualTo(100);
        assertThat(matched.c5ScoreGroup).isEqualTo("C5-ON-TIME");
        assertThat(matched.matchedReachRowId).isEqualTo(rowIndex);
        assertThat(matched.matchedReachRuleVersion).isEqualTo(c3Conditions.get().versionNo);
        assertThat(matched.checks).filteredOn(x -> x.code().equals("C3")).first().extracting(RoutingService.Check::detail)
                .isEqualTo(specific);
        // 地区不在指定行的范围：退回通用行，C5 也用通用行的截单时间和偏移
        var generic = candidate("US-CA", "2026-11-20T11:00:00+08:00");
        assertThat(generic.c5.earliestValueDate()).isEqualTo(NOV_23);
        assertThat(generic.c5Score).isEqualTo(50);
        assertThat(generic.matchedReachRowId).isZero();
    }

    @Test
    void noReachMeansNoScoreAndNoDateCalculation() {
        var outcome = scoring.evaluate(null, "PROV-GD", null, "USD", NOV_20, FRI);
        assertThat(outcome.calculation()).isNull();
        assertThat(outcome.score().status()).isEqualTo("NO_REACH");
        assertThat(outcome.score().score()).isNull();
        var sim = c5.simulate(new C5Service.Input("PROV-GD", "USD", "CN", null, "ICBKCNBJXXX", "SHA", NOV_20, ZonedDateTime.parse("2026-11-20T10:00:00+08:00")));
        // PROV-GD 的演示覆盖行只覆盖 CN-GD：受益行地区为空时 C3 未匹配
        assertThat(sim.status()).isEqualTo("NO_REACH");
        assertThat(sim.score()).isNull();
        assertThat(sim.calculation()).isNull();
        assertThat(sim.note()).contains("不是 C5 拒绝");
    }

    @Test
    void zeroScoreKeepsTheCandidateRoutable() throws Exception {
        var candidate = candidate("US", "2026-11-20T17:00:00+08:00");                // 等于截单 → 延迟 2 个工作日 → 0 分
        assertThat(candidate.c5Score).isZero();
        assertThat(candidate.c5Status).isEqualTo("SCORED");
        assertThat(candidate.c5ScoreGroup).isEqualTo("C5-TWO-PLUS");
        assertThat(candidate.passed).isTrue();
        assertThat(candidate.rank).isNotNull();
        assertThat(candidate.scores.get("C5")).isEqualByComparingTo("0");
        assertThat(candidate.checks).extracting(RoutingService.Check::code).doesNotContain("C5");
        var onTime = candidate("US", "2026-11-20T10:00:00+08:00");                    // 通用行偏移 1 → 延迟 1 → 50
        assertThat(onTime.c5Score).isEqualTo(50);
        assertThat(onTime.c5ScoreGroup).isEqualTo("C5-ONE-DAY");
        assertThat(onTime.c5RuleVersion).isEqualTo(1);
        assertThat(onTime.valueDate).isEqualTo(NOV_23);
    }

    @Test
    void simulateReportsEveryGroupOutcome() {
        var sim = c5.simulate(simInput("2026-11-23", "2026-11-20T10:00:00+08:00"));
        assertThat(sim.status()).isEqualTo("SCORED");
        assertThat(sim.score()).isEqualTo(100);
        assertThat(sim.matchedScoreGroup()).isEqualTo("C5-ON-TIME");
        assertThat(sim.scoreRuleVersion()).isEqualTo(1);
        assertThat(sim.c3ConditionVersion()).isEqualTo(c3Conditions.get().versionNo);
        assertThat(sim.selectedIndex()).isZero();
        assertThat(sim.groupResults()).extracting(ScoreResult.GroupOutcome::groupCode).containsExactly("C5-ON-TIME");
        var late = c5.simulate(simInput("2026-11-20", "2026-11-20T10:00:00+08:00"));
        assertThat(late.groupResults()).extracting(ScoreResult.GroupOutcome::matched).containsExactly(false, true);
        assertThat(late.score()).isEqualTo(50);
    }
}
