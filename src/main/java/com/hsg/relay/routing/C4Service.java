package com.hsg.relay.routing;

import com.hsg.relay.domain.Provider;
import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

import static com.hsg.relay.routing.RoutingConfig.Balance;

/**
 * C4 头寸资金校验：往来账余额与未记账占用演示数据维护 + 单合作方分渠道头寸判定（BRD 6.4）。C4 为过滤器；资金校验开关在合作方管理维护（9.1）。
 */
@Service
public class C4Service {
    private final RoutingService routing;
    private final ProviderRepository providers;
    private final com.hsg.relay.service.CheckSwitchService checkSwitches;

    public C4Service(RoutingService r, ProviderRepository p, com.hsg.relay.service.CheckSwitchService checkSwitches) {
        routing = r;
        providers = p;
        this.checkSwitches = checkSwitches;
    }

    public record BalanceRow(int index, Balance balance) {
    }

    public record FundingSwitch(String code, String name, String status, boolean fundingCheckEnabled,
                                String nostroAccount) {
    }

    public record Listing(long revision, List<BalanceRow> rows, Set<String> currencies,
                          Map<String, BigDecimal> hkdRates, List<FundingSwitch> fundingSwitches) {
    }

    public record Save(Long revision, Integer index, Balance balance) {
    }

    public record Delete(Long revision, Integer index) {
    }

    /**
     * fundingCheckEnabled 为空时使用合作方已保存的开关；填写仅覆盖本次试算，不落库。
     */
    public record Input(String providerCode, String debitCurrency, String paymentCurrency, BigDecimal amount,
                        Boolean fundingCheckEnabled) {
    }

    public record Venue(String fxVenue, C4Calculator.Result calculation) {
    }

    public record Simulation(String status, long revision, String providerCode, boolean fundingCheckEnabled, boolean fx,
                             List<Venue> venues, String note) {
    }

    public Listing balances() {
        var cv = routing.config();
        return new Listing(cv.revision(), IntStream.range(0, cv.config().balances().size()).mapToObj(i -> new BalanceRow(i, cv.config().balances().get(i))).toList(),
                cv.config().hkdRates().keySet(), cv.config().hkdRates(),
                providers.findAll().stream().sorted(Comparator.comparing(p -> p.code)).map(p -> new FundingSwitch(p.code, p.name, p.status, p.fundingCheckEnabled, p.nostroAccount)).toList());
    }

    @Transactional
    public Listing save(Save in) {
        if (in == null || in.revision() == null || in.balance() == null)
            throw new IllegalArgumentException("配置版本及余额行必填");
        validateBalance(in.balance(), in.index());
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        var rows = new ArrayList<>(cv.config().balances());
        if (in.index() == null) rows.add(in.balance());
        else {
            if (in.index() < 0 || in.index() >= rows.size()) throw new IllegalArgumentException("余额行不存在");
            rows.set(in.index(), in.balance());
        }
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withBalances(rows)));
        return balances();
    }

    @Transactional
    public Listing delete(Delete in) {
        if (in == null || in.revision() == null || in.index() == null)
            throw new IllegalArgumentException("配置版本及余额行必填");
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        if (in.index() < 0 || in.index() >= cv.config().balances().size())
            throw new IllegalArgumentException("余额行不存在");
        var rows = new ArrayList<>(cv.config().balances());
        rows.remove(in.index().intValue());
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withBalances(rows)));
        return balances();
    }

    private void validateBalance(Balance b, Integer skipIndex) {
        if (b.provider() == null || !providers.existsById(b.provider()))
            throw new IllegalArgumentException("合作方不存在");
        if (b.currency() == null || !b.currency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("币种须为三位大写编码");
        rate(routing.config().config(), b.currency());
        if (b.balance() == null || b.reserved() == null || b.balance().signum() < 0 || b.reserved().signum() < 0)
            throw new IllegalArgumentException("账户余额及未记账占用必须为非负数字");
        var rows = routing.config().config().balances();
        for (int i = 0; i < rows.size(); i++) {
            if (skipIndex != null && i == skipIndex.intValue()) continue;
            Balance x = rows.get(i);
            if (x.provider().equals(b.provider()) && x.currency().equals(b.currency()))
                throw new IllegalArgumentException("该合作方此币种的余额行已存在，请编辑原行");
        }
    }

    public Simulation simulate(Input in) {
        if (in == null) throw new IllegalArgumentException("模拟请求必填");
        Provider p = in.providerCode() == null ? null : providers.findById(in.providerCode()).orElse(null);
        if (p == null) throw new IllegalArgumentException("合作方不存在");
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("交易金额必须大于0");
        var cv = routing.config();
        var cfg = cv.config();
        rate(cfg, in.debitCurrency());
        rate(cfg, in.paymentCurrency());
        if (!checkSwitches.enabled("C4"))
            return new Simulation("SKIPPED", cv.revision(), p.code, p.fundingCheckEnabled,
                    !in.debitCurrency().equals(in.paymentCurrency()), List.of(), "C4 校验开关已关闭，跳过头寸资金校验");
        boolean enabled = in.fundingCheckEnabled() == null ? p.fundingCheckEnabled : in.fundingCheckEnabled();
        boolean fx = !in.debitCurrency().equals(in.paymentCurrency());
        // 渠道生成与全流程候选一致：无需换汇为 NONE；换汇时按已配置窗口生成 TREASURY / PROVIDER
        List<Venue> venues = new ArrayList<>();
        if (!fx) venues.add(venue(p.code, enabled, "NONE", in, cfg));
        else {
            if (!C10Calculator.windowsFor(cfg.fxWindows(), "TREASURY", in.debitCurrency(), in.paymentCurrency()).isEmpty())
                venues.add(venue(p.code, enabled, "TREASURY", in, cfg));
            if (!C10Calculator.windowsFor(cfg.fxWindows(), p.code, in.debitCurrency(), in.paymentCurrency()).isEmpty())
                venues.add(venue(p.code, enabled, "PROVIDER", in, cfg));
        }
        String status = fx && venues.isEmpty() ? "NO_FX_VENUE" : venues.stream().anyMatch(v -> v.calculation().passed()) ? "PASS" : C4Calculator.SHORTFALL;
        String note;
        if (fx && venues.isEmpty())
            note = "该货币对没有任何外汇渠道窗口配置：全流程路由不生成候选，C4 不适用，返回 REFER NO_FX_VENUE。";
        else {
            note = "C4 为过滤器：可用头寸 ≥ 本笔账户币种等价金额才通过，否则 FUNDING_SHORTFALL 并剔除该候选；全部候选仅因 C4 失败时整体返回 REFER FUNDING_SHORTFALL，运营审核环节资金部确认补头寸后可人工选用该合作方（示例 6b）。";
            if (!enabled)
                note += "当前开关 OFF：直接通过，账户与头寸数值仅供参考（后补头寸的合作方，资金由资金部在出款前补足）。";
            if (in.fundingCheckEnabled() != null) note += "本次试算以所选开关覆盖已保存值，不落库。";
        }
        return new Simulation(status, cv.revision(), p.code, enabled, fx, List.copyOf(venues), note);
    }

    private static Venue venue(String provider, boolean enabled, String fxVenue, Input in, RoutingConfig cfg) {
        return new Venue(fxVenue, C4Calculator.check(provider, enabled, fxVenue, in.paymentCurrency(), in.debitCurrency(), in.amount(), cfg.hkdRates(), cfg.balances()));
    }

    private static BigDecimal rate(RoutingConfig cfg, String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("币种须为三位大写编码");
        BigDecimal v = cfg.hkdRates().get(currency);
        if (v == null || v.signum() <= 0) throw new IllegalArgumentException("缺少汇率：" + currency);
        return v;
    }
}
