package com.hsg.relay.routing;

import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.*;
import java.util.*;
import java.util.stream.IntStream;

import static com.hsg.relay.routing.RoutingConfig.Preference;

/**
 * C8 偏好规则：条件空＝匹配全部，命中给指定合作方加分，合计封顶100（BRD 6.8、9.3）。
 */
@Service
public class C8Service {
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final Set<String> PAYMENT_TYPES = Set.of("TRADE", "TREASURY", "INTRAGROUP", "PAYROLL", "FEE", "REFUND", "OTHER");
    private final RoutingService routing;
    private final ProviderRepository providers;

    public C8Service(RoutingService r, ProviderRepository p) {
        routing = r;
        providers = p;
    }

    public record RuleRow(int index, Preference rule) {
    }

    public record Listing(long revision, List<RuleRow> rows) {
    }

    public record Save(Long revision, Integer index, Preference rule) {
    }

    public record Delete(Long revision, Integer index) {
    }

    public record Input(String paymentCurrency, String beneficiaryBankCountry, String beneficiaryRegion,
                        BigDecimal amount, String paymentType, String clientSegment) {
    }

    public record Simulation(long revision, C8Calculator.Txn txn, C8Calculator.Result calculation, String note) {
    }

    public Listing rules() {
        var cv = routing.config();
        return new Listing(cv.revision(), IntStream.range(0, cv.config().preferences().size()).mapToObj(i -> new RuleRow(i, cv.config().preferences().get(i))).toList());
    }

    @Transactional
    public Listing save(Save in) {
        if (in == null || in.revision() == null || in.rule() == null)
            throw new IllegalArgumentException("配置版本及规则必填");
        validateRule(in.rule());
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        var rows = new ArrayList<>(cv.config().preferences());
        if (in.index() == null) rows.add(in.rule());
        else {
            if (in.index() < 0 || in.index() >= rows.size()) throw new IllegalArgumentException("规则不存在");
            rows.set(in.index(), in.rule());
        }
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withPreferences(rows)));
        return rules();
    }

    @Transactional
    public Listing delete(Delete in) {
        if (in == null || in.revision() == null || in.index() == null)
            throw new IllegalArgumentException("配置版本及规则必填");
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        if (in.index() < 0 || in.index() >= cv.config().preferences().size())
            throw new IllegalArgumentException("规则不存在");
        var rows = new ArrayList<>(cv.config().preferences());
        rows.remove(in.index().intValue());
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withPreferences(rows)));
        return rules();
    }

    private void validateRule(Preference p) {
        if (p.provider() == null || !providers.existsById(p.provider()))
            throw new IllegalArgumentException("合作方不存在");
        if (p.points() < 1 || p.points() > 100) throw new IllegalArgumentException("奖励分数范围1–100");
        text(p.currency(), "币种", "[A-Z]{3}");
        text(p.country(), "受益行国家", "[A-Z]{2}");
        text(p.region(), "受益人地区", "[A-Z]{2}-[A-Z0-9]{1,3}");
        if (p.paymentType() != null && !p.paymentType().isBlank() && !"ALL".equals(p.paymentType()) && !PAYMENT_TYPES.contains(p.paymentType()))
            throw new IllegalArgumentException("交易类型无效");
        if (p.minUsd() != null && p.minUsd().signum() < 0) throw new IllegalArgumentException("金额下限必须非负");
        if (p.maxUsd() != null && (p.maxUsd().signum() < 0 || (p.minUsd() != null && p.maxUsd().compareTo(p.minUsd()) < 0)))
            throw new IllegalArgumentException("金额上限必须非负且不小于下限");
    }

    private static void text(String value, String label, String pattern) {
        if (value != null && !value.isBlank() && !value.equals("ALL") && !value.matches(pattern))
            throw new IllegalArgumentException(label + "格式无效（留空或 ALL 表示匹配全部）");
    }

    public Simulation simulate(Input in) {
        if (in == null) throw new IllegalArgumentException("模拟请求必填");
        if (in.amount() == null || in.amount().signum() <= 0) throw new IllegalArgumentException("交易金额必须大于0");
        if (in.paymentCurrency() == null || !in.paymentCurrency().matches("[A-Z]{3}"))
            throw new IllegalArgumentException("交易币种须为三位大写编码");
        if (in.beneficiaryBankCountry() != null && !in.beneficiaryBankCountry().isBlank() && !in.beneficiaryBankCountry().matches("[A-Z]{2}"))
            throw new IllegalArgumentException("受益行国家须为两位大写编码");
        if (in.beneficiaryRegion() != null && !in.beneficiaryRegion().isBlank() && !in.beneficiaryRegion().matches("[A-Z]{2}-[A-Z0-9]{1,3}"))
            throw new IllegalArgumentException("受益人地区格式无效");
        if (in.paymentType() != null && !in.paymentType().isBlank() && !PAYMENT_TYPES.contains(in.paymentType()))
            throw new IllegalArgumentException("交易类型无效");
        if (in.clientSegment() == null || in.clientSegment().isBlank())
            throw new IllegalArgumentException("客户分层必填");
        var cv = routing.config();
        var cfg = cv.config();
        BigDecimal toHkd = cfg.hkdRates().get(in.paymentCurrency());
        if (toHkd == null || toHkd.signum() <= 0)
            throw new IllegalArgumentException("缺少汇率：" + in.paymentCurrency());
        BigDecimal usd = in.amount().multiply(toHkd, MC).divide(cfg.hkdRates().get("USD"), MC);
        var txn = new C8Calculator.Txn(in.paymentCurrency(), blankToNull(in.beneficiaryBankCountry()), blankToNull(in.beneficiaryRegion()), usd, in.paymentType(), in.clientSegment());
        return new Simulation(cv.revision(), txn, C8Calculator.evaluate(cfg.preferences(), txn),
                "C8 为打分项不剔除候选：每条命中规则给指定合作方加分，合计封顶100；未配置规则或未命中的合作方得0分。条件留空或 ALL 代表匹配全部；金额条件按美元等价比较。");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
