package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.c2.C2Service;
import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import com.hsg.relay.service.C1Service;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.*;

@Service
public class RoutingService {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");
    /** 路由接受的交易类型；综合评分权重配置（全局）也按同一组取值。 */
    public static final Set<String> TRANSACTION_TYPES = Set.of("TRADE", "TREASURY", "INTRAGROUP", "PAYROLL", "FEE", "REFUND", "OTHER");
    private final RoutingDocumentRepository docs;
    private final ObjectMapper json;
    private final ProviderRepository providers;
    private final ClientProfileRepository clients;
    private final RuleAuditRepository audit;
    private final C1Service c1;
    private final C2Service c2;
    private final C3ConditionService c3Conditions;
    private final C5ScoreService c5Scoring;
    private final RouteRankingService ranking;
    private final com.hsg.relay.service.CheckSwitchService checkSwitches;

    public RoutingService(RoutingDocumentRepository d, ObjectMapper j, ProviderRepository p, ClientProfileRepository cl, RuleAuditRepository a, C1Service c1, C2Service c2, C3ConditionService c3Conditions,
                          C5ScoreService c5Scoring, RouteRankingService ranking,
                          com.hsg.relay.service.CheckSwitchService checkSwitches) {
        docs = d;
        json = j;
        providers = p;
        clients = cl;
        audit = a;
        this.c1 = c1;
        this.c2 = c2;
        this.c3Conditions = c3Conditions;
        this.c5Scoring = c5Scoring;
        this.ranking = ranking;
        this.checkSwitches = checkSwitches;
    }

    public record ConfigView(long revision, RoutingConfig config) {
    }

    public record Request(String paymentId, String clientId, String debitCurrency, String paymentCurrency,
                          BigDecimal amount,
                          String beneficiaryBankCountry, String beneficiaryAddressCountry, String beneficiaryRegion,
                          String beneficiaryBankBic,
                          String chargeOption, String orderingCountry, String clientCountry, String intermediaryCountry,
                          String purpose,
                          List<String> directorUboNationalities, String paymentType, String clientSegment,
                          @com.fasterxml.jackson.annotation.JsonAlias("requested_value_date") LocalDate requestedValueDate,
                          ZonedDateTime at, List<String> includeProviders) {
    }

    public record Check(String code, boolean passed, List<String> reasons, Object detail) {
    }

    public static class Candidate {
        public String providerCode, fxVenue, channel;
        public int priority;
        public boolean passed;
        public List<Check> checks = new ArrayList<>();
        public LocalDate valueDate;
        public BigDecimal estimatedCostHkd;
        public Map<String, BigDecimal> scores = new LinkedHashMap<>();
        // 综合评分：rawScore 是未舍入的满精度值（审计用），totalScore 是两位小数、HALF_UP 的最终总分，排序只用 totalScore
        public BigDecimal totalScore;
        public Integer rank;
        // PRIMARY / ALTERNATE_1 / ALTERNATE_2；排在第四名及以后为 NOT_SELECTED，未参与排序的候选为空
        public String role;
        public ZonedDateTime nextFxWindow;
        public C5Calculator.Result c5;
        // C3 最终选中的覆盖行（行号 + C3 条件版本）：C5 的截单时间、偏移、日历都出自这一行，C6 用同一条覆盖行
        public Integer matchedReachRowId;
        public Integer matchedReachRuleVersion;
        // C5 评分结果。c5Score 只在 c5Status = SCORED 时有值；其余状态表示评分不可计算（候选保留、不参与排序），不是 C5 剔除
        public String c5Status;
        public Integer c5Score;
        public String c5ScoreGroup;
        public Integer c5RuleVersion;
        public String c5Message;
        @com.fasterxml.jackson.annotation.JsonIgnore
        public BigDecimal rawCost, rawScore;
    }

    public record Result(String decisionId, String paymentId, String result, String reasonCode, Candidate route,
                         List<Candidate> alternatives,
                         List<Candidate> candidates, ZonedDateTime nextFxWindow, long configRevision,
                         boolean simulation, boolean opsCheckRequired, List<String> messages,
                         RouteRankingService.MatchInfo ranking) {
    }

    private String encode(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public ConfigView config() {
        RoutingDocument d = docs.findById("CONFIG").orElse(null);
        try {
            return d == null ? new ConfigView(0, json.readValue(getClass().getResourceAsStream("/routing-demo.json"), RoutingConfig.class)) : new ConfigView(d.revision, json.readValue(d.payload, RoutingConfig.class));
        } catch (Exception e) {
            throw new IllegalStateException("路由参考配置不可读", e);
        }
    }

    @Transactional
    public ConfigView saveConfig(ConfigView v) {
        if (v == null || v.config() == null) throw new IllegalArgumentException("配置必填");
        ConfigView before = config();
        if (v.revision() != before.revision()) throw new IllegalArgumentException("配置已更新，请重新加载");
        validateConfig(v.config());
        docs.save(new RoutingDocument("CONFIG", v.revision() + 1, encode(v.config())));
        audit.save(new RuleAudit("ROUTING_CONFIG", "CONFIG", "UPDATE", "demo.operator", encode(before), encode(v.config())));
        return new ConfigView(v.revision() + 1, v.config());
    }

    public void validateConfig(RoutingConfig c) {
        try {
            if (c.hkdRates() == null || c.reaches() == null || c.balances() == null || c.fees() == null || c.fxWindows() == null || c.preferences() == null || c.agreements() == null || c.weights() == null || c.todayUsd() == null || c.monthUsd() == null || c.monthCount() == null || c.hongKongHolidays() == null)
                throw new IllegalArgumentException("配置集合不可缺失");
            c.hkdRates().values().forEach(x -> {
                if (x == null || x.signum() <= 0) throw new IllegalArgumentException("汇率必须大于零");
            });
            if (!BigDecimal.ONE.equals(c.hkdRates().get("HKD"))) throw new IllegalArgumentException("HKD 汇率必须为1");
            Set<String> keys = new HashSet<>();
            for (Weight w : c.weights()) {
                if (w.cost() < 0 || w.speed() < 0 || w.preference() < 0 || w.agreement() < 0 || w.cost() + w.speed() + w.preference() + w.agreement() != 100 || !keys.add(w.paymentType() + ":" + w.segment()))
                    throw new IllegalArgumentException("权重必须非负、合计100且维度唯一");
            }
            if (c.weights().stream().noneMatch(w -> "OTHER".equals(w.paymentType()) && "ALL".equals(w.segment())))
                throw new IllegalArgumentException("缺少 OTHER/ALL 默认权重");
            keys.clear();
            for (Reach r : c.reaches()) {
                provider(r.provider());
                rate(c, r.currency());
                C3Matcher.validate(r);
                ZoneId.of(r.zone());
                Objects.requireNonNull(r.cutoff());
                Objects.requireNonNull(r.country());
                Objects.requireNonNull(r.region());
                Objects.requireNonNull(r.bics());
                Objects.requireNonNull(r.charges());
                Objects.requireNonNull(r.holidays());
                nonnegative(r.perPaymentUsd(), r.dailyUsd());
                C5Calculator.validate(r.zone(), r.cutoff(), r.offsetDays(), r.holidays());
                if (!keys.add(r.provider() + r.currency() + r.country() + r.region() + new TreeSet<>(r.bics()) + new TreeSet<>(r.charges())))
                    throw new IllegalArgumentException("覆盖范围重复");
            }
            keys.clear();
            for (Balance b : c.balances()) {
                provider(b.provider());
                rate(c, b.currency());
                nonnegative(b.balance(), b.reserved());
                if (!keys.add(b.provider() + b.currency())) throw new IllegalArgumentException("余额账户重复");
            }
            keys.clear();
            for (Fee f : c.fees()) {
                provider(f.provider());
                rate(c, f.currency());
                rate(c, f.feeCurrency());
                nonnegative(f.fixed(), f.rate(), f.minimum(), f.cable(), f.intermediary());
                if (f.maximum() != null && f.maximum().compareTo(f.minimum()) < 0)
                    throw new IllegalArgumentException("最高手续费小于最低费");
                if (!keys.add(f.provider() + f.currency() + f.charge()))
                    throw new IllegalArgumentException("费用维度重复");
            }
            for (FxWindow w : c.fxWindows()) {
                if (!"TREASURY".equals(w.provider())) provider(w.provider());
                rate(c, w.debit());
                rate(c, w.payment());
                if (w.days() == null || w.days().isEmpty() || w.days().stream().anyMatch(d -> !Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOLIDAY").contains(d)) || w.start() == null || w.end() == null || !w.end().isAfter(w.start()))
                    throw new IllegalArgumentException("外汇窗口需同日且起止时间有效");
                nonnegative(w.spreadBps(), w.extraBps());
                if (w.maxUsd() != null) nonnegative(w.maxUsd());
            }
            for (Preference p : c.preferences()) {
                provider(p.provider());
                if (p.points() < 1 || p.points() > 100) throw new IllegalArgumentException("偏好奖励范围1–100");
                if (p.minUsd() != null) nonnegative(p.minUsd());
                if (p.maxUsd() != null && (p.maxUsd().signum() < 0 || (p.minUsd() != null && p.maxUsd().compareTo(p.minUsd()) < 0)))
                    throw new IllegalArgumentException("偏好金额范围无效");
            }
            for (Agreement a : c.agreements()) {
                provider(a.provider());
                if (!Set.of("AMOUNT", "COUNT").contains(a.metric()))
                    throw new IllegalArgumentException("履约指标应为 AMOUNT 或 COUNT");
                nonnegative(a.monthlyTarget());
            }
            c.todayUsd().values().forEach(x -> nonnegative(x));
            c.monthUsd().values().forEach(x -> nonnegative(x));
            if (c.monthCount().values().stream().anyMatch(x -> x == null || x < 0))
                throw new IllegalArgumentException("累计笔数不可负");
        } catch (NullPointerException | DateTimeException e) {
            throw new IllegalArgumentException("配置字段缺失或时区无效", e);
        }
    }

    private void provider(String code) {
        if (code == null || !providers.existsById(code)) throw new IllegalArgumentException("无效合作方：" + code);
    }

    private static void nonnegative(BigDecimal... v) {
        for (BigDecimal x : v) if (x == null || x.signum() < 0) throw new IllegalArgumentException("配置金额必须非负");
    }

    private static BigDecimal rate(RoutingConfig c, String currency) {
        BigDecimal v = c.hkdRates().get(currency);
        if (v == null || v.signum() <= 0) throw new IllegalArgumentException("缺少汇率：" + currency);
        return v;
    }

    private static BigDecimal n(int x) {
        return BigDecimal.valueOf(x);
    }

    private static BigDecimal money(BigDecimal x) {
        return RouteTotalScoreCalculator.round2(x);
    }

    private static void check(Candidate c, String code, List<String> reasons, Object detail) {
        c.checks.add(new Check(code, reasons.isEmpty(), reasons, detail));
    }

    private static List<String> only(Candidate c) {
        return c.checks.stream().filter(x -> !x.passed()).map(Check::code).toList();
    }

    static boolean day(FxWindow w, LocalDate d, RoutingConfig cfg) {
        return C10Calculator.day(w, d, cfg);
    }

    public Result simulate(Request req) {
        ConfigView cv = config();
        String id = UUID.randomUUID().toString();
        List<String> errors = validateRequest(req, cv.config());
        Result result;
        if (!errors.isEmpty())
            result = new Result(id, req == null ? null : req.paymentId(), "REFER", "INVALID_REQUEST", null, List.of(), List.of(), null, cv.revision(), true, true, errors, null);
        else result = evaluate(req, cv, id);
        docs.save(new RoutingDocument("DECISION-" + id, cv.revision(), encode(Map.of("request", req == null ? Map.of() : req, "response", result))));
        return result;
    }

    private List<String> validateRequest(Request r, RoutingConfig c) {
        if (r == null) return List.of("请求不能为空");
        List<String> e = new ArrayList<>();
        if (r.paymentId() == null || r.paymentId().isBlank() || r.clientId() == null || !clients.existsById(r.clientId()))
            e.add("交易编号及有效客户必填");
        if (r.amount() == null || r.amount().signum() <= 0) e.add("交易金额必须大于0");
        if (!c.hkdRates().containsKey(r.debitCurrency()) || !c.hkdRates().containsKey(r.paymentCurrency()))
            e.add("币种无效或未配置汇率");
        for (String s : Arrays.asList(r.beneficiaryBankCountry(), r.beneficiaryAddressCountry(), r.orderingCountry(), r.clientCountry()))
            if (s == null || !s.matches("[A-Z]{2}")) e.add("国家编码需为两位大写字母");
        if (r.beneficiaryBankBic() == null || !r.beneficiaryBankBic().matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?"))
            e.add("受益行 BIC 格式无效");
        if (r.chargeOption() == null || !Set.of("OUR", "SHA", "BEN").contains(r.chargeOption())) e.add("扣费方式无效");
        if (r.paymentType() == null || !TRANSACTION_TYPES.contains(r.paymentType()))
            e.add("交易类型无效");
        if (r.clientSegment() == null || r.clientSegment().isBlank() || r.directorUboNationalities() == null || r.directorUboNationalities().stream().anyMatch(x -> x == null || !x.matches("[A-Z]{2}")))
            e.add("客户层级及董事/UBO国籍列表必填");
        if (r.includeProviders() != null && r.includeProviders().stream().anyMatch(x -> x == null || !providers.existsById(x)))
            e.add("包含的合作方无效");
        return e;
    }

    private Result evaluate(Request r, ConfigView cv, String id) {
        RoutingConfig cfg = cv.config();
        ZonedDateTime now = r.at() == null ? ZonedDateTime.now(HK) : r.at().withZoneSameInstant(HK);
        BigDecimal amountHkd = r.amount().multiply(rate(cfg, r.paymentCurrency()), MC), usd = amountHkd.divide(rate(cfg, "USD"), MC);
        boolean fx = !r.debitCurrency().equals(r.paymentCurrency());
        List<Candidate> all = new ArrayList<>();
        var txn = new C8Calculator.Txn(r.paymentCurrency(), r.beneficiaryBankCountry(), r.beneficiaryRegion(), usd, r.paymentType(), r.clientSegment());
        for (Provider p : providers.findAll()) {
            if (!"LIVE".equals(p.status) && (r.includeProviders() == null || !r.includeProviders().contains(p.code)))
                continue;
            List<String> venues = new ArrayList<>();
            if (!fx) venues.add("NONE");
            else {
                if (cfg.fxWindows().stream().anyMatch(w -> w.provider().equals("TREASURY") && w.debit().equals(r.debitCurrency()) && w.payment().equals(r.paymentCurrency())))
                    venues.add("TREASURY");
                if (cfg.fxWindows().stream().anyMatch(w -> w.provider().equals(p.code) && w.debit().equals(r.debitCurrency()) && w.payment().equals(r.paymentCurrency())))
                    venues.add("PROVIDER");
            }
            for (String venue : venues) {
                Candidate c = new Candidate();
                c.providerCode = p.code;
                c.fxVenue = venue;
                c.channel = p.channel;
                c.priority = p.priority;
                all.add(c);
                var a = c1.evaluate(new C1Service.Simulation(r.clientId(), p.code, r.paymentCurrency(), r.beneficiaryBankCountry(), usd), now.toLocalDateTime());
                check(c, "C1", a.passed() ? List.of() : List.of(a.reasonCode()), a);
                var b = c2.simulate(new C2Service.Simulation(p.code, r.paymentCurrency(), r.debitCurrency(), r.beneficiaryBankCountry(), r.beneficiaryAddressCountry(), r.beneficiaryRegion(), r.orderingCountry(), r.clientCountry(), r.intermediaryCountry(), r.purpose(), r.directorUboNationalities()), now.toLocalDateTime());
                check(c, "C2", b.passed() ? List.of() : b.hits().isEmpty() ? List.of(b.reasonCode())
                        : b.hits().stream().map(C2Service.RuleHit::reasonCode).distinct().toList(), b);
                var c3Row = c3Conditions.select(cfg.reaches(), new C3Matcher.Input(p.code, r.paymentCurrency(), r.beneficiaryBankCountry(), r.beneficiaryRegion(), r.beneficiaryBankBic(), r.chargeOption())).orElse(null);
                Reach reach = c3Row == null ? null : c3Row.reach();
                check(c, "C3", !checkSwitches.enabled("C3") || reach != null ? List.of() : List.of("NO_REACH"),
                        checkSwitches.enabled("C3") ? reach : "SKIPPED：C3 校验开关已关闭；仍查找覆盖行供 C5/C6 使用");
                if (checkSwitches.enabled("C4")) {
                    var c4 = C4Calculator.check(p.code, p.fundingCheckEnabled, venue, r.paymentCurrency(), r.debitCurrency(), r.amount(), cfg.hkdRates(), cfg.balances());
                    check(c, "C4", c4.reasons(), c4);
                } else check(c, "C4", List.of(), "SKIPPED：C4 校验开关已关闭");
                if (checkSwitches.enabled("C6")) {
                    var c6 = reach == null ? null : C6Calculator.check(reach, usd, cfg.todayUsd().getOrDefault(p.code, BigDecimal.ZERO));
                    check(c, "C6", reach == null ? List.of() : c6.reasons(), reach == null ? "无覆盖行，限额不适用" : c6);
                } else check(c, "C6", List.of(), "SKIPPED：C6 校验开关已关闭");
                var c10 = C10Calculator.check(venue, "TREASURY".equals(venue) ? "TREASURY" : p.code, r.debitCurrency(), r.paymentCurrency(), cfg.fxWindows(), usd, now, cfg);
                c.nextFxWindow = c10.nextWindow();
                check(c, "C10", checkSwitches.enabled("C10") && fx && !c10.passed() ? List.of(c10.reason()) : List.of(),
                        !checkSwitches.enabled("C10") ? "SKIPPED：C10 校验开关已关闭" : c10.open() != null ? c10.open() : "NONE".equals(venue) ? "无需换汇" : "无可执行窗口");
                c.passed = only(c).isEmpty();
                if (reach != null) {
                    // C3 最终选中的覆盖行原样交给 C5 评分执行器：先算起息日（每个候选一次），再按已发布的评分规则取分
                    var c5Outcome = c5Scoring.evaluate(ReachMatch.of(c3Row, c3Conditions.get().versionNo, cv.revision()), p.code, r.clientId(),
                            r.paymentCurrency(), r.requestedValueDate(), now.toLocalDateTime());
                    var c5Score = c5Outcome.score();
                    c.c5 = c5Outcome.calculation();
                    c.valueDate = c.c5 == null ? null : c.c5.candidateValueDate();
                    c.matchedReachRowId = c3Row.index();
                    c.matchedReachRuleVersion = c3Conditions.get().versionNo;
                    c.c5Status = c5Score.status();
                    c.c5Score = c5Score.score();
                    c.c5ScoreGroup = c5Score.matchedGroupCode();
                    c.c5RuleVersion = c5Score.ruleVersion();
                    c.c5Message = c5Score.message();
                    // 分数来自已发布的 C5 评分规则；不是 SCORED 就没有分数，不伪造 0 分或 100 分
                    if (c5Outcome.scored()) c.scores.put("C5", n(c5Score.score()));
                }
                // 评分与硬过滤独立：被剔除的候选也保留成本、偏好及履约分，供规则排查。
                // 缺少手续费配置时不能伪造 C7；已通过过滤的候选仍按原口径报配置错误。
                try {
                    Fee fee = C7Calculator.feeFor(cfg.fees(), p.code, r.paymentCurrency(), r.chargeOption());
                    BigDecimal spread = c10.open() == null ? BigDecimal.ZERO : c10.open().spreadBps().add(c10.open().extraBps());
                    var c7 = C7Calculator.estimate(fee, amountHkd, rate(cfg, fee.feeCurrency()), spread);
                    c.rawCost = c7.totalHkd();
                    c.estimatedCostHkd = money(c.rawCost);
                } catch (IllegalStateException missingFee) {
                    if (c.passed || !missingFee.getMessage().startsWith("缺少手续费配置：")) throw missingFee;
                }
                c.scores.put("C8", n(C8Calculator.scoreFor(cfg.preferences(), p.code, txn)));
                int agreement = C9Calculator.score(cfg.agreements().stream().filter(x -> x.provider().equals(p.code)).toList(), cfg.monthUsd().getOrDefault(p.code, BigDecimal.ZERO), cfg.monthCount().getOrDefault(p.code, 0L), now);
                c.scores.put("C9", n(agreement));
            }
        }
        // 通过全部过滤但 C5 评分不可计算的候选：保留在 candidates 里（passed = true），不参与加权排序，并在结果消息里逐个说明——这不是 C5 剔除
        List<Candidate> unscored = all.stream().filter(c -> c.passed && c.c5Score == null).toList();
        List<String> messages = new ArrayList<>(List.of("模拟结果不占用资金或限额；客户分类与流转类别读取本地客户档案；国籍、余额、汇率及累计量为演示输入。"));
        for (Candidate c : unscored)
            messages.add(c.providerCode + "：C5 评分不可计算（" + c.c5Status + " — " + c.c5Message + "），保留在候选清单但不参与排序；这不是 C5 剔除候选");
        List<Candidate> valid = new ArrayList<>(all.stream().filter(c -> c.passed && c.c5Score != null).toList());
        // C7 相对成本以本次所有可估成本的候选为基准，不随过滤通过与否改变。
        BigDecimal min = all.stream().map(c -> c.rawCost).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        for (Candidate c : all) if (c.rawCost != null) c.scores.put("C7", money(C7Calculator.score(min, c.rawCost)));
        // 综合评分与排序：按交易类型 + 客户层级从全局权重配置匹配一套权重（P1 → P2 → P3），全部候选用同一个版本计算、按多级条件排序；
        // 没有合格候选时不匹配权重，保持下面的 REFER 原因码不变；没有生效的权重配置或评分数据异常时抛出异常（不默认 0 权重、不按 0 分）
        var outcome = ranking.rank(r.paymentId(), id, r.paymentType(), r.clientSegment(), valid, now.toLocalDateTime());
        // 排名 1 → Primary，2 → Alternate 1，3 → Alternate 2；不足三个有多少取多少
        List<Candidate> ordered = outcome.ranked().stream().map(RouteRankingService.Scored::candidate).toList();
        if (!ordered.isEmpty())
            messages.add("综合评分使用权重 " + RouteRankingService.describe(outcome.match()) + "；排序：总分降序 → 预估成本 HKD 升序 → 起息日升序 → 合作方优先级 → 合作方编码。");
        String reason = null;
        if (ordered.isEmpty()) {
            if (fx && all.isEmpty()) reason = "NO_FX_VENUE";
            else if (!unscored.isEmpty()) reason = "C5_NOT_SCOREABLE";
            else if (!all.isEmpty() && all.stream().allMatch(c -> c.checks.stream().anyMatch(x -> Set.of("C1", "C2").contains(x.code()) && !x.passed())))
                reason = "NO_PROVIDER_ACCEPTS";
            else if (all.stream().anyMatch(c -> only(c).equals(List.of("C10")))) reason = "FX_WINDOW_CLOSED";
            else if (all.stream().anyMatch(c -> only(c).equals(List.of("C4")))) reason = "FUNDING_SHORTFALL";
            else reason = "NO_ROUTE";
        }
        ZonedDateTime next = "FX_WINDOW_CLOSED".equals(reason) ? all.stream().filter(c -> only(c).equals(List.of("C10"))).map(c -> c.nextFxWindow).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null) : null;
        return new Result(id, r.paymentId(), ordered.isEmpty() ? "REFER" : "ROUTE", reason, ordered.isEmpty() ? null : ordered.get(0), ordered.stream().skip(1).limit(2).toList(), all, next, cv.revision(), true, true, messages, outcome.match());
    }

    public Object decision(String id) {
        return docs.findById("DECISION-" + id).map(d -> {
            try {
                return json.readTree(d.payload);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).orElseThrow(() -> new IllegalArgumentException("决策不存在"));
    }
}
