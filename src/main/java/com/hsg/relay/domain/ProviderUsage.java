package com.hsg.relay.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "relay_provider_usage", uniqueConstraints = @UniqueConstraint(columnNames = {"providerCode", "flowCategory", "monthKey"}))
public class ProviderUsage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 32)
    public String providerCode;
    @Column(nullable = false, length = 32)
    public String flowCategory;
    @Column(nullable = false, length = 7)
    public String monthKey;
    @Column(nullable = false, precision = 22, scale = 2)
    public BigDecimal amountUsd = BigDecimal.ZERO;

    protected ProviderUsage() {
    }

    public ProviderUsage(String p, String f, String m, BigDecimal a) {
        providerCode = p;
        flowCategory = f;
        monthKey = m;
        amountUsd = a;
    }
}
