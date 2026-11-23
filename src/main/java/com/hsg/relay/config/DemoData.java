package com.hsg.relay.config;

import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;

import java.math.BigDecimal;
import java.time.*;

@Configuration
public class DemoData {
    @Bean
    @Order(1)
    CommandLineRunner seed(ProviderRepository p, AppetiteRepository a, ClientProfileRepository c, ClientExclusionRepository e, CaseApprovalRepository ap, ProviderUsageRepository u, FieldDefinitionRepository f) {
        return args -> {
            if (p.count() == 0) {
                p.save(new Provider("PROV-T1", "T1 Bank", "TIER1_BANK", "SWIFT", true));
                p.save(new Provider("PROV-HO", "Bakai Head Office", "HEAD_OFFICE", "SWIFT", true));
                p.save(new Provider("PROV-W", "Wise", "FINTECH", "API", false));
                p.save(new Provider("PROV-GD", "GD Pay", "NETWORK", "API", false));
            }
            if (c.count() == 0) {
                c.save(new ClientProfile("C10001", "COMPLETE", "PSP"));
                c.save(new ClientProfile("C10002", "COMPLETE", "STANDARD"));
                c.save(new ClientProfile("C10003", "PENDING", "WEB3"));
                c.save(new ClientProfile("C10004", "COMPLETE", "STANDARD,PSP"));
            }
            if (a.count() == 0) {
                LocalDateTime from = LocalDateTime.of(2025, 10, 1, 0, 0);
                LocalDate review = LocalDate.of(2027, 10, 1);
                rule(a, "PROV-T1", "STANDARD", "ALL", "ALL", "ACCEPT", new BigDecimal("10000000"), "APP-T1-001", from, review);
                rule(a, "PROV-T1", "PSP", "ALL", "ALL", "EXCLUDE", null, "APP-T1-002", from, review);
                rule(a, "PROV-T1", "WEB3", "ALL", "ALL", "EXCLUDE", null, "APP-T1-003", from, review);
                rule(a, "PROV-HO", "PSP", "USD", "KG", "CASE_BY_CASE", null, "APP-HO-004", from, review);
                rule(a, "PROV-W", "STANDARD", "USD", "ALL", "ACCEPT", new BigDecimal("5000000"), "APP-W-005", from, review);
            }
            if (u.count() == 0) {
                String month = YearMonth.now(ZoneId.of("Asia/Hong_Kong")).toString();
                u.save(new ProviderUsage("PROV-T1", "STANDARD", month, new BigDecimal("1800000")));
                u.save(new ProviderUsage("PROV-W", "STANDARD", month, new BigDecimal("800000")));
            }
            if (ap.count() == 0) {
                LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
                ap.save(new CaseApproval("C10001", "PROV-HO", "PSP", "APR-HO-PSP-001", now.minusDays(7), now.plusMonths(2)));
            }
            if (f.count() == 0) {
                f.save(new FieldDefinition("flowCategory", "客户流转类别", "STRING", "EQ,NE", "C1"));
                f.save(new FieldDefinition("paymentCurrency", "交易币种", "ENUM", "EQ,NE,IN", "C1,C2,C8", "CNY,USD,HKD,KGS,CNH,EUR,AUD"));
                f.save(new FieldDefinition("beneficiaryBankCountry", "受益行国家", "ENUM", "EQ,NE,IN", "C1,C2,C3,C8", "CN,KG,RU,HK,US,SG,AE,TR,KZ,AM"));
                f.save(new FieldDefinition("amountUsd", "美元等价金额", "DECIMAL", "EQ,NE,GT,GTE,LT,LTE", "C8"));
                f.save(new FieldDefinition("clientSegment", "客户分层", "STRING", "EQ,NE", "C8"));
            }
        };
    }

    private void rule(AppetiteRepository repo, String provider, String flow, String c, String country, String action, BigDecimal cap, String doc, LocalDateTime from, LocalDate review) {
        Appetite x = new Appetite();
        x.providerCode = provider;
        x.flowCategory = flow;
        x.currency = c;
        x.bankCountry = country;
        x.action = action;
        x.monthlyCapUsd = cap;
        x.evidenceDocNo = doc;
        x.effectiveFrom = from;
        x.nextReviewDate = review;
        repo.save(x);
    }
}
