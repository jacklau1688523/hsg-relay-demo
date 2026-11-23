package com.hsg.relay.domain;

import jakarta.persistence.*;

import java.time.*;

@Entity
@Table(name = "relay_case_approval")
public class CaseApproval {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 64)
    public String clientId;
    @Column(nullable = false, length = 32)
    public String providerCode;
    @Column(nullable = false, length = 32)
    public String flowCategory;
    @Column(nullable = false, length = 128)
    public String approvalReference;
    @Column(nullable = false)
    public LocalDateTime effectiveFrom;
    @Column(nullable = false)
    public LocalDateTime expiresAt;

    protected CaseApproval() {
    }

    public CaseApproval(String c, String p, String f, String ref, LocalDateTime from, LocalDateTime end) {
        clientId = c;
        providerCode = p;
        flowCategory = f;
        approvalReference = ref;
        effectiveFrom = from;
        expiresAt = end;
    }
}
