package com.hsg.relay.routing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 综合评分计算：总分 = (C7×W1 + C5×W2 + C8×W3 + C9×W4) / 100。
 * <p>
 * 只做汇总与加权，不重新实现 C5/C7/C8/C9（四项得分由各自模块给出）。全程 BigDecimal：乘法保持完整精度（DECIMAL128，分数最多两位小数 ×
 * 整数权重，结果本身就是精确值），相加、除以 100 都不产生舍入；只有最终总分统一保留两位小数、HALF_UP。
 * 未舍入结果一并返回，供排序以外的审计和排查使用；排序使用舍入后的总分。
 * 舍入口径集中在 {@link #round2}，路由引擎里其它「保留两位小数」的地方也复用它，避免不同模块处理不一致。
 */
@Component
public class RouteTotalScoreCalculator {
    public static final int SCORE_SCALE = 2;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    /** 一个候选的四项得分（范围均为 0–100）。 */
    public record Scores(BigDecimal c7Cost, BigDecimal c5Speed, BigDecimal c8Preference, BigDecimal c9Commercial) {
    }

    /** 未舍入总分（满精度）与最终总分（两位小数、HALF_UP）。 */
    public record Total(BigDecimal unrounded, BigDecimal rounded) {
    }

    /** 统一舍入口径：保留两位小数、HALF_UP。入参为空时返回空。 */
    public static BigDecimal round2(BigDecimal value) {
        return value == null ? null : value.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 校验一项得分：缺失或超出 0–100 都视为数据异常并抛出 {@link IllegalStateException}，不会按 0 分处理。
     * 0 分是合法得分（例如 C5 延迟较大时得 0 分），照常参与计算。
     */
    public static BigDecimal requireScore(String item, String candidate, BigDecimal value) {
        if (value == null)
            throw new IllegalStateException("评分数据异常：" + candidate + " 缺少 " + item + " 得分，无法计算综合评分（不按 0 分处理）");
        if (value.signum() < 0 || value.compareTo(HUNDRED) > 0)
            throw new IllegalStateException("评分数据异常：" + candidate + " 的 " + item + " 得分 " + value.toPlainString() + " 超出 0–100，无法计算综合评分");
        return value;
    }

    public Total calculate(Scores scores, ScoreWeightMatcher.Weights weights) {
        if (scores == null) throw new IllegalStateException("评分数据异常：四项得分不能为空");
        if (weights == null || weights.sum() != 100)
            throw new IllegalStateException("评分权重配置异常：权重 " + weights + " 合计不等于 100，不能用于计算");
        BigDecimal weightedSum = scores.c7Cost().multiply(BigDecimal.valueOf(weights.cost()), MC)
                .add(scores.c5Speed().multiply(BigDecimal.valueOf(weights.speed()), MC))
                .add(scores.c8Preference().multiply(BigDecimal.valueOf(weights.preference()), MC))
                .add(scores.c9Commercial().multiply(BigDecimal.valueOf(weights.commercial()), MC));
        BigDecimal unrounded = weightedSum.divide(HUNDRED, MC);
        return new Total(unrounded, round2(unrounded));
    }

    /** 校验四项得分后计算；candidate 仅用于错误信息（如 PROV-A/NONE）。 */
    public Total calculateChecked(Scores scores, ScoreWeightMatcher.Weights weights, String candidate) {
        return calculate(new Scores(
                requireScore("C7 成本", candidate, scores.c7Cost()),
                requireScore("C5 速度", candidate, scores.c5Speed()),
                requireScore("C8 偏好", candidate, scores.c8Preference()),
                requireScore("C9 商业履约", candidate, scores.c9Commercial())), weights);
    }

    /** 总分范围检查（权重非负且合计 100、各项得分 0–100 时必然成立），作为最后一道防线。 */
    public static boolean inRange(BigDecimal total) {
        return total != null && total.compareTo(ZERO) >= 0 && total.compareTo(HUNDRED) <= 0;
    }
}
