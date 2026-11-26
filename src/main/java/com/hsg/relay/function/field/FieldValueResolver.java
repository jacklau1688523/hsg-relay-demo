package com.hsg.relay.function.field;

import com.hsg.relay.function.RuleExecutionContext;

/**
 * 元数据字段的取值解析器：按 resolverCode 与字段定义（relay_rule_field）绑定，负责「取数据」，不做条件判断。
 * <p>约定：
 * <ul>
 *   <li>返回本次求值上下文里的字段取值；集合类型返回 {@link RuleCollection}，空数据返回空集合；</li>
 *   <li>数据读取失败、配置不可用或结果不完整时抛异常——不能返回 null 或空集合，否则会把本该拒绝的情况放行；</li>
 *   <li>只读：不写业务数据、不扣款、不冻结；</li>
 *   <li>同一上下文里的重复解析由 {@link FieldValueService} 按字段做请求级复用，实现里不必自己缓存。</li>
 * </ul>
 */
public interface FieldValueResolver {

    /** 与字段定义的 resolverCode 对应的编码。 */
    String resolverCode();

    /** 解析字段在本次求值中的取值；实现须先确认 fieldCode 是自己支持的字段。 */
    Object resolve(String fieldCode, RuleExecutionContext context);
}
