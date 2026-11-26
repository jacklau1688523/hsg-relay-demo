package com.hsg.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.C1ConditionConfig;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.C1ConditionConfigRepository;
import org.springframework.stereotype.Service;

import java.util.*;

/** 执行 C1 页面配置的全局准入表达式：满足才继续后续准入流程。 */
@Service
public class C1ConditionService {
    private final C1ConditionConfigRepository repository;
    private final ExpressionService expressions;
    private final ObjectMapper json;

    public C1ConditionService(C1ConditionConfigRepository repository, ExpressionService expressions, ObjectMapper json) {
        this.repository = repository;
        this.expressions = expressions;
        this.json = json;
    }

    public record Input(Boolean enabled, ExpressionService.Node tree, Integer versionNo) {}
    public record Outcome(boolean configured, boolean matched, String expression, String note) {}

    public C1ConditionConfig get() {
        return repository.findById(1L).orElseGet(C1ConditionConfig::new);
    }

    public String compile(Input input) {
        if (input == null || input.tree() == null) throw new IllegalArgumentException("C1 过滤条件树不能为空");
        return expressions.compile(input.tree(), "C1");
    }

    public C1ConditionConfig save(Input input) {
        if (input == null) throw new IllegalArgumentException("C1 过滤条件不能为空");
        C1ConditionConfig row = get();
        if (input.versionNo() != null && input.versionNo() != row.versionNo)
            throw new IllegalArgumentException("C1 过滤条件已更新，请刷新后重试");
        if (Boolean.FALSE.equals(input.enabled())) {
            row.conditionJson = null;
        } else {
            compile(input);
            try { row.conditionJson = json.writeValueAsString(input.tree()); }
            catch (Exception ex) { throw new IllegalArgumentException("C1 过滤条件树无效", ex); }
            if (row.conditionJson.length() > 12000) throw new IllegalArgumentException("C1 过滤条件树过长");
        }
        row.versionNo++;
        return repository.save(row);
    }

    /** 条件树的结构摘要（不取值、不执行）：函数编码、取值来自字段解析器的字段及其解析器。 */
    public record Summary(List<String> functionCodes, Map<String, String> resolvedFields) {
        public static final Summary EMPTY = new Summary(List.of(), Map.of());
    }

    /**
     * 当前保存的条件树引用了哪些函数、哪些数据解析字段（集合字段条件，以及函数实参里以 FIELD 方式绑定的字段）。
     * 只看结构，不执行：客户排除必检先于条件树给出 CLIENT_EXCLUDED 时，审计里仍能记录条件树引用的函数与字段。
     */
    public Summary summary() {
        C1ConditionConfig config = get();
        if (config.conditionJson == null || config.conditionJson.isBlank()) return Summary.EMPTY;
        ExpressionService.Node tree;
        try {
            tree = json.readValue(config.conditionJson, ExpressionService.Node.class);
        } catch (Exception ex) {
            throw new IllegalStateException("C1 全局过滤条件已失效：" + ex.getMessage(), ex);
        }
        List<String> functionCodes = new ArrayList<>();
        Map<String, String> resolvedFields = new LinkedHashMap<>();
        summarize(tree, functionCodes, resolvedFields);
        return new Summary(List.copyOf(functionCodes), Collections.unmodifiableMap(resolvedFields));
    }

    private void summarize(ExpressionService.Node node, List<String> functionCodes, Map<String, String> resolvedFields) {
        if (node == null) return;
        if (node.isFunction()) {
            functionCodes.add(node.functionCode());
            for (Object argument : node.arguments() == null ? List.of() : node.arguments().values())
                    if (argument instanceof Map<?, ?> mapping && "FIELD".equalsIgnoreCase(String.valueOf(mapping.get("source"))))
                        resolverOf(String.valueOf(mapping.get("fieldCode")), resolvedFields);
            return;
        }
        if (node.children() != null)
            for (ExpressionService.Node child : node.children()) summarize(child, functionCodes, resolvedFields);
        if (node.field() != null && expressions.isDataResolved(node.field())) resolverOf(node.field(), resolvedFields);
    }

    private void resolverOf(String fieldCode, Map<String, String> resolvedFields) {
        FieldDefinition field = expressions.field(fieldCode);
        resolvedFields.put(fieldCode, field == null ? null : field.resolverCode);
    }

    public Outcome evaluate(Map<String, Object> variables, RuleExecutionContext context) {
        C1ConditionConfig config = get();
        if (config.conditionJson == null || config.conditionJson.isBlank())
            return new Outcome(false, false, null, "未配置全局过滤条件");
        try {
            ExpressionService.Node tree = json.readValue(config.conditionJson, ExpressionService.Node.class);
            ExpressionService.Compiled compiled = expressions.compileDetailed(tree, "C1");
            // 函数绑定字段、数据解析字段（如集合字段）的取值来自规则执行，不算缺值
            List<String> missing = expressions.referencedFields(tree).stream()
                    .filter(expressions::requiresInput)
                    .filter(field -> variables.get(field) == null || variables.get(field).toString().isBlank()).toList();
            if (!missing.isEmpty())
                return new Outcome(true, false, compiled.readable(), "字段 " + String.join("、", missing) + " 没有取值，准入条件不满足");
            boolean matched = expressions.evaluate(compiled, variables, context, "C1 全局准入条件").matched();
            return new Outcome(true, matched, compiled.readable(), matched ? "准入条件满足" : "准入条件不满足");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("C1 全局过滤条件已失效：" + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("C1 全局过滤条件执行失败：" + ex.getMessage(), ex);
        }
    }
}
