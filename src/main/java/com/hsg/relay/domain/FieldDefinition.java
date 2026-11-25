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
    // 取值来源：REQUEST 请求输入（默认，为空视为 REQUEST）/ FUNCTION 由绑定的业务函数计算
    @Column(length = 20)
    public String valueSource;
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
