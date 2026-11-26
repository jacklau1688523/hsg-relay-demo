package com.hsg.relay.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.function.field.FieldResolverRegistry;
import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.C1ConditionConfigRepository;
import com.hsg.relay.c2.RegulatoryRuleRepository;
import com.hsg.relay.c2.C2ConditionConfigRepository;
import com.hsg.relay.routing.C3ConditionConfigRepository;
import jakarta.transaction.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.*;

/**
 * Shared metadata catalog for the expression editor and C2 field picker.
 */
@RestController
@RequestMapping("/api/field-metadata")
public class FieldMetadataController {
    private final FieldDefinitionRepository fields;
    private final RuleAuditRepository audit;
    private final ObjectMapper json;
    private final AppetiteRepository appetites;
    private final C1ConditionConfigRepository c1Conditions;
    private final RegulatoryRuleRepository regulatoryRules;
    private final C2ConditionConfigRepository c2Conditions;
    private final C3ConditionConfigRepository c3Conditions;
    private final FunctionMetadataService functionMetadata;
    private final FieldResolverRegistry resolvers;

    public FieldMetadataController(FieldDefinitionRepository fields, RuleAuditRepository audit, ObjectMapper json,
                                   AppetiteRepository appetites, C1ConditionConfigRepository c1Conditions,
                                   RegulatoryRuleRepository regulatoryRules,
                                   C2ConditionConfigRepository c2Conditions, C3ConditionConfigRepository c3Conditions,
                                   FunctionMetadataService functionMetadata, FieldResolverRegistry resolvers) {
        this.fields = fields;
        this.audit = audit;
        this.json = json;
        this.appetites = appetites;
        this.c1Conditions = c1Conditions;
        this.regulatoryRules = regulatoryRules;
        this.c2Conditions = c2Conditions;
        this.c3Conditions = c3Conditions;
        this.functionMetadata = functionMetadata;
        this.resolvers = resolvers;
    }

    /**
     * 字段定义。valueSource：REQUEST 请求输入 / FUNCTION 由业务函数计算 / DATA_RESOLVER 由字段解析器取数；
     * 绑定函数时 functionCode 为函数编码，argumentBindings 为参数绑定 JSON 对象（参数编码 → 受控路径 "$candidate.providerCode" 或字面量）；
     * 数据解析字段带 resolverCode（字段解析器）、executionScope（执行作用域）、cachePolicy（缓存策略），
     * 集合类型（STRING_SET）另带 elementType（元素类型）。
     * 取值来源相关的各项都没有传（升级前的页面 / 脚本）表示请求没有表达取值来源：新增字段留空，修改时保持字段已有的配置。
     */
    public record Input(String fieldCode, String fieldName, String dataType, String allowedOperators,
                        String allowedRules, String valueOptions, boolean enabled,
                        String valueSource, String functionCode, JsonNode argumentBindings,
                        String resolverCode, String elementType, String executionScope, String cachePolicy) {
        @JsonCreator
        public Input {
        }

        /** 旧版十参构造（取值来源为请求输入或函数计算），已有调用方和测试继续使用。 */
        public Input(String fieldCode, String fieldName, String dataType, String allowedOperators, String allowedRules, String valueOptions, boolean enabled,
                     String valueSource, String functionCode, JsonNode argumentBindings) {
            this(fieldCode, fieldName, dataType, allowedOperators, allowedRules, valueOptions, enabled, valueSource, functionCode, argumentBindings,
                    null, null, null, null);
        }

        /** 旧版七参构造（取值来源为请求输入），已有调用方和测试继续使用。 */
        public Input(String fieldCode, String fieldName, String dataType, String allowedOperators, String allowedRules, String valueOptions, boolean enabled) {
            this(fieldCode, fieldName, dataType, allowedOperators, allowedRules, valueOptions, enabled, null, null, null, null, null, null, null);
        }
    }

    @GetMapping
    public List<FieldDefinition> list() {
        return fields.findAll().stream().sorted(Comparator.comparing(f -> f.fieldCode)).toList();
    }

    /** 可绑定的字段解析器：代码里已注册的 FieldValueResolver（白名单），元数据不会生成代码。 */
    @GetMapping("/resolvers")
    public List<String> resolvers() {
        return resolvers.resolverCodes();
    }

    @PostMapping
    @Transactional
    public FieldDefinition create(@RequestBody Input input) {
        FieldDefinition value = validate(input, null);
        if (fields.existsById(value.fieldCode)) throw new IllegalArgumentException("字段编码已存在");
        FieldDefinition saved = fields.save(value);
        writeAudit(saved.fieldCode, "CREATE", null, saved);
        return saved;
    }

    @PutMapping("/{code}")
    @Transactional
    public FieldDefinition update(@PathVariable String code, @RequestBody Input input) {
        FieldDefinition old = fields.findById(code).orElseThrow(() -> new IllegalArgumentException("字段不存在"));
        FieldDefinition value = validate(input, old);
        if (!code.equals(value.fieldCode))
            throw new IllegalArgumentException("字段编码不可修改；请新增字段并停用旧字段");
        String before = serialize(old);
        old.fieldName = value.fieldName;
        old.dataType = value.dataType;
        old.allowedOperators = value.allowedOperators;
        old.allowedRules = value.allowedRules;
        old.valueOptions = value.valueOptions;
        old.valueSource = value.valueSource;
        old.functionCode = value.functionCode;
        old.argumentBindings = value.argumentBindings;
        old.resolverCode = value.resolverCode;
        old.elementType = value.elementType;
        old.executionScope = value.executionScope;
        old.cachePolicy = value.cachePolicy;
        old.enabled = value.enabled;
        FieldDefinition saved = fields.save(old);
        writeAudit(code, "UPDATE", before, saved);
        return saved;
    }

    @PostMapping("/{code}/toggle")
    @Transactional
    public FieldDefinition toggle(@PathVariable String code) {
        FieldDefinition field = fields.findById(code).orElseThrow(() -> new IllegalArgumentException("字段不存在"));
        String before = serialize(field);
        field.enabled = !field.enabled;
        FieldDefinition saved = fields.save(field);
        writeAudit(code, field.enabled ? "ENABLE" : "DISABLE", before, saved);
        return saved;
    }

    @DeleteMapping("/{code}")
    @Transactional
    public Map<String, String> delete(@PathVariable String code) {
        FieldDefinition field = fields.findById(code).orElseThrow(() -> new IllegalArgumentException("字段不存在"));
        List<String> references = new ArrayList<>();
        c1Conditions.findById(1L).ifPresent(row -> {
            if (usesField(row.conditionJson, code)) references.add("C1 全局过滤条件");
        });
        appetites.findAll().forEach(row -> { if (usesField(row.conditionJson, code)) references.add("C1 准入规则 #" + row.id); });
        regulatoryRules.findAll().forEach(row -> {
            if (usesField(row.conditionJson, code) || usesField(row.triggerConditionJson, code))
                references.add("监管路由规则 " + row.ruleId);
        });
        c2Conditions.findById(1L).ifPresent(row -> { if (usesField(row.conditionJson, code)) references.add("C2 全局条件"); });
        c3Conditions.findById(1L).ifPresent(row -> { if (usesField(row.conditionJson, code)) references.add("C3 全局条件"); });
        if (!references.isEmpty()) throw new IllegalArgumentException("字段已被引用，不能删除：" + String.join("、", references.stream().limit(5).toList()));
        writeAudit(code, "DELETE", serialize(field), null);
        fields.delete(field);
        return Map.of("deleted", code);
    }

    private boolean usesField(String conditionJson, String code) {
        if (conditionJson == null || conditionJson.isBlank()) return false;
        try { return usesField(json.readTree(conditionJson), code); }
        catch (Exception e) { throw new IllegalStateException("已保存的规则条件无法读取，请先修复规则配置", e); }
    }

    private boolean usesField(com.fasterxml.jackson.databind.JsonNode node, String code) {
        if (node == null) return false;
        if (node.isObject()) {
            if (code.equals(node.path("field").asText())) return true;
            for (var child : node) if (usesField(child, code)) return true;
        } else if (node.isArray()) for (var child : node) if (usesField(child, code)) return true;
        return false;
    }

    /**
     * 校验字段定义并换算成实体。existing 为被修改的字段（新增时为 null）：
     * 请求没有表达取值来源时，用它保持已有的函数绑定，见 {@link FunctionMetadataService#unchanged}。
     */
    private FieldDefinition validate(Input input, FieldDefinition existing) {
        if (input == null) throw new IllegalArgumentException("字段信息不能为空");
        String code = trim(input.fieldCode()), name = trim(input.fieldName()), type = trim(input.dataType());
        if (!code.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("字段编码须以字母开头，仅含字母、数字、下划线，最多64位");
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("字段名称为必填项，最多80位");
        if (!List.of("STRING", "ENUM", "DECIMAL", "INTEGER", "BOOLEAN", FieldValueService.STRING_SET).contains(type))
            throw new IllegalArgumentException("不支持的数据类型");
        List<String> operators = csv(input.allowedOperators());
        List<String> rules = csv(input.allowedRules());
        List<String> allowed = List.of("DECIMAL", "INTEGER").contains(type) ? List.of("EQ", "NE", "GT", "GTE", "LT", "LTE") :
                "BOOLEAN".equals(type) ? List.of("EQ", "NE") : FieldValueService.isSet(type) ? List.of("CONTAINS", "NOT_CONTAINS") : List.of("EQ", "NE", "IN");
        if (operators.isEmpty() || !allowed.containsAll(operators))
            throw new IllegalArgumentException("运算符与数据类型不匹配");
        if (rules.isEmpty() || rules.stream().anyMatch(x -> !x.matches("C([1-9]|10)")))
            throw new IllegalArgumentException("适用规则须选择 C1–C10");
        String values = trim(input.valueOptions());
        if (values.length() > 1000) throw new IllegalArgumentException("可选值长度不可超过1000位");
        if (!values.isEmpty() && List.of("DECIMAL", "INTEGER", "BOOLEAN", FieldValueService.STRING_SET).contains(type))
            throw new IllegalArgumentException("数字、布尔和集合字段不使用枚举可选值");
        // 集合类型必须声明元素类型（目前只支持 STRING）；非集合类型不能带元素类型
        String elementType = trim(input.elementType()).toUpperCase(Locale.ROOT);
        if (FieldValueService.isSet(type)) {
            if (!"STRING".equals(elementType)) throw new IllegalArgumentException("集合字段 " + code + " 的元素类型 elementType 须为 STRING");
        } else if (!elementType.isEmpty())
            throw new IllegalArgumentException("非集合类型不能设置元素类型");
        String resolverCode = trim(input.resolverCode()), scope = trim(input.executionScope()).toUpperCase(Locale.ROOT), cache = trim(input.cachePolicy()).toUpperCase(Locale.ROOT);
        FieldDefinition field = new FieldDefinition(code, name, type, String.join(",", operators), String.join(",", rules), values);
        field.enabled = input.enabled();
        // 取值来源、函数绑定与字段解析器：不绑定函数的字段按请求输入取值；绑定须通过函数库和白名单校验
        FunctionMetadataService.FieldBinding binding = functionMetadata.binding(code, type, String.join(",", rules),
                input.valueSource(), input.functionCode(), input.argumentBindings(), resolverCode, scope, cache);
        // 请求没有表达取值来源（升级前的页面 / 脚本）：新增字段留空，修改时保持已有的取值配置
        if (FunctionMetadataService.UNCHANGED.equals(binding))
            binding = functionMetadata.unchanged(existing, type, String.join(",", rules));
        field.valueSource = binding.valueSource();
        field.functionCode = binding.functionCode();
        field.argumentBindings = binding.argumentBindings();
        if (FunctionMetadataService.SOURCE_DATA.equals(binding.valueSource())) {
            boolean retained = existing != null && FunctionMetadataService.SOURCE_DATA.equals(existing.valueSource)
                    && blank(input.valueSource()) && blank(input.resolverCode());
            if (retained) {
                // 保留已有解析配置的前提下复核本次提交的类型和适用规则（例如把适用规则改成含 C2 就会被拦下）
                if (!rules.equals(List.of("C1"))) throw new IllegalArgumentException("数据解析字段目前只能适用于 C1，请先把适用规则改为 C1");
                if (!type.equals(existing.dataType)) throw new IllegalArgumentException("修改数据解析字段的类型时，请同时填写取值来源和字段解析器");
            }
            field.resolverCode = retained ? existing.resolverCode : resolverCode;
            field.elementType = retained ? existing.elementType : elementType;
            field.executionScope = retained ? existing.executionScope : scope;
            field.cachePolicy = retained ? existing.cachePolicy : cache;
        }
        return field;
    }

    private List<String> csv(String value) {
        if (value == null) return List.of();
        List<String> result = Arrays.stream(value.split(",")).map(String::trim).filter(x -> !x.isEmpty()).distinct().toList();
        if (result.size() > 12) throw new IllegalArgumentException("选项数量过多");
        return result;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String serialize(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("审计序列化失败", e);
        }
    }

    private void writeAudit(String code, String action, String before, Object after) {
        audit.save(new RuleAudit("FIELD_METADATA", code, action, "demo.operator", before, serialize(after)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
