package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.hsg.relay.config.BusinessClockConfig;
import com.hsg.relay.repo.ProviderRepository;
import com.hsg.relay.service.ScoreRuleService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.Reach;

@Service
public class C5Service {
    private final RoutingService routing;
    private final C3Service c3;
    private final C3ConditionService c3Conditions;
    private final C5ScoreService scoring;
    private final ScoreRuleService scoreRules;
    private final ProviderRepository providers;
    private final Clock clock;

    public C5Service(RoutingService r, C3Service c, C3ConditionService c3Conditions, C5ScoreService scoring,
                     ScoreRuleService scoreRules, ProviderRepository p, Clock clock) {
        routing = r;
        c3 = c;
        this.c3Conditions = c3Conditions;
        this.scoring = scoring;
        this.scoreRules = scoreRules;
        providers = p;
        this.clock = clock;
    }

    public record Save(Long revision, Integer index, String zone, LocalTime cutoff, Integer offsetDays,
                       List<LocalDate> holidays) {
    }

    /** clientId 可选：只用于评分组里的函数条件（如客户是否在合作方排除名单）；at 为空时取业务时钟的当前时间。 */
    public record Input(String providerCode, String clientId, String paymentCurrency, String beneficiaryBankCountry,
                        String beneficiaryRegion,
                        String beneficiaryBankBic, String chargeOption,
                        @com.fasterxml.jackson.annotation.JsonAlias("requested_value_date") LocalDate requestedValueDate,
                        ZonedDateTime at) {
        public Input(String providerCode, String paymentCurrency, String beneficiaryBankCountry, String beneficiaryRegion,
                     String beneficiaryBankBic, String chargeOption, LocalDate requestedValueDate, ZonedDateTime at) {
            this(providerCode, null, paymentCurrency, beneficiaryBankCountry, beneficiaryRegion, beneficiaryBankBic, chargeOption,
                    requestedValueDate, at);
        }
    }

    /**
     * 一次 C5 试算。status 是评分状态（SCORED / NO_REACH / NOT_PUBLISHED / NOT_MATCHED / RULE_DISABLED / ERROR），
     * 与 scoreStatus 相同；只有 SCORED 时 score 有值。groupResults 按优先级列出每个评分组的命中结果。
     * replay = true 表示用的是指定的历史已发布版本；draft = true 表示用的是页面上的草稿（scoreRuleVersion 为空）。
     */
    public record Simulation(String status, long revision, Integer selectedIndex, Reach selectedReach, Integer c3ConditionVersion,
                             C5Calculator.Result calculation, boolean requestedValueDateDefaulted,
                             String scoreStatus, Integer score, String matchedScoreGroup, Integer scoreRuleVersion,
                             List<ScoreResult.GroupOutcome> groupResults, Map<String, Object> scoreDetails,
                             String scoreMessage, String note, boolean replay, boolean draft) {
    }

    public C3Service.Listing list() {
        return c3.list();
    }

    @Transactional
    public C3Service.Listing save(Save in) {
        if (in == null || in.revision() == null || in.index() == null)
            throw new IllegalArgumentException("配置版本及覆盖行必填");
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        if (in.index() < 0 || in.index() >= cv.config().reaches().size())
            throw new IllegalArgumentException("覆盖行不存在");
        C5Calculator.validate(in.zone(), in.cutoff(), in.offsetDays(), in.holidays());
        var rows = new ArrayList<>(cv.config().reaches());
        Reach r = rows.get(in.index());
        rows.set(in.index(), new Reach(r.provider(), r.currency(), r.country(), r.region(), r.bics(), r.charges(), in.zone(), in.cutoff(), in.offsetDays(), r.perPaymentUsd(), r.dailyUsd(), List.copyOf(in.holidays()), r.enabled()));
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withReaches(rows)));
        return list();
    }

    /** 用决策时间生效的已发布评分规则版本试算。只查询和计算，不修改交易、资金或配置。 */
    public Simulation simulate(Input in) {
        return simulate(in, (ScoreRuleService.PublishedView) null, false, false);
    }

    /** 用指定的已发布版本试算（replay = true），用于核对历史版本的评分结果。版本不存在时报参数错误。 */
    public Simulation simulate(Input in, Integer scoreRuleVersion) {
        if (scoreRuleVersion == null) throw new IllegalArgumentException("评分规则版本必填");
        return simulate(in, scoreRules.version(C5ScoreService.RULE_CODE, scoreRuleVersion), true, false);
    }

    /**
     * 用页面上的草稿试算（draft = true）：草稿不需要已保存，只做结构校验和编译，不要求通过发布前检查；
     * 不写任何数据，也不影响实际路由——正式评分只认已发布的版本。
     */
    public Simulation simulateDraft(Input in, JsonNode draft) {
        if (in == null || draft == null) throw new IllegalArgumentException("模拟请求和评分规则草稿必填");
        ScoreRule rule = scoreRules.draftRule(draft, C5ScoreService.RULE_CODE, C5ScoreService.INPUT_FIELDS);
        LocalDateTime now = LocalDateTime.now(clock);
        return simulate(in, new ScoreRuleService.PublishedView(0, rule, now, now, "draft", "草稿试算"), false, true);
    }

    private Simulation simulate(Input in, ScoreRuleService.PublishedView rule, boolean replay, boolean draft) {
        if (in == null) throw new IllegalArgumentException("模拟请求必填");
        var match = new C3Matcher.Input(in.providerCode(), in.paymentCurrency(), in.beneficiaryBankCountry(), in.beneficiaryRegion(), in.beneficiaryBankBic(), in.chargeOption());
        C3Matcher.validateInput(match);
        if (!providers.existsById(in.providerCode())) throw new IllegalArgumentException("合作方不存在");
        var cv = routing.config();
        // 与路由引擎同一入口：C3 的最终选择（已按「指定地区 > ALL、指定国家 > ALL、银行 LIST > ALL」取最精确行，并过 C3 条件），C5 不重新挑选
        var selected = c3Conditions.select(cv.config().reaches(), match).orElse(null);
        if (selected == null)
            return new Simulation("NO_REACH", cv.revision(), null, null, null, null, false, "NO_REACH", null, null, rule == null ? null : rule.version(), List.of(),
                    Map.of(), "C3 未匹配覆盖行，C5 无法计算起息日和分数", "C3 未匹配覆盖行，无法计算 C5；此处不是 C5 拒绝候选。请先维护业务覆盖。",
                    replay, draft);
        LocalDateTime decisionTime = in.at() == null ? LocalDateTime.now(clock) : in.at().withZoneSameInstant(BusinessClockConfig.BUSINESS_ZONE).toLocalDateTime();
        var reachMatch = ReachMatch.of(selected, c3Conditions.get().versionNo, cv.revision());
        // rule 为空：用决策时间生效的已发布版本；否则用调用方指定的版本（回放）或草稿（试算）
        var outcome = rule == null
                ? scoring.evaluate(reachMatch, in.providerCode(), in.clientId(), in.paymentCurrency(), in.requestedValueDate(), decisionTime)
                : scoring.evaluate(reachMatch, in.providerCode(), in.clientId(), in.paymentCurrency(), in.requestedValueDate(), decisionTime, rule);
        var score = outcome.score();
        String note = switch (score.status()) {
            case "SCORED" -> "C5 仅提供速度分数，即使得0分也不会剔除候选；最终结果仍需检查其他过滤器并加权排序。";
            case "NO_REACH" -> "C3 未匹配覆盖行，无法计算 C5；此处不是 C5 拒绝候选。请先维护业务覆盖。";
            default -> "未给出分数，这不是 C5 拒绝候选：" + score.message();
        };
        if (draft) note = "草稿试算（草稿未发布，不影响实际评分；正式评分只认已发布版本）：" + note;
        return new Simulation(score.status(), cv.revision(), selected.index(), selected.reach(), reachMatch.c3ConditionVersion(),
                outcome.calculation(), outcome.requestedValueDateDefaulted(), score.status(), score.score(), score.matchedGroupCode(),
                // 草稿没有版本号：不把内部的占位版本号 0 当成真实版本返回
                draft ? null : score.ruleVersion(), score.groups(), score.details(), score.message(), note, replay, draft);
    }
}
