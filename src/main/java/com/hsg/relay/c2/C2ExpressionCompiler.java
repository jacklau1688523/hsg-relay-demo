package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Compiles only validated, field-catalog-backed boolean ASTs; no arbitrary script input.
 */
@Service
public class C2ExpressionCompiler {
    private final C2FieldCatalog catalog;
    private final AviatorEvaluatorInstance aviator = AviatorEvaluator.newInstance();

    public C2ExpressionCompiler(C2FieldCatalog catalog) {
        this.catalog = catalog;
    }

    public String compile(JsonNode tree) {
        int[] count = {0};
        Map<String, C2FieldCatalog.Field> whitelist = new HashMap<>();
        for (C2FieldCatalog.Field f : catalog.all()) whitelist.put(f.fieldCode(), f);
        return compileNode(tree, 0, count, whitelist);
    }

    public boolean evaluate(JsonNode tree, Map<String, Object> values) {
        String expression = compile(tree);
        Map<String, Object> clean = new HashMap<>();
        for (C2FieldCatalog.Field f : catalog.all()) {
            Object value = values.get(f.fieldCode());
            clean.put(f.fieldCode(), value == null ? "" : String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        }
        Expression compiled = aviator.compile(expression, true);
        Object result = compiled.execute(clean);
        if (!(result instanceof Boolean flag)) throw new IllegalStateException("C2 条件表达式未返回布尔值");
        return flag;
    }

    private String compileNode(JsonNode node, int depth, int[] total, Map<String, C2FieldCatalog.Field> whitelist) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("条件节点必须为对象");
        if (depth > 6 || ++total[0] > 60) throw new IllegalArgumentException("条件组最多6层，节点总数最多60个");
        if (node.has("children")) {
            JsonNode children = node.get("children");
            String logic = node.path("operator").asText();
            if (!List.of("AND", "OR").contains(logic)) throw new IllegalArgumentException("条件组必须是 AND 或 OR");
            if (!children.isArray() || children.isEmpty() || children.size() > 20)
                throw new IllegalArgumentException("条件组需要1~20个子节点");
            List<String> parts = new ArrayList<>();
            for (JsonNode child : children) parts.add(compileNode(child, depth + 1, total, whitelist));
            return "(" + String.join("AND".equals(logic) ? " && " : " || ", parts) + ")";
        }
        String field = node.path("field").asText();
        String op = node.path("operator").asText();
        C2FieldCatalog.Field def = whitelist.get(field);
        if (def == null) throw new IllegalArgumentException("C2 字段库中未启用的字段: " + field);
        if (!def.operators().contains(op)) throw new IllegalArgumentException(field + " 不支持 " + op);
        JsonNode value = node.get("value");
        if ("IN".equals(op)) {
            if (value == null || !value.isArray() || value.isEmpty() || value.size() > 40)
                throw new IllegalArgumentException("IN 必须为1~40个值的数组");
            List<String> parts = new ArrayList<>();
            for (JsonNode item : value) parts.add("(" + field + " == " + literal(item, def) + ")");
            return "(" + String.join(" || ", parts) + ")";
        }
        String symbol = switch (op) {
            case "EQ" -> "==";
            case "NE" -> "!=";
            default -> throw new IllegalArgumentException("不支持的运算符");
        };
        return "(" + field + " " + symbol + " " + literal(value, def) + ("NE".equals(op) ? " && " + field + " != ''" : "") + ")";
    }

    private String literal(JsonNode value, C2FieldCatalog.Field def) {
        if (value == null || !value.isTextual() || value.asText().isBlank() || value.asText().length() > 80)
            throw new IllegalArgumentException("条件值必须为非空文本（最多80字符）");
        String upper = value.asText().trim().toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z0-9_\\-]{1,80}"))
            throw new IllegalArgumentException("条件值只能使用大写编码、数字、下划线和短横线");
        if (!def.options().isEmpty() && !def.options().contains(upper))
            throw new IllegalArgumentException(def.fieldCode() + " 不支持该值：" + upper + "；请在字段库增加后重试");
        return "'" + upper + "'";
    }
}
