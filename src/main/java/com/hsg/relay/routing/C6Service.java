package com.hsg.relay.routing;

import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static com.hsg.relay.routing.RoutingConfig.Reach;

/**
 * C6 限额控制：限额配置在 C3 覆盖行上，合作方详情维护 perPaymentUsd/dailyUsd 与当日累计演示快照（BRD 6.6、9.2）。
 */
@Service
public class C6Service {
    private static final MathContext MC = MathContext.DECIMAL128;
    private final RoutingService routing;
    private final ProviderRepository providers;

    public C6Service(RoutingService r, ProviderRepository p) {
        routing = r;
        providers = p;
    }

    public record LimitRow(int index, Reach reach) {
    }

    public record Listing(long revision, List<LimitRow> rows, Set<String> currencies,
                          Map<String, BigDecimal> todayUsd) {
    }

    public record Save(Long revision, Integer index, BigDecimal perPaymentUsd, BigDecimal dailyUsd) {
    }

    public record Today(Long revision, String provider, BigDecimal todayUsd) {
    }

    public record Revision(Long revision) {
    }

    public record Input(String providerCode, String paymentCurrency, BigDecimal amount, String beneficiaryBankCountry,
                        String beneficiaryRegion, String beneficiaryBankBic, String chargeOption, BigDecimal todayUsd,
                        ZonedDateTime at) {
    }

    public record Simulation(String status, long revision, Integer selectedIndex, Reach selectedReach,
                             C6Calculator.Result calculation, String note) {
    }

    public Listing limits() {
        var cv = routing.config();
        return new Listing(cv.revision(), IntStream.range(0, cv.config().reaches().size()).mapToObj(i -> new LimitRow(i, cv.config().reaches().get(i))).toList(),
                cv.config().hkdRates().keySet(), cv.config().todayUsd());
    }

    @Transactional
    public Listing save(Save in) {
        if (in == null || in.revision() == null || in.index() == null)
            throw new IllegalArgumentException("配置版本及覆盖行必填");
        C6Calculator.validate(in.perPaymentUsd(), in.dailyUsd());
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        if (in.index() < 0 || in.index() >= cv.config().reaches().size())
            throw new IllegalArgumentException("覆盖行不存在");
        var rows = new ArrayList<>(cv.config().reaches());
        Reach r = rows.get(in.index());
        rows.set(in.index(), new Reach(r.provider(), r.currency(), r.country(), r.region(), r.bics(), r.charges(), r.zone(), r.cutoff(), r.offsetDays(), in.perPaymentUsd(), in.dailyUsd(), r.holidays(), r.enabled()));
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withReaches(rows)));
        return limits();
    }

    @Transactional
    public Listing today(Today in) {
        if (in == null || in.revision() == null) throw new IllegalArgumentException("配置版本必填");
        if (in.provider() == null || !providers.existsById(in.provider()))
            throw new IllegalArgumentException("合作方不存在");
        if (in.todayUsd() == null || in.todayUsd().signum() < 0)
            throw new IllegalArgumentException("当日累计必须为非负数字");
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        Map<String, BigDecimal> map = new HashMap<>(cv.config().todayUsd());
        map.put(in.provider(), in.todayUsd());
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withTodayUsd(map)));
        return limits();
    }

    @Transactional
    public Listing deleteToday(String provider, Long revision) {
        if (provider == null || !providers.existsById(provider))
            throw new IllegalArgumentException("合作方不存在");
        var cv = routing.config();
        if (revision == null || revision != cv.revision())
            throw new IllegalArgumentException("配置已更新，请刷新后重试");
        Map<String, BigDecimal> map = new HashMap<>(cv.config().todayUsd());
        map.remove(provider);
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withTodayUsd(map)));
        return limits();
    }

    public Simulation simulate(Input in) {
        if (in == null) throw new IllegalArgumentException("模拟请求必填");
        var match = new C3Matcher.Input(in.providerCode(), in.paymentCurrency(), in.beneficiaryBankCountry(), in.beneficiaryRegion(), in.beneficiaryBankBic(), in.chargeOption());
        C3Matcher.validateInput(match);
        if (!providers.existsById(in.providerCode())) throw new IllegalArgumentException("合作方不存在");
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("交易金额必须大于0");
        var cv = routing.config();
        var cfg = cv.config();
        var selected = C3Matcher.select(cfg.reaches(), match).orElse(null);
        if (selected == null)
            return new Simulation("NO_REACH", cv.revision(), null, null, null, "C3 未匹配覆盖行，C6 限额不适用；该候选已被 C3 剔除（NO_REACH）。请先在合作方详情的业务覆盖页签维护。");
        BigDecimal usd = usd(cfg, in.amount(), in.paymentCurrency());
        BigDecimal today = in.todayUsd() != null ? in.todayUsd() : cfg.todayUsd().getOrDefault(in.providerCode(), BigDecimal.ZERO);
        var calc = C6Calculator.check(selected.reach(), usd, today);
        String status = calc.passed() ? "PASS" : calc.reasons().get(0);
        return new Simulation(status, cv.revision(), selected.index(), selected.reach(), calc,
                "限额与当日累计均为美元等价：本笔＝交易金额×HSG中间汇率÷USD汇率。当日累计不含本笔自身（BRD 术语表）；重算路由时本笔不计入占用。C6 为过滤器，任一不通过该候选即被剔除。");
    }

    private static BigDecimal usd(RoutingConfig cfg, BigDecimal amount, String currency) {
        BigDecimal toHkd = cfg.hkdRates().get(currency);
        if (toHkd == null || toHkd.signum() <= 0) throw new IllegalArgumentException("缺少汇率：" + currency);
        return amount.multiply(toHkd, MC).divide(cfg.hkdRates().get("USD"), MC);
    }
}
