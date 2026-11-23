package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.repo.ProviderRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

import static com.hsg.relay.routing.RoutingConfig.Balance;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class C4ServiceTest {
    @Autowired
    C4Service c4;
    @Autowired
    RoutingService routing;
    @Autowired
    ObjectMapper json;
    @Autowired
    ProviderRepository providers;
    @Autowired
    RuleAuditRepository audit;

    @Test
    void listingShowsBalancesRatesAndFundingSwitches() {
        var l = c4.balances();
        assertThat(l.rows()).hasSize(12); // T1 5 币种 ＋ HO 2 ＋ Wise 4 ＋ GD 1
        assertThat(l.rows()).extracting(r -> r.balance().provider() + r.balance().currency()).doesNotHaveDuplicates();
        assertThat(l.currencies()).contains("HKD", "USD", "AUD");
        assertThat(l.hkdRates().get("USD")).isEqualByComparingTo("7.8");
        assertThat(l.fundingSwitches()).extracting(C4Service.FundingSwitch::code).containsExactly("PROV-GD", "PROV-HO", "PROV-T1", "PROV-W");
        // Wise / GD Pay 为后补头寸：开关 OFF
        assertThat(l.fundingSwitches().stream().filter(s -> s.fundingCheckEnabled()).map(C4Service.FundingSwitch::code)).containsExactly("PROV-HO", "PROV-T1");
    }

    @Test
    void saveAddEditDeleteSharesConfigWithRouting() {
        var before = routing.config();
        long audits = audit.count();
        var saved = c4.save(new C4Service.Save(before.revision(), null, new Balance("PROV-GD", "HKD", new BigDecimal("1200000"), new BigDecimal("200000"))));
        assertThat(saved.revision()).isEqualTo(before.revision() + 1);
        assertThat(routing.config().config().balances()).hasSize(before.config().balances().size() + 1);
        assertThat(routing.config().config().reaches()).isEqualTo(before.config().reaches()); // 其余配置不变
        int index = saved.rows().stream().filter(r -> r.balance().provider().equals("PROV-GD") && r.balance().currency().equals("HKD")).findFirst().orElseThrow().index();
        c4.save(new C4Service.Save(saved.revision(), index, new Balance("PROV-GD", "HKD", new BigDecimal("1500000"), BigDecimal.ZERO)));
        assertThat(routing.config().config().balances().get(index).balance()).isEqualByComparingTo("1500000");
        c4.delete(new C4Service.Delete(routing.config().revision(), index));
        assertThat(routing.config().config().balances()).hasSize(before.config().balances().size());
        assertThat(audit.count()).isEqualTo(audits + 3);
    }

    @Test
    void saveValidatesBalanceAndRevision() {
        long rev = c4.balances().revision();
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, null, new Balance("PROV-XX", "USD", BigDecimal.TEN, BigDecimal.ZERO)))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, null, new Balance("PROV-GD", "usd", BigDecimal.TEN, BigDecimal.ZERO)))).hasMessageContaining("三位大写");
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, null, new Balance("PROV-GD", "GBP", BigDecimal.TEN, BigDecimal.ZERO)))).hasMessageContaining("汇率");
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, null, new Balance("PROV-GD", "EUR", new BigDecimal("-1"), BigDecimal.ZERO)))).hasMessageContaining("非负");
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, null, new Balance("PROV-GD", "EUR", BigDecimal.TEN, new BigDecimal("-1"))))).hasMessageContaining("非负");
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, null, new Balance("PROV-GD", "USD", BigDecimal.TEN, BigDecimal.ZERO)))).hasMessageContaining("已存在");
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, 99, new Balance("PROV-GD", "SGD", BigDecimal.TEN, BigDecimal.ZERO)))).hasMessageContaining("不存在");
        c4.save(new C4Service.Save(rev, null, new Balance("PROV-GD", "EUR", BigDecimal.TEN, BigDecimal.ZERO)));
        assertThatThrownBy(() -> c4.save(new C4Service.Save(rev, null, new Balance("PROV-GD", "SGD", BigDecimal.TEN, BigDecimal.ZERO)))).hasMessageContaining("已更新");
        assertThatThrownBy(() -> c4.delete(new C4Service.Delete(c4.balances().revision(), 99))).hasMessageContaining("不存在");
        assertThat(routing.config().config().balances()).hasSize(13);
    }

    @Test
    void simulatePassesAndFlagsShortfall() {
        var pass = c4.simulate(new C4Service.Input("PROV-T1", "USD", "USD", new BigDecimal("10000"), null));
        assertThat(pass.status()).isEqualTo("PASS");
        assertThat(pass.fx()).isFalse();
        assertThat(pass.fundingCheckEnabled()).isTrue();
        assertThat(pass.venues()).hasSize(1);
        var none = pass.venues().get(0);
        assertThat(none.fxVenue()).isEqualTo("NONE");
        assertThat(none.calculation().accountCurrency()).isEqualTo("USD");
        assertThat(none.calculation().available()).isEqualByComparingTo("50000000");
        assertThat(none.calculation().passed()).isTrue();
        var shortfall = c4.simulate(new C4Service.Input("PROV-T1", "USD", "USD", new BigDecimal("60000000"), null));
        assertThat(shortfall.status()).isEqualTo("FUNDING_SHORTFALL");
        assertThat(shortfall.venues().get(0).calculation().reasons()).containsExactly("FUNDING_SHORTFALL");
        assertThat(shortfall.venues().get(0).calculation().required()).isEqualByComparingTo("60000000");
    }

    @Test
    void simulateWiseProviderVenueAndSwitchOverride() {
        // Wise 开关 OFF（后补头寸）：USD→AUD 仅 PROVIDER 渠道，扣账账户 USD 需 78,000,000 × 5.1 ÷ 7.8 ＝ 51,000,000 ＞ 50,000,000
        var off = c4.simulate(new C4Service.Input("PROV-W", "USD", "AUD", new BigDecimal("78000000"), null));
        assertThat(off.status()).isEqualTo("PASS");
        assertThat(off.fundingCheckEnabled()).isFalse();
        assertThat(off.fx()).isTrue();
        assertThat(off.venues()).extracting(C4Service.Venue::fxVenue).containsExactly("PROVIDER"); // TREASURY 无 USD/AUD 窗口
        var c = off.venues().get(0).calculation();
        assertThat(c.accountCurrency()).isEqualTo("USD");
        assertThat(c.available()).isEqualByComparingTo("50000000");
        assertThat(c.required()).isEqualByComparingTo("51000000");
        assertThat(c.enabled()).isFalse();
        assertThat(c.passed()).isTrue();
        assertThat(off.note()).contains("OFF");
        // 仅本次试算改为 ON：头寸不足 → FUNDING_SHORTFALL，且不落库
        var on = c4.simulate(new C4Service.Input("PROV-W", "USD", "AUD", new BigDecimal("78000000"), true));
        assertThat(on.status()).isEqualTo("FUNDING_SHORTFALL");
        assertThat(on.fundingCheckEnabled()).isTrue();
        assertThat(on.venues().get(0).calculation().reasons()).containsExactly("FUNDING_SHORTFALL");
        assertThat(on.note()).contains("不落库");
        assertThat(providers.findById("PROV-W").orElseThrow().fundingCheckEnabled).isFalse();
        assertThat(c4.balances().fundingSwitches().stream().filter(s -> s.code().equals("PROV-W")).findFirst().orElseThrow().fundingCheckEnabled()).isFalse();
    }

    @Test
    void simulateTreasuryVenueAndNoFxVenue() {
        var treasury = c4.simulate(new C4Service.Input("PROV-T1", "HKD", "USD", new BigDecimal("10000"), null));
        assertThat(treasury.fx()).isTrue();
        assertThat(treasury.status()).isEqualTo("PASS");
        assertThat(treasury.venues()).extracting(C4Service.Venue::fxVenue).containsExactly("TREASURY"); // 仅 TREASURY：PROV-T1 无 HKD/USD 窗口
        var c = treasury.venues().get(0).calculation();
        assertThat(c.accountCurrency()).isEqualTo("USD");
        assertThat(c.rate()).isEqualByComparingTo("1");
        assertThat(c.required()).isEqualByComparingTo("10000");
        var none = c4.simulate(new C4Service.Input("PROV-T1", "HKD", "EUR", new BigDecimal("10000"), null));
        assertThat(none.status()).isEqualTo("NO_FX_VENUE");
        assertThat(none.venues()).isEmpty();
        assertThat(none.note()).contains("NO_FX_VENUE");
    }

    @Test
    void simulateValidatesInput() {
        assertThatThrownBy(() -> c4.simulate(new C4Service.Input("PROV-XX", "USD", "USD", BigDecimal.TEN, null))).hasMessageContaining("不存在");
        assertThatThrownBy(() -> c4.simulate(new C4Service.Input("PROV-T1", "USD", "USD", BigDecimal.ZERO, null))).hasMessageContaining("金额");
        assertThatThrownBy(() -> c4.simulate(new C4Service.Input("PROV-T1", "USD", "XXX", BigDecimal.TEN, null))).hasMessageContaining("汇率");
        assertThatThrownBy(() -> c4.simulate(new C4Service.Input("PROV-T1", null, "USD", BigDecimal.TEN, null))).hasMessageContaining("三位大写");
    }

    @Test
    void routingUsesSameC4Result() throws Exception {
        ObjectNode req = (ObjectNode) json.readTree("""
                {"paymentId":"C4-TEST","clientId":"C10002","debitCurrency":"HKD","paymentCurrency":"USD","amount":20000,
                "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        var result = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        var t1 = result.candidates().stream().filter(c -> c.providerCode.equals("PROV-T1")).findFirst().orElseThrow();
        var check = t1.checks.stream().filter(x -> x.code().equals("C4")).findFirst().orElseThrow();
        var standalone = c4.simulate(new C4Service.Input("PROV-T1", "HKD", "USD", new BigDecimal("20000"), null)).venues().get(0).calculation();
        assertThat(check.detail()).isInstanceOf(C4Calculator.Result.class).isEqualTo(standalone); // 同一计算器：结果逐字段一致
        assertThat(check.reasons()).isEqualTo(standalone.reasons());
    }

    @Test
    void savedBalanceDrivesRoutingRefer() throws Exception {
        // 页面把三个开关 ON 的合作方 USD 余额改为 0：全部候选仅 C4 失败 → REFER FUNDING_SHORTFALL（Wise 开关 OFF 仍通过，故移出候选）
        for (String code : List.of("PROV-T1", "PROV-HO", "PROV-GD")) {
            int index = c4.balances().rows().stream().filter(r -> r.balance().provider().equals(code) && r.balance().currency().equals("USD")).findFirst().orElseThrow().index();
            c4.save(new C4Service.Save(c4.balances().revision(), index, new Balance(code, "USD", BigDecimal.ZERO, new BigDecimal("100"))));
        }
        var w = providers.findById("PROV-W").orElseThrow();
        w.status = "NOT_LIVE";
        providers.save(w);
        ObjectNode req = (ObjectNode) json.readTree("""
                {"paymentId":"C4-REFER","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        var result = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        assertThat(result.result()).isEqualTo("REFER");
        assertThat(result.reasonCode()).isEqualTo("FUNDING_SHORTFALL");
        var t1 = result.candidates().stream().filter(c -> c.providerCode.equals("PROV-T1")).findFirst().orElseThrow();
        var c = (C4Calculator.Result) t1.checks.stream().filter(x -> x.code().equals("C4")).findFirst().orElseThrow().detail();
        assertThat(c.accountFound()).isTrue();
        assertThat(c.available()).isEqualByComparingTo("-100");
    }
}
