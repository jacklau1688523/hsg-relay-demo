-- 仅用于已经手工执行旧版 schema-mysql.sql 建表的 MySQL 数据库：执行一次。
-- 如果通过 spring.jpa.hibernate.ddl-auto=update 升级，请勿重复执行。
ALTER TABLE relay_rule_field
    ADD COLUMN value_options VARCHAR(1000) NULL;
-- 另外的新 C2 表，使用更新后的 schema-mysql.sql 中 CREATE TABLE IF NOT EXISTS 段创建。
