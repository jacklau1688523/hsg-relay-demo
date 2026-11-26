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
import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExpressionService {
    /** FUNCTION 节点的返回值在表达式里用这个前缀的变量承载；字段编码必须以字母开头，所以不会与字段重名。 */
    private static final String FUNCTION_VARIABLE = "__fn";
    /** 求值过程日志：DEBUG 级别，每次 {@link #evaluate(Compiled, Map, RuleExecutionContext, String)} 输出一个多行块；开关见 application.yml 的 logging.level。 */
    private static final Logger log = LoggerFactory.getLogger(ExpressionService.class);

    private final FieldDefinitionRepository fields;
    private final FunctionExecutionService functions;
    private final FieldResolver fieldResolver;
    private final FieldValueService fieldValues;
    private final AviatorEvaluatorInstance engine = AviatorEvaluator.newInstance();

    public ExpressionService(FieldDefinitionRepository fields, FunctionExecutionService functions, FieldResolver fieldResolver,
                             FieldValueService fieldValues) {
        this.fields = fields;
        this.functions = functions;
        this.fieldResolver = fieldResolver;
        this.fieldValues = fieldValues;
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
     * functions 是待求值的函数节点；boundFields 是条件里引用到的函数绑定字段（已在编译期按函数库和白名单校验）；
     * ruleCode 是编译时传入的规则编码；leaves 是按出现顺序排列的叶子条件，求值日志用它逐项给出真假；
     * resolvedFields 是条件里引用到的数据解析字段（取值来自字段解析器）。
     */
    public record Compiled(String expression, String readable, List<FunctionSlot> functions,
                           Map<String, FunctionExecutionService.Validated> boundFields, String ruleCode, List<Leaf> leaves,
                           Set<String> resolvedFields) {
        /** 是否需要 C1 执行上下文才能求值（含函数节点、函数绑定字段或数据解析字段）。 */
        public boolean needsContext() {
            return !functions.isEmpty() || !boundFields.isEmpty() || !resolvedFields.isEmpty();
        }
    }

    /** 叶子条件的两种写法：expression 可执行（函数返回值以 __fnN 变量承载）；readable 供展示（函数写成带参数的调用）。 */
    public record Leaf(String expression, String readable) {
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
        Set<String> resolvedFields = new LinkedHashSet<>();
        List<Leaf> leaves = new ArrayList<>();
        Text text = compile(tree, 0, ruleCode, slots, boundFields, resolvedFields, leaves);
        return new Compiled(text.expression(), text.readable(), List.copyOf(slots), Collections.unmodifiableMap(boundFields),
                ruleCode, List.copyOf(leaves), Set.copyOf(resolvedFields));
    }

    /** 条件树里叶子条件引用的全部字段编码，按出现顺序去重（函数节点不计）。 */
    public Set<String> referencedFields(Node tree) {
        Set<String> result = new LinkedHashSet<>();
        collect(tree, result);
        return result;
    }

    /** 字段定义（不存在时为 null）。 */
    public FieldDefinition field(String fieldCode) {
        return fieldCode == null ? null : fields.findById(fieldCode).orElse(null);
    }

    /** 字段是否绑定了业务函数（取值由函数计算，不需要请求提供）。 */
    public boolean isFunctionBound(String fieldCode) {
        return fieldResolver.isFunctionBound(fields.findById(fieldCode == null ? "" : fieldCode).orElse(null));
    }

    /** 字段是否由字段解析器取数（valueSource = DATA_RESOLVER，如集合字段）。 */
    public boolean isDataResolved(String fieldCode) {
        return FieldResolver.isDataResolved(fields.findById(fieldCode == null ? "" : fieldCode).orElse(null));
    }

    /**
     * 字段是否需要请求提供取值：函数绑定字段和数据解析字段的取值来自规则执行，不算缺值；
     * 执行器用它判断条件引用的字段在本次请求里是否齐全。
     */
    public boolean requiresInput(String fieldCode) {
        return !isFunctionBound(fieldCode) && !isDataResolved(fieldCode);
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
     * 求值。带 RuleExecutionContext 时（C1 执行器）可以求值 FUNCTION 节点、函数绑定字段和数据解析字段：
     * 函数返回值和字段解析结果先算好，再由同一个受控表达式完成比较。
     */
    public PreviewResult preview(Preview req, RuleExecutionContext context) {
        if (req.tree() == null || req.variables() == null) throw new IllegalArgumentException("tree / variables 必填");
        if (req.ruleCode() != null && !req.ruleCode().matches("C([1-9]|10)"))
            throw new IllegalArgumentException("无效规则范围");
        return evaluate(compileDetailed(req.tree(), req.ruleCode()), req.variables(), context);
    }

    /** 对已编译的条件求值：先执行函数节点和函数绑定字段，把返回值代入表达式。 */
    public PreviewResult evaluate(Compiled compiled, Map<String, Object> variables, RuleExecutionContext context) {
        return evaluate(compiled, variables, context, null);
    }

    /**
     * 同上。owner 说明这次求值来自哪里（如「C1 全局准入条件」「函数试算 clientExcluded」），只用于日志。
     * <p>求值过程以 DEBUG 级别输出（logger {@code com.hsg.relay.service.ExpressionService}，开关见 application.yml），每次求值一个多行块：
     * 表达式 → 上下文数据（CANDIDATE / TRANSACTION / CONTEXT 三组）→ 函数调用 → 代入表达式的变量及来源 → 每个叶子条件的真假 → 结果。
     * DEBUG 关闭时不创建记录，也不做任何额外计算。
     */
    public PreviewResult evaluate(Compiled compiled, Map<String, Object> variables, RuleExecutionContext context, String owner) {
        if (compiled.needsContext() && context == null) {
            // 两类字段都有的条件按先查到的类型报错；消息与升级前一致
            if (!compiled.boundFields().isEmpty())
                throw new IllegalArgumentException("条件引用了函数绑定字段 " + String.join("、", compiled.boundFields().keySet()) + "，只能在规则执行中取值");
            if (!compiled.resolvedFields().isEmpty())
                throw new IllegalArgumentException("条件引用了数据解析字段 " + String.join("、", compiled.resolvedFields()) + "，只能在规则执行中取值");
            throw new IllegalArgumentException("条件引用了业务函数，只能在规则执行中求值");
        }
        Expression exp = engine.compile(compiled.expression(), false);
        List<String> variableNames = exp.getVariableNames();
        Set<String> referenced = new HashSet<>(variableNames == null ? List.of() : variableNames);
        Trace trace = log.isDebugEnabled() ? new Trace(compiled, owner, context) : null;
        long start = trace == null ? 0 : System.nanoTime();
        Map<String, Object> vars = new HashMap<>();
        try {
            for (FieldDefinition field : fields.findAll()) {
                FunctionExecutionService.Validated bound = compiled.boundFields().get(field.fieldCode);
                if (bound != null) {
                    // 绑定了函数的字段取值来自函数，请求里提交的同名字段取值不采用
                    vars.put(field.fieldCode, functionValue(invoke(bound, context, trace, field.fieldCode).value()));
                } else if (FieldResolver.isDataResolved(field)) {
                    // 数据解析字段：取值来自字段解析器（本次求值内同一字段只解析一次），请求里提交的同名字段取值不采用；
                    // 只在表达式引用到时才解析
                    if (referenced.contains(field.fieldCode))
                        vars.put(field.fieldCode, setValue(fieldValues.value(field, context)));
                } else if (variables.containsKey(field.fieldCode)) {
                    vars.put(field.fieldCode, fieldResolver.variable(field, variables.get(field.fieldCode)));
                }
            }
            for (FunctionSlot slot : compiled.functions())
                vars.put(slot.variable(), functionValue(invoke(slot.function(), context, trace, slot.variable()).value()));
            Object value = exp.execute(vars);
            PreviewResult result = new PreviewResult(compiled.readable(), Boolean.TRUE.equals(value));
            if (trace != null) trace.finished(variableNames, vars, result.matched(), null);
            return result;
        } catch (RuntimeException ex) {
            if (trace != null) trace.finished(variableNames, vars, null, ex);
            throw ex;
        } finally {
            if (trace != null) trace.write(elapsedMillis(start));
        }
    }

    /** 函数返回值代入表达式前的整理：时间转成 ISO 字符串，与条件值的写法一致。 */
    private Object functionValue(Object value) {
        return value instanceof LocalDateTime time ? time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : value;
    }

    /** 集合字段条件代入表达式的值：Aviator 的 include 按元素判断；成员查询模式（未全量加载）的集合在这里报错，不会隐式全量加载。 */
    private static Object setValue(Object value) {
        return value instanceof RuleCollection collection ? collection.elements() : value;
    }

    private Text compile(Node n, int depth, String ruleCode, List<FunctionSlot> slots,
                         Map<String, FunctionExecutionService.Validated> boundFields, Set<String> resolvedFields, List<Leaf> leaves) {
        if (depth > 6) throw new IllegalArgumentException("最多允许 6 层条件组");
        if (n.isFunction()) {
            if (n.children() != null && !n.children().isEmpty()) throw new IllegalArgumentException("函数节点不能包含子条件");
            return compileFunction(n, ruleCode, slots, leaves);
        }
        if (n.children() != null && !n.children().isEmpty()) {
            if (n.children().size() > 20) throw new IllegalArgumentException("单组条件数不可超过20");
            if (!"AND".equals(n.logic()) && !"OR".equals(n.logic()))
                throw new IllegalArgumentException("条件组只允许 AND/OR");
            String delimiter = "AND".equals(n.logic()) ? " && " : " || ";
            List<Text> parts = n.children().stream().map(x -> compile(x, depth + 1, ruleCode, slots, boundFields, resolvedFields, leaves)).toList();
            return new Text("(" + String.join(delimiter, parts.stream().map(Text::expression).toList()) + ")",
                    "(" + String.join(delimiter, parts.stream().map(Text::readable).toList()) + ")");
        }
        FieldDefinition f = fields.findById(n.field() == null ? "" : n.field()).orElseThrow(() -> new IllegalArgumentException("非法字段: " + n.field()));
        if (!f.enabled || !Arrays.asList(f.allowedOperators.split(",")).contains(n.operator()) || (ruleCode != null && !Arrays.asList(f.allowedRules.split(",")).contains(ruleCode)))
            throw new IllegalArgumentException("字段 " + f.fieldCode + " 不适用于当前规则或不支持该操作符");
        if (n.value() == null) throw new IllegalArgumentException("条件值不可为空");
        // 绑定了函数的字段：保存 / 编译时校验函数与参数绑定（函数停用、绑定失效都在这里报出），运行时取值来自函数
        if (fieldResolver.isFunctionBound(f)) boundFields.putIfAbsent(f.fieldCode, boundFunction(f, ruleCode));
        // 数据解析字段：运行时取值来自字段解析器；集合类型字段的条件是「集合是否包含这个元素」（元素为条件值，写死在条件里；
        // 元素来自交易或其它字段时用函数条件 collectionContains）
        if (FieldResolver.isDataResolved(f)) resolvedFields.add(f.fieldCode);
        if (FieldValueService.isSet(f.dataType)) return compileSetLeaf(n, f, leaves);
        String literal = literal("字段 " + f.fieldCode, f.dataType, n.value());
        if ("IN".equals(n.operator())) {
            if (List.of("DECIMAL", "INTEGER", "BOOLEAN").contains(f.dataType))
                throw new IllegalArgumentException("该类型不支持 IN");
            String[] parts = n.value().toString().split(",");
            if (parts.length == 0 || parts.length > 40) throw new IllegalArgumentException("IN 只允许1~40个值");
            for (String part : parts) checkOption(f, part.trim());
            String text = "(" + String.join(" || ", Arrays.stream(parts).map(x -> "(" + f.fieldCode + " == '" + x.trim().replace("\\", "\\\\").replace("'", "\\'") + "')").toList()) + ")";
            return leaf(text, leaves);
        }
        checkOption(f, n.value().toString());
        String text = "(" + f.fieldCode + " " + symbol(n.operator()) + " " + literal + ")";
        return leaf(text, leaves);
    }

    /** 集合字段条件：CONTAINS / NOT_CONTAINS，条件值是要判断的单个元素；用 Aviator 的 include 做成员判断，与 collectionContains 语义一致。 */
    private static Text compileSetLeaf(Node n, FieldDefinition f, List<Leaf> leaves) {
        String owner = "字段 " + f.fieldCode;
        String element = n.value() == null ? "" : n.value().toString().trim();
        if (element.isEmpty() || element.length() > 80)
            throw new IllegalArgumentException(owner + " 的条件值须为 1~80 位的集合元素");
        String call = "include(" + f.fieldCode + ", '" + element.replace("\\", "\\\\").replace("'", "\\'") + "')";
        return leaf("CONTAINS".equals(n.operator()) ? "(" + call + ")" : "(!" + call + ")", leaves);
    }

    /** 字段条件的两种写法相同；同时记入 leaves，供求值日志逐项给出真假。 */
    private static Text leaf(String text, List<Leaf> leaves) {
        leaves.add(new Leaf(text, text));
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
    private Text compileFunction(Node n, String ruleCode, List<FunctionSlot> slots, List<Leaf> leaves) {
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
        Text text = new Text("(" + variable + comparison, "(" + call + comparison);
        leaves.add(new Leaf(text.expression(), text.readable()));
        return text;
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

    // ---------- 求值过程日志（DEBUG） ----------

    /** 日志块里一行的缩进；叶子、路径、变量等条目再缩进一级。 */
    private static final String TRACE_LINE = "\n    ";
    private static final String TRACE_ITEM = "\n        ";

    /** 执行条件里引用的函数（函数节点或函数绑定字段）；trace 不为 null（DEBUG 开启）时把这次调用（成功或失败）记入求值过程。 */
    private FunctionExecutionService.Call invoke(FunctionExecutionService.Validated function, RuleExecutionContext context,
                                                 Trace trace, String target) {
        if (trace == null) return functions.invoke(function, context);
        try {
            FunctionExecutionService.Call call = functions.invoke(function, context);
            trace.invocations.add(new Invocation(target, function, call, null));
            return call;
        } catch (RuntimeException ex) {
            trace.invocations.add(new Invocation(target, function, null, ex));
            throw ex;
        }
    }

    /** 一次函数调用的过程记录：target 是取值目标（函数绑定字段的编码，或函数节点的 __fnN 变量）；call 与 failure 必有其一。 */
    private record Invocation(String target, FunctionExecutionService.Validated function,
                              FunctionExecutionService.Call call, RuntimeException failure) {
    }

    /**
     * 一次求值的过程记录。求值期间只收集原始数据（上下文、函数调用、代入变量、结果或异常），全部在 write 里整理并输出：
     * DEBUG 关闭时不会创建；整理出错只记一句提示，不影响求值结果。
     */
    private final class Trace {
        private final Compiled compiled;
        private final String owner;
        private final RuleExecutionContext context;
        private final List<Invocation> invocations = new ArrayList<>();
        private List<String> referenced = List.of();
        private Map<String, Object> vars = Map.of();
        private Boolean matched;
        private RuntimeException failure;

        private Trace(Compiled compiled, String owner, RuleExecutionContext context) {
            this.compiled = compiled;
            this.owner = owner;
            this.context = context;
        }

        /** 求值结束（无论成功还是失败）：表达式引用的变量、实际代入的取值、结果和失败原因。 */
        private void finished(List<String> referenced, Map<String, Object> vars, Boolean matched, RuntimeException failure) {
            this.referenced = referenced;
            this.vars = vars;
            this.matched = matched;
            this.failure = failure;
        }

        private void write(long elapsedMs) {
            try {
                log.debug("{}", render(elapsedMs));
            } catch (RuntimeException ex) {
                log.debug("条件求值过程（{}）记录失败：{}", owner, ex.toString());
            }
        }

        private String render(long elapsedMs) {
            StringBuilder out = new StringBuilder(512);
            out.append("条件求值｜");
            if (owner != null) out.append(owner).append("｜");
            out.append("规则 ").append(display(compiled.ruleCode()));
            out.append(TRACE_LINE).append("表达式：").append(compiled.readable());
            if (!compiled.expression().equals(compiled.readable()))
                out.append(TRACE_LINE).append("可执行表达式：").append(compiled.expression());
            appendContext(out);
            appendInvocations(out);
            appendResolutions(out);
            appendVariables(out);
            appendLeaves(out);
            out.append(TRACE_LINE).append("结果：");
            if (failure == null) out.append(Boolean.TRUE.equals(matched) ? "命中" : "未命中");
            else out.append("执行失败｜").append(describe(failure));
            return out.append("｜耗时 ").append(elapsedMs).append("ms").toString();
        }

        /** CANDIDATE / TRANSACTION / CONTEXT 三组受控路径的当前取值；没有执行上下文时说明只有请求变量参与。 */
        private void appendContext(StringBuilder out) {
            if (context == null) {
                out.append(TRACE_LINE).append("[执行上下文] 无（这次求值没有 RuleExecutionContext，只代入请求给出的变量）");
                return;
            }
            context.valuesByGroup().forEach((group, values) -> {
                out.append(TRACE_LINE).append('[').append(group.name()).append(' ').append(group.label()).append(']');
                values.forEach((path, value) -> out.append(TRACE_ITEM).append(path).append(" = ").append(display(value)));
            });
        }

        /** 本次求值执行的函数（函数绑定字段在前、函数节点在后，即执行顺序）：目标、函数与 Handler、入参、返回值、耗时或复用、数据来源、命中记录。 */
        private void appendInvocations(StringBuilder out) {
            out.append(TRACE_LINE).append("[函数调用]");
            if (invocations.isEmpty()) out.append(" 无");
            for (Invocation invocation : invocations) {
                FunctionExecutionService.Validated function = invocation.function();
                out.append(TRACE_ITEM).append(invocation.target()).append(" ← ").append(function.functionCode())
                        .append("（").append(display(function.functionName())).append("，Handler ").append(function.handler().handlerCode()).append("）");
                FunctionExecutionService.Call call = invocation.call();
                if (call == null) {
                    // 入参取值为空等在执行前就失败的情况没有调用记录：给出参数绑定和失败原因
                    List<String> bindings = new ArrayList<>();
                    function.bindings().forEach((parameter, binding) -> bindings.add(parameter + "=" + binding.text()));
                    out.append("｜参数绑定 ").append(String.join(", ", bindings)).append("｜执行失败：").append(describe(invocation.failure()));
                    continue;
                }
                out.append("｜入参 ").append(display(call.arguments())).append("｜返回 ").append(display(call.value()))
                        .append("｜").append(call.cached() ? "复用本次判定内的结果" : "耗时 " + call.durationMs() + "ms")
                        .append("｜数据来源 ").append(display(call.dataSource()));
                if (call.recordId() != null) out.append("｜命中记录 #").append(call.recordId());
            }
        }

        /** 本次求值解析过的数据字段（条件里用到、且取值来自字段解析器）：字段、解析器、耗时或复用。没有则为空。 */
        private void appendResolutions(StringBuilder out) {
            if (context == null || context.resolutions().isEmpty()) return;
            out.append(TRACE_LINE).append("[数据字段]");
            for (FieldValueService.Resolution resolution : context.resolutions())
                out.append(TRACE_ITEM).append(resolution.fieldCode()).append(" ← 解析器 ").append(resolution.resolverCode())
                        .append("｜").append(resolution.cached() ? "复用本次判定内的结果" : "耗时 " + resolution.durationMs() + "ms");
        }

        /**
         * 表达式引用的每个变量：代入的取值和来源。没有取值的变量点名说明——Aviator 对它按 nil 比较（== 得 false、不报错），
         * 条件「莫名其妙」不命中时先看这里。
         */
        private void appendVariables(StringBuilder out) {
            out.append(TRACE_LINE).append("[代入变量]");
            if (referenced.isEmpty()) out.append(" 无");
            Map<String, FunctionExecutionService.Validated> computed = new HashMap<>();
            compiled.boundFields().forEach((code, bound) -> computed.put(code, bound));
            for (FunctionSlot slot : compiled.functions()) computed.put(slot.variable(), slot.function());
            for (String name : referenced) {
                FunctionExecutionService.Validated function = computed.get(name);
                Object value = vars.get(name);
                out.append(TRACE_ITEM).append(name).append(" = ");
                if (value != null) out.append(display(value)).append("（").append(source(name, function)).append("）");
                else if (function != null) out.append("—（函数 ").append(function.functionCode()).append(" 调用失败，没有取到值）");
                else if (vars.containsKey(name)) out.append("—（请求取值为空）");
                else out.append("—（请求变量里没有提供取值，Aviator 按 nil 比较）");
            }
        }

        /** 变量取值的来源：函数绑定字段由函数计算，函数节点的返回值以 __fnN 变量代入，其余是请求取值。 */
        private String source(String name, FunctionExecutionService.Validated function) {
            if (function == null) return compiled.resolvedFields().contains(name) ? "字段解析器取值" : "请求取值";
            return "函数 " + function.functionCode() + (compiled.boundFields().containsKey(name) ? " 计算" : " 返回值");
        }

        /** 每个叶子条件单独求值的结果，只用于展示，不影响整体结果。 */
        private void appendLeaves(StringBuilder out) {
            out.append(TRACE_LINE).append("[逐项求值]");
            List<Leaf> leaves = compiled.leaves();
            for (int i = 0; i < leaves.size(); i++) {
                Leaf leaf = leaves.get(i);
                out.append(TRACE_ITEM).append(i + 1).append(". ").append(leaf.readable()).append(" → ").append(leafResult(leaf));
            }
        }

        /**
         * 单独求值一个叶子条件。它引用的变量没有取值时 Aviator 按 nil 比较（== 得 false），这里一并说明，
         * 因为这样的叶子会让条件不命中却不报错。
         */
        private String leafResult(Leaf leaf) {
            try {
                Expression leafExpression = engine.compile(leaf.expression(), false);
                String unset = leafExpression.getVariableNames().stream().filter(name -> vars.get(name) == null).collect(Collectors.joining("、"));
                String result = Boolean.TRUE.equals(leafExpression.execute(vars)) ? "true" : "false";
                return unset.isEmpty() ? result : result + "（" + unset + " 没有取值，按 nil 比较）";
            } catch (RuntimeException ex) {
                return "无法单独求值：" + describe(ex);
            }
        }
    }

    /** 日志里的取值展示：空值为 —，空串为 ''，时间精确到毫秒，数字不用科学计数法，Map（函数入参）逐项展开。 */
    private static String display(Object value) {
        if (value == null) return "—";
        if (value instanceof LocalDateTime time) return time.truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().map(entry -> entry.getKey() + "=" + display(entry.getValue())).collect(Collectors.joining(", ", "{", "}"));
        // 集合只给出元素个数和前几个元素，不输出完整名单
        if (value instanceof RuleCollection collection) return collection.summary();
        if (value instanceof Set<?> set) return "集合（" + set.size() + " 个元素）" + (set.isEmpty() ? "" : "：" + set.stream().limit(5).map(String::valueOf).collect(Collectors.joining(", ")) + (set.size() > 5 ? " …" : ""));
        if (value instanceof String text) return text.isBlank() ? "''" : text;
        return String.valueOf(value);
    }

    /** 日志里的异常说明：类名 + message。 */
    private static String describe(Throwable ex) {
        return ex == null ? "—" : ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : "：" + ex.getMessage());
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
