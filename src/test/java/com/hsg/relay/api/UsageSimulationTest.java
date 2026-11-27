package com.hsg.relay.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.ProviderUsage;
import com.hsg.relay.repo.ProviderUsageRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.service.C1PathConfigService;
import com.hsg.relay.service.C1PathFixtures;
import com.hsg.relay.service.C1Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class UsageSimulationTest {
    private static final String MONTH = YearMonth.now(ZoneId.of("Asia/Hong_Kong")).toString();

    @Autowired
    RelayController api;
    @Autowired
    C1Service c1;
    @Autowired
    ProviderUsageRepository usages;
    @Autowired
    RuleAuditRepository audit;
    @Autowired
    ObjectMapper json;
    @Autowired
    C1PathConfigService pathConfig;

    @BeforeEach
    void publishBaselinePaths() {
        // C1 只执行已发布的路径配置：先发布基线（只有 DEFAULT 路径，条件恒成立）
        C1PathFixtures.publishDefaultOnly(pathConfig, json);
    }

    private ProviderUsage put(String provider, String flow, String month, String amount) {
        return api.saveUsage(new RelayController.UsageInput(provider, flow, month, new BigDecimal(amount)));
    }

    // C10002 为 COMPLETE / STANDARD；PROV-T1 STANDARD 规则为 ACCEPT，月上限 10,000,000
    private C1Service.Result standardPaymentToTierOne(String amount) {
        return c1.evaluate(new C1Service.Simulation("C10002", "PROV-T1", "USD", "KG", new BigDecimal(amount)));
    }

    @Test
    void mtdNearCapMakesC1ReturnCapReachedAndLowerMtdRestoresPass() {
        put("PROV-T1", "STANDARD", MONTH, "1800000");
        assertThat(standardPaymentToTierOne("150000").reasonCode()).isEqualTo("PASS");

        put("PROV-T1", "STANDARD", MONTH, "9900000");
        var result = standardPaymentToTierOne("150000");
        assertThat(result.passed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("APPETITE_CAP_REACHED");
        assertThat(result.matchedRuleAction()).isEqualTo("ACCEPT");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("失败原因");
            assertThat(step.detail()).isEqualTo("MTD 9900000.00 + 本笔 150000 > 月上限 10000000.00");
        });

        put("PROV-T1", "STANDARD", MONTH, "9850000"); // 恰好等于月上限：仍然通过
        var atCap = standardPaymentToTierOne("150000");
        assertThat(atCap.passed()).isTrue();
        assertThat(atCap.steps()).extracting(C1Service.Trace::detail).contains("MTD 9850000.00 + 150000 <= 10000000.00");
    }

    @Test
    void onlyCurrentMonthMtdIsRead() {
        put("PROV-T1", "STANDARD", MONTH, "1000000");
        put("PROV-T1", "STANDARD", "2000-01", "99999999");
        assertThat(standardPaymentToTierOne("150000").reasonCode()).isEqualTo("PASS");
    }

    @Test
    void putUpsertsByProviderFlowAndMonthAndRoundsToCents() {
        long before = usages.count();
        var first = put("PROV-W", "STANDARD", MONTH, "1234.567");
        var second = put("PROV-W", "STANDARD", MONTH, "4321");
        assertThat(second.id).isEqualTo(first.id);
        assertThat(second.amountUsd).isEqualByComparingTo("4321");
        assertThat(second.amountUsd.scale()).isEqualTo(2);
        assertThat(usages.findAll().stream().filter(u -> u.providerCode.equals("PROV-W") && u.flowCategory.equals("STANDARD") && u.monthKey.equals(MONTH))).hasSize(1);

        var created = put("PROV-HO", "PSP", "2027-01", "500");
        assertThat(created.id).isNotNull();
        assertThat(created.providerCode).isEqualTo("PROV-HO");
        assertThat(usages.count()).isEqualTo(before + 1);
        assertThat(audit.findAll().stream().filter(a -> a.objectType.equals("USAGE")).map(a -> a.operation)).containsExactlyInAnyOrder("UPDATE", "UPDATE", "CREATE");
    }

    @Test
    void deleteRemovesSimulatedRow() {
        var row = put("PROV-GD", "PSP", "2027-02", "10");
        assertThat(api.deleteUsage(row.id)).containsEntry("deleted", true);
        assertThat(usages.findById(row.id)).isEmpty();
        assertThatThrownBy(() -> api.deleteUsage(row.id)).hasMessage("MTD 记录不存在");
        assertThat(audit.findAll().stream().filter(a -> a.objectType.equals("USAGE")).map(a -> a.operation)).containsExactly("CREATE", "DELETE");
    }

    @Test
    void invalidUsageInputIsRejectedAndLeavesMtdUntouched() {
        put("PROV-T1", "STANDARD", MONTH, "777");
        assertThatThrownBy(() -> put("NOPE", "STANDARD", MONTH, "1")).hasMessage("无效合作方");
        assertThatThrownBy(() -> put("PROV-T1", "OTHER", MONTH, "1")).hasMessage("无效流转类别");
        assertThatThrownBy(() -> put("PROV-T1", null, MONTH, "1")).hasMessage("无效流转类别");
        assertThatThrownBy(() -> put("PROV-T1", "STANDARD", "2026-13", "1")).hasMessage("月份格式应为 YYYY-MM");
        assertThatThrownBy(() -> put("PROV-T1", "STANDARD", "2026/11", "1")).hasMessage("月份格式应为 YYYY-MM");
        assertThatThrownBy(() -> put("PROV-T1", "STANDARD", MONTH, "-1")).hasMessage("月累计金额不可小于0");
        assertThat(api.usage().stream().filter(u -> u.providerCode.equals("PROV-T1") && u.flowCategory.equals("STANDARD") && u.monthKey.equals(MONTH)))
                .singleElement().satisfies(row -> assertThat(row.amountUsd).isEqualByComparingTo("777"));
    }

    @Test
    void missingAmountInRequestBodyIsRejectedInsteadOfResettingMtd() throws Exception {
        // 漏传 amountUsd（例如字段名拼错）：反序列化后金额为 null，必须拒绝，不能当成 0 覆盖原有 MTD
        String body = "{\"providerCode\":\"PROV-T1\",\"flowCategory\":\"STANDARD\",\"monthKey\":\"" + MONTH + "\",\"amount\":123}";
        RelayController.UsageInput mistyped = json.readValue(body, RelayController.UsageInput.class);
        assertThat(mistyped.amountUsd()).isNull();
        assertThatThrownBy(() -> api.saveUsage(mistyped)).hasMessage("月累计金额不可为空");
        assertThatThrownBy(() -> api.saveUsage(new RelayController.UsageInput("PROV-T1", "STANDARD", MONTH, null))).hasMessage("月累计金额不可为空");
        assertThatThrownBy(() -> api.saveUsage(new RelayController.UsageInput("PROV-T1", "STANDARD", null, BigDecimal.ONE))).hasMessage("月份格式应为 YYYY-MM");
        assertThat(currentTierOneMtd()).singleElement().satisfies(row -> assertThat(row.amountUsd).isEqualByComparingTo("1800000"));
        assertThat(audit.findAll()).noneMatch(a -> a.objectType.equals("USAGE"));
    }

    private java.util.stream.Stream<ProviderUsage> currentTierOneMtd() {
        return api.usage().stream().filter(u -> u.providerCode.equals("PROV-T1") && u.flowCategory.equals("STANDARD") && u.monthKey.equals(MONTH));
    }
}
