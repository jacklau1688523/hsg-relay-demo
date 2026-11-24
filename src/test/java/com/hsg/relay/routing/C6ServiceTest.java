package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class C6ServiceTest {
    @Autowired
    C6Service c6;
    @Autowired
    RoutingService routing;
    @Autowired
    ObjectMapper json;
    private static final ZonedDateTime AT = ZonedDateTime.parse("2026-11-20T10:00:00+08:00");

    private C6Service.LimitRow row(int index) {
        return c6.limits().rows().get(index);
    }

    @Test
    void listingShowsReachLimitsAndToday() {
        var listing = c6.limits();
        assertThat(listing.rows()).isNotEmpty();
        assertThat(listing.currencies()).contains("USD", "HKD");
        assertThat(listing.todayUsd()).containsKey("PROV-T1");
        var t1 = listing.rows().stream().filter(r -> r.reach().provider().equals("PROV-T1") && r.reach().currency().equals("USD")).findFirst().orElseThrow();
        assertThat(t1.reach().perPaymentUsd()).isEqualByComparingTo("2000000");
        assertThat(t1.reach().dailyUsd()).isEqualByComparingTo("10000000");
    }

    @Test
    void saveUpdatesLimitsAndKeepsOtherReachFields() {
        int index = 0;
        assertThat(row(index).reach().provider()).isEqualTo("PROV-T1");
        c6.save(new C6Service.Save(c6.limits().revision(), index, new BigDecimal("5000000"), new BigDecimal("50000000")));
        var after = row(index).reach();
        assertThat(after.perPaymentUsd()).isEqualByComparingTo("5000000");
        assertThat(after.dailyUsd()).isEqualByComparingTo("50000000");
        assertThat(after.cutoff()).isEqualTo(java.time.LocalTime.of(17, 0));
        assertThat(after.zone()).isEqualTo("Asia/Hong_Kong");
        assertThat(after.currency()).isEqualTo("USD");
        assertThat(routing.config().config().reaches().get(index).dailyUsd()).isEqualByComparingTo("50000000");
    }

    @Test
    void saveRejectsBadInput() {
        var revision = c6.limits().revision();
        assertThatThrownBy(() -> c6.save(new C6Service.Save(revision, 0, new BigDecimal("-1"), BigDecimal.TEN))).hasMessageContaining("非负");
        assertThatThrownBy(() -> c6.save(new C6Service.Save(revision, 99, BigDecimal.TEN, BigDecimal.TEN))).hasMessageContaining("不存在");
        c6.save(new C6Service.Save(revision, 0, BigDecimal.TEN, BigDecimal.TEN));
        assertThatThrownBy(() -> c6.save(new C6Service.Save(revision, 0, BigDecimal.ONE, BigDecimal.TEN))).hasMessageContaining("已更新");
    }

    @Test
    void todayUpdatesDemoSnapshotForRouting() {
        c6.today(new C6Service.Today(c6.limits().revision(), "PROV-W", new BigDecimal("12345")));
        assertThat(c6.limits().todayUsd().get("PROV-W")).isEqualByComparingTo("12345");
        assertThat(routing.config().config().todayUsd().get("PROV-W")).isEqualByComparingTo("12345");
        assertThatThrownBy(() -> c6.today(new C6Service.Today(c6.limits().revision(), "PROV-W", new BigDecimal("-1")))).hasMessageContaining("非负");
        assertThatThrownBy(() -> c6.today(new C6Service.Today(c6.limits().revision(), "PROV-XX", BigDecimal.ONE))).hasMessageContaining("不存在");
    }

    @Test
    void deleteTodayReturnsToZeroWithoutChangingReachLimits() {
        var before = c6.limits();
        assertThat(before.todayUsd()).containsKey("PROV-T1");
        assertThatThrownBy(() -> c6.deleteToday("PROV-T1", before.revision() - 1)).hasMessageContaining("更新");
        var after = c6.deleteToday("PROV-T1", before.revision());
        assertThat(after.todayUsd()).doesNotContainKey("PROV-T1");
        assertThat(after.rows()).isEqualTo(before.rows());
    }

    @Test
    void simulatePassesAndConvertsToUsd() {
        var sim = c6.simulate(new C6Service.Input("PROV-T1", "USD", new BigDecimal("10000"), "US", null, "CHASUS33", "SHA", null, AT));
        assertThat(sim.status()).isEqualTo("PASS");
        assertThat(sim.selectedIndex()).isNotNull();
        assertThat(sim.calculation().amountUsd()).isEqualByComparingTo("10000");
        var hkd = c6.simulate(new C6Service.Input("PROV-T1", "HKD", new BigDecimal("78000"), "US", null, "CHASUS33", "SHA", null, AT));
        assertThat(hkd.calculation().amountUsd()).isEqualByComparingTo("10000");
    }

    @Test
    void simulateFlagsPerPaymentAndDailySeparately() {
        var per = c6.simulate(new C6Service.Input("PROV-T1", "USD", new BigDecimal("3000000"), "US", null, "CHASUS33", "SHA", null, AT));
        assertThat(per.status()).isEqualTo("LIMIT_PER_PAYMENT");
        assertThat(per.calculation().reasons()).containsExactly("LIMIT_PER_PAYMENT");
        var daily = c6.simulate(new C6Service.Input("PROV-W", "USD", new BigDecimal("100000"), "US", null, "CHASUS33", "SHA", new BigDecimal("9950000"), AT));
        assertThat(daily.status()).isEqualTo("LIMIT_DAILY");
        assertThat(daily.calculation().reasons()).containsExactly("LIMIT_DAILY");
        assertThat(c6.limits().todayUsd().get("PROV-W")).isEqualByComparingTo("0"); // 试算覆盖值不落库
    }

    @Test
    void simulateWithoutReachReportsNotApplicable() {
        var sim = c6.simulate(new C6Service.Input("PROV-GD", "USD", new BigDecimal("1000"), "CN", "CN-ZJ", "ICBKCNBJBJA", "SHA", null, AT));
        assertThat(sim.status()).isEqualTo("NO_REACH");
        assertThat(sim.calculation()).isNull();
        assertThat(sim.note()).contains("C3");
        assertThatThrownBy(() -> c6.simulate(new C6Service.Input("PROV-XX", "USD", BigDecimal.TEN, "US", null, "CHASUS33", "SHA", null, AT))).hasMessageContaining("不存在");
    }

    @Test
    void routingUsesSameC6Result() throws Exception {
        ObjectNode req = (ObjectNode) json.readTree("""
                {"paymentId":"C6-TEST","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":3000000,
                "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        var result = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        var check = result.candidates().stream().filter(c -> c.providerCode.equals("PROV-T1")).findFirst().orElseThrow()
                .checks.stream().filter(x -> x.code().equals("C6")).findFirst().orElseThrow();
        var standalone = c6.simulate(new C6Service.Input("PROV-T1", "USD", new BigDecimal("3000000"), "US", null, "CHASUS33", "SHA", null, AT));
        assertThat(check.reasons()).isEqualTo(standalone.calculation().reasons());
        assertThat(check.detail()).isInstanceOf(C6Calculator.Result.class);
    }
}
