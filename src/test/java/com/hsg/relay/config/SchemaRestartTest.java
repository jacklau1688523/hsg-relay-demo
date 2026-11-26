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

    @Test
    void existingRuleFieldTableGainsFunctionBindingColumnsWithoutLosingData() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("rule_field_upgrade")
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        // 本次升级前的 relay_rule_field：没有取值来源、函数编码、参数绑定三列，带一个已在 C1 条件里使用的字段
        try (var c = DriverManager.getConnection(url, "sa", ""); var stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE relay_rule_field (field_code VARCHAR(64) PRIMARY KEY,field_name VARCHAR(80) NOT NULL,"
                    + "data_type VARCHAR(20) NOT NULL,allowed_operators VARCHAR(200) NOT NULL,allowed_rules VARCHAR(100) NOT NULL,"
                    + "value_options VARCHAR(1000),enabled BOOLEAN NOT NULL)");
            stmt.execute("INSERT INTO relay_rule_field (field_code,field_name,data_type,allowed_operators,allowed_rules,enabled) "
                    + "VALUES ('clientProviderExcluded','合作方排除客户','BOOLEAN','EQ,NE','C1',TRUE)");
        }
        updateSchema(url, com.hsg.relay.domain.FieldDefinition.class);
        // 再次启动：列已存在，不重复处理也不报错
        updateSchema(url, com.hsg.relay.domain.FieldDefinition.class);
        try (var c = DriverManager.getConnection(url, "sa", ""); var stmt = c.createStatement();
             var rs = stmt.executeQuery("SELECT field_name,enabled,value_source,function_code,argument_bindings FROM relay_rule_field")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("field_name")).isEqualTo("合作方排除客户");
            assertThat(rs.getBoolean("enabled")).isTrue();
            assertThat(rs.getString("value_source")).as("升级前没有取值来源，保持为空").isNull();
            assertThat(rs.getString("function_code")).isNull();
            assertThat(rs.getString("argument_bindings")).isNull();
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void existingRuleFieldTableGainsCollectionResolverColumnsWithoutLosingData() throws Exception {
        String url = "jdbc:h2:file:" + dir.resolve("rule_field_collection_upgrade")
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        // 上一版升级后的 relay_rule_field：有取值来源、函数编码、参数绑定三列，没有解析器、元素类型、执行作用域、缓存策略四列
        try (var c = DriverManager.getConnection(url, "sa", ""); var stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE relay_rule_field (field_code VARCHAR(64) PRIMARY KEY,field_name VARCHAR(80) NOT NULL,"
                    + "data_type VARCHAR(20) NOT NULL,allowed_operators VARCHAR(200) NOT NULL,allowed_rules VARCHAR(100) NOT NULL,"
                    + "value_options VARCHAR(1000),enabled BOOLEAN NOT NULL,value_source VARCHAR(20),function_code VARCHAR(64),"
                    + "argument_bindings VARCHAR(4000))");
            stmt.execute("INSERT INTO relay_rule_field (field_code,field_name,data_type,allowed_operators,allowed_rules,enabled,value_source,function_code) "
                    + "VALUES ('riskLevel','风险等级','STRING','EQ,NE','C1',TRUE,'FUNCTION','riskLevelOf')");
        }
        updateSchema(url, com.hsg.relay.domain.FieldDefinition.class);
        // 再次启动：列已存在，不重复处理也不报错
        updateSchema(url, com.hsg.relay.domain.FieldDefinition.class);
        try (var c = DriverManager.getConnection(url, "sa", ""); var stmt = c.createStatement();
             var rs = stmt.executeQuery("SELECT field_name,data_type,value_source,function_code,resolver_code,element_type,execution_scope,cache_policy "
                     + "FROM relay_rule_field")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("field_name")).isEqualTo("风险等级");
            assertThat(rs.getString("data_type")).isEqualTo("STRING");
            assertThat(rs.getString("value_source")).isEqualTo("FUNCTION");
            assertThat(rs.getString("function_code")).isEqualTo("riskLevelOf");
            assertThat(rs.getString("resolver_code")).as("升级前的字段没有解析器").isNull();
            assertThat(rs.getString("element_type")).isNull();
            assertThat(rs.getString("execution_scope")).isNull();
            assertThat(rs.getString("cache_policy")).isNull();
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
