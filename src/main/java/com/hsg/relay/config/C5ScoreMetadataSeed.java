package com.hsg.relay.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.BusinessFunctionDefinition;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.BusinessFunctionRepository;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.routing.C5ScoreService;
import com.hsg.relay.routing.CalculateValueDateFunction;
import com.hsg.relay.routing.ScoreRule;
import com.hsg.relay.service.ScoreRuleService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * C5 起息日评分所需的最小元数据：8 个字段、组合计算函数 calculateValueDate、V1.10 默认评分规则（草稿 + 已发布 v1）。
 * 幂等——已存在的一律不动（含运营改过的），只补缺失的；升级前的 H2 数据原样保留。每补一条写一行审计。
 * <p>默认评分规则只在「从未发布过 C5 评分规则」时写入，之后修改分数必须走页面发布新版本。
 */
@Configuration
public class C5ScoreMetadataSeed {
    static final String RULE = C5ScoreService.RULE_CODE;
    static final String OPERATOR = "system";
    static final String DEFAULT_RULE = """
            {"ruleCode":"C5","ruleType":"SCORE","matchMode":"FIRST_MATCH","scoreRange":{"min":0,"max":100},"enabled":true,
             "groups":[
              {"groupCode":"C5-ON-TIME","name":"按时到达","priority":1,
               "condition":{"type":"FIELD","field":"delayBusinessDays","operator":"EQ","value":0},"result":{"type":"SCORE","score":100}},
              {"groupCode":"C5-ONE-DAY","name":"延迟一个工作日","priority":2,
               "condition":{"type":"FIELD","field":"delayBusinessDays","operator":"EQ","value":1},"result":{"type":"SCORE","score":50}},
              {"groupCode":"C5-TWO-PLUS","name":"延迟两个及以上工作日","priority":3,
               "condition":{"type":"FIELD","field":"delayBusinessDays","operator":"GTE","value":2},"result":{"type":"SCORE","score":0}}
             ]}
            """;

    @Bean
    ApplicationListener<ApplicationReadyEvent> seedC5ScoreMetadata(FieldDefinitionRepository fields, BusinessFunctionRepository functions,
                                                                  RuleAuditRepository audit, ObjectMapper json, ScoreRuleService scoreRules) {
        return event -> {
            // 评分条件的输入字段（由 calculateValueDate 算出后写入求值变量）；运算符沿用字段库现有编码 EQ/NE/GT/GTE/LT/LTE
            field(fields, audit, json, "requestedValueDate", "请求起息日", "DATE", "EQ,NE,GT,GTE,LT,LTE");
            field(fields, audit, json, "providerBusinessDay", "当前是否合作方工作日", "BOOLEAN", "EQ,NE");
            field(fields, audit, json, "beforeCutoff", "是否早于截单时间", "BOOLEAN", "EQ,NE");
            field(fields, audit, json, "valueDateOffsetDays", "覆盖行工作日偏移", "INTEGER", "EQ,NE,GT,GTE,LT,LTE");
            field(fields, audit, json, "earliestValueDate", "最早可实现起息日", "DATE", "EQ,NE,GT,GTE,LT,LTE");
            field(fields, audit, json, "candidateValueDate", "候选起息日", "DATE", "EQ,NE,GT,GTE,LT,LTE");
            field(fields, audit, json, "delayBusinessDays", "延迟工作日数", "INTEGER", "EQ,NE,GT,GTE,LT,LTE");
            field(fields, audit, json, C5ScoreService.OUTPUT_FIELD, "最终 C5 分数", "INTEGER", "EQ,NE,GT,GTE,LT,LTE");
            function(functions, audit, json);
            try {
                ScoreRule rule = json.readValue(DEFAULT_RULE, ScoreRule.class);
                scoreRules.seedDefault(RULE, rule, C5ScoreService.INPUT_FIELDS, C5ScoreService.PROBE_DOMAIN, OPERATOR,
                        "系统初始化：V1.10 正式默认评分规则（延迟 0 → 100，1 → 50，≥2 → 0）");
            } catch (JsonProcessingException | IllegalStateException ex) {
                throw new IllegalStateException("C5 默认评分规则无效", ex);
            }
        };
    }

    private static void field(FieldDefinitionRepository fields, RuleAuditRepository audit, ObjectMapper json,
                              String code, String name, String type, String operators) {
        if (fields.existsById(code)) return;
        FieldDefinition field = new FieldDefinition(code, name, type, operators, RULE);
        fields.save(field);
        audit.save(new RuleAudit("FIELD_METADATA", code, "CREATE", OPERATOR, null, write(json, field)));
    }

    private static void function(BusinessFunctionRepository functions, RuleAuditRepository audit, ObjectMapper json) {
        String code = CalculateValueDateFunction.CODE;
        if (functions.existsById(code)) return;
        BusinessFunctionDefinition definition = new BusinessFunctionDefinition();
        definition.functionCode = code;
        definition.functionName = "起息日组合计算";
        definition.category = "覆盖与限额";
        definition.returnType = "OBJECT";
        definition.allowedRules = RULE;
        definition.executionScope = "CANDIDATE";
        definition.cacheStrategy = "REQUEST";
        definition.handlerCode = code;
        definition.dataSource = "覆盖行配置（截单时间 / 时区 / 工作日偏移 / 节假日）";
        definition.parametersJson = """
                [{"code":"providerCode","type":"STRING","source":"CANDIDATE"},{"code":"paymentCurrency","type":"STRING","source":"TRANSACTION"},
                 {"code":"decisionTime","type":"DATE","source":"CONTEXT"},{"code":"matchedReachRowId","type":"INTEGER","source":"CONTEXT"},
                 {"code":"matchedReachRuleVersion","type":"INTEGER","source":"CONTEXT"},{"code":"requestedValueDate","type":"DATE","source":"TRANSACTION"}]
                """;
        definition.description = "按 C3 最终选中的覆盖行（截单时间、时区、工作日偏移、节假日）计算 earliestValueDate、candidateValueDate、"
                + "delayBusinessDays。C5 评分执行器对每个候选调用一次，结果供全部评分组使用；返回结构，不能作为条件节点。";
        functions.save(definition);
        audit.save(new RuleAudit("BUSINESS_FUNCTION", code, "CREATE", OPERATOR, null, write(json, definition)));
    }

    private static String write(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("审计序列化失败", ex);
        }
    }
}
