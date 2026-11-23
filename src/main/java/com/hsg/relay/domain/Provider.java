package com.hsg.relay.domain;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(name = "relay_provider")
public class Provider {
    @Id
    @Column(length = 32)
    public String code;
    @Column(nullable = false, length = 120)
    public String name;
    @Column(nullable = false, length = 24)
    public String providerType;
    @Column(nullable = false, length = 16)
    public String status = "LIVE";
    @Column(length = 32)
    public String channel = "SWIFT";
    public boolean fundingCheckEnabled = true;
    public int priority = 99;
    @Column(length = 11)
    public String bic;
    @Column(length = 64)
    public String nostroAccount;
    @Column(length = 64)
    public String glCode;
    @Column(length = 64)
    public String relationshipOwner;
    public LocalDate appetiteReviewDate;
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    public long version;

    protected Provider() {
    }

    public Provider(String code, String name, String type, String channel, boolean funding) {
        this.code = code;
        this.name = name;
        this.providerType = type;
        this.channel = channel;
        this.fundingCheckEnabled = funding;
    }
}
