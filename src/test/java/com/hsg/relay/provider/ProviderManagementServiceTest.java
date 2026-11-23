package com.hsg.relay.provider;

import com.hsg.relay.c2.C2Service;
import com.hsg.relay.repo.*;
import com.hsg.relay.routing.RoutingService;
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
class ProviderManagementServiceTest {
    @Autowired
    ProviderManagementService service;
    @Autowired
    ProviderRepository providers;
    @Autowired
    RoutingService routing;
    @Autowired
    C2Service c2;
    @Autowired
    RuleAuditRepository audit;
    @Autowired
    ObjectMapper json;

    ProviderManagementService.Input input(String code, String status, String channel, String bic, Integer priority, List<String> tags, Long version) {
        return new ProviderManagementService.Input(code, "测试合作方", "OTHER_BANK", status, channel, true, priority, bic, "HSG-NOSTRO", "GL-001", "ops.owner", LocalDate.now(ZoneId.of("Asia/Hong_Kong")).plusDays(20), tags, version);
    }

    @Test
    void createsDraftAndSynchronizesC2TagsWithAudit() {
        var v = service.create(input("PROV-NEW", "NOT_LIVE", "API", null, 20, List.of("LOCAL_CLEARING"), null));
        assertThat(v.provider().code).isEqualTo("PROV-NEW");
        assertThat(v.ready()).isFalse();
        assertThat(c2.providerCapabilities("PROV-NEW")).containsExactly("LOCAL_CLEARING");
        assertThat(service.history("PROV-NEW")).extracting(a -> a.objectType).contains("PROVIDER", "PROVIDER_CAPABILITY");
        assertThat(v.reviewStatus()).isEqualTo("DUE_SOON");
    }

    @Test
    void blocksLiveBeforeReferencesExist() {
        assertThatThrownBy(() -> service.create(input("PROV-NEW", "LIVE", "API", null, 20, List.of(), null))).hasMessageContaining("上线条件").hasMessageContaining("业务覆盖").hasMessageContaining("手续费").hasMessageContaining("业务准入");
    }

    @Test
    void swiftBicRequiredAndFormatValidated() {
        assertThatThrownBy(() -> service.create(input("PROV-NEW", "NOT_LIVE", "SWIFT", null, 20, List.of(), null))).hasMessageContaining("BIC");
    }

    @Test
    void rejectsInvalidPriority() {
        assertThatThrownBy(() -> service.create(input("PROV-NEW", "NOT_LIVE", "API", null, 100, List.of(), null))).hasMessageContaining("1–99");
    }

    @Test
    void rejectsUnknownCapabilityTags() {
        assertThatThrownBy(() -> service.create(input("PROV-NEW", "NOT_LIVE", "API", null, 20, List.of("UNKNOWN"), null))).hasMessageContaining("标签无效");
    }

    @Test
    void liveReadyProviderSavesAndRecordsBeforeAfter() {
        var before = service.get("PROV-T1");
        long oldVersion = before.provider().version;
        var updated = service.update("PROV-T1", input("PROV-T1", "LIVE", "SWIFT", "CHASUS33", 12, before.capabilityTags(), before.provider().version));
        assertThat(updated.ready()).isTrue();
        assertThat(updated.provider().priority).isEqualTo(12);
        assertThat(updated.provider().version).isGreaterThan(oldVersion);
        var log = service.history("PROV-T1").get(0);
        assertThat(log.beforeJson).contains("T1 Bank");
        assertThat(log.afterJson).contains("测试合作方");
    }

    @Test
    void rejectsStaleVersion() {
        var v = service.create(input("PROV-NEW", "NOT_LIVE", "API", null, 20, List.of(), null));
        long original = v.provider().version;
        service.update("PROV-NEW", input("PROV-NEW", "NOT_LIVE", "API", null, 21, List.of(), original));
        assertThatThrownBy(() -> service.update("PROV-NEW", input("PROV-NEW", "NOT_LIVE", "API", null, 22, List.of(), original))).hasMessageContaining("版本");
    }

    @Test
    void rejectsCodeChanges() {
        var p = providers.findById("PROV-T1").orElseThrow();
        assertThatThrownBy(() -> service.update("PROV-T1", input("PROV-X", "NOT_LIVE", "API", null, 20, List.of(), p.version))).hasMessageContaining("不可修改");
    }

    @Test
    void notLiveProviderExcludedUnlessExplicitlyIncludedInSimulation() throws Exception {
        var p = providers.findById("PROV-W").orElseThrow();
        var v = service.update("PROV-W", input("PROV-W", "NOT_LIVE", "API", null, 20, List.of("LOCAL_CLEARING"), p.version));
        ObjectNode req = (ObjectNode) json.readTree("""
                {"paymentId":"PROVIDER-TEST","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                 "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                 "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                 "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """);
        assertThat(routing.simulate(json.convertValue(req, RoutingService.Request.class)).candidates()).extracting(c -> c.providerCode).doesNotContain("PROV-W");
        req.putArray("includeProviders").add("PROV-W");
        assertThat(routing.simulate(json.convertValue(req, RoutingService.Request.class)).candidates()).extracting(c -> c.providerCode).contains("PROV-W");
    }

    @Test
    void overdueReviewIsFlagged() {
        var p = providers.findById("PROV-W").orElseThrow();
        p.appetiteReviewDate = LocalDate.now(ZoneId.of("Asia/Hong_Kong")).minusDays(1);
        providers.saveAndFlush(p);
        assertThat(service.get("PROV-W").reviewStatus()).isEqualTo("OVERDUE");
    }
}
