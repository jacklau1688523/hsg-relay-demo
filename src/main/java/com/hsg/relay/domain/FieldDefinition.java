package com.hsg.relay.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "relay_rule_field")
public class FieldDefinition {
    @Id
    @Column(length = 64)
    public String fieldCode;
    @Column(nullable = false, length = 80)
    public String fieldName;
    @Column(nullable = false, length = 20)
    public String dataType;
    @Column(nullable = false, length = 200)
    public String allowedOperators;
    @Column(nullable = false, length = 100)
    public String allowedRules;
    @Column(length = 1000)
    public String valueOptions;
    // 取值来源：REQUEST 请求输入（默认，为空视为 REQUEST）/ FUNCTION 由绑定的业务函数计算 / DATA_RESOLVER 由字段解析器取数
    @Column(length = 20)
    public String valueSource;
    // 数据解析字段：字段解析器编码（对应 FieldResolverRegistry 里登记的实现）
    @Column(length = 64)
    public String resolverCode;
    // 集合类型（STRING_SET / STRING_LIST）的元素类型，目前为 STRING；非集合字段为空
    @Column(length = 20)
    public String elementType;
    // 对象集合（OBJECT_LIST）的元素结构编码（代码登记，如 REACH_ROW）；非对象集合字段为空
    @Column(length = 40)
    public String elementSchema;
    // 字段执行作用域：CANDIDATE 当前候选路由 / TRANSACTION 当前交易 / CONTEXT 规则上下文 / GLOBAL
    @Column(length = 20)
    public String executionScope;
    // 缓存策略：REQUEST 本次求值内复用（默认）/ NONE 每次重新解析
    @Column(length = 20)
    public String cachePolicy;
    @Column(length = 64)
    public String functionCode;
    // 函数参数绑定 JSON：参数编码 → 受控路径（"$candidate.providerCode"）或字面量
    @Column(length = 4000)
    public String argumentBindings;
    @Column(nullable = false)
    public boolean enabled = true;

    protected FieldDefinition() {
    }

    public FieldDefinition(String code, String name, String type, String operators, String rules) {
        fieldCode = code;
        fieldName = name;
        dataType = type;
        allowedOperators = operators;
        allowedRules = rules;
    }

    public FieldDefinition(String code, String name, String type, String operators, String rules, String values) {
        this(code, name, type, operators, rules);
        valueOptions = values;
    }
}
