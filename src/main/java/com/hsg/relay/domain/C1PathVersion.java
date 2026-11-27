package com.hsg.relay.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * C1 合作方动态规则路径配置的一个已发布版本：只插入、不更新、不删除（执行、回放和审计都读这里）。
 * C1 没有生效时间：某一时刻生效的是最新已发布版本；回放历史决策时按决策记录里的 configVersion 取版本，结果可重现。
 * <p>唯一约束必须命名（uk_c1_path_version，与 sql/schema-mysql.sql 相同）：不命名时 Hibernate 用自动生成的名字，与库里的索引对不上，
 * 每次启动都会删掉重建，在手工建表的库上还会多出一个重复的唯一索引。
 */
@Entity
@Table(name = "relay_c1_path_version", uniqueConstraints = @UniqueConstraint(name = "uk_c1_path_version", columnNames = {"version"}))
public class C1PathVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public int version;
    @Column(nullable = false, length = 20)
    public String branchMode;
    /**
     * 完整的路径规则 JSON。length = Integer.MAX_VALUE 就是 MySQLDialect 的默认 LOB 长度：MariaDB / MySQL 生成 longtext not null，
     * H2 生成 clob not null，重复启动不会再 ALTER。注意只写 nullable = false 时 length 取 JPA 默认值 255，MariaDB / MySQL 会生成
     * 只能存 255 字节的 tinytext（实体 {@link C1PathConfig} 的 draftJson 不写 @Column，所以同样是 longtext）。
     */
    @Lob
    @Column(nullable = false, length = Integer.MAX_VALUE)
    public String payloadJson;
    @Column(nullable = false)
    public LocalDateTime publishedAt;
    @Column(nullable = false, length = 64)
    public String publishedBy;
    @Column(length = 500)
    public String note;

    protected C1PathVersion() {
    }

    public C1PathVersion(int version, String branchMode, String payloadJson, LocalDateTime publishedAt, String publishedBy, String note) {
        this.version = version;
        this.branchMode = branchMode;
        this.payloadJson = payloadJson;
        this.publishedAt = publishedAt;
        this.publishedBy = publishedBy;
        this.note = note;
    }
}
