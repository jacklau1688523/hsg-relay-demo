-- 仅用于已经手工执行旧版 schema-mysql.sql 建表、且未开启 spring.jpa.hibernate.ddl-auto=update 的 MySQL 数据库：执行一次。
-- 开启了 ddl-auto=update 的数据库（本项目两个 profile 的默认设置）启动时会自动加上这三列，请勿重复执行。
-- 字段增加「取值来源」：升级前的字段三列都为空，照旧按请求输入取值，已保存的 C1 / C2 / C3 条件 JSON 不需要任何改动。
--   value_source       REQUEST 请求输入 / FUNCTION 由函数库的业务函数计算（为空视为 REQUEST）
--   function_code      取值来源为 FUNCTION 时绑定的函数编码
--   argument_bindings  参数绑定 JSON 对象：参数编码 → 受控路径（"$candidate.providerCode"）或字面量
ALTER TABLE relay_rule_field
    ADD COLUMN value_source VARCHAR(20) NULL,
    ADD COLUMN function_code VARCHAR(64) NULL,
    ADD COLUMN argument_bindings VARCHAR(4000) NULL;

-- 内置字段 clientProviderExcluded（客户是否被合作方排除）与函数库 clientExcluded 的绑定不需要在库里手工做：
-- 应用升级后第一次启动时会自动绑定（FunctionMetadataService.bindBuiltInField，写入审计 FIELD_METADATA / BIND_FUNCTION），
-- 且只在函数 clientExcluded 已启用、后端实现已注册、字段类型为 BOOLEAN、适用规则仅为 C1、字段还没有配置过取值来源时才绑定，
-- 运营改过的取值配置不会被覆盖。等价的语句如下，仅供核对，默认不执行：
-- UPDATE relay_rule_field
-- SET value_source = 'FUNCTION',
--     function_code = 'clientExcluded',
--     argument_bindings = '{"providerCode":"$candidate.providerCode","clientId":"$transaction.clientId"}'
-- WHERE field_code = 'clientProviderExcluded'
--   AND data_type = 'BOOLEAN'
--   AND allowed_rules = 'C1'
--   AND value_source IS NULL;
