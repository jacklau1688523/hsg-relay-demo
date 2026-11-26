package com.hsg.relay.function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.Appetite;
import com.hsg.relay.domain.BusinessFunctionDefinition;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.domain.RuleAudit;
import com.hsg.relay.function.FieldResolver;
import com.hsg.relay.function.collection.CollectionFunction;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.ElementSchemaRegistry;
import com.hsg.relay.function.field.FieldResolverRegistry;
import com.hsg.relay.function.field.RuleList;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.BusinessFunctionRepository;
import com.hsg.relay.repo.C1ConditionConfigRepository;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.repo.RuleAuditRepository;
import com.hsg.relay.service.ExpressionService;
import jakarta.transaction.Transactional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * 函数库的页面数据与校验：
 * <ul>
 *   <li>使用策略（REQUIRED / OPTIONAL）只读自代码里实现了 {@link RequiredFunctionProvider} 的执行器，函数库元数据改不了它；</li>
 *   <li>「添加函数」选择器、系统必检清单和函数试算；</li>
 *   <li>字段 ↔ 函数绑定的校验；</li>
 *   <li>函数被哪些配置引用（删除前检查）。</li>
 * </ul>
 * 这里只做校验和只读查询，不执行函数；执行统一走 {@link FunctionExecutionService}。
 */
@Service
public class FunctionMetadataService {
    /** 已接入函数节点的规则：执行器提供受控取值路径，函数节点和函数绑定字段只能用于这些规则（见 {@link RuleExecutionContext#FUNCTION_RULES}）。 */
    public static final String BINDING_RULE = "C1";
    /** 可以使用函数节点、数据解析字段的规则（说明文字用）。 */
    private static final String FUNCTION_RULE_TEXT = String.join(" / ", RuleExecutionContext.FUNCTION_RULES.stream().sorted().toList());
    public static final String SOURCE_REQUEST = "REQUEST";
    public static final String SOURCE_FUNCTION = "FUNCTION";
    /** 取值来源为数据解析：由字段元数据的 resolverCode 对应的字段解析器取数（如集合字段 providerExcludedClientIds）。 */
    public static final String SOURCE_DATA = "DATA_RESOLVER";
    /** 内置字段「合作方排除客户」及其绑定的函数：启动时若该字段存在且从未配置取值来源，自动补上绑定（幂等）。 */
    public static final String BUILT_IN_EXCLUSION_FIELD = "clientProviderExcluded";
    public static final String BUILT_IN_EXCLUSION_FUNCTION = "clientExcluded";
    private static final Map<String, Object> BUILT_IN_EXCLUSION_BINDINGS = Map.of("providerCode", "$candidate.providerCode", "clientId", "$transaction.clientId");

    /** 参数声明（以 Java Handler 为准）：defaultBinding 为未配置时取的受控路径，label 为该路径的中文名。 */
    public record ParameterView(String code, String type, String source, boolean required, String defaultBinding, String label) {
    }

    /**
     * 「添加函数」选择器里的一个函数：名称 / 编码、返回类型、参数、数据来源、可用运算符、使用策略。
     * operators 为空表示返回值不能直接比较（集合），只能被后置函数引用；referable 表示输出可以被同一棵条件树里的后置函数引用。
     */
    public record FunctionView(String functionCode, String functionName, String category, String returnType, String dataSource,
                               String description, String usagePolicy, String requiredNote, List<ParameterView> parameters,
                               List<String> operators, Map<String, String> defaultArguments, Object defaultValue, boolean referable) {
    }

    /** 一棵条件树的试算结果：表达式、每个函数节点的调用明细（按执行顺序）、条件结果与异常（失败时 matched 为空）。 */
    public record Evaluation(String ruleCode, String expression, String readable, Boolean matched,
                             List<FunctionExecutionService.Detail> functions, String error) {
    }

    /**
     * 函数参数可以使用的取值来源（页面下拉用）。路径来源按分组写：CANDIDATE / TRANSACTION / CONTEXT（fieldCode 为去掉分组前缀的路径末段），
     * RULE_ROW 仅 C3 可用；CURRENT_ELEMENT 只能出现在谓词条件的比较值里。
     */
    public static final List<Map<String, String>> ARGUMENT_SOURCES = List.of(
            source("CANDIDATE", "候选合作方", "candidate.* 受控路径"),
            source("TRANSACTION", "交易要素", "transaction.* 受控路径"),
            source("CONTEXT", "决策上下文", "context.* 受控路径"),
            source("RULE_ROW", "当前覆盖行", "仅 C3：ruleRow.* 受控路径"),
            source("CONSTANT", "常量", "按参数类型校验的字面量"),
            source("FIELD", "数据字段", "元数据中心里取值来源为数据解析的字段"),
            source("FUNCTION_OUTPUT", "前置函数输出", "同一棵条件树里的其它函数节点"),
            source("PREDICATE", "条件", "集合类函数的筛选 / 匹配条件"));

    private static Map<String, String> source(String code, String label, String note) {
        return Map.of("source", code, "label", label, "note", note);
    }

    /** 系统必检清单的一项（只读，不可删除）。 */
    public record RequiredView(String functionCode, String functionName, String note, boolean implemented) {
    }

    /** 受控取值路径。 */
    public record PathView(String path, String source, String type, String label) {
    }

    public record Catalog(String ruleCode, List<PathView> paths, List<RequiredView> required, List<FunctionView> functions,
                          List<DataFieldView> dataFields, List<Map<String, Object>> elementSchemas) {
    }

    /** 可以绑定为函数参数（FIELD 来源）的数据字段：类型、元素类型、元素结构（OBJECT_LIST）、执行作用域。 */
    public record DataFieldView(String fieldCode, String fieldName, String dataType, String elementType, String elementSchema,
                                String executionScope) {
    }

    /**
     * 函数试算结果：入参、绑定、返回值、命中记录、数据来源、耗时、条件结果、异常；
     * 另带规则、状态（MATCHED / NOT_MATCHED / SUCCESS / NO_MATCH / FAILED）和逐项明细 detail。
     */
    public record FunctionSimulation(String functionCode, String functionName, String expression, Map<String, Object> arguments,
                                     Map<String, String> bindings, Object value, String recordId, String dataSource,
                                     long durationMs, Boolean matched, String error, String ruleCode, String status,
                                     FunctionExecutionService.Detail detail) {
    }

    /** 字段取值来源的规范化结果（valueSource 为 REQUEST 时后两项为空；三项全空表示请求没有表达取值来源，见 UNCHANGED）。 */
    public record FieldBinding(String valueSource, String functionCode, String argumentBindings) {
    }

    /** 请求完全没有表达取值来源（升级前的页面 / 脚本）：保持字段已有的取值配置，见 {@link #unchanged}。 */
    public static final FieldBinding UNCHANGED = new FieldBinding(null, null, null);

    private final BusinessFunctionRepository definitions;
    private final BusinessFunctionRegistry registry;
    private final List<RequiredFunctionProvider> executors;
    private final FunctionExecutionService execution;
    private final ExpressionService expressions;
    private final FieldDefinitionRepository fields;
    private final AppetiteRepository appetites;
    private final C1ConditionConfigRepository c1Conditions;
    private final RuleAuditRepository audit;
    private final ObjectMapper json;
    private final FieldResolverRegistry resolvers;
    private final ElementSchemaRegistry schemas;

    public FunctionMetadataService(BusinessFunctionRepository definitions, BusinessFunctionRegistry registry,
                                   List<RequiredFunctionProvider> executors, FunctionExecutionService execution,
                                   ExpressionService expressions, FieldDefinitionRepository fields,
                                   AppetiteRepository appetites, C1ConditionConfigRepository c1Conditions,
                                   RuleAuditRepository audit, ObjectMapper json, FieldResolverRegistry resolvers,
                                    ElementSchemaRegistry schemas) {
        this.definitions = definitions;
        this.registry = registry;
        this.executors = executors;
        this.execution = execution;
        this.expressions = expressions;
        this.fields = fields;
        this.appetites = appetites;
        this.c1Conditions = c1Conditions;
        this.audit = audit;
        this.json = json;
        this.resolvers = resolvers;
        this.schemas = schemas;
    }

    /** 已登记的元素结构（OBJECT_LIST 字段的 elementSchema 只能从这里选）。 */
    public List<Map<String, Object>> elementSchemas() {
        return schemas.all().stream().map(ElementSchema::view).toList();
    }

    /** 字段元数据保存时的校验：OBJECT_LIST 字段的 elementSchema 须已登记（页面不能新建结构）。 */
    public ElementSchema requireElementSchema(String code) {
        String schema = code == null ? "" : code.trim();
        if (schema.isEmpty()) return null;
        try {
            return schemas.require(schema);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("元素结构 " + schema + " 未登记，只能从已登记的结构里选择");
        }
    }

    // ---------- 使用策略 ----------

    /** 使用策略：执行器声明为系统必检的函数是 REQUIRED，其余为 OPTIONAL。只读自代码，不读函数库元数据。 */
    public String usagePolicy(String ruleCode, String functionCode) {
        return required(ruleCode).containsKey(functionCode) ? "REQUIRED" : "OPTIONAL";
    }

    public boolean isRequired(String functionCode) {
        return executors.stream().anyMatch(executor -> executor.requiredFunctions().containsKey(functionCode));
    }

    /** 某条规则的系统必检清单（函数编码 → 说明），顺序与执行器声明一致。 */
    public Map<String, String> required(String ruleCode) {
        Map<String, String> required = new LinkedHashMap<>();
        for (RequiredFunctionProvider executor : executors)
            if (executor.ruleCode().equals(ruleCode)) required.putAll(executor.requiredFunctions());
        return required;
    }

    public String requiredNote(String functionCode) {
        for (RequiredFunctionProvider executor : executors)
            if (executor.requiredFunctions().containsKey(functionCode)) return executor.requiredFunctions().get(functionCode);
        return null;
    }

    /** 代码里已注册的 Handler（白名单）。 */
    public List<String> registeredHandlers() {
        return registry.handlerCodes();
    }

    /** 函数能否用于 ruleCode 的条件：已启用、适用该规则、后端实现已在白名单注册且返回类型一致。 */
    public boolean isExecutable(String functionCode, String ruleCode) {
        if (functionCode == null) return false;
        return definitions.findById(functionCode)
                .filter(d -> d.enabled && rules(d.allowedRules).contains(ruleCode))
                .flatMap(d -> registry.find(d.handlerCode).map(h -> h.returnType().equals(d.returnType)))
                .orElse(false);
    }

    /** 函数库接口的返回值补上使用策略和实现状态（不落库）。 */
    public BusinessFunctionDefinition decorate(BusinessFunctionDefinition definition) {
        definition.usagePolicy = isRequired(definition.functionCode) ? "REQUIRED" : "OPTIONAL";
        definition.implemented = registry.find(definition.handlerCode).map(h -> h.returnType().equals(definition.returnType)).orElse(false);
        return definition;
    }

    // ---------- 页面数据 ----------

    /** 「添加函数」选择器的数据：受控路径、系统必检清单、当前规则可添加的函数。 */
    public Catalog available(String ruleCode) {
        if (!RuleExecutionContext.FUNCTION_RULES.contains(ruleCode == null ? "" : ruleCode))
            throw new IllegalArgumentException("目前只有 " + String.join(" / ", RuleExecutionContext.FUNCTION_RULES.stream().sorted().toList())
                    + " 的条件编辑器支持添加函数，不能用于 " + ruleCode);
        List<PathView> paths = RuleExecutionContext.paths(ruleCode).stream()
                .map(p -> new PathView(p.name(), p.group().name(), p.type(), p.label())).toList();
        List<RequiredView> required = required(ruleCode).entrySet().stream()
                .map(entry -> new RequiredView(entry.getKey(), functionName(entry.getKey()), entry.getValue(), isExecutable(entry.getKey(), ruleCode)))
                .toList();
        // 可添加的是「能直接比较」的函数，加上返回集合、供后置函数引用的函数；返回 OBJECT / MAP 的函数目前没有后置函数能接收，
        // 不进选择器（只能在函数库里试算）
        List<FunctionView> functions = definitions.findAll().stream()
                .filter(d -> isExecutable(d.functionCode, ruleCode))
                .filter(d -> registry.find(d.handlerCode).map(h -> !FunctionTypes.OBJECT.equals(h.returnType())
                        && !FunctionTypes.MAP.equals(h.returnType())).orElse(false))
                .sorted(Comparator.comparing(d -> d.functionCode))
                .map(d -> view(d, ruleCode))
                .toList();
        // 可以绑定为函数参数的数据字段（已启用、适用当前规则）
        List<DataFieldView> dataFields = fields.findAll().stream()
                .filter(f -> f.enabled && FieldResolver.isDataResolved(f) && rules(f.allowedRules).contains(ruleCode))
                .sorted(Comparator.comparing((FieldDefinition f) -> f.fieldCode))
                .map(f -> new DataFieldView(f.fieldCode, f.fieldName, f.dataType, f.elementType, f.elementSchema, f.executionScope))
                .toList();
        return new Catalog(ruleCode, paths, required, functions, dataFields, elementSchemas());
    }

    private String functionName(String functionCode) {
        return definitions.findById(functionCode).map(d -> d.functionName).orElse(functionCode);
    }

    private FunctionView view(BusinessFunctionDefinition d, String ruleCode) {
        BusinessFunction handler = registry.find(d.handlerCode).orElseThrow();
        Map<String, RuleExecutionContext.Path> paths = new LinkedHashMap<>();
        for (RuleExecutionContext.Path path : RuleExecutionContext.paths(ruleCode)) paths.put(path.name(), path);
        List<ParameterView> parameters = new ArrayList<>();
        Map<String, String> defaults = new LinkedHashMap<>();
        for (BusinessFunction.Parameter p : handler.parameters()) {
            // 默认绑定：Handler 声明的默认路径；否则取同来源、同类型且末段与参数同名的受控路径（providerCode → candidate.providerCode）
            String path = p.defaultPath() != null ? p.defaultPath() : paths.values().stream()
                    .filter(x -> x.group().name().equals(p.source()) && x.type().equals(p.type()) && x.name().endsWith("." + p.code()))
                    .map(RuleExecutionContext.Path::name).findFirst().orElse(null);
            String binding = path == null ? null : "$" + path;
            parameters.add(new ParameterView(p.code(), p.type(), p.source(), p.required(), binding, path == null ? null : paths.get(path).label()));
            if (binding != null) defaults.put(p.code(), binding);
        }
        return new FunctionView(d.functionCode, d.functionName, d.category, handler.returnType(), handler.dataSource(), d.description,
                usagePolicy(ruleCode, d.functionCode), requiredNote(d.functionCode), List.copyOf(parameters),
                operators(handler.returnType()), defaults, defaultValue(handler.returnType()),
                !FunctionTypes.OBJECT.equals(handler.returnType()) && !FunctionTypes.MAP.equals(handler.returnType()));
    }

    /** 返回类型允许的比较运算符；OBJECT 等类型不能直接比较，不开放。 */
    public static List<String> operators(String returnType) {
        return switch (returnType) {
            case "BOOLEAN", "STRING" -> List.of("EQ", "NE");
            case "DATE", "INTEGER", "DECIMAL" -> List.of("EQ", "NE", "GT", "GTE", "LT", "LTE");
            default -> List.of();
        };
    }

    private static Object defaultValue(String returnType) {
        return switch (returnType) {
            case "BOOLEAN" -> Boolean.FALSE;
            case "INTEGER", "DECIMAL" -> 0;
            default -> "";
        };
    }

    // ---------- 函数试算 ----------

    /** 单个函数试算时函数节点的 id（调用记录按它登记）。 */
    private static final String SIMULATE_NODE = "simulate";

    /**
     * 函数试算（C1）：用右侧模拟面板的要素（合作方、客户、交易要素）构造一次性的 C1 上下文，解析入参、调用函数并与期望值比较。
     * 参数 / 运算符 / 期望值不合法时抛参数错误；函数执行失败不抛出，在结果里用 error 说明（不会当成 false）。
     */
    public FunctionSimulation simulate(String functionCode, Map<String, Object> arguments, String operator, Object value, Map<String, Object> sample) {
        return simulate(functionCode, arguments, operator, value, sample, BINDING_RULE);
    }

    /**
     * 同上，按 ruleCode（C1 / C2 / C3 / C5 / C7 / C8）构造上下文。带运算符或期望值时做条件比较（matched 为真假）；
     * 都不带时只执行函数、给出返回值明细（返回集合 / 对象 / 分组等不能直接比较的函数用这种方式试算）。
     * 单个函数试算不能引用其它函数的输出（FUNCTION_OUTPUT），需要串联时用 {@link #evaluate} 试算整棵条件树。
     */
    public FunctionSimulation simulate(String functionCode, Map<String, Object> arguments, String operator, Object value,
                                       Map<String, Object> sample, String ruleCode) {
        if (functionCode == null || functionCode.isBlank()) throw new IllegalArgumentException("请选择要试算的函数");
        String rule = blank(ruleCode) ? BINDING_RULE : ruleCode.trim();
        if (!RuleExecutionContext.FUNCTION_RULES.contains(rule))
            throw new IllegalArgumentException("规则 " + rule + " 的条件不支持函数节点，不能试算");
        RuleExecutionContext context = sampleContext(rule, sample);
        boolean compared = !blank(operator) || value != null;
        FunctionExecutionService.Validated validated;
        String expression = null;
        Boolean matched = null;
        String error = null;
        if (compared) {
            ExpressionService.Node node = new ExpressionService.Node("FUNCTION", null, null, null, operator, value, functionCode, arguments);
            ExpressionService.Compiled compiled = expressions.compileDetailed(node, rule);
            validated = compiled.functions().get(0).function();
            expression = compiled.readable();
            try {
                matched = expressions.evaluate(compiled, Map.of(), context, "函数试算 " + functionCode).matched();
            } catch (RuntimeException ex) {
                error = reason(ex);
            }
        } else {
            validated = execution.validate(functionCode, arguments, rule).at(SIMULATE_NODE, null);
            try {
                execution.invoke(validated, context);
            } catch (RuntimeException ex) {
                error = reason(ex);
            }
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        validated.bindings().forEach((parameter, binding) -> bindings.put(parameter, binding.text()));
        FunctionExecutionService.Call call = context.callOf(validated.nodeId());
        if (call == null) call = new FunctionExecutionService.Call(validated.functionCode(), validated.handler().handlerCode(),
                Map.of(), null, null, validated.handler().dataSource(), 0, false, error == null ? "没有调用记录" : error);
        FunctionExecutionService.Detail detail = FunctionExecutionService.detail(validated, call);
        String status = status(context, validated.nodeId(), call, compared ? matched : null);
        return new FunctionSimulation(validated.functionCode(), validated.functionName(), expression, call.arguments(), bindings,
                call.value(), call.recordId(), call.dataSource(), call.durationMs(), matched, error, rule, status, detail);
    }

    /** 明细里的状态：detail 只分成功 / 失败，这里补上「没有满足条件的记录」。 */
    private static FunctionExecutionService.Detail withStatus(FunctionExecutionService.Detail detail, String status) {
        return new FunctionExecutionService.Detail(detail.functionCode(), detail.functionName(), detail.nodeId(), detail.handlerCode(),
                detail.returnType(), detail.arguments(), detail.value(), detail.recordId(), detail.dataSource(), detail.durationMs(),
                detail.cached(), status, detail.error());
    }

    /** 异常说明：message 为空时给出类名，不会丢掉失败原因。 */
    private static String reason(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /**
     * 一个函数节点的状态：调用失败时区分「没有满足条件的记录」（collectionFirstMatch 的受控结果，NO_MATCH）与其它失败（FAILED）；
     * 成功时按是否做了比较给出 MATCHED / NOT_MATCHED 或 SUCCESS。
     */
    private static String status(RuleExecutionContext context, String nodeId, FunctionExecutionService.Call call, Boolean matched) {
        if (call.error() == null) return matched == null ? "SUCCESS" : matched ? "MATCHED" : "NOT_MATCHED";
        Throwable failure = context.failureOf(nodeId);
        while (failure != null) {
            if (failure instanceof CollectionFunction.NoMatch) return "NO_MATCH";
            failure = failure.getCause();
        }
        return "FAILED";
    }

    /** 整棵条件树的试算：先按规则编译（函数、参数、引用、循环都在这里校验），再在一次性上下文里求值，逐个函数节点给出调用明细。 */
    public Evaluation evaluate(String ruleCode, ExpressionService.Node tree, Map<String, Object> sample) {
        String rule = blank(ruleCode) ? BINDING_RULE : ruleCode.trim();
        if (!RuleExecutionContext.FUNCTION_RULES.contains(rule))
            throw new IllegalArgumentException("规则 " + rule + " 的条件不支持函数节点，不能试算");
        if (tree == null) throw new IllegalArgumentException("条件树不能为空");
        ExpressionService.Compiled compiled = expressions.compileDetailed(tree, rule);
        RuleExecutionContext context = sampleContext(rule, sample);
        Boolean matched = null;
        String error = null;
        try {
            matched = expressions.evaluate(compiled, Map.of(), context, "条件试算 " + rule).matched();
        } catch (RuntimeException ex) {
            error = reason(ex);
        }
        List<FunctionExecutionService.Detail> details = new ArrayList<>();
        for (FunctionExecutionService.Validated node : compiled.nodes()) {
            FunctionExecutionService.Call call = context.callOf(node.nodeId());
            if (call != null) details.add(withStatus(FunctionExecutionService.detail(node, call),
                    status(context, node.nodeId(), call, null)));
        }
        return new Evaluation(rule, compiled.expression(), compiled.readable(), matched, List.copyOf(details), error);
    }

    private RuleExecutionContext sampleContext(Map<String, Object> sample) {
        Map<String, Object> input = sample == null ? Map.of() : sample;
        if (text(input, "providerCode").isEmpty() || text(input, "clientId").isEmpty())
            throw new IllegalArgumentException("试算需要合作方和客户");
        BigDecimal amount;
        try {
            amount = new BigDecimal(text(input, "amountUsd").isEmpty() ? "0" : text(input, "amountUsd"));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("本笔金额不是有效数字：" + input.get("amountUsd"));
        }
        RuleExecutionContext context = RuleExecutionContext.c1(text(input, "providerCode"), text(input, "clientId"),
                text(input, "paymentCurrency"), text(input, "beneficiaryBankCountry"), amount,
                LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")));
        if (!text(input, "flowCategory").isEmpty()) context.set("candidate.flowCategory", text(input, "flowCategory"));
        return context;
    }

    /**
     * C1 以外的规则：按该规则登记的受控路径，从试算要素里取同名项（providerCode → candidate.providerCode，
     * clientId → transaction.clientId 等）构造一次性上下文；没有提供的路径保持为空（引用它会报「没有取值」，不会当成空集合）。
     * C3 另可提供 ruleRow（当前覆盖行的属性）。
     */
    private RuleExecutionContext sampleContext(String ruleCode, Map<String, Object> sample) {
        if (BINDING_RULE.equals(ruleCode)) return sampleContext(sample);
        Map<String, Object> input = sample == null ? Map.of() : sample;
        if (text(input, "providerCode").isEmpty()) throw new IllegalArgumentException("试算需要合作方");
        Map<String, Object> values = new LinkedHashMap<>();
        for (RuleExecutionContext.Path path : RuleExecutionContext.paths(ruleCode)) {
            if (path.group() == RuleExecutionContext.Group.CONTEXT) continue;
            String key = path.name().substring(path.name().indexOf('.') + 1);
            if (!input.containsKey(key)) continue;
            try {
                values.put(path.name(), sampleValue(path, input.get(key)));
            } catch (IllegalArgumentException | java.time.DateTimeException ex) {
                throw new IllegalArgumentException("试算要素 " + key + " 无效：" + ex.getMessage());
            }
        }
        RuleExecutionContext context = RuleExecutionContext.of(ruleCode, values, LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")));
        if ("C3".equals(ruleCode) && input.get("ruleRow") instanceof Map<?, ?> row) {
            Map<String, Object> current = new LinkedHashMap<>();
            for (RuleExecutionContext.Path path : RuleExecutionContext.RULE_ROW_PATHS) {
                String attribute = path.name().substring("ruleRow.".length());
                current.put(attribute, row.containsKey(attribute) ? sampleValue(path, row.get(attribute)) : null);
            }
            context.ruleRow(current);
        }
        return context;
    }

    /** 试算要素按路径类型规整（字符串、整数、小数、日期、布尔、字符串清单）；空值返回 null。 */
    private static Object sampleValue(RuleExecutionContext.Path path, Object raw) {
        if (raw == null || raw instanceof String text && text.isBlank()) return null;
        String type = path.type();
        if ("STRING".equals(type)) return raw.toString().trim();
        if ("INTEGER".equals(type)) return raw instanceof Number number ? number.longValue() : Long.parseLong(raw.toString().trim());
        if ("DECIMAL".equals(type)) return raw instanceof BigDecimal decimal ? decimal : new BigDecimal(raw.toString().trim());
        if ("DATE".equals(type)) {
            String value = raw.toString().trim();
            return value.length() == 10 ? LocalDate.parse(value).atStartOfDay() : LocalDateTime.parse(value);
        }
        if ("BOOLEAN".equals(type)) return raw instanceof Boolean flag ? flag : Boolean.parseBoolean(raw.toString().trim());
        if (RuleList.STRING_LIST.equals(type)) {
            if (raw instanceof List<?> list) return list.stream().map(String::valueOf).toList();
            throw new IllegalArgumentException("须为字符串清单");
        }
        throw new IllegalArgumentException("不支持的类型 " + type);
    }

    private static String text(Map<String, Object> sample, String key) {
        Object value = sample.get(key);
        return value == null ? "" : value.toString().trim();
    }

    // ---------- 字段 ↔ 函数绑定 ----------

    /**
     * 校验并规范化字段的取值来源。valueSource 为 FUNCTION 时：字段只能适用于已接入函数节点的规则（{@link RuleExecutionContext#FUNCTION_RULES}），函数须可用于这些规则（已启用、Handler 已注册），
     * 返回类型与字段类型一致，参数绑定合法；为 REQUEST 时不能带函数。
     * 请求完全没有表达取值来源（升级前的页面 / 脚本）时返回 {@link #UNCHANGED}，由调用方保持字段已有的配置。
     */
    public FieldBinding binding(String fieldCode, String dataType, String allowedRules, String valueSource, String functionCode, JsonNode argumentBindings) {
        return binding(fieldCode, dataType, allowedRules, valueSource, functionCode, argumentBindings, null, null, null);
    }

    /**
     * 同上，并处理取值来源为数据解析（DATA_RESOLVER）的字段：只能适用于已接入函数节点的规则，须指定已注册的字段解析器 resolverCode，
     * 不能同时绑定函数；executionScope / cachePolicy 须为受控取值。
     */
    public FieldBinding binding(String fieldCode, String dataType, String allowedRules, String valueSource, String functionCode, JsonNode argumentBindings,
                                String resolverCode, String executionScope, String cachePolicy) {
        if (blank(valueSource) && blank(functionCode) && argumentBindings == null && blank(resolverCode)) return UNCHANGED;
        String source = blank(valueSource) ? SOURCE_REQUEST : valueSource.trim().toUpperCase(Locale.ROOT);
        if (SOURCE_REQUEST.equals(source)) {
            if (!blank(functionCode)) throw new IllegalArgumentException("取值来源为请求输入时不能绑定函数");
            if (!blank(resolverCode)) throw new IllegalArgumentException("取值来源为请求输入时不能配置字段解析器");
            return new FieldBinding(SOURCE_REQUEST, null, null);
        }
        if (SOURCE_DATA.equals(source)) {
            if (!blank(functionCode) || (argumentBindings != null && !argumentBindings.isNull()))
                throw new IllegalArgumentException("取值来源为数据解析时不能绑定函数");
            List<String> rules = rules(allowedRules);
            if (rules.isEmpty() || !RuleExecutionContext.FUNCTION_RULES.containsAll(rules))
                throw new IllegalArgumentException("数据解析字段只能适用于 " + FUNCTION_RULE_TEXT + "，不能用于 " + allowedRules);
            String resolver = resolverCode == null ? "" : resolverCode.trim();
            if (resolver.isEmpty()) throw new IllegalArgumentException("请选择字段解析器（resolverCode）");
            if (!resolvers.isRegistered(resolver)) throw new IllegalArgumentException("字段解析器 " + resolver + " 尚未注册");
            if (!List.of("CANDIDATE", "TRANSACTION", "CONTEXT", "GLOBAL").contains(executionScope))
                throw new IllegalArgumentException("执行作用域无效");
            if (!List.of("REQUEST", "NONE").contains(cachePolicy)) throw new IllegalArgumentException("缓存策略无效");
            return new FieldBinding(SOURCE_DATA, null, null);
        }
        if (!SOURCE_FUNCTION.equals(source))
            throw new IllegalArgumentException("取值来源只能是 REQUEST（请求输入）、FUNCTION（函数计算）或 DATA_RESOLVER（数据解析）");
        if (blank(functionCode)) throw new IllegalArgumentException("请选择字段要绑定的业务函数");
        List<String> rules = rules(allowedRules);
        if (rules.isEmpty() || !RuleExecutionContext.FUNCTION_RULES.containsAll(rules))
            throw new IllegalArgumentException("绑定函数的字段只能适用于 " + FUNCTION_RULE_TEXT + "，不能用于 " + allowedRules);
        // 先核对函数本身与返回类型，再校验参数绑定：类型不符时直接说明原因（函数须适用于字段的每一条适用规则）
        BusinessFunction handler = execution.handler(functionCode, rules.get(0));
        for (String rule : rules) execution.handler(functionCode, rule);
        if (!expectedType(dataType).equals(handler.returnType()))
            throw new IllegalArgumentException("函数 " + functionCode + " 返回类型为 " + handler.returnType()
                    + "，与字段类型 " + dataType + " 不一致，不能绑定");
        Map<String, Object> arguments = arguments("字段 " + fieldCode, argumentBindings);
        FunctionExecutionService.Validated validated = execution.validate(functionCode, arguments, rules.get(0));
        // 落库的是运营配置的绑定（不含执行时才补的默认路径），按 Handler 参数顺序排列，便于核对和审计
        Map<String, Object> configured = new LinkedHashMap<>();
        validated.bindings().forEach((parameter, binding) -> {
            if (arguments.containsKey(parameter)) configured.put(parameter, binding.text());
        });
        return new FieldBinding(SOURCE_FUNCTION, functionCode, write(configured));
    }

    /**
     * 请求没有表达取值来源时字段的取值配置：新增字段留空（按请求输入处理）；修改时保留已有配置
     * （数据解析字段的解析器、执行作用域、缓存策略由调用方同样保留），
     * 其中已绑定函数的按本次提交的类型和适用规则复核一遍（例如把适用规则改成含 C2 就会被拦下）。
     */
    public FieldBinding unchanged(FieldDefinition existing, String dataType, String allowedRules) {
        if (existing == null) return new FieldBinding(null, null, null);
        if (!FieldResolver.isFunctionBound(existing))
            return new FieldBinding(SOURCE_FUNCTION.equals(existing.valueSource) ? SOURCE_REQUEST : existing.valueSource, null, null);
        return binding(existing.fieldCode, dataType, allowedRules, SOURCE_FUNCTION, existing.functionCode, bindingsOf(existing));
    }

    /** ENUM 字段对应返回类型为 STRING 的函数，其余按类型同名匹配。 */
    public static String expectedType(String dataType) {
        return "ENUM".equals(dataType) ? "STRING" : dataType;
    }

    private JsonNode bindingsOf(FieldDefinition field) {
        if (field.argumentBindings == null || field.argumentBindings.isBlank()) return null;
        try {
            return json.readTree(field.argumentBindings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("字段 " + field.fieldCode + " 的参数绑定无法解析，请在元数据中心重新配置", ex);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }


    /** argumentBindings：JSON 对象（参数编码 → 受控路径或字面量）；兼容直接传 JSON 字符串的调用方。 */
    private Map<String, Object> arguments(String owner, JsonNode node) {
        if (node == null || node.isNull()) return new LinkedHashMap<>();
        if (node.isTextual()) {
            try {
                node = json.readTree(node.asText());
            } catch (Exception ex) {
                throw new IllegalArgumentException(owner + " 的参数绑定不是有效 JSON");
            }
            if (node == null || node.isNull()) return new LinkedHashMap<>();
        }
        if (!node.isObject()) throw new IllegalArgumentException(owner + " 的参数绑定须为 JSON 对象");
        Map<String, Object> arguments = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> arguments.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
        return arguments;
    }

    /** 引用了某个函数的配置：绑定了该函数的字段、条件树里含该函数节点的 C1 准入规则。 */
    public List<String> references(String functionCode) {
        List<String> references = new ArrayList<>();
        for (FieldDefinition field : fields.findAll())
            if (FieldResolver.isFunctionBound(field) && functionCode.equals(field.functionCode)) references.add("字段绑定 " + field.fieldCode);
        c1Conditions.findById(1L).ifPresent(row -> {
            if (row.conditionJson != null && usesFunction(row.conditionJson, functionCode)) references.add("C1 全局过滤条件");
        });
        for (Appetite rule : appetites.findAll())
            if (rule.conditionJson != null && usesFunction(rule.conditionJson, functionCode)) references.add("C1 准入规则 #" + rule.id);
        return references;
    }

    private boolean usesFunction(String conditionJson, String functionCode) {
        try {
            return usesFunction(json.readTree(conditionJson), functionCode);
        } catch (Exception ex) {
            throw new IllegalStateException("已保存的规则条件无法读取，请先修复规则配置", ex);
        }
    }

    private boolean usesFunction(JsonNode node, String functionCode) {
        if (node == null) return false;
        if (node.isObject()) {
            if (functionCode.equals(node.path("functionCode").asText(null))) return true;
            for (JsonNode child : node) if (usesFunction(child, functionCode)) return true;
        } else if (node.isArray()) for (JsonNode child : node) if (usesFunction(child, functionCode)) return true;
        return false;
    }

    /**
     * 启动时为内置字段 clientProviderExcluded 补上函数绑定（升级前该字段由执行器直接赋值）。
     * 只在该字段存在、取值来源从未配置过、适用规则仅为 C1、且函数可用于 C1 时执行；幂等，不覆盖已有配置，写入审计。
     * 自动绑定只是升级便利：任何一项不满足都跳过，字段照旧按请求输入取值（引用它的历史准入条件在请求没有提供取值时按不命中处理），不影响启动。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bindBuiltInField() {
        FieldDefinition field = fields.findById(BUILT_IN_EXCLUSION_FIELD).orElse(null);
        // valueSource 为空表示从未配置过（升级前的数据）；运营明确保存过取值来源的，不再自动处理
        if (field == null || (field.valueSource != null && !field.valueSource.isBlank())) return;
        if (!rules(field.allowedRules).equals(List.of(BINDING_RULE)) || !"BOOLEAN".equals(field.dataType)) return;
        if (!isExecutable(BUILT_IN_EXCLUSION_FUNCTION, BINDING_RULE)) return;
        FieldBinding binding = binding(field.fieldCode, field.dataType, field.allowedRules, SOURCE_FUNCTION,
                BUILT_IN_EXCLUSION_FUNCTION, json.valueToTree(BUILT_IN_EXCLUSION_BINDINGS));
        String before = write(field);
        field.valueSource = binding.valueSource();
        field.functionCode = binding.functionCode();
        field.argumentBindings = binding.argumentBindings();
        fields.save(field);
        audit.save(new RuleAudit("FIELD_METADATA", field.fieldCode, "BIND_FUNCTION", "system", before, write(field)));
    }

    private static List<String> rules(String csv) {
        return Arrays.stream((csv == null ? "" : csv).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("审计序列化失败", ex);
        }
    }
}
