package com.hsg.relay.function.field;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 元素结构白名单：OBJECT_LIST 字段的 elementSchema 只能指向这里登记的结构。
 * 清单来自代码（Spring 容器里类型为 {@link ElementSchema} 的 Bean），不是配置；页面不能新建或修改结构。
 * 字段解析器按结构编码取结构来校验自己返回的记录。
 */
@Component
public class ElementSchemaRegistry {
    private final Map<String, ElementSchema> schemas = new TreeMap<>();

    public ElementSchemaRegistry(List<ElementSchema> beans) {
        for (ElementSchema bean : beans) {
            if (schemas.putIfAbsent(bean.code(), bean) != null)
                throw new IllegalStateException("元素结构重复注册：" + bean.code());
        }
    }

    public Optional<ElementSchema> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(schemas.get(code));
    }

    /** 按编码取结构；未登记直接报错（不会放行未知结构）。 */
    public ElementSchema require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("元素结构未登记：" + code));
    }

    /** 全部已登记的结构（按编码排序）。 */
    public Collection<ElementSchema> all() {
        return Collections.unmodifiableCollection(schemas.values());
    }
}
