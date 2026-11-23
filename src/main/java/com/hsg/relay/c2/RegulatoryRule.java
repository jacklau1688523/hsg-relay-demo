package com.hsg.relay.c2;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "relay_regulatory_rule")
public class RegulatoryRule {
    @Id @Column(length = 48) public String ruleId;
    @Column(nullable = false, length = 140) public String ruleName;
    @Column(nullable = false, length = 10) public String ruleCode = "C2";
    @Column(nullable = false, length = 16) public String scope = "GLOBAL";
    @Lob @Column(nullable = false, length=10000000) public String conditionJson;
    @Lob @Column(nullable = false, length=10000000) public String requiredTagsJson;
    @Column(nullable = false, length = 32) public String reasonCode = "REG_RULE_NOT_MET";
    @Column(nullable = false) public boolean enabled = true;
    @Column(nullable = false) public int versionNo = 1;
    @Column(nullable = false) public LocalDateTime effectiveFrom;
    public LocalDateTime effectiveTo;
    @Column(length = 400) public String remark;
    protected RegulatoryRule() {}
}
