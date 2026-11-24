package com.hsg.relay.provider;

import com.hsg.relay.c2.C2Service;
import com.hsg.relay.c2.ProviderCapabilityRepository;
import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import com.hsg.relay.routing.RoutingConfig;
import com.hsg.relay.routing.RoutingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;

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
    AppetiteRepository appetites;
    @Autowired
    ProviderUsageRepository usages;
    @Autowired
    ProviderCapabilityRepository capabilities;
    @Autowired
    ClientExclusionRepository exclusions;
    @Autowired
    CaseApprovalRepository approvals;
    @Autowired
    ObjectMapper json;
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");

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
    void deletingASeededProviderRemovesItsRoutingRowsAndLeavesTheRestOfTheConfigIntact() throws Exception {
        String code = "PROV-T1";
        var before = routing.config();
        var impact = service.impact(code);
        assertThat(impact.reaches()).isEqualTo(before.config().reaches().stream().filter(r -> r.provider().equals(code)).count());
        assertThat(impact.fees()).isEqualTo(before.config().fees().stream().filter(f -> f.provider().equals(code)).count());
        assertThat(impact.appetites()).isEqualTo(3);
        assertThat(impact.usages()).isEqualTo(1);

        service.delete(code, providers.findById(code).orElseThrow().version);

        assertThat(providers.existsById(code)).isFalse();
        var after = routing.config();
        assertThat(after.revision()).isEqualTo(before.revision() + 1);
        assertThat(after.config().reaches()).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(otherProviders(before.config().reaches(), RoutingConfig.Reach::provider, code));
        assertThat(after.config().balances()).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(otherProviders(before.config().balances(), RoutingConfig.Balance::provider, code));
        assertThat(after.config().fees()).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(otherProviders(before.config().fees(), RoutingConfig.Fee::provider, code));
        assertThat(after.config().fxWindows()).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(otherProviders(before.config().fxWindows(), RoutingConfig.FxWindow::provider, code));
        assertThat(after.config().preferences()).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(otherProviders(before.config().preferences(), RoutingConfig.Preference::provider, code));
        assertThat(after.config().agreements()).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(otherProviders(before.config().agreements(), RoutingConfig.Agreement::provider, code));
        assertThat(after.config().todayUsd().keySet()).containsExactlyInAnyOrderElementsOf(otherKeys(before.config().todayUsd(), code));
        assertThat(after.config().monthUsd().keySet()).containsExactlyInAnyOrderElementsOf(otherKeys(before.config().monthUsd(), code));
        assertThat(after.config().monthCount().keySet()).containsExactlyInAnyOrderElementsOf(otherKeys(before.config().monthCount(), code));
        // 剩余配置仍然合法：各路由配置页照常保存；试算不再出现该合作方
        routing.validateConfig(after.config());
        assertThat(routing.simulate(standardRequest()).candidates()).extracting(c -> c.providerCode).doesNotContain(code);
    }

    @Test
    void deleteCascadesOwnedRowsKeepsClientRecordsAndSnapshotsWhatWasRemoved() {
        var created = service.create(input("PROV-DEL", "NOT_LIVE", "API", null, 20, List.of("LOCAL_CLEARING"), null));
        Appetite appetite = new Appetite();
        appetite.providerCode = "PROV-DEL";
        appetite.flowCategory = "STANDARD";
        appetite.action = "ACCEPT";
        appetite.evidenceDocNo = "APP-DEL-001";
        appetite.nextReviewDate = LocalDate.now(HK).plusYears(1);
        appetite.effectiveFrom = LocalDateTime.now(HK).minusDays(1);
        appetites.save(appetite);
        c2.saveRestriction(new C2Service.RestrictionInput(null, "PROV-DEL", "CURRENCY", "KGS", true, null, LocalDateTime.now(HK).minusDays(1), null, "测试"));
        usages.save(new ProviderUsage("PROV-DEL", "STANDARD", "2026-11", new BigDecimal("125.50")));
        LocalDateTime now = LocalDateTime.now(HK);
        var exclusion = exclusions.save(new ClientExclusion("C10002", "PROV-DEL", "测试", now, null));
        var approval = approvals.save(new CaseApproval("C10002", "PROV-DEL", "PSP", "APR-DEL-001", now, now.plusMonths(1)));
        long revision = routing.config().revision();

        assertThat(service.impact("PROV-DEL")).isEqualTo(new ProviderManagementService.Impact(1, 1, 1, 1, 0, 0, 0, 0, 0, 0));

        service.delete("PROV-DEL", created.provider().version);

        assertThat(providers.existsById("PROV-DEL")).isFalse();
        assertThat(appetites.findByProviderCode("PROV-DEL")).isEmpty();
        assertThat(capabilities.findByProviderCode("PROV-DEL")).isEmpty();
        assertThat(usages.findByProviderCode("PROV-DEL")).isEmpty();
        assertThat(c2.restrictions()).noneMatch(r -> r.providerCode.equals("PROV-DEL"));
        assertThat(routing.config().revision()).isEqualTo(revision);
        assertThat(exclusions.existsById(exclusion.id)).isTrue();
        assertThat(approvals.existsById(approval.id)).isTrue();
        var log = audit.findAll().stream().filter(a -> a.objectType.equals("PROVIDER") && a.objectId.equals("PROV-DEL") && a.operation.equals("DELETE")).toList();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).beforeJson).contains("PROV-DEL").contains("LOCAL_CLEARING").contains("APP-DEL-001").contains("KGS").contains("125.50");
        assertThat(log.get(0).afterJson).isNull();
        assertThatThrownBy(() -> service.history("PROV-DEL")).hasMessageContaining("不存在");
    }

    @Test
    void deleteRequiresTheCurrentVersionAndRemovesNothingOtherwise() {
        var created = service.create(input("PROV-DEL", "NOT_LIVE", "API", null, 20, List.of("LOCAL_CLEARING"), null));
        assertThatThrownBy(() -> service.delete("PROV-DEL", null)).hasMessageContaining("版本");
        assertThatThrownBy(() -> service.delete("PROV-DEL", created.provider().version + 1)).hasMessageContaining("版本");
        assertThat(providers.existsById("PROV-DEL")).isTrue();
        assertThat(capabilities.findByProviderCode("PROV-DEL")).hasSize(1);
        assertThatThrownBy(() -> service.delete("PROV-MISSING", 0L)).hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.impact("PROV-MISSING")).hasMessageContaining("不存在");

        service.delete("PROV-DEL", created.provider().version);

        assertThat(providers.existsById("PROV-DEL")).isFalse();
        assertThat(capabilities.findByProviderCode("PROV-DEL")).isEmpty();
    }

    private RoutingService.Request standardRequest() throws Exception {
        return json.convertValue(json.readTree("""
                {"paymentId":"PROVIDER-TEST","clientId":"C10002","debitCurrency":"USD","paymentCurrency":"USD","amount":10000,
                 "beneficiaryBankCountry":"US","beneficiaryAddressCountry":"US","beneficiaryBankBic":"CHASUS33","chargeOption":"SHA",
                 "orderingCountry":"HK","clientCountry":"HK","directorUboNationalities":[],"paymentType":"TRADE","clientSegment":"STANDARD",
                 "requestedValueDate":"2026-11-20","at":"2026-11-20T10:00:00+08:00"}
                """), RoutingService.Request.class);
    }

    private static <T> List<T> otherProviders(List<T> rows, Function<T, String> provider, String code) {
        return rows.stream().filter(r -> !code.equals(provider.apply(r))).toList();
    }

    private static Set<String> otherKeys(Map<String, ?> rows, String code) {
        return rows.keySet().stream().filter(k -> !code.equals(k)).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void overdueReviewIsFlagged() {
        var p = providers.findById("PROV-W").orElseThrow();
        p.appetiteReviewDate = LocalDate.now(ZoneId.of("Asia/Hong_Kong")).minusDays(1);
        providers.saveAndFlush(p);
        assertThat(service.get("PROV-W").reviewStatus()).isEqualTo("OVERDUE");
    }
}
