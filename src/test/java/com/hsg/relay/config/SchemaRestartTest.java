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

    private void updateSchema(String url) {
        var cfg = new Configuration().addAnnotatedClass(ProviderRestriction.class)
                .setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", url).setProperty("hibernate.connection.username", "sa")
                .setProperty("hibernate.hbm2ddl.auto", "update")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true");
        try (var sf = cfg.buildSessionFactory()) {
            assertThat(sf.isOpen()).isTrue();
        }
    }
}
