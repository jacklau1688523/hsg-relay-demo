package com.hsg.relay.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hsg.relay.domain.C1PathConfig;
import com.hsg.relay.domain.C1PathVersion;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C1 路径配置两张表（relay_c1_path_config / relay_c1_path_version）的建表映射。
 * <p>ddl-auto=update 下，Hibernate 只在实体映射与库里的列、索引不一致时才改表，所以这里锁定三件事：
 * ① MariaDB / MySQL 上三个 JSON 列都是 longtext（NOT NULL 的 LOB 只写 nullable = false 会变成只能存 255 字节的 tinytext）；
 * ② 唯一约束有名字，且与 sql/schema-mysql.sql 里的索引名相同（没名字的约束每次启动都会以自动生成的名字删掉重建，
 *    在手工建表的 MariaDB 上还会多出一个重复的唯一索引）；
 * ③ H2 上重复启动不再改这两张表的列，已发布版本和草稿原样保留。
 */
class C1PathSchemaTest {
    @TempDir
    Path dir;

    @Test
    void mysqlAndMariaDbUseLongtextForEveryJsonColumn() {
        for (String dialectClass : List.of("org.hibernate.dialect.MariaDBDialect", "org.hibernate.dialect.MySQLDialect")) {
            StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
                    .applySetting(AvailableSettings.DIALECT, dialectClass)
                    // 方言已显式指定，不需要（这里也没有）数据库连接去取 JDBC 元数据
                    .applySetting(AvailableSettings.ALLOW_METADATA_ON_BOOT, "false")
                    .build();
            try {
                Metadata metadata = new MetadataSources(registry)
                        .addAnnotatedClass(C1PathConfig.class)
                        .addAnnotatedClass(C1PathVersion.class)
                        .getMetadataBuilder()
                        .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                        .build();
                Table config = table(metadata, "relay_c1_path_config");
                Table version = table(metadata, "relay_c1_path_version");
                assertThat(sqlType(config, "draft_json", metadata)).as(dialectClass + " draft_json").isEqualTo("longtext");
                assertThat(sqlType(config, "migration_hints_json", metadata)).as(dialectClass + " migration_hints_json").isEqualTo("longtext");
                assertThat(sqlType(version, "payload_json", metadata)).as(dialectClass + " payload_json").isEqualTo("longtext");
                assertThat(column(version, "payload_json").isNullable()).as(dialectClass + " payload_json nullable").isFalse();
                assertThat(version.getUniqueKeys().keySet()).as(dialectClass + " relay_c1_path_version unique keys")
                        .containsExactly("uk_c1_path_version");
                List<String> uniqueColumns = new ArrayList<>();
                version.getUniqueKeys().get("uk_c1_path_version").getColumns().forEach(c -> uniqueColumns.add(c.getName()));
                assertThat(uniqueColumns).as(dialectClass + " uk_c1_path_version columns").containsExactly("version");
            } finally {
                StandardServiceRegistryBuilder.destroy(registry);
            }
        }
    }

    @Test
    void c1PathTablesSurviveRepeatedSchemaUpdateWithoutFurtherDdl() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("c1_path_restart")
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        // 第一次启动：建表
        updateSchema(url);
        // 一个已发布版本 + 一份草稿
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO relay_c1_path_version (version,branch_mode,payload_json,published_at,published_by) "
                    + "VALUES (1,'FIRST_MATCH','{\"ruleCode\":\"C1\",\"paths\":[]}',CURRENT_TIMESTAMP,'tester')");
            s.executeUpdate("INSERT INTO relay_c1_path_config (id,draft_version,draft_json,migration_hints_json) "
                    + "VALUES (1,2,'{\"ruleCode\":\"C1\",\"paths\":[]}','[{\"code\":\"V1_SNAPSHOT\"}]')");
        }
        // 第二次、第三次启动：列类型不能再变，约束名必须是 uk_c1_path_version（不再是 Hibernate 自动生成的 UK…）。
        // H2 把唯一约束的索引存成 <约束名>_INDEX_<n>，Hibernate 按约束名找不到，于是每次启动都删掉重建——项目里其它表的
        // @UniqueConstraint 也是如此，无害，所以只允许这两条。
        for (int restart = 2; restart <= 3; restart++) {
            List<String> ddl = new ArrayList<>();
            runWhileLoggingSql(() -> updateSchema(url), ddl);
            List<String> onC1PathTables = ddl.stream().filter(sql -> sql.contains("relay_c1_path")).toList();
            assertThat(onC1PathTables).as("第 " + restart + " 次启动不能再改这两张表的列").noneMatch(sql -> sql.contains("alter column"));
            assertThat(onC1PathTables).as("第 " + restart + " 次启动的约束 DDL")
                    .allMatch(sql -> sql.contains("uk_c1_path_version"));
        }
        try (var c = DriverManager.getConnection(url, "sa", ""); var s = c.createStatement()) {
            List<String> indexes = new ArrayList<>();
            try (var rs = s.executeQuery("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                    + "WHERE TABLE_NAME='relay_c1_path_version' AND INDEX_TYPE_NAME='UNIQUE INDEX'")) {
                while (rs.next()) {
                    indexes.add(rs.getString(1));
                }
            }
            // H2 给唯一约束的索引起名 <约束名>_INDEX_<n>
            assertThat(indexes).as("version 列上只有一个唯一索引").hasSize(1);
            assertThat(indexes.get(0)).startsWith("uk_c1_path_version");
            try (var rs = s.executeQuery("SELECT payload_json FROM relay_c1_path_version WHERE version=1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("{\"ruleCode\":\"C1\",\"paths\":[]}");
                assertThat(rs.next()).isFalse();
            }
            try (var rs = s.executeQuery("SELECT draft_version, draft_json, migration_hints_json FROM relay_c1_path_config WHERE id=1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
                assertThat(rs.getString(2)).isEqualTo("{\"ruleCode\":\"C1\",\"paths\":[]}");
                assertThat(rs.getString(3)).isEqualTo("[{\"code\":\"V1_SNAPSHOT\"}]");
            }
        }
    }

    private static Table table(Metadata metadata, String name) {
        for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
            for (Table table : namespace.getTables()) {
                if (name.equals(table.getName())) {
                    return table;
                }
            }
        }
        throw new IllegalStateException("实体里没有表 " + name);
    }

    private static Column column(Table table, String name) {
        Column column = table.getColumn(Identifier.toIdentifier(name));
        assertThat(column).as(table.getName() + "." + name).isNotNull();
        return column;
    }

    private static String sqlType(Table table, String name, Metadata metadata) {
        return column(table, name).getSqlType(metadata);
    }

    private static void updateSchema(String url) {
        Configuration cfg = new Configuration()
                .addAnnotatedClass(C1PathConfig.class)
                .addAnnotatedClass(C1PathVersion.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        try (var sf = cfg.buildSessionFactory()) {
            assertThat(sf.isOpen()).isTrue();
        }
    }

    /** 执行 startup，并收集 Hibernate 在此期间发出的 SQL（DDL 走 org.hibernate.SQL，DEBUG 级别）。 */
    private static void runWhileLoggingSql(Runnable startup, List<String> sql) {
        Logger logger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            startup.run();
            appender.list.forEach(event -> sql.add(event.getFormattedMessage()));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
            appender.stop();
        }
    }
}
