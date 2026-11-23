package com.hsg.relay.c2;

import jakarta.persistence.*;

@Entity
@Table(name = "relay_provider_capability", uniqueConstraints = @UniqueConstraint(name = "uk_provider_capability", columnNames = {"providerCode", "tagCode"}))
public class ProviderCapability {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 32)
    public String providerCode;
    @Column(nullable = false, length = 64)
    public String tagCode;

    protected ProviderCapability() {
    }

    public ProviderCapability(String providerCode, String tagCode) {
        this.providerCode = providerCode;
        this.tagCode = tagCode;
    }
}
