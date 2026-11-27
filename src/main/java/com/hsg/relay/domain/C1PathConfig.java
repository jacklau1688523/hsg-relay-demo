package com.hsg.relay.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * C1 合作方动态规则路径配置的当前状态：草稿与「最新已发布版本」的指针。全局一行（id = 1）。
 * <p>草稿可以反复保存，不参与执行；执行只用 {@link C1PathVersion} 里已发布的版本。发布版本只增不改。
 * migrationHintsJson 只在系统迁移时写入（旧版全局准入条件 → 路径配置的说明），首次发布后清空。
 */
@Entity
@Table(name = "relay_c1_path_config")
public class C1PathConfig {
    @Id
    public Long id = 1L;
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
    /**
     * 迁移提示（JSON 数组，见 C1PathConfigService.Hint）；由系统迁移写入，首次发布后清空。
     * 不写 columnDefinition = "TEXT"：那样 MariaDB / MySQL 上每次启动都会被 Hibernate 改一次列类型。@Lob 在 MariaDB / MySQL 上是
     * longtext，在 H2 上是 clob。
     */
    @Lob
    public String migrationHintsJson;

    protected C1PathConfig() {
    }

    public C1PathConfig(long id) {
        this.id = id;
    }
}
