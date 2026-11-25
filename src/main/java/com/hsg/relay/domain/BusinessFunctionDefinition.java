package com.hsg.relay.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "relay_business_function")
public class BusinessFunctionDefinition {
    @Id @Column(length = 64)
    public String functionCode;
    @Column(nullable = false, length = 80)
    public String functionName;
    @Column(nullable = false, length = 40)
    public String category;
    @Column(nullable = false, length = 20)
    public String returnType;
    @Column(nullable = false, length = 100)
    public String allowedRules;
    @Column(nullable = false, length = 20)
    public String executionScope;
    @Column(nullable = false, length = 20)
    public String cacheStrategy;
    @Column(nullable = false, length = 64)
    public String handlerCode;
    @Column(length = 100)
    public String dataSource;
    @Lob @Column(length = 100000)
    public String parametersJson;
    @Column(length = 2000)
    public String description;
    @Column(nullable = false)
    public boolean enabled = true;

    public BusinessFunctionDefinition() {}
}
