package com.hsg.relay.service;

import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.FieldValueResolver;
import com.hsg.relay.function.field.RuleCollection;
import org.springframework.stereotype.Component;

/**
 * 元数据字段 providerExcludedClientIds 的解析器：当前候选合作方在决策时间的有效排除客户集合。
 * <p>合作方取自候选路由（candidate.providerCode），决策时间取自规则上下文，代码里不写死任何合作方或客户；
 * 数据读取复用 ClientExclusionChecker（同一张表 relay_client_exclusion、同一套有效性判断），
 * 并与 C1 的客户排除必检共用请求级缓存里的同一份集合。读失败时异常向上抛出，不返回空集合。
 */
@Component
public class ProviderExclusionListResolver implements FieldValueResolver {
    public static final String RESOLVER_CODE = "providerExclusionListResolver";
    public static final String FIELD_CODE = "providerExcludedClientIds";

    private final ClientExclusionChecker exclusions;

    public ProviderExclusionListResolver(ClientExclusionChecker exclusions) {
        this.exclusions = exclusions;
    }

    @Override
    public String resolverCode() {
        return RESOLVER_CODE;
    }

    @Override
    public RuleCollection resolve(String fieldCode, RuleExecutionContext context) {
        if (!FIELD_CODE.equals(fieldCode))
            throw new IllegalArgumentException("字段解析器 " + RESOLVER_CODE + " 不支持字段 " + fieldCode);
        return (RuleCollection) exclusions.exclusionSet(context);
    }
}
