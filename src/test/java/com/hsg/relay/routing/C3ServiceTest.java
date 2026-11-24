package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.repo.RuleAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static com.hsg.relay.routing.RoutingConfig.Reach;

@SpringBootTest
@Transactional
class C3ServiceTest {
    @Autowired
    C3Service c3;
    @Autowired
    RoutingService routing;
    @Autowired
    ObjectMapper json;
    @Autowired
    RuleAuditRepository audit;

    Reach row(String provider, String country, String region, List<String> bics) {
        return new Reach(provider, "USD", country, region, bics, List.of("SHA"), "Asia/Hong_Kong", LocalTime.of(16, 0), 2, BigDecimal.valueOf(5000), BigDecimal.valueOf(50000), List.of(), true);
    }

    @Test
    void savesEditsAndTogglesWithoutReplacingOtherConfig() {
        var before = routing.config();
        long audits = audit.count();
        var r = row("PROV-T1", "US", "ALL", List.of("CHASUS33"));
        var saved = c3.save(new C3Service.Save(before.revision(), null, "LIST", r));
        assertThat(saved.revision()).isEqualTo(before.revision() + 1);
        int index = saved.rows().size() - 1;
        assertThat(routing.config().config().balances()).isEqualTo(before.config().balances());
        assertThat(routing.config().config().fees()).isEqualTo(before.config().fees());
        var off = c3.toggle(index, new C3Service.Toggle(saved.revision()));
        assertThat(off.rows().get(index).reach().active()).isFalse();
        var edited = c3.save(new C3Service.Save(off.revision(), index, "ALL", row("PROV-T1", "US", "ALL", List.of())));
        assertThat(edited.rows()).hasSize(saved.rows().size());
        assertThat(edited.rows().get(index).reach().bics()).isEmpty();
        assertThat(audit.count()).isEqualTo(audits + 3);
    }

    @Test
    void staleSaveCannotOverwriteLatestConfig() {
        var before = c3.list();
        c3.save(new C3Service.Save(before.revision(), null, "ALL", row("PROV-T1", "US", "ALL", List.of())));
        assertThatThrownBy(() -> c3.save(new C3Service.Save(before.revision(), null, "ALL", row("PROV-T1", "GB", "ALL", List.of())))).hasMessageContaining("更新");
    }

    @Test
    void deletesCoverageWithRevisionCheckAndKeepsOtherRows() {
        var before = c3.list();
        var saved = c3.save(new C3Service.Save(before.revision(), null, "ALL", row("PROV-T1", "US", "ALL", List.of())));
        int index = saved.rows().size() - 1;
        assertThatThrownBy(() -> c3.delete(index, new C3Service.Toggle(before.revision()))).hasMessageContaining("更新");
        var deleted = c3.delete(index, new C3Service.Toggle(saved.revision()));
        assertThat(deleted.rows()).hasSize(before.rows().size());
        assertThat(deleted.rows()).extracting(C3Service.RowView::reach).containsExactlyElementsOf(before.rows().stream().map(C3Service.RowView::reach).toList());
    }

    @Test
    void emptyListAndInvalidChargesAreRejected() {
        long rev = c3.list().revision();
        assertThatThrownBy(() -> c3.save(new C3Service.Save(rev, null, "LIST", row("PROV-T1", "US", "ALL", List.of())))).hasMessageContaining("LIST");
        var r = row("PROV-T1", "US", "ALL", List.of());
        var invalid = new Reach(r.provider(), r.currency(), r.country(), r.region(), List.of(), List.of(), r.zone(), r.cutoff(), r.offsetDays(), r.perPaymentUsd(), r.dailyUsd(), List.of(), true);
        assertThatThrownBy(() -> c3.save(new C3Service.Save(rev, null, "ALL", invalid))).hasMessageContaining("扣费");
    }

    @Test
    void duplicateBicOrderDoesNotCreateDuplicateCoverage() {
        var before = c3.list();
        var one = c3.save(new C3Service.Save(before.revision(), null, "LIST", row("PROV-T1", "US", "ALL", List.of("CHASUS33", "CITIUS33"))));
        assertThatThrownBy(() -> c3.save(new C3Service.Save(one.revision(), null, "LIST", row("PROV-T1", "US", "ALL", List.of("CITIUS33", "CHASUS33"))))).hasMessageContaining("重复");
    }

    @Test
    void simulationAndRoutingUseSameSpecificRowForC5C6() throws Exception {
        var r = row("PROV-T1", "CN", "CN-GD", List.of("ICBKCNBJ"));
        var saved = c3.save(new C3Service.Save(c3.list().revision(), null, "LIST", r));
        var in = new C3Matcher.Input("PROV-T1", "USD", "CN", "CN-GD", "ICBKCNBJXXX", "SHA");
        var sim = c3.simulate(in);
        assertThat(sim.passed()).isTrue();
        assertThat(sim.selectedIndex()).isEqualTo(saved.rows().size() - 1);
        assertThat(sim.selectedReach()).isEqualTo(r);
        var request = json.readValue("""
                {"paymentId":"C3-INTEGRATION","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                 "beneficiaryBankCountry":"CN","beneficiaryAddressCountry":"CN","beneficiaryRegion":"CN-GD","beneficiaryBankBic":"ICBKCNBJXXX","chargeOption":"SHA",
                 "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                 "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """, RoutingService.Request.class);
        var candidate = routing.simulate(request).candidates().stream().filter(c -> c.providerCode.equals("PROV-T1")).findFirst().orElseThrow();
        assertThat(candidate.valueDate.toString()).isEqualTo("2026-11-24");
        assertThat(candidate.checks.stream().filter(c -> c.code().equals("C3")).findFirst().orElseThrow().detail()).isEqualTo(r);
        assertThat(candidate.checks.stream().filter(c -> c.code().equals("C6")).findFirst().orElseThrow().reasons()).contains("LIMIT_PER_PAYMENT");
        c3.toggle(sim.selectedIndex(), new C3Service.Toggle(saved.revision()));
        assertThat(c3.simulate(in).selectedReach()).isNotEqualTo(r);
    }
}
