package com.hsg.relay.routing;

import com.hsg.relay.domain.RoutingScoreWeight;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.repo.RoutingScoreWeightRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static com.hsg.relay.domain.RoutingScoreWeight.ALL_TIERS;
import static com.hsg.relay.domain.RoutingScoreWeight.PUBLISHED;
import static com.hsg.relay.routing.RoutingScoreWeightService.SEED_EFFECTIVE_FROM;

/**
 * 修改权重配置的测试不能用 {@code @Transactional}：内存快照 {@link ScoreWeightMatcher} 是整个应用共享的，事务回滚不会把它恢复。
 * 所以这类测试都在非事务里执行，并在每个用例前后把权重表恢复成「系统初始化后的 7 条默认配置」，再重建快照。
 */
abstract class ScoreWeightCleanSlate {
    @Autowired
    RoutingScoreWeightRepository weights;
    @Autowired
    RuleAuditRepository audits;
    @Autowired
    ScoreWeightMatcher matcher;
    @Autowired
    RoutingScoreWeightService service;

    @BeforeEach
    void restoreBefore() {
        restoreDefaults();
    }

    @AfterEach
    void restoreAfter() {
        restoreDefaults();
    }

    static RoutingScoreWeightService.DefaultWeight defaultOf(String transactionType) {
        return RoutingScoreWeightService.DEFAULTS.stream().filter(d -> d.transactionType().equals(transactionType)).findFirst().orElse(null);
    }

    /** 用例产生的行：不是 v1、不是 ALL 层级、或不是默认交易类型。 */
    static boolean isUserRow(RoutingScoreWeight r) {
        return r.versionNo != 1 || !ALL_TIERS.equals(r.clientTier) || defaultOf(r.transactionType) == null;
    }

    void restoreDefaults() {
        List<RoutingScoreWeight> extra = weights.findAll().stream().filter(ScoreWeightCleanSlate::isUserRow).toList();
        if (!extra.isEmpty()) weights.deleteAll(extra);
        for (RoutingScoreWeight r : weights.findAll()) {
            var d = defaultOf(r.transactionType);
            boolean dirty = !PUBLISHED.equals(r.status) || !SEED_EFFECTIVE_FROM.equals(r.effectiveFrom) || r.effectiveTo != null
                    || r.w1Cost != d.w1Cost() || r.w2Speed != d.w2Speed() || r.w3Preference != d.w3Preference() || r.w4Commercial != d.w4Commercial();
            if (!dirty) continue;
            r.status = PUBLISHED;
            r.effectiveFrom = SEED_EFFECTIVE_FROM;
            r.effectiveTo = null;
            r.w1Cost = d.w1Cost();
            r.w2Speed = d.w2Speed();
            r.w3Preference = d.w3Preference();
            r.w4Commercial = d.w4Commercial();
            weights.save(r);
        }
        // 被用例删掉的默认行按同一套幂等逻辑补齐；权重配置的审计记录清空
        service.seedDefaults();
        audits.deleteAll(audits.findTop500ByObjectTypeOrderByIdDesc(RoutingScoreWeightService.AUDIT_TYPE));
        matcher.refresh();
    }

    RoutingScoreWeightService.View draft(String type, String tier, String operator, int w1, int w2, int w3, int w4) {
        return service.saveDraft(new RoutingScoreWeightService.DraftRequest(null, null, type, tier, w1, w2, w3, w4, null, operator));
    }

    RoutingScoreWeightService.View publish(RoutingScoreWeightService.View draft, LocalDateTime from, String operator) {
        return service.publish(draft.id(), new RoutingScoreWeightService.PublishRequest(draft.rowVersion(), operator, from, null));
    }

    RoutingScoreWeightService.View disable(RoutingScoreWeightService.View row, String operator) {
        return service.disable(row.id(), new RoutingScoreWeightService.PublishRequest(row.rowVersion(), operator, null, null));
    }

    /** 按业务时钟的当前时间匹配（与路由实际调用的区别只是决策时间）。 */
    ScoreWeightMatcher.Match matchNow(String type, String tier) {
        return matcher.require(type, tier, null);
    }

    ScoreWeightMatcher.Trace traceNow(String type, String tier) {
        return matcher.trace(type, tier, null);
    }

    /** 某个「交易类型 + 客户层级」的全部版本，版本号从大到小（与列表接口同一顺序）。 */
    List<RoutingScoreWeightService.View> rows(String type, String tier) {
        return service.list(type, tier, null);
    }
}
