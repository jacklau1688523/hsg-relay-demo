package com.hsg.relay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.BusinessFunctionDefinition;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.function.CollectionContainsFunction;
import com.hsg.relay.function.CollectionIntersectsFunction;
import com.hsg.relay.repo.BusinessFunctionRepository;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.service.ProviderExclusionListResolver;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 通用集合能力的元数据：启动时补齐集合字段 providerExcludedClientIds 和通用函数 collectionContains / collectionIntersects。
 * 幂等——已存在的（含运营改过的）不覆盖、不清空，只补缺失的；升级前的 H2 数据原样保留。每补一条写一行审计。
 */
@Configuration
public class CollectionMetadataSeed {
    @Bean
    ApplicationListener<ApplicationReadyEvent> seedCollectionMetadata(FieldDefinitionRepository fields, BusinessFunctionRepository functions,
                                                                       RuleAuditRepository audit, ObjectMapper json) {
        return event -> {
            if (!fields.existsById(ProviderExclusionListResolver.FIELD_CODE)) {
                FieldDefinition field = new FieldDefinition(ProviderExclusionListResolver.FIELD_CODE, "合作方有效排除客户集合",
                        "STRING_SET", "CONTAINS,NOT_CONTAINS", "C1");
                field.elementType = "STRING";
                field.valueSource = "DATA_RESOLVER";
                field.resolverCode = ProviderExclusionListResolver.RESOLVER_CODE;
                field.executionScope = "CANDIDATE";
                field.cachePolicy = "REQUEST";
                fields.save(field);
                audit.save(new RuleAudit("FIELD_METADATA", field.fieldCode, "CREATE", "system", null, write(json, field)));
            }
            function(functions, audit, json, CollectionContainsFunction.CODE, "集合是否包含指定值",
                    "[{\"code\":\"collection\",\"type\":\"STRING_SET\",\"source\":\"ANY\"},{\"code\":\"element\",\"type\":\"STRING\",\"source\":\"ANY\"}]",
                    "通用集合函数：集合里包含指定元素返回 true，否则 false（空集合返回 false）。集合来自数据解析字段（如 providerExcludedClientIds），"
                            + "元素可取自交易、候选路由、上下文或另一个字段。只做计算，不查询业务表；集合读取失败时报错，不按 false 处理。");
            function(functions, audit, json, CollectionIntersectsFunction.CODE, "两个集合是否存在交集",
                    "[{\"code\":\"leftCollection\",\"type\":\"STRING_SET\",\"source\":\"ANY\"},{\"code\":\"rightCollection\",\"type\":\"STRING_SET\",\"source\":\"ANY\"}]",
                    "通用集合函数：两个集合有共同元素返回 true，否则 false（任一方为空集合返回 false）。可按国家、币种、国籍、BIC 等集合复用。"
                            + "只做计算，不查询业务表。");
        };
    }

    private static void function(BusinessFunctionRepository functions, RuleAuditRepository audit, ObjectMapper json,
                                 String code, String name, String parameters, String description) {
        if (functions.existsById(code)) return;
        BusinessFunctionDefinition definition = new BusinessFunctionDefinition();
        definition.functionCode = code;
        definition.functionName = name;
        definition.category = "集合运算";
        definition.returnType = "BOOLEAN";
        definition.allowedRules = "C1";
        definition.executionScope = "GLOBAL";
        definition.cacheStrategy = "REQUEST";
        definition.handlerCode = code;
        definition.dataSource = "ARGUMENT";
        definition.parametersJson = parameters;
        definition.description = description;
        functions.save(definition);
        audit.save(new RuleAudit("BUSINESS_FUNCTION", code, "CREATE", "system", null, write(json, definition)));
    }

    private static String write(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("审计序列化失败", ex);
        }
    }
}
