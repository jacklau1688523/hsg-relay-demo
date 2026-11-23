-- HSG RELAY C1 Demo schema, MySQL 8.0+
-- 与 Java Entity 对应；也可直接使用 spring.jpa.hibernate.ddl-auto=update 在开发环境自动建表。
CREATE DATABASE IF NOT EXISTS hsg_relay CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hsg_relay;
CREATE TABLE IF NOT EXISTS relay_provider (
 code VARCHAR(32) PRIMARY KEY, name VARCHAR(120) NOT NULL, provider_type VARCHAR(24) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'LIVE', channel VARCHAR(32), funding_check_enabled BOOLEAN NOT NULL,
 priority INT NOT NULL, bic VARCHAR(11), nostro_account VARCHAR(64), gl_code VARCHAR(64),
 relationship_owner VARCHAR(64), appetite_review_date DATE, version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_appetite (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, provider_code VARCHAR(32) NOT NULL, flow_category VARCHAR(32) NOT NULL,
 currency VARCHAR(8) NOT NULL DEFAULT 'ALL', bank_country VARCHAR(8) NOT NULL DEFAULT 'ALL',
 action VARCHAR(24) NOT NULL, monthly_cap_usd DECIMAL(22,2), evidence_doc_no VARCHAR(128) NOT NULL,
 next_review_date DATE NOT NULL, effective_from DATETIME(6) NOT NULL, effective_to DATETIME(6),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, version_no INT NOT NULL DEFAULT 1, remark VARCHAR(512),
 INDEX idx_appetite_lookup (provider_code,flow_category,currency,bank_country),
 CONSTRAINT fk_appetite_provider FOREIGN KEY(provider_code) REFERENCES relay_provider(code)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_client_profile (
 client_id VARCHAR(64) PRIMARY KEY, classification_status VARCHAR(24) NOT NULL, flow_categories VARCHAR(255)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_client_exclusion (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, client_id VARCHAR(64) NOT NULL, provider_code VARCHAR(32) NOT NULL,
 reason VARCHAR(512) NOT NULL, effective_from DATETIME(6) NOT NULL, effective_to DATETIME(6),
 INDEX idx_exclusion_lookup (client_id,provider_code)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_case_approval (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, client_id VARCHAR(64) NOT NULL, provider_code VARCHAR(32) NOT NULL,
 flow_category VARCHAR(32) NOT NULL, approval_reference VARCHAR(128) NOT NULL,
 effective_from DATETIME(6) NOT NULL, expires_at DATETIME(6) NOT NULL,
 INDEX idx_approval_lookup (client_id,provider_code,flow_category)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_provider_usage (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, provider_code VARCHAR(32) NOT NULL, flow_category VARCHAR(32) NOT NULL,
 month_key VARCHAR(7) NOT NULL, amount_usd DECIMAL(22,2) NOT NULL DEFAULT 0,
 UNIQUE KEY uk_usage_provider_flow_month (provider_code,flow_category,month_key)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_rule_field (
 field_code VARCHAR(64) PRIMARY KEY, field_name VARCHAR(80) NOT NULL, data_type VARCHAR(20) NOT NULL,
 allowed_operators VARCHAR(200) NOT NULL, allowed_rules VARCHAR(100) NOT NULL, value_options VARCHAR(1000), enabled BOOLEAN NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_rule_audit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, object_type VARCHAR(32) NOT NULL, object_id VARCHAR(64) NOT NULL,
 operation VARCHAR(16) NOT NULL, operator_name VARCHAR(64) NOT NULL, operated_at DATETIME(6) NOT NULL,
 before_json LONGTEXT, after_json LONGTEXT
) ENGINE=InnoDB;

-- C2: GLOBAL 监管路由规则，JSON AST + 要求的能力标签 JSON
CREATE TABLE IF NOT EXISTS relay_regulatory_rule (
 rule_id VARCHAR(48) PRIMARY KEY,
 rule_name VARCHAR(140) NOT NULL,
 rule_code VARCHAR(10) NOT NULL DEFAULT 'C2',
 scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
 condition_json LONGTEXT NOT NULL,
 required_tags_json LONGTEXT NOT NULL,
 reason_code VARCHAR(32) NOT NULL DEFAULT 'REG_RULE_NOT_MET',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version_no INT NOT NULL DEFAULT 1,
 effective_from DATETIME(6) NOT NULL,
 effective_to DATETIME(6) NULL,
 remark VARCHAR(400),
 CHECK (JSON_VALID(condition_json)),
 CHECK (JSON_VALID(required_tags_json))
) ENGINE=InnoDB;

-- C2: 合作方屏蔽国家、国籍、地区、币种和用途等
CREATE TABLE IF NOT EXISTS relay_provider_restriction (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 provider_code VARCHAR(32) NOT NULL,
 restriction_type VARCHAR(48) NOT NULL,
 blocked_values VARCHAR(2000) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version_no INT NOT NULL DEFAULT 1,
 effective_from DATETIME(6) NOT NULL,
 effective_to DATETIME(6) NULL,
 remark VARCHAR(400),
 INDEX idx_c2_restrict_provider_type (provider_code, restriction_type),
 CONSTRAINT fk_c2_restriction_provider FOREIGN KEY (provider_code) REFERENCES relay_provider(code)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS relay_capability_tag (
 tag_code VARCHAR(64) PRIMARY KEY,
 tag_name VARCHAR(120) NOT NULL
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS relay_provider_capability (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 provider_code VARCHAR(32) NOT NULL,
 tag_code VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_provider_capability(provider_code, tag_code),
 CONSTRAINT fk_c2_cap_provider FOREIGN KEY (provider_code) REFERENCES relay_provider(code),
 CONSTRAINT fk_c2_cap_tag FOREIGN KEY (tag_code) REFERENCES relay_capability_tag(tag_code)
) ENGINE=InnoDB;

-- C1–C10 routing configuration and simulated decisions (Hibernate also creates this table).
CREATE TABLE IF NOT EXISTS relay_routing_document (
 id VARCHAR(255) PRIMARY KEY,
 revision BIGINT NOT NULL,
 payload LONGTEXT NOT NULL
);
