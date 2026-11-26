package com.hsg.relay.function;

import com.hsg.relay.domain.BusinessFunctionDefinition;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.RuleCollection;
import com.hsg.relay.function.field.RuleList;
import com.hsg.relay.function.FunctionArgumentResolver.Binding;
import com.hsg.relay.repo.BusinessFunctionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 业务函数的校验与执行。两条调用路径：
 * <ul>
 *   <li>条件节点 / 函数绑定字段：{@link #validate} 按函数编码读取元数据，再经白名单找到 Handler；</li>
 *   <li>执行器的系统必检：{@link #execute} 直接按 Java Handler 调用，不经过函数库元数据，停用 / 删除函数都绕不过。</li>
 * </ul>
 * 两条路径对相同 Handler + 相同有效参数共用 {@link RuleExecutionContext} 里的结果，一次判定只查询一次。
 * 函数抛异常时原样向上报错，不会当成 false 放行。
 */
@Service
public class FunctionExecutionService {
    /** 一次函数调用的记录：入参、返回值、数据来源、耗时、是否复用了本次判定内的结果、异常信息、命中业务记录 ID。 */
    public record Call(String functionCode, String handlerCode, Map<String, Object> arguments, Object value, String recordId,
                       String dataSource, long durationMs, boolean cached, String error) {
        Call reused() {
            return new Call(functionCode, handlerCode, arguments, value, recordId, dataSource, 0, true, null);
        }
    }

    /** 函数执行失败。继承 IllegalStateException，接口按参数错误返回 400，不会被当成「未命中」。 */
    public static class FunctionExecutionException extends IllegalStateException {
        public FunctionExecutionException(String message, Throwable cause) {
            super(message, cause);
        }

        public FunctionExecutionException(String message) {
            super(message);
        }
    }

    /**
     * 保存 / 编译条件时校验好的函数节点：执行时不再读取元数据。
     * nodeId 是节点在条件树里的标识（FUNCTION_OUTPUT 引用它）；staticType 是返回值的实际类型——普通函数等于 returnType()，
     * 返回 COLLECTION 的函数按输入确定（如 STRING_LIST / OBJECT_LIST），staticSchema 是 OBJECT_LIST 的元素结构；
     * call 是可读的调用文本（如 collectionSize(collection=providerExcludedClientIds)）。
     */
    public record Validated(String functionCode, String functionName, BusinessFunction handler, Map<String, Binding> bindings,
                            String nodeId, String resolvedType, ElementSchema staticSchema, String call) {
        public Validated(String functionCode, String functionName, BusinessFunction handler, Map<String, Binding> bindings) {
            // 不在这里读取 handler（可能还是未完成桩的 mock）：返回类型跟随输入的函数由 typed() 另行确定
            this(functionCode, functionName, handler, bindings, null, null, null, null);
        }

        public String returnType() {
            return handler.returnType();
        }

        /** 返回值的实际类型：没有另行确定时等于声明的返回类型。 */
        public String staticType() {
            return resolvedType == null ? handler.returnType() : resolvedType;
        }

        /** 带上节点标识与可读调用文本。 */
        public Validated at(String id, String callText) {
            return new Validated(functionCode, functionName, handler, bindings, id, resolvedType, staticSchema, callText);
        }
    }

    /** 审计 / 试算用的一次函数调用明细：在 Call 之上补充函数名称、节点、参数来源、参数类型、返回类型与输入规模。 */
    public record Detail(String functionCode, String functionName, String nodeId, String handlerCode, String returnType,
                         List<Argument> arguments, Object value, String recordId, String dataSource, long durationMs,
                         boolean cached, String status, String error) {
        /** 一个实参的来源说明：参数编码、名称、类型、来源、取值目标、输入规模（集合元素个数；标量为空）。 */
        public record Argument(String parameter, String label, String type, String source, String target, Integer inputSize) {
        }
    }

    private final BusinessFunctionRepository definitions;
    private final BusinessFunctionRegistry registry;
    private final FunctionArgumentResolver resolver;

    public FunctionExecutionService(BusinessFunctionRepository definitions, BusinessFunctionRegistry registry, FunctionArgumentResolver resolver) {
        this.definitions = definitions;
        this.registry = registry;
        this.resolver = resolver;
    }

    /**
     * 按函数编码找到可用于 ruleCode 的 Handler：函数存在、已启用、适用该规则、Handler 已在白名单注册、返回类型与实现一致。
     * 不含参数校验；任何一项不满足都抛参数错误。
     */
    public BusinessFunction handler(String functionCode, String ruleCode) {
        return resolve(functionCode, ruleCode).handler();
    }

    /**
     * 按函数编码校验能否用于 ruleCode 的条件：{@link #handler} 的各项，加上参数绑定合法。
     * 任何一项不满足都抛参数错误，条件不能保存。
     */
    public Validated validate(String functionCode, Map<String, Object> arguments, String ruleCode) {
        return validate(functionCode, arguments, ruleCode, null);
    }

    /** 同上；nodes 不为空时参数可以引用条件树里其它函数节点的输出（FUNCTION_OUTPUT）。 */
    public Validated validate(String functionCode, Map<String, Object> arguments, String ruleCode, FunctionArgumentResolver.NodeContext nodes) {
        Resolved resolved = resolve(functionCode, ruleCode);
        Validated validated = new Validated(functionCode, resolved.definition().functionName, resolved.handler(),
                resolver.toBindings("函数 " + functionCode, resolved.handler(), arguments, ruleCode, nodes));
        return typed(validated, nodes, ruleCode);
    }

    /**
     * 确定返回值的实际类型：返回类型跟随输入的函数（见 {@link BusinessFunction#typeFollows()}）按被跟随参数的绑定取
     * 数据类型与元素结构——数据字段按字段定义，前置函数按它的实际类型，受控路径按路径登记的类型。
     */
    public Validated typed(Validated validated, FunctionArgumentResolver.NodeContext nodes, String ruleCode) {
        String follows = validated.handler().typeFollows();
        if (follows == null) return validated;
        Binding binding = validated.bindings().get(follows);
        String owner = "函数 " + validated.functionCode();
        if (binding == null) throw new IllegalArgumentException(owner + " 缺少参数 " + follows + "，无法确定返回的集合类型");
        String type;
        ElementSchema schema = null;
        if (binding.isField()) {
            FieldDefinition field = resolver.fieldType(binding.fieldCode(), ruleCode);
            type = field.dataType;
            schema = resolver.fieldSchema(field);
        } else if (binding.isOutput()) {
            Validated producer = nodes.producer(binding.outputNodeId());
            type = producer.staticType();
            schema = producer.staticSchema();
        } else if (binding.path() != null) {
            type = RuleExecutionContext.paths(ruleCode).stream().filter(p -> p.name().equals(binding.path()))
                    .map(RuleExecutionContext.Path::type).findFirst().orElseThrow(() -> new IllegalArgumentException(owner + " 引用了不受控的取值路径：" + binding.path()));
        } else
            throw new IllegalArgumentException(owner + " 的参数 " + follows + " 须绑定数据字段、受控路径或前置函数的输出，不能是常量 / 字面量");
        return new Validated(validated.functionCode(), validated.functionName(), validated.handler(), validated.bindings(),
                validated.nodeId(), type, schema, validated.call());
    }

    /**
     * 一次调用的审计 / 试算明细：函数、节点、每个实参的来源 / 类型 / 取值目标 / 输入规模、返回值（集合为摘要预览）、耗时、状态与错误。
     * 不带完整名单、整份记录或敏感明细。
     */
    public static Detail detail(Validated validated, Call call) {
        List<Detail.Argument> arguments = new ArrayList<>();
        if (validated.bindings() != null && call.arguments() != null) for (BusinessFunction.Parameter parameter : validated.handler().parameters()) {
            Binding binding = validated.bindings().get(parameter.code());
            if (binding == null || !call.arguments().containsKey(parameter.code())) continue;
            Object value = call.arguments().get(parameter.code());
            arguments.add(new Detail.Argument(parameter.code(), parameter.label(), parameter.type(), String.valueOf(binding.source()),
                    binding.isField() ? binding.fieldCode() : binding.isOutput() ? binding.outputNodeId() : binding.path(),
                    value instanceof RuleCollection collection && collection.materialized() ? collection.elements().size()
                            : value instanceof RuleList list ? list.size()
                    // 调用记录里的集合是展示形态（view：size / sample），输入规模从 size 取
                    : value instanceof Map<?, ?> shown && shown.get("size") instanceof Number size ? size.intValue() : null));
        }
        return new Detail(call.functionCode(), validated.functionName(), validated.nodeId(), call.handlerCode(),
                validated.staticType(), Collections.unmodifiableList(arguments), call.value(), call.recordId(), call.dataSource(),
                call.durationMs(), call.cached(), call.error() == null ? "SUCCESS" : "FAILED", call.error());
    }

    private record Resolved(BusinessFunctionDefinition definition, BusinessFunction handler) {
    }

    private Resolved resolve(String functionCode, String ruleCode) {
        if (!RuleExecutionContext.FUNCTION_RULES.contains(ruleCode == null ? "" : ruleCode))
            throw new IllegalArgumentException("函数节点目前仅支持 " + String.join(" / ", RuleExecutionContext.FUNCTION_RULES.stream().sorted().toList())
                    + " 的条件，不能用于 " + ruleCode);
        if (functionCode == null || functionCode.isBlank()) throw new IllegalArgumentException("函数节点缺少 functionCode");
        String owner = "函数 " + functionCode;
        BusinessFunctionDefinition definition = definitions.findById(functionCode)
                .orElseThrow(() -> new IllegalArgumentException("函数不存在：" + functionCode));
        if (!definition.enabled) throw new IllegalArgumentException(owner + " 已停用，不能用于条件");
        if (Arrays.stream(definition.allowedRules == null ? new String[0] : definition.allowedRules.split(","))
                .map(String::trim).noneMatch(ruleCode::equals))
            throw new IllegalArgumentException(owner + " 不适用于当前规则 " + ruleCode);
        BusinessFunction handler = registry.find(definition.handlerCode)
                .orElseThrow(() -> new IllegalArgumentException(owner + " 的后端实现 " + definition.handlerCode + " 尚未注册，不能用于条件"));
        if (!handler.returnType().equals(definition.returnType))
            throw new IllegalArgumentException(owner + " 的返回类型（元数据 " + definition.returnType + "）与后端实现（" + handler.returnType() + "）不一致，请先在函数库核对");
        return new Resolved(definition, handler);
    }

    /** 条件节点 / 函数绑定字段：先按函数编码校验元数据，再执行。 */
    public Call invoke(String functionCode, Map<String, Object> arguments, String ruleCode, RuleExecutionContext context) {
        return invoke(validate(functionCode, arguments, ruleCode), context);
    }

    public Call invoke(Validated validated, RuleExecutionContext context) {
        return run(validated.handler(), validated.functionCode(), validated.bindings(), context, validated.nodeId());
    }

    /** 执行器的系统必检：直接调用 Java Handler，不读取函数库元数据。functionCode 只用于展示和调用记录。 */
    public Call execute(BusinessFunction handler, String functionCode, Map<String, Object> arguments, RuleExecutionContext context) {
        String owner = "函数 " + functionCode;
        return run(handler, functionCode, resolver.toBindings(owner, handler, arguments, context.ruleCode()), context, null);
    }

    /**
     * 执行一次函数。nodeId 是条件树里的函数节点 id：不为空时把真实返回值登记为该节点的输出，供后置函数以 FUNCTION_OUTPUT 引用
     * （复用本次求值内的结果时同样登记）。
     */
    private Call run(BusinessFunction handler, String functionCode, Map<String, Binding> bindings, RuleExecutionContext context, String nodeId) {
        String owner = "函数 " + functionCode;
        // 实参解析（受控路径取值、FIELD 实参的字段解析）失败：记入调用记录后原样向上报错（异常类型与消息不变），不会当成 false
        long start = System.nanoTime();
        Map<String, Object> arguments;
        try {
            arguments = resolver.resolve(owner, handler, bindings, context);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            context.record(nodeId, new Call(functionCode, handler.handlerCode(), Map.of(), null, null, handler.dataSource(), elapsed(start), false, reason));
            context.failed(nodeId, ex);
            throw ex;
        }
        arguments = Collections.unmodifiableMap(arguments);
        // 缓存键含 Handler 与全部有效参数（合作方、客户、决策时间等），不会跨交易、跨客户、跨合作方串用
        String key = handler.handlerCode() + "\n" + canonical(handler, arguments);
        Optional<Call> memo = context.memo(key);
        if (memo.isPresent()) {
            Call reused = memo.get().reused();
            context.record(nodeId, reused);
            if (nodeId != null) context.output(nodeId, context.memoValue(key));
            return reused;
        }
        start = System.nanoTime();
        Call call;
        Object output;
        try {
            BusinessFunction.Outcome outcome = handler.execute(arguments, context);
            output = outcome == null ? null : outcome.value();
            if (outcome == null || outcome.value() == null)
                throw new IllegalStateException("没有返回结果");
            if (!matchesReturnType(handler.returnType(), outcome.value()))
                throw new IllegalStateException("返回类型与声明的 " + handler.returnType() + " 不一致：" + outcome.value().getClass().getSimpleName());
            call = new Call(functionCode, handler.handlerCode(), shown(arguments), auditValue(outcome.value()), outcome.recordId(), handler.dataSource(),
                    elapsed(start), false, null);
        } catch (RuntimeException ex) {
            // 失败的调用同样记入调用记录（带异常信息），便于试算和校验过程展示；随后原样报错，不会当成「未命中」
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            context.record(nodeId, new Call(functionCode, handler.handlerCode(), shown(arguments), null, null, handler.dataSource(),
                    elapsed(start), false, reason));
            context.failed(nodeId, ex);
            throw new FunctionExecutionException(owner + " 执行失败：" + reason, ex);
        }
        // 真实返回值登记为函数节点的输出（调用记录里的 value 是展示形态）
        if (nodeId != null) context.output(nodeId, output);
        // 只读函数才在本次判定内复用；失败的结果不缓存
        if (handler.readOnly()) context.remember(key, call, output);
        context.record(nodeId, call);
        return call;
    }

    private static long elapsed(long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }

    private static String canonical(BusinessFunction handler, Map<String, Object> arguments) {
        return handler.parameters().stream()
                .map(p -> p.code() + "=" + argumentKey(arguments.get(p.code())))
                .sorted().collect(Collectors.joining(";"));
    }

    /** 缓存键里的实参：时间转 ISO 文本；集合用含合作方 / 决策时间的身份标识，而不是名单内容或 toString。 */
    private static String argumentKey(Object argument) {
        if (argument instanceof LocalDateTime time) return time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if (argument instanceof RuleCollection collection) return "collection:" + collection.cacheKey();
        if (argument instanceof RuleList list) return "list:" + list.cacheKey();
        // 谓词按条件树内容；排序字段按清单内容；单个对象按对象身份（只在本次求值内有效）
        if (argument instanceof Map<?, ?>) return "object:" + System.identityHashCode(argument);
        return String.valueOf(argument);
    }

    /**
     * 调用记录里的返回值：集合保持摘要形态（序列化时只输出来源、类型、个数和样例）；单个对象只列属性，其中的集合属性只给个数；
     * 分组结果（键 → 记录集合）只给每组的记录数和记录 ID 样例，不给整份记录。Handler 的真实返回值不受影响。
     */
    static Object auditValue(Object value) {
        if (value instanceof RuleList || value instanceof RuleCollection) return value;
        return value instanceof Map<?, ?> map ? preview(map) : value;
    }

    private static Object preview(Map<?, ?> map) {
        Map<String, Object> shown = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            Object item;
            if (value instanceof RuleList list) {
                List<Object> ids = new ArrayList<>();
                for (int i = 0; i < list.size() && ids.size() < 5; i++) ids.add(list.schema() == null ? list.elements().get(i) : list.schema().recordId(list.elements().get(i), i));
                item = Map.of("size", list.size(), "sample", List.copyOf(ids));
            } else if (value instanceof RuleCollection collection) item = collection.summary();
            else if (value instanceof Map<?, ?> nested) item = preview(nested);
            else if (value instanceof Collection<?> set) item = "集合（" + set.size() + " 个元素）";
            else item = value;
            shown.put(String.valueOf(key), item);
        });
        return Collections.unmodifiableMap(shown);
    }

    /**
     * 调用记录里的实参（接口返回、试算展示、求值日志用）：集合参数换成展示形态——来源、元素个数、是否已加载、最多 5 个元素，
     * 不带完整名单。Handler 拿到的仍是真正的集合。
     */
    private static Map<String, Object> view(RuleCollection collection) {
        RuleCollection.View view = collection.view(5);
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("source", view.source());
        shown.put("materialized", view.materialized());
        shown.put("size", view.size());
        shown.put("sample", view.sample());
        return shown;
    }

    private static Map<String, Object> shown(Map<String, Object> arguments) {
        Map<String, Object> shown = new LinkedHashMap<>();
        arguments.forEach((code, value) -> shown.put(code, value instanceof RuleCollection collection ? view(collection)
                : value instanceof RuleList list ? list.view(5)
                : value instanceof ElementPredicate predicate ? predicate.text()
                : value instanceof Map<?, ?> map ? preview(map) : value));
        return Collections.unmodifiableMap(shown);
    }

    private static boolean matchesReturnType(String type, Object value) {
        return switch (type) {
            case "BOOLEAN" -> value instanceof Boolean;
            case "STRING" -> value instanceof String;
            case "DECIMAL" -> value instanceof BigDecimal;
            case "INTEGER" -> value instanceof Long || value instanceof Integer || value instanceof BigInteger;
            case "DATE" -> value instanceof LocalDateTime || value instanceof LocalDate;
            // 结构返回（组合计算函数）：多个输出字段一次算出，不能直接比较，所以不能作为条件节点
            case "OBJECT" -> value instanceof Map<?, ?> || value != null && value.getClass().isRecord();
            case FunctionTypes.COLLECTION -> value instanceof RuleCollection || value instanceof RuleList;
            case FunctionTypes.MAP -> value instanceof Map<?, ?> map && map.values().stream().allMatch(v -> v instanceof RuleList);
            case FunctionTypes.STRING_LIST, FunctionTypes.NUMBER_LIST, FunctionTypes.DATE_LIST, FunctionTypes.OBJECT_LIST ->
                    value instanceof RuleList list && list.dataType().equals(type);
            default -> false;
        };
    }
}
