package com.hsg.relay.c2;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name="relay_provider_restriction", indexes=@Index(name="idx_c2_restrict_provider_type",columnList="providerCode,restrictionType"))
public class ProviderRestriction {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(nullable=false,length=32) public String providerCode;
    @Column(nullable=false,length=48) public String restrictionType;
    @Column(nullable=false,length=2000) public String blockedValues;
    @Column(nullable=false) public boolean enabled=true;
    @Column(nullable=false) public int versionNo=1;
    @Column(nullable=false) public LocalDateTime effectiveFrom;
    public LocalDateTime effectiveTo;
    @Column(length=400) public String remark;
    protected ProviderRestriction() {}
}
