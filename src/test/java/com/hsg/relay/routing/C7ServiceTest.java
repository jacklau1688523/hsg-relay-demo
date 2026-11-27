package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.IntStream;

import static com.hsg.relay.routing.RoutingConfig.Fee;
import static org.assertj.core.api.Assertions.*;
import com.hsg.relay.service.C1PathConfigService;
import com.hsg.relay.service.C1PathFixtures;

@SpringBootTest
@Transactional
class C7ServiceTest {
    @Autowired
    C7Service c7;
    @Autowired
    RoutingService routing;
    @Autowired
    ObjectMapper json;
    @Autowired
    com.hsg.relay.repo.AppetiteRepository appetites;

    @Autowired
    C1PathConfigService pathConfig;

    @BeforeEach
    void publishBaselinePaths() {
        // C1 只执行已发布的路径配置：先发布基线（只有 DEFAULT 路径，条件恒成立），结果由必检和准入规则决定
        C1PathFixtures.publishDefaultOnly(pathConfig, json);
    }

    private static final ZonedDateTime FRI = ZonedDateTime.parse("2026-11-20T10:00:00+08:00");
    private static final ZonedDateTime SAT = ZonedDateTime.parse("2026-11-21T10:00:00+08:00");
    private static final ZonedDateTime MON = ZonedDateTime.parse("2026-11-23T09:05:00+08:00");

    private int indexOf(Fee fee) {
        var rows = c7.fees().rows();
        return IntStream.range(0, rows.size()).filter(i -> rows.get(i).fee().equals(fee)).findFirst().orElseThrow();
    }

    @Test
    void listingShowsFeesAndRates() {
        var listing = c7.fees();
        assertThat(listing.rows()).isNotEmpty();
        assertThat(listing.currencies()).contains("USD", "HKD", "AUD");
        assertThat(listing.hkdRates().get("USD")).isEqualByComparingTo("7.8");
        assertThat(listing.rows().stream().filter(r -> r.fee().provider().equals("PROV-W")).count()).isPositive();
    }

    @Test
    void saveAddsEditDeletesAndSharesConfigWithRouting() {
        int before = routing.config().config().fees().size();
        Fee fee = new Fee("PROV-GD", "CNY", "ALL", "HKD", new BigDecimal("60"), BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("30"), new BigDecimal("20"));
        c7.save(new C7Service.Save(c7.fees().revision(), null, fee));
        int added = indexOf(fee);
        assertThat(c7.fees().rows()).hasSize(before + 1);
        assertThat(routing.config().config().fees()).hasSize(before + 1);
        Fee edited = new Fee("PROV-GD", "CNY", "ALL", "HKD", new BigDecimal("70"), BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("30"), new BigDecimal("20"));
        c7.save(new C7Service.Save(c7.fees().revision(), added, edited));
        assertThat(c7.fees().rows().get(added).fee().fixed()).isEqualByComparingTo("70");
        c7.delete(new C7Service.Delete(c7.fees().revision(), added));
        assertThat(routing.config().config().fees()).hasSize(before);
        assertThatThrownBy(() -> c7.delete(new C7Service.Delete(c7.fees().revision(), added))).hasMessageContaining("不存在");
    }

    @Test
    void saveRejectsBadFees() {
        long revision = c7.fees().revision();
        Fee dup = new Fee("PROV-T1", "USD", "ALL", "HKD", new BigDecimal("80"), BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("30"), new BigDecimal("20"));
        assertThatThrownBy(() -> c7.save(new C7Service.Save(revision, null, dup))).hasMessageContaining("费用维度重复");
        Fee negative = new Fee("PROV-GD", "USD", "ALL", "HKD", new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThatThrownBy(() -> c7.save(new C7Service.Save(revision, null, negative))).hasMessageContaining("非负");
        Fee inverted = new Fee("PROV-GD", "USD", "ALL", "HKD", BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50"), new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO);
        assertThatThrownBy(() -> c7.save(new C7Service.Save(revision, null, inverted))).hasMessageContaining("最高手续费不可小于最低");
        Fee badCcy = new Fee("PROV-GD", "XXX", "ALL", "HKD", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO);
        assertThatThrownBy(() -> c7.save(new C7Service.Save(revision, null, badCcy))).hasMessageContaining("汇率");
        c7.save(new C7Service.Save(revision, null, new Fee("PROV-GD", "CNY", "ALL", "HKD", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThatThrownBy(() -> c7.save(new C7Service.Save(revision, null, new Fee("PROV-GD", "EUR", "ALL", "HKD", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO)))).hasMessageContaining("已更新");
    }

    @Test
    void simulateWithoutFxSumsFeesOnly() {
        var sim = c7.simulate(new C7Service.Input("PROV-W", "USD", "USD", new BigDecimal("10000"), "SHA", FRI));
        assertThat(sim.venues()).hasSize(1);
        var v = sim.venues().get(0);
        assertThat(v.fxVenue()).isEqualTo("NONE");
        assertThat(v.window()).isNull();
        assertThat(v.windowOpen()).isTrue();
        assertThat(v.calculation().totalHkd()).isEqualByComparingTo("100"); // 50+30+20
    }

    @Test
    void simulateTreasuryWindowAndSpread() {
        var sim = c7.simulate(new C7Service.Input("PROV-T1", "HKD", "USD", new BigDecimal("20000"), "SHA", MON));
        assertThat(sim.venues()).hasSize(1);
        var v = sim.venues().get(0);
        assertThat(v.fxVenue()).isEqualTo("TREASURY");
        assertThat(v.windowOpen()).isTrue();
        assertThat(v.spreadBps()).isEqualByComparingTo("10");
        assertThat(v.calculation().fxCostHkd()).isEqualByComparingTo("156"); // 20000×7.8＝156000 HKD ×10bp÷10000
        assertThat(v.calculation().totalHkd()).isEqualByComparingTo("286"); // 手续费 80+30+20＝130 ＋ 换汇 156
    }

    @Test
    void simulateProviderWeekendAddsExtraSpread() {
        var weekday = c7.simulate(new C7Service.Input("PROV-W", "HKD", "AUD", new BigDecimal("20000"), "SHA", FRI));
        assertThat(weekday.venues()).hasSize(1);
        assertThat(weekday.venues().get(0).spreadBps()).isEqualByComparingTo("35");
        assertThat(weekday.venues().get(0).calculation().totalHkd()).isEqualByComparingTo("457"); // 手续费100＋102000×35bp÷10000＝357
        var weekend = c7.simulate(new C7Service.Input("PROV-W", "HKD", "AUD", new BigDecimal("20000"), "SHA", SAT));
        assertThat(weekend.venues().get(0).spreadBps()).isEqualByComparingTo("55");
        assertThat(weekend.venues().get(0).calculation().totalHkd()).isEqualByComparingTo("661"); // 100＋102000×55bp＝561
    }

    @Test
    void simulateClosedWindowMarksC10() {
        var sim = c7.simulate(new C7Service.Input("PROV-T1", "HKD", "USD", new BigDecimal("20000"), "SHA", SAT));
        var v = sim.venues().get(0);
        assertThat(v.windowOpen()).isFalse();
        assertThat(v.windowNote()).contains("窗口");
        assertThat(v.calculation().totalHkd()).isEqualByComparingTo("130"); // 点差按0展示
    }

    @Test
    void simulateMissingFeeAndNoVenueAreRejectedOrEmpty() {
        assertThatThrownBy(() -> c7.simulate(new C7Service.Input("PROV-GD", "CNY", "CNY", BigDecimal.TEN, "SHA", FRI))).hasMessageContaining("缺少手续费配置");
        var noVenue = c7.simulate(new C7Service.Input("PROV-T1", "HKD", "EUR", new BigDecimal("1000"), "SHA", FRI));
        assertThat(noVenue.venues()).isEmpty();
        assertThat(noVenue.note()).contains("NO_FX_VENUE");
    }

    @Test
    void routingUsesSameCostForProviderVenue() throws Exception {
        var a = appetites.findAll().stream().filter(x -> x.providerCode.equals("PROV-W")).findFirst().orElseThrow();
        var copy = json.convertValue(json.valueToTree(a), com.hsg.relay.domain.Appetite.class);
        copy.id = null;
        copy.currency = "AUD";
        appetites.save(copy);
        ObjectNode req = (ObjectNode) json.readTree("""
                {"paymentId":"C7-TEST","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"AUD","amount":10000,
                "beneficiaryBankCountry":"AU","beneficiaryAddressCountry":"AU","beneficiaryBankBic":"CHASAU2S","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"OTHER","clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        var result = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        assertThat(result.route().providerCode).isEqualTo("PROV-W");
        assertThat(result.route().fxVenue).isEqualTo("PROVIDER");
        assertThat(result.route().estimatedCostHkd).isEqualByComparingTo("278.50");
        var sim = c7.simulate(new C7Service.Input("PROV-W", "USD", "AUD", new BigDecimal("10000"), "SHA", FRI));
        var v = sim.venues().stream().filter(x -> x.fxVenue().equals("PROVIDER")).findFirst().orElseThrow();
        assertThat(v.calculation().totalHkd().setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("278.50");
    }
}
