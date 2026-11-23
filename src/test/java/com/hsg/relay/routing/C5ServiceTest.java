package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class C5ServiceTest {
    @Autowired
    C5Service c5;
    @Autowired
    C3Service c3;
    @Autowired
    RoutingService routing;
    @Autowired
    ObjectMapper json;

    private C5Service.Input input(String at, String requested) {
        return new C5Service.Input("PROV-T1", "USD", "US", null, "CHASUS33", "SHA", requested == null ? null : LocalDate.parse(requested), ZonedDateTime.parse(at));
    }

    @Test
    void updatesOnlyScheduleAndSharesItWithC3() {
        var listing = c5.list();
        var row = listing.rows().get(0);
        var original = row.reach();
        var before = routing.config().config();
        c5.save(new C5Service.Save(listing.revision(), row.index(), original.zone(), LocalTime.of(15, 0), 3, List.of(LocalDate.parse("2026-12-25"))));
        var saved = c3.list().rows().get(row.index()).reach();
        assertThat(saved.cutoff()).isEqualTo(LocalTime.of(15, 0));
        assertThat(saved.offsetDays()).isEqualTo(3);
        assertThat(saved.bics()).isEqualTo(original.bics());
        assertThat(saved.charges()).isEqualTo(original.charges());
        assertThat(saved.perPaymentUsd()).isEqualTo(original.perPaymentUsd());
        assertThat(saved.enabled()).isEqualTo(original.enabled());
        assertThat(routing.config().config().fees()).isEqualTo(before.fees());
    }

    @Test
    void staleRevisionCannotOverwrite() {
        var a = c5.list();
        var row = a.rows().get(0);
        var in = new C5Service.Save(a.revision(), row.index(), "Asia/Hong_Kong", LocalTime.NOON, 1, List.of());
        c5.save(in);
        assertThatThrownBy(() -> c5.save(in)).hasMessageContaining("更新");
    }

    @Test
    void noReachIsPrerequisiteMissingRatherThanC5Failure() {
        var result = c5.simulate(new C5Service.Input("PROV-GD", "USD", "CN", null, "ICBKCNBJXXX", "SHA", null, null));
        assertThat(result.status()).isEqualTo("NO_REACH");
        assertThat(result.calculation()).isNull();
        assertThat(result.note()).contains("不是 C5 拒绝");
    }

    @Test
    void zeroSpeedScoreDoesNotFilterCandidateAndRoutingReturnsCalculation() throws Exception {
        var sim = c5.simulate(input("2026-11-20T17:00:00+08:00", "2026-11-20"));
        assertThat(sim.calculation().speedScore()).isZero();
        ObjectNode req = (ObjectNode) json.readTree("""
                 {"paymentId":"C5-TEST","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                 "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                 "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                 "requestedValueDate":"2026-11-20","at":"2026-11-20T17:00:00+08:00"}
                """);
        var result = routing.simulate(json.convertValue(req, RoutingService.Request.class));
        var candidate = result.candidates().stream().filter(c -> c.providerCode.equals("PROV-T1")).findFirst().orElseThrow();
        assertThat(candidate.passed).isTrue();
        assertThat(candidate.rank).isNotNull();
        assertThat(candidate.checks).extracting(RoutingService.Check::code).doesNotContain("C5");
        assertThat(candidate.c5).isEqualTo(sim.calculation());
        assertThat(candidate.valueDate).isEqualTo(sim.calculation().candidateValueDate());
        assertThat(candidate.scores.get("C5")).isEqualByComparingTo("0");
        assertThat(result.result()).isEqualTo("ROUTE");
    }

    @Test
    void hsgRequestedValueDateAliasIsAccepted() throws Exception {
        var input = json.readValue("{\"requested_value_date\":\"2026-12-01\"}", C5Service.Input.class);
        assertThat(input.requestedValueDate()).isEqualTo("2026-12-01");
        var request = json.readValue("{\"requested_value_date\":\"2026-12-01\"}", RoutingService.Request.class);
        assertThat(request.requestedValueDate()).isEqualTo("2026-12-01");
    }
}
