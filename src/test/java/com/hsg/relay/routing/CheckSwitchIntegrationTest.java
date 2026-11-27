package com.hsg.relay.routing;

import com.hsg.relay.api.CheckSwitchController;
import com.hsg.relay.c2.C2Service;
import com.hsg.relay.service.C1Service;
import com.hsg.relay.service.CheckSwitchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class CheckSwitchIntegrationTest {
    @Autowired CheckSwitchController controller;
    @Autowired CheckSwitchService switches;
    @Autowired C1Service c1;
    @Autowired C2Service c2;
    @Autowired C3Service c3;
    @Autowired C4Service c4;
    @Autowired C6Service c6;
    @Autowired C10Service c10;

    @Test
    void eachSavedSwitchSkipsItsStandaloneSimulation() {
        for (String code : List.of("C1", "C2", "C3", "C4", "C6", "C10")) {
            assertThat(controller.get(code).enabled()).isTrue();
            assertThat(controller.save(code, new CheckSwitchController.Input(false)).enabled()).isFalse();
            assertThat(switches.get(code).enabled()).isFalse();
        }
        assertThat(c1.evaluate(new C1Service.Simulation("C10001", "PROV-HO", "USD", "KG", BigDecimal.TEN)).reasonCode()).isEqualTo("SKIPPED");
        assertThat(c2.simulate(new C2Service.Simulation("PROV-HO", "USD", "USD", "KG", "KG", null, "HK", "HK", null, null, List.of())).reasonCode()).isEqualTo("SKIPPED");
        assertThat(c3.simulate(new C3Matcher.Input("PROV-HO", "USD", "KG", null, "CHASUS33", "SHA")).reasonCode()).isEqualTo("SKIPPED");
        assertThat(c4.simulate(new C4Service.Input("PROV-HO", "USD", "USD", BigDecimal.TEN, null)).status()).isEqualTo("SKIPPED");
        assertThat(c6.simulate(new C6Service.Input("PROV-HO", "USD", BigDecimal.TEN, "KG", null, "CHASUS33", "SHA", null, null)).status()).isEqualTo("SKIPPED");
        assertThat(c10.simulate(new C10Service.Input("USD", "USD", BigDecimal.TEN, null)).status()).isEqualTo("SKIPPED");
    }
}
