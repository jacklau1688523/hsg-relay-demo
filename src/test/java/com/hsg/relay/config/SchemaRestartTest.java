package com.hsg.relay.config;

import com.hsg.relay.c2.ProviderRestriction;
import org.hibernate.cfg.Configuration;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaRestartTest {
    @TempDir
    Path dir;

    @Test
    void existingDatabaseSurvivesRepeatedSchemaUpdate() throws Exception {
        String oldUrl = "jdbc:h2:file:" + dir.resolve("restart") + ";MODE=MySQL;DATABASE_TO_UPPER=false";
        // Simulate the existing database, including data and the named index.
        updateSchema(oldUrl);
        try (var c = DriverManager.getConnection(oldUrl, "sa", ""); var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO relay_provider_restriction (provider_code,restriction_type,blocked_values,enabled,version_no,effective_from) VALUES ('PROV-W','CURRENCY','KGS',true,1,CURRENT_TIMESTAMP)");
        }
        assertThatThrownBy(() -> updateSchema(oldUrl)).hasStackTraceContaining("already exists");
        String fixedUrl = oldUrl + ";CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        updateSchema(fixedUrl);
        updateSchema(fixedUrl);
        try (var c = DriverManager.getConnection(fixedUrl, "sa", ""); var s = c.createStatement()) {
            try (var rs = s.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE INDEX_NAME='idx_c2_restrict_provider_type'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            try (var rs = s.executeQuery("SELECT blocked_values FROM relay_provider_restriction")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("KGS");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    @Test
    void existingProviderSchemaGainsFieldsWithoutLosingData() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("provider_upgrade") + ";MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (var c = DriverManager.getConnection(url, "sa", ""); var stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE relay_provider (code VARCHAR(32) PRIMARY KEY,name VARCHAR(120) NOT NULL,provider_type VARCHAR(24) NOT NULL,status VARCHAR(16) NOT NULL,channel VARCHAR(32),funding_check_enabled BOOLEAN NOT NULL,priority INTEGER NOT NULL)");
            stmt.execute("INSERT INTO relay_provider VALUES ('PROV-OLD','Existing provider','NETWORK','LIVE','API',false,100)");
        }
        var cfg = new Configuration().addAnnotatedClass(com.hsg.relay.domain.Provider.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "update").setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        try (var sf = cfg.buildSessionFactory(); var session = sf.openSession()) {
            var p = session.get(com.hsg.relay.domain.Provider.class, "PROV-OLD");
            assertThat(p.name).isEqualTo("Existing provider");
            assertThat(p.priority).isEqualTo(100);
            assertThat(p.version).isZero();
            assertThat(p.bic).isNull();
            assertThat(p.status).isEqualTo("LIVE");
        }
    }

    @Test
    void existingRegulatoryRuleTableGainsTheTriggerColumnWithoutLosingData() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("regulatory_upgrade")
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        // 本次升级前的 relay_regulatory_rule：没有附加触发条件列，带一条已保存的规则
        try (var c = DriverManager.getConnection(url, "sa", ""); var stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE relay_regulatory_rule (rule_id VARCHAR(48) PRIMARY KEY,rule_name VARCHAR(140) NOT NULL,"
                    + "rule_code VARCHAR(10) NOT NULL DEFAULT 'C2',scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',"
                    + "condition_json CLOB NOT NULL,required_tags_json CLOB NOT NULL,"
                    + "reason_code VARCHAR(32) NOT NULL DEFAULT 'REG_RULE_NOT_MET',enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                    + "version_no INT NOT NULL DEFAULT 1,effective_from TIMESTAMP NOT NULL,effective_to TIMESTAMP NULL,remark VARCHAR(400))");
            stmt.execute("INSERT INTO relay_regulatory_rule (rule_id,rule_name,condition_json,required_tags_json,effective_from) "
                    + "VALUES ('REG-OLD','升级前保存的规则','{\"operator\":\"AND\",\"children\":[]}','[\"SWIFT\"]',TIMESTAMP '2025-10-01 00:00:00')");
        }
        updateSchema(url, com.hsg.relay.c2.RegulatoryRule.class);
        // 再次启动：列已存在，不重复处理也不报错
        updateSchema(url, com.hsg.relay.c2.RegulatoryRule.class);
        try (var c = DriverManager.getConnection(url, "sa", ""); var stmt = c.createStatement();
             var rs = stmt.executeQuery("SELECT rule_name,version_no,enabled,trigger_condition_json FROM relay_regulatory_rule")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("rule_name")).isEqualTo("升级前保存的规则");
            assertThat(rs.getInt("version_no")).isEqualTo(1);
            assertThat(rs.getBoolean("enabled")).isTrue();
            assertThat(rs.getString("trigger_condition_json")).as("旧规则没有附加触发条件").isNull();
            assertThat(rs.next()).isFalse();
        }
    }

    private void updateSchema(String url) {
        updateSchema(url, ProviderRestriction.class);
    }

    private void updateSchema(String url, Class<?>... entities) {
        Configuration cfg = new Configuration();
        for (Class<?> entity : entities) cfg.addAnnotatedClass(entity);
        cfg.setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        try (var sf = cfg.buildSessionFactory()) {
            assertThat(sf.isOpen()).isTrue();
        }
    }
}
