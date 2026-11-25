package com.hsg.relay.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.repo.AppetiteRepository;
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
    private final RegulatoryRuleRepository regulatoryRules;
    private final C2ConditionConfigRepository c2Conditions;
    private final C3ConditionConfigRepository c3Conditions;

    public FieldMetadataController(FieldDefinitionRepository fields, RuleAuditRepository audit, ObjectMapper json,
                                   AppetiteRepository appetites, RegulatoryRuleRepository regulatoryRules,
                                   C2ConditionConfigRepository c2Conditions, C3ConditionConfigRepository c3Conditions) {
        this.fields = fields;
        this.audit = audit;
        this.json = json;
        this.appetites = appetites;
        this.regulatoryRules = regulatoryRules;
        this.c2Conditions = c2Conditions;
        this.c3Conditions = c3Conditions;
    }

    public record Input(String fieldCode, String fieldName, String dataType, String allowedOperators,
                        String allowedRules, String valueOptions, boolean enabled) {
    }

    @GetMapping
    public List<FieldDefinition> list() {
        return fields.findAll().stream().sorted(Comparator.comparing(f -> f.fieldCode)).toList();
    }

    @PostMapping
    @Transactional
    public FieldDefinition create(@RequestBody Input input) {
        FieldDefinition value = validate(input);
        if (fields.existsById(value.fieldCode)) throw new IllegalArgumentException("字段编码已存在");
        FieldDefinition saved = fields.save(value);
        writeAudit(saved.fieldCode, "CREATE", null, saved);
        return saved;
    }

    @PutMapping("/{code}")
    @Transactional
    public FieldDefinition update(@PathVariable String code, @RequestBody Input input) {
        FieldDefinition old = fields.findById(code).orElseThrow(() -> new IllegalArgumentException("字段不存在"));
        FieldDefinition value = validate(input);
        if (!code.equals(value.fieldCode))
            throw new IllegalArgumentException("字段编码不可修改；请新增字段并停用旧字段");
        String before = serialize(old);
        old.fieldName = value.fieldName;
        old.dataType = value.dataType;
        old.allowedOperators = value.allowedOperators;
        old.allowedRules = value.allowedRules;
        old.valueOptions = value.valueOptions;
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

    private FieldDefinition validate(Input input) {
        if (input == null) throw new IllegalArgumentException("字段信息不能为空");
        String code = trim(input.fieldCode()), name = trim(input.fieldName()), type = trim(input.dataType());
        if (!code.matches("[A-Za-z][A-Za-z0-9_]{0,63}"))
            throw new IllegalArgumentException("字段编码须以字母开头，仅含字母、数字、下划线，最多64位");
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("字段名称为必填项，最多80位");
        if (!List.of("STRING", "ENUM", "DECIMAL", "INTEGER", "BOOLEAN").contains(type))
            throw new IllegalArgumentException("不支持的数据类型");
        List<String> operators = csv(input.allowedOperators());
        List<String> rules = csv(input.allowedRules());
        List<String> allowed = List.of("DECIMAL", "INTEGER").contains(type) ? List.of("EQ", "NE", "GT", "GTE", "LT", "LTE") :
                "BOOLEAN".equals(type) ? List.of("EQ", "NE") : List.of("EQ", "NE", "IN");
        if (operators.isEmpty() || !allowed.containsAll(operators))
            throw new IllegalArgumentException("运算符与数据类型不匹配");
        if (rules.isEmpty() || rules.stream().anyMatch(x -> !x.matches("C([1-9]|10)")))
            throw new IllegalArgumentException("适用规则须选择 C1–C10");
        String values = trim(input.valueOptions());
        if (values.length() > 1000) throw new IllegalArgumentException("可选值长度不可超过1000位");
        if (!values.isEmpty() && List.of("DECIMAL", "INTEGER", "BOOLEAN").contains(type))
            throw new IllegalArgumentException("数字和布尔字段不使用枚举可选值");
        FieldDefinition field = new FieldDefinition(code, name, type, String.join(",", operators), String.join(",", rules), values);
        field.enabled = input.enabled();
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
