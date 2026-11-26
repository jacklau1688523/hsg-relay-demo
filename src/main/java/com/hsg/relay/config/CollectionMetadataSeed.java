package com.hsg.relay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.c2.C2ProviderCollectionsResolver;
import com.hsg.relay.domain.BusinessFunctionDefinition;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.BusinessFunctionRegistry;
import com.hsg.relay.function.NumberMinFunction;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.BusinessFunctionRepository;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.routing.ReachRows;
import com.hsg.relay.service.CostComponentsResolver;
import com.hsg.relay.service.MatchedPreferenceRulesResolver;
import com.hsg.relay.service.ProviderExclusionListResolver;
import com.hsg.relay.service.ProviderReachRowsResolver;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用集合能力的元数据：启动时补齐集合 / 对象集合字段和 27 个通用函数（加封顶函数 numberMin）。
 * <p>幂等——已存在的（含运营改过的）不覆盖、不清空，只补缺失的；升级前的 H2 数据原样保留。每补一条写一行审计。
 * 函数的参数清单取自代码里 Handler 的声明，不在这里另抄一份；分类、适用规则按下表。
 */
@Configuration
public class CollectionMetadataSeed {
    /** 集合函数的四个分类（页面按分类筛选）。 */
    public static final String COMPARE = "COLLECTION_COMPARE", OPERATION = "COLLECTION_OPERATION",
            AGGREGATE = "COLLECTION_AGGREGATE", RECORD = "COLLECTION_RECORD";

    /** 集合类函数的适用规则：与 {@link RuleExecutionContext#FUNCTION_RULES}（已接入函数节点的规则）保持一致。 */
    public static final String FUNCTION_RULES = String.join(",", RuleExecutionContext.FUNCTION_RULES.stream().sorted().toList());

    /** 通用函数清单：编码、名称、分类、说明。参数与返回类型以 Handler 声明为准。 */
    private static final List<Object[]> FUNCTIONS = List.of(
            // ---------- 集合判断 ----------
            new Object[]{"collectionContains", "集合是否包含指定值", COMPARE,
                    "集合里包含指定元素返回 true，否则 false。已确认的空集合返回 false；集合未加载、读取失败时报错，不按 false 处理。"},
            new Object[]{"collectionNotContains", "集合是否不包含指定值", COMPARE,
                    "集合里不包含指定元素返回 true。已确认的空集合返回 true；集合未加载、读取失败时报错，不按 true 处理。"},
            new Object[]{"collectionContainsAny", "是否包含任一指定值", COMPARE,
                    "集合包含候选元素中的任意一个返回 true。集合为空返回 false；候选为空返回 false（没有可命中的元素）。"},
            new Object[]{"collectionContainsAll", "是否包含全部指定值", COMPARE,
                    "集合包含候选元素中的每一个返回 true。候选为空返回 true；集合读取失败时报错。"},
            new Object[]{"collectionIntersects", "两个集合是否存在交集", COMPARE,
                    "两个集合有共同元素返回 true。任一方为已确认的空集合返回 false；区分大小写；读取失败时报错。"},
            new Object[]{"collectionIsEmpty", "集合是否为空", COMPARE,
                    "已加载的集合没有元素返回 true。未加载、读取失败时报错，不会把取不到数据当成空集合。"},
            new Object[]{"collectionSize", "集合元素个数", COMPARE,
                    "集合的元素个数：清单按元素计（重复各算一个），集合按去重后的成员计。空集合返回 0；未加载、无法得知个数的集合报错，不按 0 处理。"},
            // ---------- 集合运算 ----------
            new Object[]{"collectionUnion", "集合并集", OPERATION,
                            "两个集合并成一个。集合去重；清单按「左在前、右在后」拼接，保留重复。结果类型与左集合相同。"},
            new Object[]{"collectionIntersection", "集合交集", OPERATION,
                            "同时在两个集合里的元素，按左集合的出现顺序。元素类型须相同。"},
            new Object[]{"collectionDifference", "集合差集", OPERATION,
                            "在左集合、不在右集合里的元素（有方向：A 减 B），按左集合的出现顺序。"},
            new Object[]{"collectionDistinct", "集合去重", OPERATION,
                            "去掉重复元素，保留首次出现的顺序。结果类型与输入相同。"},
            new Object[]{"collectionFilter", "按条件筛选集合", OPERATION,
                            "按元素谓词筛选字符串 / 数值 / 日期清单（元素本身用「value」引用），保留原顺序。结果类型与输入相同，可继续被后置函数引用。"},
            // ---------- 集合聚合 ----------
            new Object[]{"collectionSum", "集合求和", AGGREGATE,
                            "求和，全程 BigDecimal：数值清单取元素本身；对象集合用取值属性（valueSelector）指定参与合计的数值属性。"
                    + "空集合返回 0；任何一条记录取不到数值都报错，不会静默跳过。"},
            new Object[]{"collectionCount", "集合元素数量", AGGREGATE,
                            "集合的元素个数。空集合返回 0；未加载、无法得知个数的集合报错，不按 0 处理。"},
            new Object[]{"collectionCountWhere", "按条件计数", AGGREGATE,
                            "满足谓词条件的元素个数，逐个元素判断。空集合或没有元素满足时返回 0；比较对象没有取值时报错，不按不满足处理。"},
            new Object[]{"collectionSumWhere", "按条件求和", AGGREGATE,
                            "先按谓词筛选元素，再对留下的元素求和（对象集合用取值属性 valueSelector），全程 BigDecimal。"
                    + "没有元素满足时返回 0；留下的记录取不到数值时报错，不会静默跳过。"},
            new Object[]{"collectionAvg", "集合平均值", AGGREGATE,
                            "平均值（数值清单取元素本身；对象集合用取值属性 valueSelector），保留 6 位小数、四舍五入。空集合属于「没有结果」，报错而不返回 0。"},
            new Object[]{"collectionMin", "集合最小值", AGGREGATE,
                            "最小值（数值清单取元素本身；对象集合用取值属性 valueSelector）。空集合属于「没有结果」，报错而不返回 0。"},
            new Object[]{"collectionMax", "集合最大值", AGGREGATE,
                            "最大值（数值清单取元素本身；对象集合用取值属性 valueSelector）。空集合属于「没有结果」，报错而不返回 0。"},
            // ---------- 记录匹配（对象集合） ----------
            new Object[]{"collectionAnyMatch", "存在满足条件的记录", RECORD,
                            "对象集合里至少有一条记录满足谓词返回 true。逐条记录求值：两条记录各满足一半不算满足。空集合返回 false。"},
            new Object[]{"collectionAllMatch", "全部记录满足条件", RECORD,
                            "对象集合里每一条记录都满足谓词返回 true。空集合返回 true。"},
            new Object[]{"collectionNoneMatch", "没有满足条件的记录", RECORD,
                            "对象集合里没有任何一条记录满足谓词返回 true。空集合返回 true。"},
            new Object[]{"collectionFilterRecords", "按条件筛选记录", RECORD,
                            "对象集合里满足谓词的记录组成新的对象集合（结构与原集合相同），保留原顺序，可继续被后置函数引用。"},
            new Object[]{"collectionFirstMatch", "第一条满足条件的记录", RECORD,
                            "对象集合里按排序字段（可多字段、升序 / 降序）排在最前的满足条件的记录。没有满足的记录时按「无结果」处理，"
                            + "与读取失败区分。返回对象，供展示；目前没有后置函数接收对象。"},
            new Object[]{"collectionCountMatch", "满足条件的记录数", RECORD,
                            "对象集合里满足谓词的记录条数，返回整数。空集合返回 0。"},
            new Object[]{"collectionGroupBy", "按字段分组", RECORD,
                            "对象集合按某个属性（groupField）分组，可先按谓词筛选；属性为空的记录归入「（空）」组。返回分组结果（MAP），目前没有后置函数接收。"},
            new Object[]{"collectionSort", "多字段排序", RECORD,
                            "对象集合按多个属性排序（每个属性可升序 / 降序；字符串、数值、日期），排序稳定，空值排在最后，"
                            + "不改变原集合。返回对象集合，可继续被后置函数引用。"},
            // ---------- 数值 ----------
            new Object[]{NumberMinFunction.CODE, "数值封顶（取较小值）", "数值计算",
                    "返回 value 与 cap 中的较小者，全程 BigDecimal。用于把函数输出封顶（如偏好加分合计封顶 100）。"});

    /** 集合 / 对象集合字段：编码、名称、类型、元素结构、适用规则、解析器、执行作用域。 */
    private record SeedField(String code, String name, String type, String elementSchema, String rules, String resolver, String scope) {
    }

    private static final List<SeedField> FIELDS = List.of(
            new SeedField(ProviderExclusionListResolver.FIELD_CODE, "合作方有效排除客户集合", "STRING_SET", null, "C1",
                    ProviderExclusionListResolver.RESOLVER_CODE, "CANDIDATE"),
            new SeedField(C2ProviderCollectionsResolver.RESTRICTED_NATIONALITIES, "合作方国籍限制取值", "STRING_SET", null, "C2",
                    C2ProviderCollectionsResolver.RESOLVER_CODE, "CANDIDATE"),
            new SeedField(C2ProviderCollectionsResolver.RESTRICTIONS, "合作方生效的限制记录", "OBJECT_LIST", "PROVIDER_RESTRICTION", "C2",
                    C2ProviderCollectionsResolver.RESOLVER_CODE, "CANDIDATE"),
            new SeedField(C2ProviderCollectionsResolver.CAPABILITY_TAGS, "合作方能力标签", "STRING_SET", null, "C2",
                    C2ProviderCollectionsResolver.RESOLVER_CODE, "CANDIDATE"),
            new SeedField(C2ProviderCollectionsResolver.REQUIRED_TAGS, "监管规则要求的能力标签", "STRING_SET", null, "C2",
                    C2ProviderCollectionsResolver.RESOLVER_CODE, "GLOBAL"),
            new SeedField(ProviderReachRowsResolver.FIELD_CODE, "合作方覆盖行", "OBJECT_LIST", ReachRows.SCHEMA_CODE, "C3",
                    ProviderReachRowsResolver.RESOLVER_CODE, "CANDIDATE"),
            new SeedField(CostComponentsResolver.FIELD_CODE, "预估成本构成（港币）", "OBJECT_LIST", CostComponentsResolver.SCHEMA.code(), "C7",
                    CostComponentsResolver.RESOLVER_CODE, "CANDIDATE"),
            new SeedField(MatchedPreferenceRulesResolver.FIELD_CODE, "命中的偏好规则", "OBJECT_LIST",
                    MatchedPreferenceRulesResolver.SCHEMA.code(), "C8", MatchedPreferenceRulesResolver.RESOLVER_CODE, "CANDIDATE"));

    @Bean
    ApplicationListener<ApplicationReadyEvent> seedCollectionMetadata(FieldDefinitionRepository fields, BusinessFunctionRepository functions,
                                                                       RuleAuditRepository audit, ObjectMapper json,
                                                                       BusinessFunctionRegistry registry) {
        return event -> {
            for (SeedField seed : FIELDS) {
                if (fields.existsById(seed.code())) continue;
                FieldDefinition field = new FieldDefinition(seed.code(), seed.name(), seed.type(),
                        "STRING_SET".equals(seed.type()) ? "CONTAINS,NOT_CONTAINS" : "", seed.rules());
                field.elementType = "OBJECT_LIST".equals(seed.type()) ? "OBJECT" : "STRING";
                field.elementSchema = seed.elementSchema();
                field.valueSource = "DATA_RESOLVER";
                field.resolverCode = seed.resolver();
                field.executionScope = seed.scope();
                field.cachePolicy = "REQUEST";
                fields.save(field);
                audit.save(new RuleAudit("FIELD_METADATA", field.fieldCode, "CREATE", "system", null, write(json, field)));
            }
            for (Object[] spec : FUNCTIONS)
                function(registry, functions, audit, json, (String) spec[0], (String) spec[1], (String) spec[2], (String) spec[3]);
        };
    }

    /** 补缺失的函数：参数清单取自已注册的 Handler 声明；Handler 未注册的函数不补（补了也无法执行）。 */
    private static void function(BusinessFunctionRegistry registry, BusinessFunctionRepository functions, RuleAuditRepository audit,
                                 ObjectMapper json, String code, String name, String category, String description) {
        // 已存在的（含升级前由旧种子写入、或运营改过的）不覆盖
        if (functions.existsById(code)) return;
        BusinessFunction handler = registry.find(code).orElse(null);
        if (handler == null) return;
        BusinessFunctionDefinition definition = new BusinessFunctionDefinition();
        definition.functionCode = code;
        definition.functionName = name;
        definition.category = category;
        definition.returnType = handler.returnType();
        definition.allowedRules = FUNCTION_RULES;
        definition.executionScope = "GLOBAL";
        definition.cacheStrategy = "REQUEST";
        definition.handlerCode = handler.handlerCode();
        definition.dataSource = handler.dataSource();
        definition.parametersJson = write(json, visible(handler));
        definition.description = description;
        definition.implemented = true;
        functions.save(definition);
        audit.save(new RuleAudit("BUSINESS_FUNCTION", code, "CREATE", "system", null, write(json, definition)));
    }

    /** 参数清单的展示形态：编码、类型、来源、是否必填、默认路径、名称。 */
    private static List<Map<String, Object>> visible(BusinessFunction handler) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (BusinessFunction.Parameter p : handler.parameters())
            parameters.add(Map.of("code", p.code(), "type", p.type(), "source", p.source(), "required", p.required(),
                    "defaultPath", p.defaultPath() == null ? "" : p.defaultPath(), "label", p.label() == null ? "" : p.label()));
        return parameters;
    }

    private static String write(ObjectMapper json, Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("审计序列化失败", ex);
        }
    }
}
