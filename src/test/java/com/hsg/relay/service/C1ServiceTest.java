package com.hsg.relay.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;
import com.hsg.relay.api.RelayController;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.repo.AppetiteRepository;
import java.util.List;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relaytest;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class C1ServiceTest {
    @Autowired
    C1Service service;
    @Autowired
    RelayController controller;
    @Autowired
    AppetiteRepository appetites;

    @Test
    void caseByCaseWithApprovalPasses() {
        var result = service.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("150000")));
        assertThat(result.passed()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("PASS");
    }

    @Test
    void pspRejectedByTierOneBank() {
        var result = service.evaluate(new C1Service.Simulation("C10001", "PROV-T1", "USD", "KG", new BigDecimal("150000")));
        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("APPETITE_EXCLUDED");
    }

    @Test
    void incompleteClientRejected() {
        var result = service.evaluate(new C1Service.Simulation("C10003", "PROV-T1", "USD", "KG", new BigDecimal("150000")));
        assertThat(result.reasonCode()).isEqualTo("CLIENT_NOT_CLASSIFIED");
    }

    @Test
    void missingRuleDefaultsToDeny() {
        var result = service.evaluate(new C1Service.Simulation("C10002", "PROV-HO", "USD", "CN", new BigDecimal("100")));
        assertThat(result.reasonCode()).isEqualTo("APPETITE_NOT_SET");
    }

    @Test
    @Transactional
    void exclusionCanBeReleasedWithoutDeletingHistory() {
        var exclusion = controller.addExclusion(new ClientExclusion("C10002", "PROV-T1", "review", java.time.LocalDateTime.now().minusDays(1), null));
        assertThat(controller.exclusions("PROV-T1")).anyMatch(x -> x.id.equals(exclusion.id));
        assertThat(controller.exclusions("PROV-HO")).noneMatch(x -> x.id.equals(exclusion.id));
        var request = new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal("100"));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("CLIENT_EXCLUDED");
        controller.releaseExclusion(exclusion.id);
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("PASS");
    }

    @Test
    @Transactional
    void configuredConditionControlsWhichAppetiteRowMatches() {
        var rule = appetites.findAll().stream().filter(a -> a.providerCode.equals("PROV-HO") && a.flowCategory.equals("PSP")).findFirst().orElseThrow();
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true,
                new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "paymentCurrency", "EQ", "CNY")), null, null, null)));
        var request = new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", new BigDecimal("100"));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("APPETITE_NOT_SET");
        controller.saveC1Condition(rule.id, new RelayController.C1ConditionUpdate(true,
                new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "paymentCurrency", "EQ", "USD")), null, null, null)));
        assertThat(service.evaluate(request).reasonCode()).isEqualTo("PASS");
    }
}
