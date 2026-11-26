package com.hsg.relay.routing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

/** 总分公式的 BigDecimal 精度、四舍五入和异常处理。 */
class RouteTotalScoreCalculatorTest {
    final RouteTotalScoreCalculator calculator = new RouteTotalScoreCalculator();
    static final ScoreWeightMatcher.Weights TRADE = new ScoreWeightMatcher.Weights(40, 30, 20, 10);
    static final ScoreWeightMatcher.Weights QUARTERS = new ScoreWeightMatcher.Weights(25, 25, 25, 25);

    static RouteTotalScoreCalculator.Scores scores(String c7, int c5, int c8, int c9) {
        return new RouteTotalScoreCalculator.Scores(new BigDecimal(c7), BigDecimal.valueOf(c5), BigDecimal.valueOf(c8), BigDecimal.valueOf(c9));
    }

    @Test
    void weightedSumMatchesTheWorkedExample() {
        // (90×40 + 100×30 + 80×20 + 50×10) / 100 = 87
        var total = calculator.calculate(scores("90", 100, 80, 50), TRADE);
        assertThat(total.rounded()).hasScaleOf(2).isEqualByComparingTo("87.00");
        assertThat(total.unrounded()).isEqualByComparingTo("87");
        assertThat(total.rounded().toPlainString()).isEqualTo("87.00");
    }

    @Test
    void intermediateStepsKeepFullPrecisionAndOnlyTheTotalIsRounded() {
        // (99.99×33 + 1×33 + 2×33 + 3×1) / 100 = 3401.67 / 100 = 34.0167 → 34.02
        var total = calculator.calculate(scores("99.99", 1, 2, 3), new ScoreWeightMatcher.Weights(33, 33, 33, 1));
        assertThat(total.unrounded()).isEqualByComparingTo("34.0167");
        assertThat(total.unrounded().scale()).isGreaterThanOrEqualTo(4);
        assertThat(total.rounded()).isEqualByComparingTo("34.02");
    }

    @Test
    void roundingIsHalfUpAtEveryBoundary() {
        // 0.02×25 / 100 = 0.005 → 0.01（四舍六入五成双会得到 0.00）
        assertThat(calculator.calculate(scores("0.02", 0, 0, 0), QUARTERS).rounded()).isEqualByComparingTo("0.01");
        // 0.10×25 / 100 = 0.025 → 0.03
        assertThat(calculator.calculate(scores("0.10", 0, 0, 0), QUARTERS).rounded()).isEqualByComparingTo("0.03");
        // 0.12×25 / 100 = 0.03；0.14×25 / 100 = 0.035 → 0.04
        assertThat(calculator.calculate(scores("0.12", 0, 0, 0), QUARTERS).rounded()).isEqualByComparingTo("0.03");
        assertThat(calculator.calculate(scores("0.14", 0, 0, 0), QUARTERS).rounded()).isEqualByComparingTo("0.04");
        assertThat(RouteTotalScoreCalculator.round2(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
        assertThat(RouteTotalScoreCalculator.round2(new BigDecimal("1.004"))).isEqualByComparingTo("1.00");
        assertThat(RouteTotalScoreCalculator.round2(new BigDecimal("2.345"))).isEqualByComparingTo("2.35");
        assertThat(RouteTotalScoreCalculator.round2(null)).isNull();
    }

    @Test
    void zeroSpeedScoreStillCountsAndBoundsStayWithinZeroToHundred() {
        var zero = calculator.calculate(scores("90", 0, 80, 50), TRADE);
        assertThat(zero.rounded()).isEqualByComparingTo("57.00");
        assertThat(calculator.calculate(scores("0", 0, 0, 0), TRADE).rounded()).isEqualByComparingTo("0.00");
        assertThat(calculator.calculate(scores("100", 100, 100, 100), TRADE).rounded()).isEqualByComparingTo("100.00");
        assertThat(RouteTotalScoreCalculator.inRange(zero.rounded())).isTrue();
    }

    @Test
    void zeroWeightMeansThatScoreDropsOutOfTheTotal() {
        assertThat(calculator.calculate(scores("90", 100, 80, 50), new ScoreWeightMatcher.Weights(100, 0, 0, 0)).rounded()).isEqualByComparingTo("90.00");
        assertThat(calculator.calculate(scores("90", 100, 80, 50), new ScoreWeightMatcher.Weights(0, 0, 0, 100)).rounded()).isEqualByComparingTo("50.00");
    }

    @Test
    void missingScoreIsAnErrorNotZero() {
        assertThatThrownBy(() -> calculator.calculateChecked(new RouteTotalScoreCalculator.Scores(null, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN), TRADE, "PROV-A/NONE"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROV-A/NONE").hasMessageContaining("缺少 C7 成本").hasMessageContaining("不按 0 分处理");
        assertThatThrownBy(() -> calculator.calculateChecked(new RouteTotalScoreCalculator.Scores(BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.TEN), TRADE, "PROV-A/NONE"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少 C5 速度");
        assertThatThrownBy(() -> calculator.calculate(null, TRADE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void outOfRangeScoreIsAnErrorNotClamped() {
        assertThatThrownBy(() -> calculator.calculateChecked(scores("100.01", 100, 80, 50), TRADE, "PROV-A/NONE"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("超出 0–100");
        assertThatThrownBy(() -> calculator.calculateChecked(scores("90", -1, 80, 50), TRADE, "PROV-A/NONE"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("C5 速度 得分 -1 超出 0–100");
        assertThatThrownBy(() -> calculator.calculateChecked(scores("90", 100, 101, 50), TRADE, "PROV-A/NONE"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("C8 偏好");
        assertThatThrownBy(() -> calculator.calculateChecked(scores("90", 100, 80, 999), TRADE, "PROV-A/NONE"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("C9 商业履约");
    }

    @Test
    void weightsNotSummingToHundredAreRejected() {
        assertThatThrownBy(() -> calculator.calculate(scores("90", 100, 80, 50), new ScoreWeightMatcher.Weights(0, 0, 0, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("合计不等于 100");
        assertThatThrownBy(() -> calculator.calculate(scores("90", 100, 80, 50), null)).isInstanceOf(IllegalStateException.class);
    }
}
