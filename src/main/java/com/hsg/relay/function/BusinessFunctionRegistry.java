package com.hsg.relay.function;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Java Handler 白名单：函数库元数据的 handlerCode 只能指向这里登记的实现。
 * 清单来自代码（实现了 {@link BusinessFunction} 的 Spring Bean），不是配置。
 */
@Component
public class BusinessFunctionRegistry {
    private final Map<String, BusinessFunction> handlers = new TreeMap<>();

    public BusinessFunctionRegistry(List<BusinessFunction> beans) {
        for (BusinessFunction bean : beans) {
            String code = bean.handlerCode();
            if (code == null || code.isBlank()) throw new IllegalStateException("BusinessFunction 缺少 handlerCode：" + bean.getClass().getName());
            if (handlers.putIfAbsent(code, bean) != null) throw new IllegalStateException("handlerCode 重复注册：" + code);
        }
    }

    public Optional<BusinessFunction> find(String handlerCode) {
        return handlerCode == null ? Optional.empty() : Optional.ofNullable(handlers.get(handlerCode));
    }

    public boolean isImplemented(String handlerCode) {
        return handlerCode != null && handlers.containsKey(handlerCode);
    }

    public List<String> handlerCodes() {
        return List.copyOf(handlers.keySet());
    }
}
