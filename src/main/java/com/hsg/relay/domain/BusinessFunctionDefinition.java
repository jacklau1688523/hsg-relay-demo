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
    // 以下两项不落库，接口返回时由 FunctionMetadataService 按代码补上：
    // usagePolicy：REQUIRED 系统必检（执行器强制落实，不可删除）/ OPTIONAL 可选；implemented：后端实现已注册且返回类型一致
    @Transient
    public String usagePolicy;
    @Transient
    public boolean implemented;

    public BusinessFunctionDefinition() {}
}
