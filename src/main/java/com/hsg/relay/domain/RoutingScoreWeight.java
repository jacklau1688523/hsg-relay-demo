package com.hsg.relay.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.LocalDateTime;

/**
 * 综合评分权重配置（全局，不属于单个合作方）：交易类型 × 客户层级 → W1 成本 / W2 速度 / W3 偏好 / W4 履约。
 * <p>
 * 版本模型：同一「交易类型 + 客户层级」为一组，组内每个版本一行（{@code versionNo} 从 1 递增）。
 * <ul>
 *   <li>草稿（DRAFT）：每组最多一行，可反复保存、可删除，不参与路由评分。</li>
 *   <li>已发布（PUBLISHED）：发布后内容不再修改；{@code effectiveFrom}/{@code effectiveTo} 是该版本的有效时间段，
 *       某一决策时间生效的是「有效时间段覆盖它」的那个版本。发布新版本时，当前未结束的版本在新的生效时间结束。</li>
 *   <li>已停用（DISABLED）：被运营停用的版本，{@code effectiveTo} 即停用时间（尚未到生效时间的待生效版本停用后从未生效）。历史版本不删除。</li>
 * </ul>
 * 权重合法性由数据库 CHECK 约束（每项 0–100、合计 100）和 {@code RoutingScoreWeightService} 的业务校验共同保证。
 * 时间均为香港业务时间（{@code Asia/Hong_Kong}）。
 */
@Entity
@Table(name = "relay_routing_score_weight",
        uniqueConstraints = @UniqueConstraint(name = "uk_score_weight_key_version",
                columnNames = {"transactionType", "clientTier", "versionNo"}))
@Check(name = "ck_score_weight_range",
        constraints = "w1_cost BETWEEN 0 AND 100 AND w2_speed BETWEEN 0 AND 100 AND w3_preference BETWEEN 0 AND 100 AND w4_commercial BETWEEN 0 AND 100")
@Check(name = "ck_score_weight_sum", constraints = "w1_cost + w2_speed + w3_preference + w4_commercial = 100")
@Check(name = "ck_score_weight_status", constraints = "status IN ('DRAFT', 'PUBLISHED', 'DISABLED')")
public class RoutingScoreWeight {
    public static final String DRAFT = "DRAFT", PUBLISHED = "PUBLISHED", DISABLED = "DISABLED";
    /** 客户层级的通配取值：该交易类型下任何层级都适用（P2）。 */
    public static final String ALL_TIERS = "ALL";
    /** 兜底交易类型：P1/P2 都没有时使用 OTHER + ALL（P3）。 */
    public static final String FALLBACK_TYPE = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 32)
    public String transactionType;
    @Column(nullable = false, length = 64)
    public String clientTier;
    // 四个权重列显式指定列名：默认命名策略会把 w1Cost 映射成 w1cost，与下面 CHECK 约束引用的 w1_cost 不一致
    @Column(name = "w1_cost", nullable = false)
    public int w1Cost;
    @Column(name = "w2_speed", nullable = false)
    public int w2Speed;
    @Column(name = "w3_preference", nullable = false)
    public int w3Preference;
    @Column(name = "w4_commercial", nullable = false)
    public int w4Commercial;
    @Column(nullable = false)
    public int versionNo;
    @Column(nullable = false, length = 16)
    public String status = DRAFT;
    /** 生效时间；草稿在发布时确定，所以为空。 */
    public LocalDateTime effectiveFrom;
    /** 结束时间（不含）；为空表示一直生效到有新版本接替或被停用。 */
    public LocalDateTime effectiveTo;
    @Column(nullable = false)
    public LocalDateTime createdAt;
    @Column(nullable = false, length = 64)
    public String createdBy;
    @Column(nullable = false)
    public LocalDateTime updatedAt;
    @Column(nullable = false, length = 64)
    public String updatedBy;
    public LocalDateTime publishedAt;
    @Column(length = 64)
    public String publishedBy;
    @Column(length = 500)
    public String note;
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    public long version;

    protected RoutingScoreWeight() {
    }

    public RoutingScoreWeight(String transactionType, String clientTier, int versionNo, int w1Cost, int w2Speed,
                               int w3Preference, int w4Commercial) {
        this.transactionType = transactionType;
        this.clientTier = clientTier;
        this.versionNo = versionNo;
        this.w1Cost = w1Cost;
        this.w2Speed = w2Speed;
        this.w3Preference = w3Preference;
        this.w4Commercial = w4Commercial;
    }

    public int weightSum() {
        return w1Cost + w2Speed + w3Preference + w4Commercial;
    }
}
