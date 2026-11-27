-- 仅用于已经手工执行旧版 schema-mysql.sql 建表、且未开启 spring.jpa.hibernate.ddl-auto=update 的 MySQL 数据库：执行一次。
-- 开启了 ddl-auto=update 的数据库（本项目两个 profile 的默认设置）启动时会自动建这两张表，请勿重复执行。
-- 执行方式（本文件不含 USE，先选库）：mysql -u<用户> -p hsg_relay < sql/migrate-c1-path.sql
--
-- C1「合作方动态规则路径配置」新增两张表（只有 CREATE TABLE IF NOT EXISTS，没有 ALTER，旧表和旧数据都不改动）：
--   relay_c1_path_config   全局一行（id = 1）：草稿 + 「最新已发布版本」指针。草稿不参与执行。
--   relay_c1_path_version  已发布版本：只插入，不更新、不删除。
-- 建表后第一次启动，应用自动把旧版「全局准入条件」（c1condition_config）迁入：v1 = 旧条件树原样作为 DEFAULT 路径，
-- 草稿 = 去掉两个旧版布尔条件后的条件树，等人工确认后发布；旧配置行原样保留。
-- 发布前（没有任何已发布版本时）C1 对全部候选合作方返回 C1_CONFIG_MISSING，不会默认通过。
-- 新执行 schema-mysql.sql 建表的库已包含这两张表，无需再执行本文件。
-- 列类型与 schema-mysql.sql、实体映射保持一致（三个 JSON 列：MariaDB / MySQL longtext，H2 clob；唯一约束名 uk_c1_path_version），
-- 否则开启了 ddl-auto=update 的应用每次启动都会 ALTER 这两张表。
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
