package com.hsg.relay.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.FieldResolver;
import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExpressionService {
    /** FUNCTION 节点的返回值在表达式里用这个前缀的变量承载；字段编码必须以字母开头，所以不会与字段重名。 */
    private static final String FUNCTION_VARIABLE = "__fn";

    private final FieldDefinitionRepository fields;
    private final FunctionExecutionService functions;
    private final FieldResolver fieldResolver;
    private final AviatorEvaluatorInstance engine = AviatorEvaluator.newInstance();

    public ExpressionService(FieldDefinitionRepository fields, FunctionExecutionService functions, FieldResolver fieldResolver) {
        this.fields = fields;
        this.functions = functions;
        this.fieldResolver = fieldResolver;
    }

    /**
     * 条件树节点。三种类型：GROUP（logic + children）、FIELD（field + operator + value）、
     * FUNCTION（functionCode + arguments + operator + value）。
     * 旧版保存的 JSON 没有 type：有 children 的是条件组，否则是字段条件，照常执行。
     */
    public record Node(String type, String logic, List<Node> children, String field, String operator, Object value,
                       String functionCode, Map<String, Object> arguments) {
        @JsonCreator
        public Node {
            if (type == null) type = children != null && !children.isEmpty() ? "GROUP" : functionCode == null ? "FIELD" : "FUNCTION";
        }

        /** 旧版五参构造（字段条件 / 条件组），已有调用方和测试继续使用。 */
        public Node(String logic, List<Node> children, String field, String operator, Object value) {
            this(null, logic, children, field, operator, value, null, null);
        }

        /** 仅代码判断用，不作为 JSON 属性输出（条件树 JSON 只含节点自身的字段）。 */
        @JsonIgnore
        public boolean isFunction() {
            return "FUNCTION".equals(type);
        }
    }

    public record Preview(Node tree, Map<String, Object> variables, String ruleCode) {
    }

    public record PreviewResult(String expression, boolean matched) {
    }

    /**
     * 编译结果：expression 是可执行的受控表达式（函数返回值以 __fnN 变量承载）；readable 是页面展示用的可读形式；
     * functions 是待求值的函数节点；boundFields 是条件里引用到的函数绑定字段（已在编译期按函数库和白名单校验）。
     */
    public record Compiled(String expression, String readable, List<FunctionSlot> functions,
                           Map<String, FunctionExecutionService.Validated> boundFields) {
        /** 是否需要 C1 执行上下文才能求值（含函数节点或函数绑定字段）。 */
        public boolean needsContext() {
            return !functions.isEmpty() || !boundFields.isEmpty();
        }
    }

    public record FunctionSlot(String variable, FunctionExecutionService.Validated function) {
    }

    private record Text(String expression, String readable) {
    }

    /** 编译并校验条件树，返回可读表达式；函数节点和函数绑定字段在这里按函数库和白名单校验。 */
    public String compile(Node tree, String ruleCode) {
        return compileDetailed(tree, ruleCode).readable();
    }

    public Compiled compileDetailed(Node tree, String ruleCode) {
        if (tree == null) throw new IllegalArgumentException("条件树不能为空");
        List<FunctionSlot> slots = new ArrayList<>();
        Map<String, FunctionExecutionService.Validated> boundFields = new LinkedHashMap<>();
        Text text = compile(tree, 0, ruleCode, slots, boundFields);
        return new Compiled(text.expression(), text.readable(), List.copyOf(slots), Collections.unmodifiableMap(boundFields));
    }

    /** 条件树里叶子条件引用的全部字段编码，按出现顺序去重（函数节点不计）。 */
    public Set<String> referencedFields(Node tree) {
        Set<String> result = new LinkedHashSet<>();
        collect(tree, result);
        return result;
    }

    /** 字段是否绑定了业务函数（取值由函数计算，不需要请求提供）。 */
    public boolean isFunctionBound(String fieldCode) {
        return fieldResolver.isFunctionBound(fields.findById(fieldCode == null ? "" : fieldCode).orElse(null));
    }

    private void collect(Node node, Set<String> result) {
        if (node == null) return;
        if (node.children() != null && !node.children().isEmpty()) node.children().forEach(child -> collect(child, result));
        else if (node.field() != null) result.add(node.field());
    }

    public PreviewResult preview(Preview req) {
        return preview(req, null);
    }

    /**
     * 求值。带 RuleExecutionContext 时（C1 执行器）可以求值 FUNCTION 节点和函数绑定字段：
     * 函数返回值先算好，再由同一个受控表达式完成比较。
     */
    public PreviewResult preview(Preview req, RuleExecutionContext context) {
        if (req.tree() == null || req.variables() == null) throw new IllegalArgumentException("tree / variables 必填");
        if (req.ruleCode() != null && !req.ruleCode().matches("C([1-9]|10)"))
            throw new IllegalArgumentException("无效规则范围");
        return evaluate(compileDetailed(req.tree(), req.ruleCode()), req.variables(), context);
    }

    /** 对已编译的条件求值：先执行函数节点和函数绑定字段，把返回值代入表达式。 */
    public PreviewResult evaluate(Compiled compiled, Map<String, Object> variables, RuleExecutionContext context) {
        if (compiled.needsContext() && context == null)
            throw new IllegalArgumentException(compiled.boundFields().isEmpty()
                    ? "条件引用了业务函数，只能在规则执行中求值"
                    : "条件引用了函数绑定字段 " + String.join("、", compiled.boundFields().keySet()) + "，只能在规则执行中取值");
        Expression exp = engine.compile(compiled.expression(), false);
        Map<String, Object> vars = new HashMap<>();
        for (FieldDefinition field : fields.findAll()) {
            FunctionExecutionService.Validated bound = compiled.boundFields().get(field.fieldCode);
            if (bound != null) {
                // 绑定了函数的字段取值来自函数，请求里提交的同名字段取值不采用
                vars.put(field.fieldCode, functionValue(functions.invoke(bound, context).value()));
            } else if (variables.containsKey(field.fieldCode)) {
                vars.put(field.fieldCode, fieldResolver.variable(field, variables.get(field.fieldCode)));
            }
        }
        for (FunctionSlot slot : compiled.functions())
            vars.put(slot.variable(), functionValue(functions.invoke(slot.function(), context).value()));
        Object value = exp.execute(vars);
        return new PreviewResult(compiled.readable(), Boolean.TRUE.equals(value));
    }

    /** 函数返回值代入表达式前的整理：时间转成 ISO 字符串，与条件值的写法一致。 */
    private Object functionValue(Object value) {
        return value instanceof LocalDateTime time ? time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : value;
    }

    private Text compile(Node n, int depth, String ruleCode, List<FunctionSlot> slots,
                         Map<String, FunctionExecutionService.Validated> boundFields) {
        if (depth > 6) throw new IllegalArgumentException("最多允许 6 层条件组");
        if (n.isFunction()) {
            if (n.children() != null && !n.children().isEmpty()) throw new IllegalArgumentException("函数节点不能包含子条件");
            return compileFunction(n, ruleCode, slots);
        }
        if (n.children() != null && !n.children().isEmpty()) {
            if (n.children().size() > 20) throw new IllegalArgumentException("单组条件数不可超过20");
            if (!"AND".equals(n.logic()) && !"OR".equals(n.logic()))
                throw new IllegalArgumentException("条件组只允许 AND/OR");
            String delimiter = "AND".equals(n.logic()) ? " && " : " || ";
            List<Text> parts = n.children().stream().map(x -> compile(x, depth + 1, ruleCode, slots, boundFields)).toList();
            return new Text("(" + String.join(delimiter, parts.stream().map(Text::expression).toList()) + ")",
                    "(" + String.join(delimiter, parts.stream().map(Text::readable).toList()) + ")");
        }
        FieldDefinition f = fields.findById(n.field() == null ? "" : n.field()).orElseThrow(() -> new IllegalArgumentException("非法字段: " + n.field()));
        if (!f.enabled || !Arrays.asList(f.allowedOperators.split(",")).contains(n.operator()) || (ruleCode != null && !Arrays.asList(f.allowedRules.split(",")).contains(ruleCode)))
            throw new IllegalArgumentException("字段 " + f.fieldCode + " 不适用于当前规则或不支持该操作符");
        if (n.value() == null) throw new IllegalArgumentException("条件值不可为空");
        // 绑定了函数的字段：保存 / 编译时校验函数与参数绑定（函数停用、绑定失效都在这里报出），运行时取值来自函数
        if (fieldResolver.isFunctionBound(f)) boundFields.putIfAbsent(f.fieldCode, boundFunction(f, ruleCode));
        String literal = literal("字段 " + f.fieldCode, f.dataType, n.value());
        if ("IN".equals(n.operator())) {
            if (List.of("DECIMAL", "INTEGER", "BOOLEAN").contains(f.dataType))
                throw new IllegalArgumentException("该类型不支持 IN");
            String[] parts = n.value().toString().split(",");
            if (parts.length == 0 || parts.length > 40) throw new IllegalArgumentException("IN 只允许1~40个值");
            for (String part : parts) checkOption(f, part.trim());
            String text = "(" + String.join(" || ", Arrays.stream(parts).map(x -> "(" + f.fieldCode + " == '" + x.trim().replace("\\", "\\\\").replace("'", "\\'") + "')").toList()) + ")";
            return new Text(text, text);
        }
        checkOption(f, n.value().toString());
        String text = "(" + f.fieldCode + " " + symbol(n.operator()) + " " + literal + ")";
        return new Text(text, text);
    }

    /** 函数绑定字段：函数须可用，且返回类型与字段类型一致（枚举字段对应 STRING）。 */
    private FunctionExecutionService.Validated boundFunction(FieldDefinition field, String ruleCode) {
        // 先核对函数本身与返回类型，再校验参数绑定：类型不符时直接说明原因
        BusinessFunction handler = functions.handler(field.functionCode, ruleCode);
        String expected = FunctionMetadataService.expectedType(field.dataType);
        if (!expected.equals(handler.returnType()))
            throw new IllegalArgumentException("字段 " + field.fieldCode + " 绑定的函数 " + field.functionCode + " 返回类型为 "
                    + handler.returnType() + "，与字段类型 " + field.dataType + " 不一致");
        return functions.validate(field.functionCode, fieldResolver.bindings(field), ruleCode);
    }

    /** FUNCTION 叶子：按函数库 + 白名单校验，编译成「函数返回值变量 比较 期望值」。 */
    private Text compileFunction(Node n, String ruleCode, List<FunctionSlot> slots) {
        FunctionExecutionService.Validated validated = functions.validate(n.functionCode(), n.arguments(), ruleCode);
        String owner = "函数 " + n.functionCode();
        List<String> operators = FunctionMetadataService.operators(validated.returnType());
        if (n.operator() == null || !operators.contains(n.operator()))
            throw new IllegalArgumentException(owner + " 返回类型为 " + validated.returnType() + "，不支持运算符 " + n.operator() + "，可用：" + String.join("、", operators));
        String comparison = " " + symbol(n.operator()) + " " + literal(owner, validated.returnType(), n.value()) + ")";
        String variable = FUNCTION_VARIABLE + slots.size();
        slots.add(new FunctionSlot(variable, validated));
        String call = validated.functionCode() + "(" + validated.handler().parameters().stream()
                .map(p -> validated.bindings().get(p.code())).filter(Objects::nonNull)
                .map(b -> b.parameter() + "=" + b.text()).collect(Collectors.joining(", ")) + ")";
        return new Text("(" + variable + comparison, "(" + call + comparison);
    }

    private String literal(String owner, String dataType, Object raw) {
        if (raw == null) throw new IllegalArgumentException("条件值不可为空");
        if (List.of("DECIMAL", "INTEGER").contains(dataType)) {
            // 条件值为空或不是数字时给出带字段名的校验错误（空字符串的 NumberFormatException 没有 message，会变成 500）
            try {
                return new BigDecimal(raw.toString().trim()).toPlainString();
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(owner + " 的条件值不是有效数字：" + raw);
            }
        }
        if ("BOOLEAN".equals(dataType)) {
            if (raw instanceof Boolean flag) return flag ? "true" : "false";
            if (!List.of("true", "false").contains(raw.toString()))
                throw new IllegalArgumentException("布尔值只能为 true 或 false");
            return raw.toString();
        }
        if ("DATE".equals(dataType)) {
            try {
                return "'" + LocalDateTime.parse(raw.toString().trim()).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "'";
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(owner + " 的条件值不是有效的日期时间（应为 ISO 格式，如 2026-11-25T10:15:30）：" + raw);
            }
        }
        return "'" + raw.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private String symbol(String operator) {
        return switch (operator == null ? "" : operator) {
            case "EQ" -> "==";
            case "NE" -> "!=";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            default -> throw new IllegalArgumentException("不允许的运算符");
        };
    }

    private void checkOption(FieldDefinition field, String value) {
        if (field.valueOptions != null && !field.valueOptions.isBlank() && Arrays.stream(field.valueOptions.split(",")).map(String::trim).noneMatch(value::equals))
            throw new IllegalArgumentException(field.fieldCode + " 不支持该值：" + value);
    }
}
