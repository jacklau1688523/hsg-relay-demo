package com.hsg.relay.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hsg.relay.repo.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class RoutingServiceTest {
    @Autowired
    RoutingService service;
    @Autowired
    ObjectMapper json;
    @Autowired
    ProviderRepository providers;
    @Autowired
    AppetiteRepository appetites;
    ObjectNode req;

    @BeforeEach
    void setup() throws Exception {
        req = (ObjectNode) json.readTree("""
                {"paymentId":"TEST-1","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
    }

    RoutingService.Result run() {
        return service.simulate(json.convertValue(req, RoutingService.Request.class));
    }

    RoutingService.Candidate candidate(RoutingService.Result r, String p) {
        return r.candidates().stream().filter(c -> c.providerCode.equals(p)).findFirst().orElseThrow();
    }

    ObjectNode config() {
        return json.valueToTree(service.config().config());
    }

    void save(ObjectNode cfg) {
        service.saveConfig(new RoutingService.ConfigView(service.config().revision(), json.convertValue(cfg, RoutingConfig.class)));
    }

    @Test
    void defaultConfigIsValid() {
        service.validateConfig(service.config().config());
    }

    @Test
    void routesAndPersistsWithoutReserving() {
        var before = service.config();
        var r = run();
        assertThat(r.result()).isEqualTo("ROUTE");
        assertThat(r.route().rank).isEqualTo(1);
        assertThat(r.alternatives()).hasSize(1);
        assertThat(r.candidates()).hasSize(4);
        assertThat(service.decision(r.decisionId()).toString()).contains("TEST-1");
        assertThat(service.config()).isEqualTo(before);
    }

    @Test
    void allFiltersRunEvenAfterC1Failure() {
        var c = candidate(run(), "PROV-GD");
        assertThat(c.checks).extracting(RoutingService.Check::code).containsExactly("C1", "C2", "C3", "C4", "C6", "C10");
        assertThat(c.checks).filteredOn(x -> !x.passed()).extracting(RoutingService.Check::code).contains("C1", "C3");
    }

    @Test
    void invalidRequestHasNoCandidates() {
        req.put("amount", -1);
        assertThat(run().reasonCode()).isEqualTo("INVALID_REQUEST");
        assertThat(run().candidates()).isEmpty();
    }

    @Test
    void incompleteClassificationTakesPrecedence() {
        req.put("clientId", "C10003");
        assertThat(run().reasonCode()).isEqualTo("CLIENT_NOT_CLASSIFIED");
    }

    @Test
    void unsupportedFxPairHasNoCandidates() {
        req.put("debitCurrency", "HKD");
        req.put("paymentCurrency", "EUR");
        assertThat(run().reasonCode()).isEqualTo("NO_FX_VENUE");
    }

    @Test
    void closedWeekendFxIncludesNextWindow() {
        req.put("debitCurrency", "HKD");
        req.put("at", "2026-11-21T10:00:00+08:00");
        var r = run();
        assertThat(r.reasonCode()).isEqualTo("FX_WINDOW_CLOSED");
        assertThat(r.nextFxWindow().toString()).startsWith("2026-11-23T09:00");
    }

    @Test
    void fundingOnlyFailureRefers() {
        var cfg = config();
        cfg.withArray("balances").forEach(b -> ((ObjectNode) b).put("balance", 0));
        save(cfg);
        var p = providers.findById("PROV-W").orElseThrow();
        p.status = "NOT_LIVE";
        providers.save(p);
        assertThat(run().reasonCode()).isEqualTo("FUNDING_SHORTFALL");
    }

    @Test
    void monthlyAcceptanceFailureRefers() {
        req.put("clientId", "C10001");
        assertThat(run().reasonCode()).isEqualTo("NO_PROVIDER_ACCEPTS");
    }

    @Test
    void zeroCostScores100() {
        var cfg = config();
        cfg.withArray("fees").forEach(f -> {
            for (String key : List.of("fixed", "cable", "intermediary")) ((ObjectNode) f).put(key, 0);
        });
        save(cfg);
        var r = run();
        assertThat(r.route().scores.get("C7")).isEqualByComparingTo("100");
        assertThat(r.route().estimatedCostHkd).isEqualByComparingTo("0");
    }

    @Test
    void futureValueDateGetsFullSpeed() {
        req.put("requestedValueDate", "2026-12-01");
        var c = candidate(run(), "PROV-T1");
        assertThat(c.valueDate.toString()).isEqualTo("2026-12-01");
        assertThat(c.scores.get("C5")).isEqualByComparingTo("100");
    }

    @Test
    void dailyAndTicketLimitsAreBothTraced() {
        req.put("amount", 3000000);
        var c = candidate(run(), "PROV-T1");
        assertThat(c.checks).filteredOn(x -> x.code().equals("C6")).allSatisfy(x -> assertThat(x.reasons()).contains("LIMIT_PER_PAYMENT"));
    }

    @Test
    void rejectsBadWeightAndStaleRevision() {
        var cfg = config();
        ((ObjectNode) cfg.withArray("weights").get(0)).put("cost", 99);
        assertThatThrownBy(() -> save(cfg)).isInstanceOf(IllegalArgumentException.class);
        var current = service.config();
        save(config());
        assertThatThrownBy(() -> service.saveConfig(current)).hasMessageContaining("已更新");
    }

    @Test
    void treasuryUsesPaymentCurrencyBalance() {
        req.put("debitCurrency", "HKD");
        var c = candidate(run(), "PROV-T1");
        var funding = c.checks.stream().filter(x -> x.code().equals("C4")).findFirst().orElseThrow();
        assertThat(funding.detail()).isInstanceOfSatisfying(C4Calculator.Result.class, d -> {
            assertThat(d.accountCurrency()).isEqualTo("USD");
            assertThat(d.rate()).isEqualByComparingTo("1");
            assertThat(d.required()).isEqualByComparingTo("10000");
        });
        assertThat(c.fxVenue).isEqualTo("TREASURY");
    }

    private void wiseAud() {
        var a = appetites.findAll().stream().filter(x -> x.providerCode.equals("PROV-W")).findFirst().orElseThrow();
        var copy = json.convertValue(json.valueToTree(a), com.hsg.relay.domain.Appetite.class);
        copy.id = null;
        copy.currency = "AUD";
        appetites.save(copy);
        req.put("paymentCurrency", "AUD");
    }

    @Test
    void providerFxUsesDebitCurrencyAndSpread() {
        wiseAud();
        var r = run();
        assertThat(r.result()).isEqualTo("ROUTE");
        assertThat(r.route().providerCode).isEqualTo("PROV-W");
        assertThat(r.route().fxVenue).isEqualTo("PROVIDER");
        assertThat(r.route().estimatedCostHkd).isEqualByComparingTo("278.50");
        assertThat(r.route().checks.stream().filter(x -> x.code().equals("C4")).findFirst().orElseThrow().detail()).isInstanceOfSatisfying(C4Calculator.Result.class, d -> {
            assertThat(d.accountCurrency()).isEqualTo("USD");
            assertThat(d.enabled()).isFalse();
            assertThat(d.required().doubleValue()).isCloseTo(6538.4615, within(0.0001));
        });
    }

    @Test
    void providerFxTicketLimitRetainsTrace() {
        wiseAud();
        req.put("amount", 1000000);
        var r = run();
        assertThat(r.result()).isEqualTo("REFER");
        assertThat(r.reasonCode()).isEqualTo("FX_WINDOW_CLOSED");
        assertThat(r.candidates().get(0).checks.stream().filter(x -> x.code().equals("C10")).findFirst().orElseThrow().reasons()).contains("FX_TICKET_LIMIT");
        assertThat(r.nextFxWindow()).isNull();
    }
}
