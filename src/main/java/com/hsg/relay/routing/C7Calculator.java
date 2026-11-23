package com.hsg.relay.routing;

import java.math.*;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.Fee;

/**
 * C7 cost estimate (HKD) shared by the isolated page and the C1–C10 engine.
 */
public final class C7Calculator {
    private static final MathContext MC = MathContext.DECIMAL128;

    private C7Calculator() {
    }

    /**
     * clamp: NONE 未触发、MIN 按最低手续费托底、MAX 按最高手续费封顶；totalHkd 为满精度值，展示时由调用方保留2位小数。
     */
    public record Result(Fee fee, BigDecimal amountInFeeCurrency, BigDecimal rawCommission, String clamp,
                         BigDecimal commission,
                         BigDecimal commissionHkd, BigDecimal cableHkd, BigDecimal intermediaryHkd,
                         BigDecimal feeSubtotalFeeCurrency, BigDecimal feeSubtotalHkd,
                         BigDecimal fxSpreadBps, BigDecimal fxCostHkd, BigDecimal totalHkd) {
    }

    public static Fee feeFor(List<Fee> fees, String provider, String paymentCurrency, String chargeOption) {
        return fees.stream().filter(f -> f.provider().equals(provider) && f.currency().equals(paymentCurrency) && scope(f.charge(), chargeOption))
                .max(Comparator.comparingInt(f -> "ALL".equals(f.charge()) ? 0 : 1))
                .orElseThrow(() -> new IllegalStateException("缺少手续费配置：" + provider + "/" + paymentCurrency));
    }

    public static Result estimate(Fee fee, BigDecimal amountHkd, BigDecimal feeCurrencyHkdRate, BigDecimal fxSpreadBps) {
        Objects.requireNonNull(fee);
        Objects.requireNonNull(amountHkd);
        if (feeCurrencyHkdRate == null || feeCurrencyHkdRate.signum() <= 0)
            throw new IllegalArgumentException("缺少费币种汇率");
        BigDecimal spread = fxSpreadBps == null ? BigDecimal.ZERO : fxSpreadBps;
        BigDecimal amountFee = amountHkd.divide(feeCurrencyHkdRate, MC);
        BigDecimal raw = fee.fixed().add(amountFee.multiply(fee.rate(), MC));
        BigDecimal commission = raw.max(fee.minimum());
        String clamp = "NONE";
        if (raw.compareTo(fee.minimum()) < 0) clamp = "MIN";
        if (fee.maximum() != null && commission.compareTo(fee.maximum()) > 0) {
            commission = fee.maximum();
            clamp = "MAX";
        }
        BigDecimal subtotal = commission.add(fee.cable()).add(fee.intermediary());
        BigDecimal fx = amountHkd.multiply(spread, MC).divide(BigDecimal.valueOf(10000), MC);
        return new Result(fee, amountFee, raw, clamp, commission,
                commission.multiply(feeCurrencyHkdRate, MC), fee.cable().multiply(feeCurrencyHkdRate, MC), fee.intermediary().multiply(feeCurrencyHkdRate, MC),
                subtotal, subtotal.multiply(feeCurrencyHkdRate, MC), spread, fx, subtotal.multiply(feeCurrencyHkdRate, MC).add(fx));
    }

    /**
     * 成本得分＝100×（剩余候选最低预估成本÷本候选预估成本）；成本为0直接得100分。
     */
    public static BigDecimal score(BigDecimal minCost, BigDecimal cost) {
        if (cost == null || cost.signum() == 0) return BigDecimal.valueOf(100);
        return minCost.multiply(BigDecimal.valueOf(100), MC).divide(cost, MC);
    }

    private static boolean scope(String configured, String actual) {
        return configured == null || configured.isBlank() || "ALL".equals(configured) || configured.equals(actual);
    }
}
