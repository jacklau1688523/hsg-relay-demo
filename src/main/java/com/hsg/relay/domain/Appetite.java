package com.hsg.relay.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name = "relay_appetite")
public class Appetite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 32)
    public String providerCode;
    @Column(nullable = false, length = 32)
    public String flowCategory;
    @Column(nullable = false, length = 8)
    public String currency = "ALL";
    @Column(nullable = false, length = 8)
    public String bankCountry = "ALL";
    @Column(nullable = false, length = 24)
    public String action;
    @Column(precision = 22, scale = 2)
    public BigDecimal monthlyCapUsd;
    @Column(nullable = false, length = 128)
    public String evidenceDocNo;
    @Column(nullable = false)
    public LocalDate nextReviewDate;
    @Column(nullable = false)
    public LocalDateTime effectiveFrom;
    public LocalDateTime effectiveTo;
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(nullable = false)
    public int versionNo = 1;
    @Column(length = 512)
    public String remark;

    public Appetite() {
    }
}
