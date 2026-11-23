package com.hsg.relay.routing;

import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;

import static com.hsg.relay.routing.RoutingConfig.*;

/**
 * C7 综合成本：手续费配置维护 + 单合作方成本预估（含换汇点差，BRD 6.7、9.3）。
 */
@Service
public class C7Service {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");
    private final RoutingService routing;
    private final ProviderRepository providers;

    public C7Service(RoutingService r, ProviderRepository p) {
        routing = r;
        providers = p;
    }

    public record FeeRow(int index, Fee fee) {
    }

    public record Listing(long revision, List<FeeRow> rows, Set<String> currencies, Map<String, BigDecimal> hkdRates) {
    }

    public record Save(Long revision, Integer index, Fee fee) {
    }

    public record Delete(Long revision, Integer index) {
    }

    public record Venue(String fxVenue, FxWindow window, boolean windowOpen, String windowNote, BigDecimal spreadBps,
                        C7Calculator.Result calculation) {
    }

    public record Input(String providerCode, String debitCurrency, String paymentCurrency, BigDecimal amount,
                        String chargeOption, ZonedDateTime at) {
    }

    public record Simulation(long revision, String providerCode, String paymentCurrency, BigDecimal amountHkd,
                             BigDecimal amountUsd, List<Venue> venues, String note) {
    }

    public Listing fees() {
        var cv = routing.config();
        return new Listing(cv.revision(), IntStream.range(0, cv.config().fees().size()).mapToObj(i -> new FeeRow(i, cv.config().fees().get(i))).toList(),
                cv.config().hkdRates().keySet(), cv.config().hkdRates());
    }

    @Transactional
    public Listing save(Save in) {
        if (in == null || in.revision() == null || in.fee() == null)
            throw new IllegalArgumentException("配置版本及手续费行必填");
        validateFee(in.fee());
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        var rows = new ArrayList<>(cv.config().fees());
        if (in.index() == null) rows.add(in.fee());
        else {
            if (in.index() < 0 || in.index() >= rows.size()) throw new IllegalArgumentException("手续费行不存在");
            rows.set(in.index(), in.fee());
        }
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withFees(rows)));
        return fees();
    }

    @Transactional
    public Listing delete(Delete in) {
        if (in == null || in.revision() == null || in.index() == null)
            throw new IllegalArgumentException("配置版本及手续费行必填");
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        if (in.index() < 0 || in.index() >= cv.config().fees().size())
            throw new IllegalArgumentException("手续费行不存在");
        var rows = new ArrayList<>(cv.config().fees());
        rows.remove(in.index().intValue());
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withFees(rows)));
        return fees();
    }

    private void validateFee(Fee f) {
        if (f == null) throw new IllegalArgumentException("手续费行必填");
        if (f.provider() == null || !providers.existsById(f.provider()))
            throw new IllegalArgumentException("合作方不存在");
        if (f.currency() == null || !f.currency().matches("[A-Z]{3}") || f.feeCurrency() == null || !f.feeCurrency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("币种须为三位大写编码");
        var cv = routing.config();
        rate(cv.config(), f.currency());
        rate(cv.config(), f.feeCurrency());
        if (f.charge() == null || !Set.of("ALL", "OUR", "SHA", "BEN").contains(f.charge()))
            throw new IllegalArgumentException("扣费维度须为 ALL/OUR/SHA/BEN");
        for (BigDecimal v : List.of(f.fixed(), f.rate(), f.minimum(), f.cable(), f.intermediary()))
            if (v == null || v.signum() < 0) throw new IllegalArgumentException("费用金额必须非负");
        if (f.maximum() != null) {
            if (f.maximum().signum() < 0) throw new IllegalArgumentException("最高手续费必须非负");
            if (f.maximum().compareTo(f.minimum()) < 0)
                throw new IllegalArgumentException("最高手续费不可小于最低手续费");
        }
    }

    public Simulation simulate(Input in) {
        if (in == null) throw new IllegalArgumentException("模拟请求必填");
        if (in.providerCode() == null || !providers.existsById(in.providerCode()))
            throw new IllegalArgumentException("合作方不存在");
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("交易金额必须大于0");
        if (in.chargeOption() == null || !Set.of("OUR", "SHA", "BEN").contains(in.chargeOption()))
            throw new IllegalArgumentException("扣费方式无效");
        var cv = routing.config();
        var cfg = cv.config();
        rate(cfg, in.debitCurrency());
        rate(cfg, in.paymentCurrency());
        ZonedDateTime now = in.at() == null ? ZonedDateTime.now(HK) : in.at().withZoneSameInstant(HK);
        BigDecimal amountHkd = in.amount().multiply(rate(cfg, in.paymentCurrency()), MC);
        BigDecimal usd = amountHkd.divide(rate(cfg, "USD"), MC);
        boolean fx = !in.debitCurrency().equals(in.paymentCurrency());
        List<Venue> venues = new ArrayList<>();
        if (!fx) venues.add(venue(cfg, in, "NONE", null, amountHkd, usd, now));
        else {
            for (String v : List.of("TREASURY", "PROVIDER")) {
                String owner = "TREASURY".equals(v) ? "TREASURY" : in.providerCode();
                List<FxWindow> windows = cfg.fxWindows().stream().filter(w -> w.provider().equals(owner) && w.debit().equals(in.debitCurrency()) && w.payment().equals(in.paymentCurrency())).toList();
                if (!windows.isEmpty()) venues.add(venue(cfg, in, v, windows, amountHkd, usd, now));
            }
        }
        String note = venues.isEmpty() ? "该货币对没有任何外汇渠道窗口配置：全流程路由不会生成候选，返回 REFER NO_FX_VENUE。"
                : "C7 为打分项不剔除候选：得分＝100×（剩余候选最低预估成本÷本候选预估成本），成本为0直接得100分。全部项目按 HSG 中间汇率折算港币，展示保留2位小数；窗口关闭时该候选先被 C10 过滤器剔除。";
        return new Simulation(cv.revision(), in.providerCode(), in.paymentCurrency(), amountHkd.setScale(2, RoundingMode.HALF_UP), usd.setScale(2, RoundingMode.HALF_UP), List.copyOf(venues), note);
    }

    private Venue venue(RoutingConfig cfg, Input in, String venue, List<FxWindow> windows, BigDecimal amountHkd, BigDecimal usd, ZonedDateTime now) {
        FxWindow open = null;
        String note = "无需换汇，换汇成本为0。";
        if (windows != null) {
            open = windows.stream().filter(w -> C10Calculator.day(w, now.toLocalDate(), cfg) && !now.toLocalTime().isBefore(w.start()) && now.toLocalTime().isBefore(w.end()))
                    .filter(w -> !"PROVIDER".equals(venue) || w.maxUsd() == null || usd.compareTo(w.maxUsd()) <= 0)
                    .min(Comparator.comparing(w -> w.spreadBps().add(w.extraBps()))).orElse(null);
            if (open != null)
                note = "换汇点差＝窗口点差 " + open.spreadBps() + "bps" + (open.extraBps().signum() > 0 ? "＋非工作时段 " + open.extraBps() + "bps" : "") + "（取同时段点差最低的窗口）。";
            else {
                boolean inTime = windows.stream().anyMatch(w -> RoutingService.day(w, now.toLocalDate(), cfg) && !now.toLocalTime().isBefore(w.start()) && now.toLocalTime().isBefore(w.end()));
                note = inTime ? "当前时间在窗口内，但本笔美元等价金额超过窗口单笔上限（FX_TICKET_LIMIT，C10 将剔除该候选）。" : "当前时间不在外汇窗口内（C10 将剔除该候选），换汇成本按0点差仅作展示。";
            }
        }
        BigDecimal spread = open == null ? BigDecimal.ZERO : open.spreadBps().add(open.extraBps());
        Fee fee = C7Calculator.feeFor(cfg.fees(), in.providerCode(), in.paymentCurrency(), in.chargeOption());
        var calc = C7Calculator.estimate(fee, amountHkd, rate(cfg, fee.feeCurrency()), spread);
        return new Venue(venue, open, open != null || windows == null, note, spread, calc);
    }

    private static BigDecimal rate(RoutingConfig cfg, String currency) {
        BigDecimal v = cfg.hkdRates().get(currency);
        if (v == null || v.signum() <= 0) throw new IllegalArgumentException("缺少汇率：" + currency);
        return v;
    }
}
