package com.hsg.relay.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 评分规则的一个已发布版本：只插入、不更新、不删除（回放和审计都读这里）。
 * effectiveFrom 是该版本的生效时间；某一决策时间生效的版本是「生效时间不晚于它」的最高版本
 * （系统默认版本的生效时间是 {@link com.hsg.relay.service.ScoreRuleService#DEFAULT_EFFECTIVE_FROM}，即自始生效）。
 */
@Entity
@Table(name = "relay_score_rule_version", uniqueConstraints = @UniqueConstraint(columnNames = {"ruleCode", "version"}))
public class ScoreRuleVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 16)
    public String ruleCode;
    @Column(nullable = false)
    public int version;
    @Column(nullable = false, length = 20)
    public String ruleType;
    @Column(nullable = false, length = 20)
    public String matchMode;
    @Lob
    @Column(nullable = false)
    public String payloadJson;
    @Column(nullable = false)
    public LocalDateTime effectiveFrom;
    @Column(nullable = false)
    public LocalDateTime publishedAt;
    @Column(nullable = false, length = 64)
    public String publishedBy;
    @Column(length = 500)
    public String note;

    protected ScoreRuleVersion() {
    }

    public ScoreRuleVersion(String ruleCode, int version, String ruleType, String matchMode, String payloadJson,
                            LocalDateTime effectiveFrom, LocalDateTime publishedAt, String publishedBy, String note) {
        this.ruleCode = ruleCode;
        this.version = version;
        this.ruleType = ruleType;
        this.matchMode = matchMode;
        this.payloadJson = payloadJson;
        this.effectiveFrom = effectiveFrom;
        this.publishedAt = publishedAt;
        this.publishedBy = publishedBy;
        this.note = note;
    }
}
