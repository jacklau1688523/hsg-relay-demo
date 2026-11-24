-- 仅用于已经手工执行旧版 schema-mysql.sql 建表、且未开启 spring.jpa.hibernate.ddl-auto=update 的 MySQL 数据库：执行一次。
-- 为全局监管路由规则增加「附加触发条件」列（按字段库构建的条件树；为空表示落在范围内的交易一律触发）。
-- 开启了 ddl-auto=update 的数据库启动时会自动加上这一列，请勿重复执行。
ALTER TABLE relay_regulatory_rule
    ADD COLUMN trigger_condition_json LONGTEXT NULL;
