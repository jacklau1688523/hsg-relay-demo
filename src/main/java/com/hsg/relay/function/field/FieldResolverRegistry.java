package com.hsg.relay.function.field;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 字段解析器白名单：字段元数据的 resolverCode 只能指向这里登记的实现。
 * 清单来自代码（实现了 {@link FieldValueResolver} 的 Spring Bean），不是配置；元数据不会生成代码。
 */
@Component
public class FieldResolverRegistry {
    private final Map<String, FieldValueResolver> resolvers = new TreeMap<>();

    public FieldResolverRegistry(List<FieldValueResolver> beans) {
        for (FieldValueResolver bean : beans) {
            String code = bean.resolverCode();
            if (code == null || code.isBlank()) throw new IllegalStateException("FieldValueResolver 缺少 resolverCode：" + bean.getClass().getName());
            if (resolvers.putIfAbsent(code, bean) != null) throw new IllegalStateException("resolverCode 重复注册：" + code);
        }
    }

    public Optional<FieldValueResolver> find(String resolverCode) {
        return resolverCode == null ? Optional.empty() : Optional.ofNullable(resolvers.get(resolverCode));
    }

    public boolean isRegistered(String resolverCode) {
        return resolverCode != null && resolvers.containsKey(resolverCode);
    }

    /** 已注册的解析器编码（按名称排序）。 */
    public List<String> resolverCodes() {
        return List.copyOf(resolvers.keySet());
    }
}
