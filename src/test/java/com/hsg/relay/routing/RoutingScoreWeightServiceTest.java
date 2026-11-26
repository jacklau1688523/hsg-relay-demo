package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.domain.RoutingScoreWeight;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.routing.RoutingScoreWeightService.*;
import com.hsg.relay.routing.ScoreWeightMatcher.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hsg.relay.domain.RoutingScoreWeight.*;
import static com.hsg.relay.routing.RoutingScoreWeightService.SEED_EFFECTIVE_FROM;
import static org.assertj.core.api.Assertions.*;

/** 权重配置管理：默认值、校验、版本与发布时间、停用、审计、层级字典，以及它和路由（P1 生效、无配置）的关系。 */
@SpringBootTest
class RoutingScoreWeightServiceTest extends ScoreWeightCleanSlate {
    static final String OPS = "ops.weight";

    @Autowired
    FieldDefinitionRepository fields;
    @Autowired
    RoutingService routing;
    @Autowired
    RoutingDocumentRepository docs;
    @Autowired
    RuleAuditRepository allAudits;
    @Autowired
    ObjectMapper json;
    @Autowired
    PlatformTransactionManager tx;
    /** 本用例的模拟产生的决策记录，结束时删除。 */
    final List<String> decisions = new ArrayList<>();

    @AfterEach
    void dropSimulatedDecisions() {
        for (String id : decisions) {
            docs.findById("DECISION-" + id).ifPresent(docs::delete);
            allAudits.deleteAll(allAudits.findTop500ByObjectTypeOrderByIdDesc(RouteRankingService.AUDIT_TYPE).stream()
                    .filter(x -> id.equals(x.objectId)).toList());
        }
    }

    static ScoreWeightMatcher.Weights weightsOf(View v) {
        return new ScoreWeightMatcher.Weights(v.w1Cost(), v.w2Speed(), v.w3Preference(), v.w4Commercial());
    }

    /** 数据库的时间精确到微秒：比较时两边都截到微秒。 */
    static void sameMoment(LocalDateTime a, LocalDateTime b) {
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        assertThat(a.truncatedTo(ChronoUnit.MICROS)).isEqualTo(b.truncatedTo(ChronoUnit.MICROS));
    }

    // ---------- 默认配置 ----------

    @Test
    void sevenDefaultsAreSeededAsPublishedVersionOneAndSeedingIsIdempotent() {
        assertThat(service.list(null, null, null)).hasSize(7).allSatisfy(v -> {
            assertThat(v.status()).isEqualTo(PUBLISHED);
            assertThat(v.enabled()).isTrue();
            assertThat(v.effectiveNow()).isTrue();
            assertThat(v.effectiveTo()).isNull();
            assertThat(v.effectiveFrom()).isEqualTo(SEED_EFFECTIVE_FROM);
            assertThat(v.weightSum()).isEqualTo(100);
            assertThat(v.clientTier()).isEqualTo(ALL_TIERS);
            assertThat(v.versionNo()).isEqualTo(1);
            assertThat(v.createdBy()).isEqualTo("system");
        });
        for (DefaultWeight d : RoutingScoreWeightService.DEFAULTS) {
            var rows = rows(d.transactionType(), ALL_TIERS);
            assertThat(rows).hasSize(1);
            assertThat(weightsOf(rows.get(0))).isEqualTo(new ScoreWeightMatcher.Weights(d.w1Cost(), d.w2Speed(), d.w3Preference(), d.w4Commercial()));
        }
        // 重复启动不会再写一遍
        assertThat(service.seedDefaults()).isZero();
        assertThat(weights.count()).isEqualTo(7);
        assertThat(matcher.size()).isEqualTo(7);
        assertThat(service.options().fallbackEffective()).isTrue();
    }

    @Test
    void seedingNeverOverwritesWeightsTheUserHasPublished() {
        View v2 = publish(draft("TRADE", ALL_TIERS, OPS, 55, 25, 10, 10), null, OPS);
        assertThat(service.seedDefaults()).isZero();
        var trade = rows("TRADE", ALL_TIERS);
        assertThat(trade).extracting(View::versionNo).containsExactly(2, 1);
        assertThat(weightsOf(trade.get(1))).isEqualTo(new ScoreWeightMatcher.Weights(40, 30, 20, 10));
        assertThat(matchNow("TRADE", "VIP").versionNo()).isEqualTo(v2.versionNo());
        assertThat(weights.count()).isEqualTo(8);
    }

    // ---------- 校验 ----------

    @Test
    void draftWeightsMustBeIntegersFromZeroToHundredSummingToHundred() {
        View ok = draft("TRADE", ALL_TIERS, OPS, 40, 30, 20, 10);
        assertThat(ok.status()).isEqualTo(DRAFT);
        assertThat(ok.versionNo()).isEqualTo(2);
        assertThat(ok.weightSum()).isEqualTo(100);
        assertThat(ok.enabled()).isFalse();
        assertThat(ok.effectiveFrom()).isNull();
        assertThat(ok.createdBy()).isEqualTo(OPS);
        assertThatThrownBy(() -> draft("TREASURY", ALL_TIERS, OPS, 25, 50, 20, 4)).hasMessageContaining("合计必须等于 100，当前为 99");
        assertThatThrownBy(() -> draft("TREASURY", ALL_TIERS, OPS, 25, 50, 20, 6)).hasMessageContaining("合计必须等于 100，当前为 101");
        assertThatThrownBy(() -> draft("PAYROLL", ALL_TIERS, OPS, 60, -1, 20, 21)).hasMessageContaining("不能为负数：W2 速度 = -1");
        assertThatThrownBy(() -> draft("PAYROLL", ALL_TIERS, OPS, 101, 0, 0, -1)).hasMessageContaining("不能大于 100：W1 成本 = 101");
        assertThatThrownBy(() -> service.saveDraft(new DraftRequest(null, null, "FEE", ALL_TIERS, 60, 20, 10, null, null, OPS))).hasMessageContaining("W4 履约 未填写");
        // 只留下第一份草稿；草稿不参与路由评分
        assertThat(service.list(null, null, DRAFT)).containsExactly(ok);
        assertThat(matchNow("TRADE", "VIP").versionNo()).isEqualTo(1);
    }

    @Test
    void databaseConstraintRejectsWeightsThatBreakTheRule() {
        long before = weights.count();
        assertThatThrownBy(() -> weights.save(raw("FEE", 60, 20, 10, 9, DRAFT)))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?is).*ck_score_weight_sum.*");
        assertThatThrownBy(() -> weights.save(raw("FEE", 120, -20, 0, 0, DRAFT)))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?is).*ck_score_weight_range.*");
        assertThatThrownBy(() -> weights.save(raw("FEE", 60, 20, 10, 10, "ARCHIVED")))
                .isInstanceOf(DataAccessException.class).hasMessageMatching("(?is).*ck_score_weight_status.*");
        assertThat(weights.count()).isEqualTo(before);
    }

    /** 绕过服务层直接构造一行（必填列都填上，保证失败的只能是 CHECK 约束）。 */
    static RoutingScoreWeight raw(String type, int w1, int w2, int w3, int w4, String status) {
        RoutingScoreWeight r = new RoutingScoreWeight(type, ALL_TIERS, 9, w1, w2, w3, w4);
        r.status = status;
        r.createdAt = r.updatedAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        r.createdBy = r.updatedBy = OPS;
        return r;
    }

    @Test
    void tierCodesOperatorsAndNotesAreValidated() {
        assertThat(draft("PAYROLL", " standard ", OPS, 55, 25, 10, 10).clientTier()).isEqualTo("STANDARD");
        for (String bad : Arrays.asList("", "   ", "VIP GOLD", "客户分层", "A".repeat(65)))
            assertThatThrownBy(() -> draft("PAYROLL", bad, OPS, 55, 25, 10, 10)).as("tier '%s'", bad).hasMessageContaining("客户层级编码");
        assertThatThrownBy(() -> draft("trade", ALL_TIERS, OPS, 40, 30, 20, 10)).hasMessageContaining("交易类型无效");
        assertThatThrownBy(() -> draft("UNKNOWN", ALL_TIERS, OPS, 40, 30, 20, 10)).hasMessageContaining("交易类型无效");
        assertThatThrownBy(() -> draft("FEE", ALL_TIERS, " ", 60, 20, 10, 10)).hasMessageContaining("操作人不能为空");
        assertThatThrownBy(() -> draft("FEE", ALL_TIERS, "x".repeat(65), 60, 20, 10, 10)).hasMessageContaining("操作人不能为空");
        assertThatThrownBy(() -> service.saveDraft(new DraftRequest(null, null, "FEE", ALL_TIERS, 60, 20, 10, 10, "n".repeat(501), OPS))).hasMessageContaining("说明最多500位");
        assertThatThrownBy(() -> service.saveDraft(null)).hasMessageContaining("请求不能为空");
        assertThatThrownBy(() -> service.deleteDraft(999_999, OPS)).hasMessageContaining("权重配置不存在");
        assertThatThrownBy(() -> service.publish(999_999, new PublishRequest(null, OPS, null, null))).hasMessageContaining("权重配置不存在");
        assertThatThrownBy(() -> service.disable(999_999, new PublishRequest(null, OPS, null, null))).hasMessageContaining("权重配置不存在");
        // 只有第一份合法草稿落库
        assertThat(service.list(null, null, DRAFT)).hasSize(1).allSatisfy(v -> assertThat(v.clientTier()).isEqualTo("STANDARD"));
        assertThat(service.list(null, null, PUBLISHED)).hasSize(7);
    }

    // ---------- 版本 ----------

    @Test
    void oneDraftPerKeyPublishedVersionsAreImmutableAndDraftsCanBeDeleted() {
        View v2 = draft("TRADE", ALL_TIERS, OPS, 55, 25, 10, 10);
        View v1 = rows("TRADE", ALL_TIERS).get(1);
        // 草稿可以反复保存（带读取时的行版本号）
        View updated = service.saveDraft(new DraftRequest(v2.id(), v2.rowVersion(), null, null, 25, 50, 20, 5, "调整比重", OPS));
        assertThat(updated.id()).isEqualTo(v2.id());
        assertThat(weightsOf(updated)).isEqualTo(new ScoreWeightMatcher.Weights(25, 50, 20, 5));
        assertThat(updated.versionNo()).isEqualTo(2);
        assertThat(updated.note()).isEqualTo("调整比重");
        assertThatThrownBy(() -> draft("TRADE", ALL_TIERS, OPS, 40, 30, 20, 10)).hasMessageContaining("已有待发布的草稿（版本 v2）");
        // 已发布的版本不能修改：修改 = 新建草稿，发布后成为新版本
        assertThatThrownBy(() -> service.saveDraft(new DraftRequest(v1.id(), null, null, null, 40, 30, 20, 10, null, OPS))).hasMessageContaining("已发布的版本不能修改");
        // 被他人改过 / 草稿的键不能变
        assertThatThrownBy(() -> service.saveDraft(new DraftRequest(v2.id(), updated.rowVersion() + 5, null, null, 40, 30, 20, 10, null, OPS))).hasMessageContaining("配置已被他人修改");
        assertThatThrownBy(() -> service.saveDraft(new DraftRequest(v2.id(), updated.rowVersion(), "FEE", null, 40, 30, 20, 10, null, OPS))).hasMessageContaining("不能修改");
        assertThatThrownBy(() -> service.saveDraft(new DraftRequest(v2.id(), updated.rowVersion(), null, "VIP", 40, 30, 20, 10, null, OPS))).hasMessageContaining("不能修改");
        // 只有草稿可以删除
        assertThatThrownBy(() -> service.deleteDraft(v1.id(), OPS)).hasMessageContaining("只有草稿可以删除");
        assertThatThrownBy(() -> service.deleteDraft(v2.id(), " ")).hasMessageContaining("操作人不能为空");
        service.deleteDraft(v2.id(), OPS);
        assertThat(weights.findById(v2.id())).isEmpty();
        assertThat(rows("TRADE", ALL_TIERS)).hasSize(1);
        assertThat(service.audits()).extracting(AuditView::operation, AuditView::operator)
                .containsExactlyInAnyOrder(tuple("CREATE_DRAFT", OPS), tuple("UPDATE_DRAFT", OPS), tuple("DELETE_DRAFT", OPS));
    }

    @Test
    void publishClosesTheOpenVersionAndTheSnapshotUsesTheNewVersionRightAway() {
        View v2 = publish(draft("TRADE", ALL_TIERS, OPS, 25, 50, 20, 5), null, OPS);
        assertThat(v2.status()).isEqualTo(PUBLISHED);
        assertThat(v2.enabled()).isTrue();
        assertThat(v2.publishedBy()).isEqualTo(OPS);
        assertThat(v2.publishedAt()).isNotNull();
        assertThat(v2.effectiveFrom()).isNotNull();
        assertThat(v2.effectiveTo()).isNull();
        View v1 = rows("TRADE", ALL_TIERS).get(1);
        assertThat(v1.status()).isEqualTo(PUBLISHED);
        assertThat(v1.enabled()).isFalse();
        sameMoment(v1.effectiveTo(), v2.effectiveFrom());
        var match = matchNow("TRADE", "VIP");
        assertThat(match.level()).isEqualTo(ScoreWeightMatcher.P2);
        assertThat(match.versionNo()).isEqualTo(2);
        assertThat(match.configId()).isEqualTo(v2.id());
        assertThat(match.weights()).isEqualTo(new ScoreWeightMatcher.Weights(25, 50, 20, 5));
        sameMoment(match.effectiveFrom(), v2.effectiveFrom());
        // 停用之前的历史决策时间仍然用当时生效的版本（可回放）
        assertThat(matcher.require("TRADE", "VIP", SEED_EFFECTIVE_FROM.plusYears(20)).versionNo()).isEqualTo(1);
        assertThat(service.versions()).filteredOn(v -> v.transactionType().equals("TRADE")).extracting(View::versionNo).containsExactly(2, 1);
        assertThat(service.audits()).extracting(AuditView::operation, AuditView::operator, AuditView::versionNo)
                .containsExactlyInAnyOrder(tuple("CREATE_DRAFT", OPS, 2), tuple("PUBLISH", OPS, 2), tuple("SUPERSEDED", OPS, 1));
    }

    @Test
    void aNewVersionCannotTakeEffectInThePastWhenTheKeyAlreadyHasPublishedVersions() {
        LocalDateTime now = service.now();
        View v2 = draft("TRADE", ALL_TIERS, OPS, 55, 25, 10, 10);
        // 回溯生效会把已发生决策回放成新权重：拒绝；v1 不被截断，草稿还在，没有审计
        assertThatThrownBy(() -> publish(v2, now.minusMinutes(1), OPS)).hasMessageContaining("生效时间不能早于当前时间").hasMessageContaining("TRADE + ALL");
        assertThatThrownBy(() -> publish(v2, SEED_EFFECTIVE_FROM, OPS)).hasMessageContaining("生效时间不能早于当前时间");
        assertThat(weights.findById(v2.id()).orElseThrow().status).isEqualTo(DRAFT);
        assertThat(rows("TRADE", ALL_TIERS).get(1).effectiveTo()).isNull();
        assertThat(service.audits()).extracting(AuditView::operation).containsExactly("CREATE_DRAFT");
        // 之后的时间可以发布，但发布同样必须提供操作人
        assertThatThrownBy(() -> service.publish(v2.id(), new PublishRequest(v2.rowVersion(), "", now.plusHours(1), null))).hasMessageContaining("发布必须提供操作人");
        assertThatThrownBy(() -> service.publish(v2.id(), null)).hasMessageContaining("请求不能为空");
    }

    @Test
    void aScheduledVersionTakesEffectAtItsTimeAndOnlyOneCanBePending() {
        LocalDateTime now = service.now(), at = now.plusHours(1);
        View scheduled = publish(draft("TRADE", ALL_TIERS, OPS, 55, 25, 10, 10), at, OPS);
        assertThat(scheduled.status()).isEqualTo(PUBLISHED);
        assertThat(scheduled.enabled()).isFalse();
        sameMoment(scheduled.effectiveFrom(), at);
        // 当前版本在定时版本的生效时间结束；在此之前一直生效
        View v1 = rows("TRADE", ALL_TIERS).get(1);
        sameMoment(v1.effectiveTo(), at);
        assertThat(v1.enabled()).isTrue();
        // 到点自然命中：靠有效时间段判断，不需要刷新缓存
        assertThat(matchNow("TRADE", "VIP").versionNo()).isEqualTo(1);
        assertThat(matcher.require("TRADE", "VIP", at.plusSeconds(1)).versionNo()).isEqualTo(2);
        // 已有待生效版本时不能再发布
        View v3 = draft("TRADE", ALL_TIERS, OPS, 40, 30, 20, 10);
        assertThatThrownBy(() -> publish(v3, null, OPS)).hasMessageContaining("已有待生效版本 v2");
        assertThatThrownBy(() -> publish(v3, now.plusHours(3), OPS)).hasMessageContaining("已有待生效版本 v2");
        assertThat(rows("TRADE", ALL_TIERS)).extracting(View::status).containsExactly(DRAFT, PUBLISHED, PUBLISHED);
    }

    @Test
    void disablingAScheduledVersionUndoesTheHandoverAndRestoresThePreviousVersion() {
        LocalDateTime now = service.now(), at = now.plusHours(2);
        publish(draft("FEE", ALL_TIERS, OPS, 55, 25, 10, 10), null, OPS);              // v2 立即生效
        View later = publish(draft("FEE", ALL_TIERS, OPS, 60, 30, 5, 5), at, OPS);     // v3 定时生效：v2 的结束时间 = v3 的生效时间
        assertThat(later.versionNo()).isEqualTo(3);
        View closed = rows("FEE", ALL_TIERS).get(1);
        sameMoment(closed.effectiveTo(), at);
        assertThat(closed.enabled()).isTrue();
        // 停用尚未生效的 v3：它从未生效，v2 恢复为长期有效，FEE 不会出现没有权重的空窗
        View off = disable(later, OPS);
        assertThat(off.status()).isEqualTo(DISABLED);
        assertThat(off.effectiveTo()).isEqualTo(off.effectiveFrom());
        View reopened = rows("FEE", ALL_TIERS).get(1);
        assertThat(reopened.versionNo()).isEqualTo(2);
        assertThat(reopened.status()).isEqualTo(PUBLISHED);
        assertThat(reopened.effectiveTo()).isNull();
        assertThat(weightsOf(reopened)).isEqualTo(new ScoreWeightMatcher.Weights(55, 25, 10, 10));
        assertThat(matcher.require("FEE", "VIP", now.plusHours(3)).versionNo()).isEqualTo(2);
        assertThat(service.audits()).extracting(AuditView::operation, AuditView::versionNo).contains(tuple("REOPEN", 2));
        // 恢复后可以重新发布
        View live = publish(draft("FEE", ALL_TIERS, OPS, 60, 30, 5, 5), null, OPS);
        assertThat(live.versionNo()).isEqualTo(4);
        assertThat(live.enabled()).isTrue();
        sameMoment(rows("FEE", ALL_TIERS).get(2).effectiveTo(), live.effectiveFrom());
    }

    @Test
    void effectiveTimeMayNotOverlapAClosedVersion() {
        // 正常的发布流程不会产生这种数据；这里直接改库模拟一条「还没结束」的历史版本
        RoutingScoreWeight v1 = weights.findByTransactionTypeAndClientTierOrderByVersionNoDesc("FEE", ALL_TIERS).get(0);
        v1.effectiveTo = service.now().plusHours(1);
        weights.save(v1);
        View v2 = draft("FEE", ALL_TIERS, OPS, 60, 20, 10, 10);
        assertThatThrownBy(() -> publish(v2, null, OPS)).hasMessageContaining("有效时间段重叠").hasMessageContaining("v1");
        assertThat(weights.findById(v2.id()).orElseThrow().status).isEqualTo(DRAFT);
        // 接在它之后的时间可以发布
        View live = publish(v2, service.now().plusHours(2), OPS);
        assertThat(live.enabled()).isFalse();
    }

    // ---------- 停用 ----------

    @Test
    void disablingKeepsTheVersionInHistoryAndRoutingFallsBackToTheNextLevel() {
        View v2 = publish(draft("FEE", ALL_TIERS, OPS, 55, 25, 10, 10), null, OPS);
        assertThatThrownBy(() -> service.disable(v2.id(), new PublishRequest(v2.rowVersion() + 1, OPS, null, null))).hasMessageContaining("配置已被他人修改");
        assertThatThrownBy(() -> service.disable(v2.id(), new PublishRequest(null, "", null, null))).hasMessageContaining("停用必须提供操作人");
        View off = disable(v2, OPS);
        assertThat(off.status()).isEqualTo(DISABLED);
        assertThat(off.enabled()).isFalse();
        assertThat(off.updatedBy()).isEqualTo(OPS);
        assertThat(off.effectiveTo()).isNotNull().isAfterOrEqualTo(off.effectiveFrom());
        assertThat(service.versions()).filteredOn(v -> v.transactionType().equals("FEE")).extracting(View::status).containsExactly(DISABLED, PUBLISHED);
        // FEE 没有生效的版本 → P2 落空，用 OTHER + ALL
        var match = matchNow("FEE", "VIP");
        assertThat(match.level()).isEqualTo(ScoreWeightMatcher.P3);
        assertThat(match.transactionType()).isEqualTo(FALLBACK_TYPE);
        // 已结束的版本不能再停用；停用的版本是历史记录，不能再发布或删除
        View v1 = rows("FEE", ALL_TIERS).get(1);
        assertThatThrownBy(() -> service.disable(v1.id(), new PublishRequest(null, OPS, null, null))).hasMessageContaining("已结束");
        assertThatThrownBy(() -> service.publish(v2.id(), new PublishRequest(null, OPS, null, null))).hasMessageContaining("只有草稿可以发布");
        assertThatThrownBy(() -> service.deleteDraft(v2.id(), OPS)).hasMessageContaining("只有草稿可以删除");
    }

    @Test
    void theFallbackConfigurationCannotBeDisabledButCanBeReplacedByANewVersion() {
        View fallback = rows(FALLBACK_TYPE, ALL_TIERS).get(0);
        assertThatThrownBy(() -> service.disable(fallback.id(), new PublishRequest(null, OPS, null, null))).hasMessageContaining("OTHER + ALL 是兜底权重配置，必须保留");
        assertThat(rows(FALLBACK_TYPE, ALL_TIERS).get(0).status()).isEqualTo(PUBLISHED);
        View next = publish(draft(FALLBACK_TYPE, ALL_TIERS, OPS, 40, 30, 20, 10), null, OPS);
        // 兜底配置始终有生效的版本；交易类型没配置时仍能落到它
        assertThat(service.options().fallbackEffective()).isTrue();
        var match = matchNow("PAYROLL", "VIP");
        assertThat(match.level()).isEqualTo(ScoreWeightMatcher.P2);
        assertThat(matchNow(FALLBACK_TYPE, "VIP").configId()).isEqualTo(next.id());
    }

    // ---------- 缓存 / 事务 ----------

    @Test
    void aRolledBackPublishLeavesTheSnapshotAndTheDataUntouched() {
        View v2 = draft("TREASURY", ALL_TIERS, OPS, 25, 50, 20, 5);
        new TransactionTemplate(tx).executeWithoutResult(status -> {
            publish(v2, null, OPS);
            status.setRollbackOnly();
        });
        assertThat(weights.findById(v2.id()).orElseThrow().status).isEqualTo(DRAFT);
        assertThat(rows("TREASURY", ALL_TIERS).get(1).effectiveTo()).isNull();
        assertThat(service.audits()).extracting(AuditView::operation).containsExactly("CREATE_DRAFT");
        assertThat(matcher.size()).isEqualTo(7);
        assertThat(matchNow("TREASURY", "VIP").versionNo()).isEqualTo(1);
        // 草稿的行版本号没有被占用：正常发布一次就生效
        publish(v2, null, OPS);
        assertThat(matchNow("TREASURY", "VIP").versionNo()).isEqualTo(2);
        assertThat(service.audits()).extracting(AuditView::operation).containsExactlyInAnyOrder("CREATE_DRAFT", "PUBLISH", "SUPERSEDED");
    }

    @Test
    void withoutAnyEffectiveConfigurationMatchingIsAnExplicitErrorNotZeroWeights() throws Exception {
        weights.deleteAll(weights.findByTransactionTypeAndClientTierOrderByVersionNoDesc("TRADE", ALL_TIERS));
        weights.deleteAll(weights.findByTransactionTypeAndClientTierOrderByVersionNoDesc(FALLBACK_TYPE, ALL_TIERS));
        matcher.refresh();
        var trace = traceNow("TRADE", "VIP");
        assertThat(trace.hit()).isFalse();
        assertThat(trace.matched()).isNull();
        assertThat(trace.error()).contains("没有可用的评分权重配置").contains("TRADE + VIP").contains("TRADE + ALL").contains("OTHER + ALL");
        assertThat(trace.steps()).extracting(Step::hit).containsExactly("MISS", "MISS", "MISS");
        assertThat(service.options().fallbackEffective()).isFalse();
        assertThatThrownBy(() -> matchNow("TRADE", "VIP")).isInstanceOf(IllegalStateException.class).hasMessageContaining("没有可用的评分权重配置");
        // 路由：有合格候选但没有权重 → 明确的配置异常（不会按 0 权重算出 0 分再排序）
        assertThatThrownBy(() -> simulate("TRADE")).isInstanceOf(IllegalStateException.class).hasMessageContaining("没有可用的评分权重配置");
        // 其它交易类型不受影响
        assertThat(simulate("PAYROLL").ranking().transactionType()).isEqualTo("PAYROLL");
    }

    // ---------- 与路由的关系 ----------

    @Test
    void anExactTierConfigurationIsUsedAsP1WithoutChangingAnyC1ToC10Result() throws Exception {
        RoutingService.Result before = simulate("TRADE");
        assertThat(before.ranking().weightMatchLevel()).isEqualTo(ScoreWeightMatcher.P2);
        // 草稿不参与路由
        View draftP1 = draft("TRADE", "STANDARD", OPS, 10, 20, 30, 40);
        assertThat(simulate("TRADE").ranking().weightMatchLevel()).isEqualTo(ScoreWeightMatcher.P2);
        // 全新的「交易类型 + 客户层级」可以追溯生效：模拟日期（2026-11-20）的决策也能用上
        View p1 = publish(draftP1, SEED_EFFECTIVE_FROM, OPS);
        RoutingService.Result after = simulate("TRADE");
        assertThat(after.ranking().weightMatchLevel()).isEqualTo(ScoreWeightMatcher.P1);
        assertThat(after.ranking().transactionType()).isEqualTo("TRADE");
        assertThat(after.ranking().clientTier()).isEqualTo("STANDARD");
        assertThat(after.ranking().matchedTransactionType()).isEqualTo("TRADE");
        assertThat(after.ranking().matchedClientTier()).isEqualTo("STANDARD");
        assertThat(after.ranking().weightConfigId()).isEqualTo(p1.id());
        assertThat(after.ranking().weights()).isEqualTo(new ScoreWeightMatcher.Weights(10, 20, 30, 40));
        // C1–C10 的结论和四项得分与发布前逐个候选完全一致，变的只有综合评分和排名
        Map<String, RoutingService.Candidate> old = byKey(before), current = byKey(after);
        assertThat(current.keySet()).isEqualTo(old.keySet());
        for (String key : old.keySet()) {
            RoutingService.Candidate a = old.get(key), b = current.get(key);
            assertThat(b.checks).extracting(RoutingService.Check::code, RoutingService.Check::passed, RoutingService.Check::reasons)
                    .containsExactlyElementsOf(a.checks.stream().map(c -> tuple(c.code(), c.passed(), c.reasons())).toList());
            assertThat(b.passed).isEqualTo(a.passed);
            assertThat(b.scores).isEqualTo(a.scores);
            if (a.passed) {
                assertThat(b.estimatedCostHkd).isEqualByComparingTo(a.estimatedCostHkd);
                assertThat(b.valueDate).isEqualTo(a.valueDate);
            }
        }
        // PROV-W：(100×10 + 100×20 + 0×30 + 100×40) / 100 = 70.00；PROV-T1：(76.92×10 + 50×20 + 0 + 100×40) / 100 = 57.692 → 57.69
        assertThat(after.route().providerCode).isEqualTo("PROV-W");
        assertThat(after.route().totalScore).isEqualByComparingTo("70.00");
        assertThat(after.alternatives()).hasSize(1);
        assertThat(after.alternatives().get(0).providerCode).isEqualTo("PROV-T1");
        assertThat(after.alternatives().get(0).totalScore).isEqualByComparingTo("57.69");
        assertThat(after.messages()).anySatisfy(m -> assertThat(m).contains("TRADE + STANDARD（v1，匹配级别 P1）：10/20/30/40"));
        // 评分审计和决策记录里保存的都是 P1 的配置
        List<RuleAudit> scored = allAudits.findTop500ByObjectTypeOrderByIdDesc(RouteRankingService.AUDIT_TYPE).stream()
                .filter(x -> after.decisionId().equals(x.objectId)).toList();
        assertThat(scored).hasSize(2);
        assertThat(scored).extracting(x -> json.readTree(x.afterJson).path("weightMatchLevel").asText()).containsOnly("P1");
        assertThat(scored).extracting(x -> json.readTree(x.afterJson).path("weightConfigId").asLong()).containsOnly(p1.id());
        JsonNode saved = (JsonNode) routing.decision(after.decisionId());
        assertThat(saved.path("response").path("ranking").path("weightMatchLevel").asText()).isEqualTo("P1");
        assertThat(saved.path("response").path("ranking").path("weightConfigVersion").asInt()).isEqualTo(1);
    }

    /** 演示请求（与 RoutingServiceTest 同一笔 USD 汇款），决策时间 2026-11-20 10:00 HKT。 */
    RoutingService.Result simulate(String paymentType) throws Exception {
        ObjectNode req = (ObjectNode) json.readTree("""
                {"paymentId":"SW-CFG","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        req.put("paymentType", paymentType);
        RoutingService.Result r = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        decisions.add(r.decisionId());
        return r;
    }

    private static Map<String, RoutingService.Candidate> byKey(RoutingService.Result r) {
        return r.candidates().stream().collect(Collectors.toMap(c -> c.providerCode + "/" + c.fxVenue, Function.identity()));
    }

    // ---------- 层级字典 ----------

    @Test
    void tierOptionsComeFromTheFieldDictionaryAndFromConfiguredTiers() {
        Options o = service.options();
        assertThat(o.transactionTypes()).containsExactly("FEE", "INTRAGROUP", "OTHER", "PAYROLL", "REFUND", "TRADE", "TREASURY");
        assertThat(o.tierField()).isEqualTo("clientSegment");
        assertThat(o.fallbackEffective()).isTrue();
        // 演示数据的字典没有维护取值，也没配置过具体层级：只有 ALL，不写死任何层级
        assertThat(o.clientTiers()).containsExactly(ALL_TIERS);
        // 在字段库里给「客户分层」维护取值后，下拉从字典读取
        FieldDefinition field = fields.findById("clientSegment").orElseThrow();
        String original = field.valueOptions;
        try {
            field.valueOptions = "STANDARD, PRIORITY";
            fields.save(field);
            assertThat(service.options().clientTiers()).containsExactly(ALL_TIERS, "PRIORITY", "STANDARD");
        } finally {
            field.valueOptions = original;
            fields.save(field);
        }
        assertThat(service.options().clientTiers()).containsExactly(ALL_TIERS);
        // 配置过的层级也会出现
        draft("PAYROLL", "VIP", OPS, 55, 25, 10, 10);
        assertThat(service.options().clientTiers()).containsExactly(ALL_TIERS, "VIP");
    }
}
