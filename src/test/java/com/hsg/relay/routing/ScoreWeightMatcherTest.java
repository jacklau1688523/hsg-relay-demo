package com.hsg.relay.routing;

import com.hsg.relay.domain.RoutingScoreWeight;
import com.hsg.relay.repo.RoutingScoreWeightRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.hsg.relay.domain.RoutingScoreWeight.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** 权重匹配 P1 → P2 → P3：只用内存数据，固定决策时间，结果可重现。 */
class ScoreWeightMatcherTest {
    static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");
    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 18, 0);
    static final LocalDateTime LONG_AGO = LocalDateTime.of(2000, 1, 1, 0, 0);

    final List<RoutingScoreWeight> rows = new ArrayList<>();
    ScoreWeightMatcher matcher;

    @BeforeEach
    void setUp() {
        RoutingScoreWeightRepository repo = mock(RoutingScoreWeightRepository.class);
        // 与真实仓库一致：只返回调用方指定状态的行（草稿永远不会进入快照）
        when(repo.findByStatusIn(anyCollection())).thenAnswer(inv -> {
            Collection<String> statuses = inv.getArgument(0);
            return rows.stream().filter(r -> statuses.contains(r.status)).toList();
        });
        matcher = new ScoreWeightMatcher(repo, Clock.fixed(NOW.atZone(HK).toInstant(), HK));
    }

    static RoutingScoreWeight row(long id, String type, String tier, int version, String status, int w1, int w2, int w3, int w4,
                                  LocalDateTime from, LocalDateTime to) {
        RoutingScoreWeight r = new RoutingScoreWeight(type, tier, version, w1, w2, w3, w4);
        r.id = id;
        r.status = status;
        r.effectiveFrom = from;
        r.effectiveTo = to;
        return r;
    }

    static RoutingScoreWeight published(long id, String type, String tier, int w1, int w2, int w3, int w4) {
        return row(id, type, tier, 1, PUBLISHED, w1, w2, w3, w4, LONG_AGO, null);
    }

    @Test
    void exactTierHitsP1AndStopsThere() {
        rows.add(published(1, "TRADE", "ALL", 40, 30, 20, 10));
        rows.add(published(2, "TRADE", "VIP", 10, 20, 30, 40));
        rows.add(published(3, "OTHER", "ALL", 50, 30, 10, 10));
        var match = matcher.require("TRADE", "VIP", NOW);
        assertThat(match.level()).isEqualTo(ScoreWeightMatcher.P1);
        assertThat(match.configId()).isEqualTo(2);
        assertThat(match.versionNo()).isEqualTo(1);
        assertThat(match.transactionType()).isEqualTo("TRADE");
        assertThat(match.clientTier()).isEqualTo("VIP");
        assertThat(match.weights()).isEqualTo(new ScoreWeightMatcher.Weights(10, 20, 30, 40));
        // 命中 P1 后不再降级：P2 / P3 标记为未判断
        var trace = matcher.trace("TRADE", "VIP", NOW);
        assertThat(trace.steps()).extracting(ScoreWeightMatcher.Step::level, ScoreWeightMatcher.Step::hit)
                .containsExactly(tuple("P1", "HIT"), tuple("P2", "SKIPPED"), tuple("P3", "SKIPPED"));
        assertThat(trace.error()).isNull();
    }

    @Test
    void fallsBackToTypePlusAllAsP2() {
        rows.add(published(1, "TRADE", "ALL", 40, 30, 20, 10));
        rows.add(published(3, "OTHER", "ALL", 50, 30, 10, 10));
        var match = matcher.require("TRADE", "VIP", NOW);
        assertThat(match.level()).isEqualTo(ScoreWeightMatcher.P2);
        assertThat(match.transactionType()).isEqualTo("TRADE");
        assertThat(match.clientTier()).isEqualTo("ALL");
        assertThat(matcher.trace("TRADE", "VIP", NOW).steps()).extracting(ScoreWeightMatcher.Step::hit)
                .containsExactly("MISS", "HIT", "SKIPPED");
    }

    @Test
    void fallsBackToOtherPlusAllAsP3() {
        rows.add(published(1, "TRADE", "ALL", 40, 30, 20, 10));
        rows.add(published(3, "OTHER", "ALL", 50, 30, 10, 10));
        var match = matcher.require("PAYROLL", "VIP", NOW);
        assertThat(match.level()).isEqualTo(ScoreWeightMatcher.P3);
        assertThat(match.transactionType()).isEqualTo("OTHER");
        assertThat(match.weights()).isEqualTo(new ScoreWeightMatcher.Weights(50, 30, 10, 10));
        assertThat(matcher.trace("PAYROLL", "VIP", NOW).steps()).extracting(ScoreWeightMatcher.Step::hit)
                .containsExactly("MISS", "MISS", "HIT");
    }

    @Test
    void nothingEffectiveIsAnExplicitConfigurationException() {
        rows.add(published(1, "TRADE", "ALL", 40, 30, 20, 10));
        rows.add(row(9, "OTHER", "ALL", 1, DRAFT, 50, 30, 10, 10, LONG_AGO, null));
        assertThatThrownBy(() -> matcher.require("PAYROLL", "VIP", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可用的评分权重配置")
                .hasMessageContaining("PAYROLL + VIP")
                .hasMessageContaining("OTHER + ALL");
        // 模拟接口不抛异常：返回未命中 + 原因
        var trace = matcher.trace("PAYROLL", "VIP", NOW);
        assertThat(trace.hit()).isFalse();
        assertThat(trace.matched()).isNull();
        assertThat(trace.error()).contains("没有可用的评分权重配置");
        assertThat(trace.steps()).extracting(ScoreWeightMatcher.Step::hit).containsExactly("MISS", "MISS", "MISS");
    }

    @Test
    void draftIsNeverLoadedIntoTheSnapshot() {
        RoutingScoreWeightRepository repo = mock(RoutingScoreWeightRepository.class);
        when(repo.findByStatusIn(anyCollection())).thenReturn(List.of(published(1, "TRADE", "ALL", 40, 30, 20, 10)));
        new ScoreWeightMatcher(repo, Clock.systemUTC()).require("TRADE", "STANDARD", NOW);
        // 快照只读取已发布 / 已停用：草稿不进入内存，也不存在「默认全部为 0」的权重
        verify(repo).findByStatusIn(List.of(PUBLISHED, DISABLED));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void aLowerScoringP1IsStillChosenOverP2() {
        // P1 权重把全部比重放在速度上；不允许因为「这套权重算出来的分低」而改用 P2
        rows.add(published(1, "TRADE", "ALL", 100, 0, 0, 0));
        rows.add(published(2, "TRADE", "VIP", 0, 100, 0, 0));
        var match = matcher.require("TRADE", "VIP", NOW);
        assertThat(match.level()).isEqualTo(ScoreWeightMatcher.P1);
        assertThat(match.weights().cost()).isZero();
    }

    @Test
    void expiredVersionIsIgnoredAndTheNextLevelOrVersionIsUsed() {
        rows.add(published(1, "TRADE", "ALL", 40, 30, 20, 10));
        rows.add(row(2, "TRADE", "VIP", 1, DISABLED, 10, 20, 30, 40, LONG_AGO, NOW.minusHours(1)));
        assertThat(matcher.require("TRADE", "VIP", NOW).level()).isEqualTo(ScoreWeightMatcher.P2);
        // 停用之前的历史决策时间仍然用当时生效的版本（可回放）
        assertThat(matcher.require("TRADE", "VIP", NOW.minusDays(1)).configId()).isEqualTo(2);
    }

    @Test
    void futureEffectiveTimeIsHonouredWithoutRefreshingTheCache() {
        rows.add(row(1, "TRADE", "ALL", 1, PUBLISHED, 40, 30, 20, 10, LONG_AGO, NOW.plusHours(1)));
        rows.add(row(2, "TRADE", "ALL", 2, PUBLISHED, 55, 25, 10, 10, NOW.plusHours(1), null));
        assertThat(matcher.require("TRADE", "STANDARD", NOW).versionNo()).isEqualTo(1);
        assertThat(matcher.require("TRADE", "STANDARD", NOW.plusHours(2)).versionNo()).isEqualTo(2);
        assertThat(matcher.require("TRADE", "STANDARD", NOW.minusYears(1)).versionNo()).isEqualTo(1);
    }

    @Test
    void twoEffectiveVersionsAtOneLevelAreAConflictNotARandomPick() {
        // 两条已发布版本的有效时间段重叠（发布流程会拦下，这里模拟被直接写库弄坏的数据）：加载快照时拒绝，不会随机挑一条
        rows.add(published(1, "TRADE", "ALL", 40, 30, 20, 10));
        rows.add(row(2, "TRADE", "ALL", 2, PUBLISHED, 55, 25, 10, 10, LONG_AGO, null));
        assertThatThrownBy(() -> matcher.require("TRADE", "STANDARD", NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("评分权重配置冲突").hasMessageContaining("v1 与 v2");
        // 加载失败后没有可用快照：每次匹配都明确报错，不会退回到某一条版本
        assertThatThrownBy(() -> matcher.trace("TRADE", "STANDARD", NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("评分权重配置冲突");
    }

    @Test
    void corruptSnapshotIsRejectedAndTheLastGoodOneIsKept() {
        rows.add(published(1, "TRADE", "ALL", 40, 30, 20, 10));
        matcher.require("TRADE", "STANDARD", NOW);
        rows.add(published(2, "TRADE", "VIP", 99, 0, 0, 0));              // 合计不是 100
        assertThatThrownBy(matcher::refresh).isInstanceOf(IllegalStateException.class).hasMessageContaining("校验失败").hasMessageContaining("TRADE + VIP");
        assertThat(matcher.size()).isEqualTo(1);
        assertThat(matcher.require("TRADE", "STANDARD", NOW).configId()).isEqualTo(1);
        rows.remove(1);
        rows.add(published(3, "TRADE", "VIP", 20, 20, 30, 30));
        rows.add(row(4, "TRADE", "VIP", 2, PUBLISHED, 10, 20, 30, 40, LONG_AGO, NOW.plusDays(1)));   // 与 v1 时间段重叠
        assertThatThrownBy(matcher::refresh).isInstanceOf(IllegalStateException.class).hasMessageContaining("有效时间段重叠");
        assertThat(matcher.size()).isEqualTo(1);
        // 去掉冲突行（下标 2）后恢复：快照包含 2 条
        rows.remove(2);
        matcher.refresh();
        assertThat(matcher.size()).isEqualTo(2);
        assertThat(matcher.require("TRADE", "VIP", NOW).configId()).isEqualTo(3);
    }

    @Test
    void blankKeyIsRejected() {
        assertThatThrownBy(() -> matcher.trace(" ", "VIP", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> matcher.trace("TRADE", null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
