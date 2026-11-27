-- HSG RELAY C1 Demo schema, MySQL 8.0+
-- 与 Java Entity 对应；也可直接使用 spring.jpa.hibernate.ddl-auto=update 在开发环境自动建表。
-- 用 mysql / mariadb 命令行执行时整份脚本导入：mysql -u<用户> -p < sql/schema-mysql.sql（USE 必须与库名写在同一行，否则客户端报
-- 「USE must be followed by a database name」）。
CREATE
DATABASE IF NOT EXISTS hsg_relay CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE hsg_relay;
CREATE TABLE IF NOT EXISTS relay_provider
(
    code
    VARCHAR
(
    32
) PRIMARY KEY, name VARCHAR
(
    120
) NOT NULL, provider_type VARCHAR
(
    24
) NOT NULL,
    status VARCHAR
(
    16
) NOT NULL DEFAULT 'LIVE', channel VARCHAR
(
    32
), funding_check_enabled BOOLEAN NOT NULL,
    priority INT NOT NULL, bic VARCHAR
(
    11
), nostro_account VARCHAR
(
    64
), gl_code VARCHAR
(
    64
),
    relationship_owner VARCHAR
(
    64
), appetite_review_date DATE, version BIGINT NOT NULL DEFAULT 0
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_appetite
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    provider_code
    VARCHAR
(
    32
) NOT NULL, flow_category VARCHAR
(
    32
) NOT NULL,
    currency VARCHAR
(
    8
) NOT NULL DEFAULT 'ALL', bank_country VARCHAR
(
    8
) NOT NULL DEFAULT 'ALL',
    action VARCHAR
(
    24
) NOT NULL, monthly_cap_usd DECIMAL
(
    22,
    2
), evidence_doc_no VARCHAR
(
    128
) NOT NULL,
    next_review_date DATE NOT NULL, effective_from DATETIME
(
    6
) NOT NULL, effective_to DATETIME
(
    6
),
    enabled BOOLEAN NOT NULL DEFAULT TRUE, version_no INT NOT NULL DEFAULT 1, remark VARCHAR
(
    512
),
    INDEX idx_appetite_lookup
(
    provider_code,
    flow_category,
    currency,
    bank_country
),
    CONSTRAINT fk_appetite_provider FOREIGN KEY
(
    provider_code
) REFERENCES relay_provider
(
    code
)
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_client_profile
(
    client_id
    VARCHAR
(
    64
) PRIMARY KEY, classification_status VARCHAR
(
    24
) NOT NULL, flow_categories VARCHAR
(
    255
)
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_client_exclusion
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    client_id
    VARCHAR
(
    64
) NOT NULL, provider_code VARCHAR
(
    32
) NOT NULL,
    reason VARCHAR
(
    512
) NOT NULL, effective_from DATETIME
(
    6
) NOT NULL, effective_to DATETIME
(
    6
),
    INDEX idx_exclusion_lookup
(
    client_id,
    provider_code
)
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_case_approval
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    client_id
    VARCHAR
(
    64
) NOT NULL, provider_code VARCHAR
(
    32
) NOT NULL,
    flow_category VARCHAR
(
    32
) NOT NULL, approval_reference VARCHAR
(
    128
) NOT NULL,
    effective_from DATETIME
(
    6
) NOT NULL, expires_at DATETIME
(
    6
) NOT NULL,
    INDEX idx_approval_lookup
(
    client_id,
    provider_code,
    flow_category
)
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_provider_usage
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    provider_code
    VARCHAR
(
    32
) NOT NULL, flow_category VARCHAR
(
    32
) NOT NULL,
    month_key VARCHAR
(
    7
) NOT NULL, amount_usd DECIMAL
(
    22,
    2
) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_usage_provider_flow_month
(
    provider_code,
    flow_category,
    month_key
)
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_rule_field
(
    field_code
    VARCHAR
(
    64
) PRIMARY KEY, field_name VARCHAR
(
    80
) NOT NULL, data_type VARCHAR
(
    20
) NOT NULL,
    allowed_operators VARCHAR
(
    200
) NOT NULL, allowed_rules VARCHAR
(
    100
) NOT NULL, value_options VARCHAR
(
    1000
), enabled BOOLEAN NOT NULL, value_source VARCHAR
(
    20
) NULL, function_code VARCHAR
(
    64
) NULL, argument_bindings VARCHAR
(
    4000
) NULL
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_rule_audit
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    object_type
    VARCHAR
(
    32
) NOT NULL, object_id VARCHAR
(
    64
) NOT NULL,
    operation VARCHAR
(
    16
) NOT NULL, operator_name VARCHAR
(
    64
) NOT NULL, operated_at DATETIME
(
    6
) NOT NULL,
    before_json LONGTEXT, after_json LONGTEXT
    ) ENGINE=InnoDB;

-- C1: 合作方动态规则路径配置。按候选合作方（candidate.providerCode）选路径（P1 / P2 / … / DEFAULT），只执行所选路径自己的条件树，
-- 再执行对所有路径都生效的全局必检。两张表都只由 C1 路径配置页面（/api/c1/paths）读写。
-- relay_c1_path_config  全局一行（id = 1）：草稿 + 「最新已发布版本」指针。草稿不参与执行。
--   draft_json             C1PathRule JSON：{"ruleCode":"C1","branchMode":"FIRST_MATCH","paths":[{pathCode,pathName,providerCodes,priority,
--                          enabled,conditionAst,failureReasonCode,version}]}；conditionAst 是条件树（条件组 {type:"GROUP",logic,children}、
--                          字段条件 {type:"FIELD",field,operator,value}、函数条件 {type:"FUNCTION",functionCode,arguments,operator,value}）
--   migration_hints_json   系统迁移（旧版全局准入条件 → 路径配置）写入的提示 JSON 数组 [{code,message}]，首次发布后清空
-- 列类型必须与实体映射完全一致（开启 ddl-auto=update 时 Hibernate 只在不一致时 ALTER）：三个 JSON 列在实体上都是 @Lob，
--   MariaDB / MySQL = longtext（relay_c1_path_version.payloadJson 另加 length = Integer.MAX_VALUE + nullable = false），
--   H2 = clob；唯一约束名 uk_c1_path_version 与实体 @UniqueConstraint(name) 相同，不要改。
-- relay_c1_path_version  已发布版本：只插入，不更新、不删除；某一时刻生效的是最新版本，回放按 version 取。
CREATE TABLE IF NOT EXISTS relay_c1_path_config
(
    id BIGINT PRIMARY KEY,
    draft_version INT NOT NULL,
    draft_json LONGTEXT,
    draft_updated_by VARCHAR(64),
    draft_updated_at DATETIME(6),
    published_version INT,
    published_at DATETIME(6),
    published_by VARCHAR(64),
    migration_hints_json LONGTEXT,
    CHECK (JSON_VALID(draft_json)),
    CHECK (JSON_VALID(migration_hints_json))
    ) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS relay_c1_path_version
(
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    `version` INT NOT NULL,
    branch_mode VARCHAR(20) NOT NULL,
    payload_json LONGTEXT NOT NULL,
    published_at DATETIME(6) NOT NULL,
    published_by VARCHAR(64) NOT NULL,
    note VARCHAR(500),
    UNIQUE KEY uk_c1_path_version (`version`),
    CHECK (JSON_VALID(payload_json))
    ) ENGINE=InnoDB;

-- C2: GLOBAL 监管路由规则。condition_json 为适用范围的标准条件树：
--   {"operator":"AND","children":[{"field":"paymentCurrency","operator":"EQ","value":"CNY"},{"field":"beneficiaryBankCountry","operator":"EQ","value":"CN"}]}
--   受益行国家不限（ALL）时只有币种一个叶子；required_tags_json 为要求的能力标签 JSON 数组
-- trigger_condition_json 为可选的附加触发条件：按字段库构建的条件树（条件组 {logic,children}，叶子 {field,operator,value}，
--   形态同 relay_appetite.condition_json）；为空表示落在范围内的交易一律触发
CREATE TABLE IF NOT EXISTS relay_regulatory_rule
(
    rule_id
    VARCHAR
(
    48
) PRIMARY KEY,
    rule_name VARCHAR
(
    140
) NOT NULL,
    rule_code VARCHAR
(
    10
) NOT NULL DEFAULT 'C2',
    scope VARCHAR
(
    16
) NOT NULL DEFAULT 'GLOBAL',
    condition_json LONGTEXT NOT NULL,
    required_tags_json LONGTEXT NOT NULL,
    trigger_condition_json LONGTEXT NULL,
    reason_code VARCHAR
(
    32
) NOT NULL DEFAULT 'REG_RULE_NOT_MET',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version_no INT NOT NULL DEFAULT 1,
    effective_from DATETIME
(
    6
) NOT NULL,
    effective_to DATETIME
(
    6
) NULL,
    remark VARCHAR
(
    400
),
    CHECK
(
    JSON_VALID
(
    condition_json
)),
    CHECK
(
    JSON_VALID
(
    required_tags_json
)),
    CHECK
(
    JSON_VALID
(
    trigger_condition_json
))
    ) ENGINE=InnoDB;

-- C2: 合作方屏蔽国家、国籍、地区、币种和用途等
CREATE TABLE IF NOT EXISTS relay_provider_restriction
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    provider_code
    VARCHAR
(
    32
) NOT NULL,
    restriction_type VARCHAR
(
    48
) NOT NULL,
    blocked_values VARCHAR
(
    2000
) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version_no INT NOT NULL DEFAULT 1,
    effective_from DATETIME
(
    6
) NOT NULL,
    effective_to DATETIME
(
    6
) NULL,
    remark VARCHAR
(
    400
),
    INDEX idx_c2_restrict_provider_type
(
    provider_code,
    restriction_type
),
    CONSTRAINT fk_c2_restriction_provider FOREIGN KEY
(
    provider_code
) REFERENCES relay_provider
(
    code
)
    ) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS relay_capability_tag
(
    tag_code
    VARCHAR
(
    64
) PRIMARY KEY,
    tag_name VARCHAR
(
    120
) NOT NULL
    ) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS relay_provider_capability
(
    id
    BIGINT
    PRIMARY
    KEY
    AUTO_INCREMENT,
    provider_code
    VARCHAR
(
    32
) NOT NULL,
    tag_code VARCHAR
(
    64
) NOT NULL,
    UNIQUE KEY uk_provider_capability
(
    provider_code,
    tag_code
),
    CONSTRAINT fk_c2_cap_provider FOREIGN KEY
(
    provider_code
) REFERENCES relay_provider
(
    code
),
    CONSTRAINT fk_c2_cap_tag FOREIGN KEY
(
    tag_code
) REFERENCES relay_capability_tag
(
    tag_code
)
    ) ENGINE=InnoDB;

-- C1–C10 routing configuration and simulated decisions (Hibernate also creates this table).
CREATE TABLE IF NOT EXISTS relay_routing_document
(
    id
    VARCHAR
(
    255
) PRIMARY KEY,
    revision BIGINT NOT NULL,
    payload LONGTEXT NOT NULL
    );
