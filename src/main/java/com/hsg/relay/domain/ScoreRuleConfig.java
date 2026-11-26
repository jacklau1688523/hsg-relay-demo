package com.hsg.relay.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 评分规则（ruleType = SCORE）当前配置：草稿与「最新已发布版本」的指针。每个规则编码一行（目前只有 C5）。
 * <p>草稿可以反复保存，不参与执行；执行只用 {@link ScoreRuleVersion} 里已发布的版本。发布版本只增不改。
 */
@Entity
@Table(name = "relay_score_rule")
public class ScoreRuleConfig {
    @Id
    @Column(length = 16)
    public String ruleCode;
    @Column(nullable = false, length = 20)
    public String ruleType = "SCORE";
    @Column(nullable = false, length = 20)
    public String matchMode = "FIRST_MATCH";
    /** 草稿版本号：每次保存草稿 +1；保存时带上它做并发校验。 */
    @Column(nullable = false)
    public int draftVersion = 1;
    @Lob
    public String draftJson;
    @Column(length = 64)
    public String draftUpdatedBy;
    public LocalDateTime draftUpdatedAt;
    /** 最新已发布版本号；从未发布时为空。 */
    public Integer publishedVersion;
    public LocalDateTime publishedAt;
    @Column(length = 64)
    public String publishedBy;

    protected ScoreRuleConfig() {
    }

    public ScoreRuleConfig(String ruleCode) {
        this.ruleCode = ruleCode;
    }
}
