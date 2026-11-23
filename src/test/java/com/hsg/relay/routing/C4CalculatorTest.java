package com.hsg.relay.routing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.Balance;
import static org.assertj.core.api.Assertions.*;

class C4CalculatorTest {
    private static final Map<String, BigDecimal> RATES = Map.of("HKD", new BigDecimal("1"), "USD", new BigDecimal("7.8"), "AUD", new BigDecimal("5.1"));
    private static final List<Balance> BALANCES = List.of(
            new Balance("PROV-T1", "USD", new BigDecimal("1000000"), new BigDecimal("200000")),
            new Balance("PROV-T1", "HKD", new BigDecimal("500000"), BigDecimal.ZERO),
            new Balance("PROV-W", "USD", new BigDecimal("300"), BigDecimal.ZERO));
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    @Test
    void switchOffPassesEvenWithoutBalance() {
        var r = C4Calculator.check("PROV-XX", false, "NONE", "USD", "USD", TEN_THOUSAND, RATES, BALANCES);
        assertThat(r.enabled()).isFalse();
        assertThat(r.passed()).isTrue();
        assertThat(r.reasons()).isEmpty();
        assertThat(r.accountFound()).isFalse();
        assertThat(r.balance()).isEqualByComparingTo("0");
        assertThat(r.available()).isEqualByComparingTo("0"); // 开关 OFF 时数值仍返回，仅供展示
    }

    @Test
    void noneAndTreasuryUsePaymentCurrencyWithoutConversion() {
        for (String venue : List.of("NONE", "TREASURY")) {
            var r = C4Calculator.check("PROV-T1", true, venue, "USD", "HKD", TEN_THOUSAND, RATES, BALANCES);
            assertThat(r.fxVenue()).isEqualTo(venue);
            assertThat(r.accountCurrency()).isEqualTo("USD");
            assertThat(r.rate()).isEqualByComparingTo("1");
            assertThat(r.required()).isEqualByComparingTo("10000");
            assertThat(r.balance()).isEqualByComparingTo("1000000");
            assertThat(r.reserved()).isEqualByComparingTo("200000");
            assertThat(r.available()).isEqualByComparingTo("800000"); // 步骤3：余额 − 未记账占用
            assertThat(r.passed()).isTrue();
        }
    }

    @Test
    void providerUsesDebitCurrencyWithHsgRate() {
        // 交易 78,000 AUD、扣账 HKD：78,000 × 5.1 ＝ 397,800 HKD，校验 HKD 往来账
        var r = C4Calculator.check("PROV-T1", true, "PROVIDER", "AUD", "HKD", new BigDecimal("78000"), RATES, BALANCES);
        assertThat(r.accountCurrency()).isEqualTo("HKD");
        assertThat(r.paymentCurrency()).isEqualTo("AUD");
        assertThat(r.debitCurrency()).isEqualTo("HKD");
        assertThat(r.rate()).isEqualByComparingTo("5.1");
        assertThat(r.required()).isEqualByComparingTo("397800");
        assertThat(r.available()).isEqualByComparingTo("500000");
        assertThat(r.passed()).isTrue();
        // 经港币交叉：10,000 AUD 扣账 USD → 10,000 × 5.1 ÷ 7.8，与全流程 amountHkd ÷ 账户汇率 同一口径
        var cross = C4Calculator.check("PROV-W", true, "PROVIDER", "AUD", "USD", TEN_THOUSAND, RATES, BALANCES);
        assertThat(cross.accountCurrency()).isEqualTo("USD");
        assertThat(cross.available()).isEqualByComparingTo("300");
        assertThat(cross.required().doubleValue()).isCloseTo(6538.4615, within(0.0001));
        assertThat(cross.passed()).isFalse();
    }

    @Test
    void reservedReducesAvailableAndBoundaryPasses() {
        var edge = C4Calculator.check("PROV-T1", true, "NONE", "USD", "USD", new BigDecimal("800000"), RATES, BALANCES);
        assertThat(edge.available()).isEqualByComparingTo("800000");
        assertThat(edge.passed()).isTrue(); // 可用 ＝ 等价金额 → 通过
        var over = C4Calculator.check("PROV-T1", true, "TREASURY", "USD", "USD", new BigDecimal("800000.01"), RATES, BALANCES);
        assertThat(over.passed()).isFalse();
        assertThat(over.reasons()).containsExactly("FUNDING_SHORTFALL");
    }

    @Test
    void onlyOwnProviderRowIsUsed() {
        var r = C4Calculator.check("PROV-W", true, "NONE", "USD", "USD", TEN_THOUSAND, RATES, BALANCES);
        assertThat(r.accountFound()).isTrue();
        assertThat(r.available()).isEqualByComparingTo("300");
        assertThat(r.passed()).isFalse();
        assertThat(r.reasons()).containsExactly(C4Calculator.SHORTFALL);
        // 未配置该币种余额行：可用按 0 计
        var missing = C4Calculator.check("PROV-T1", true, "NONE", "AUD", "AUD", TEN_THOUSAND, RATES, BALANCES);
        assertThat(missing.accountFound()).isFalse();
        assertThat(missing.available()).isEqualByComparingTo("0");
        assertThat(missing.passed()).isFalse();
    }

    @Test
    void rejectsInvalidVenueMissingRateAndNullAmount() {
        assertThat(C4Calculator.accountCurrency("NONE", "USD", "HKD")).isEqualTo("USD");
        assertThat(C4Calculator.accountCurrency("TREASURY", "AUD", "HKD")).isEqualTo("AUD");
        assertThat(C4Calculator.accountCurrency("PROVIDER", "AUD", "HKD")).isEqualTo("HKD");
        assertThatThrownBy(() -> C4Calculator.accountCurrency("SWIFT", "USD", "HKD")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("外汇渠道");
        assertThatThrownBy(() -> C4Calculator.check("PROV-T1", true, "PROVIDER", "GBP", "HKD", TEN_THOUSAND, RATES, BALANCES)).hasMessageContaining("缺少汇率");
        assertThatThrownBy(() -> C4Calculator.check("PROV-T1", true, "NONE", "USD", "USD", null, RATES, BALANCES)).isInstanceOf(NullPointerException.class);
    }
}
