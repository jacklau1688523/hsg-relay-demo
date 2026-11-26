package com.hsg.relay.function;

import com.hsg.relay.domain.BusinessFunctionDefinition;
import com.hsg.relay.function.field.RuleCollection;
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

    /** 保存 / 编译条件时校验好的函数节点：执行时不再读取元数据。 */
    public record Validated(String functionCode, String functionName, BusinessFunction handler, Map<String, Binding> bindings) {
        public String returnType() {
            return handler.returnType();
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
        Resolved resolved = resolve(functionCode, ruleCode);
        return new Validated(functionCode, resolved.definition().functionName, resolved.handler(),
                resolver.toBindings("函数 " + functionCode, resolved.handler(), arguments, ruleCode));
    }

    private record Resolved(BusinessFunctionDefinition definition, BusinessFunction handler) {
    }

    private Resolved resolve(String functionCode, String ruleCode) {
        if (!"C1".equals(ruleCode)) throw new IllegalArgumentException("函数节点目前仅支持 C1 附加条件，不能用于 " + ruleCode);
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
        return run(validated.handler(), validated.functionCode(), validated.bindings(), context);
    }

    /** 执行器的系统必检：直接调用 Java Handler，不读取函数库元数据。functionCode 只用于展示和调用记录。 */
    public Call execute(BusinessFunction handler, String functionCode, Map<String, Object> arguments, RuleExecutionContext context) {
        String owner = "函数 " + functionCode;
        return run(handler, functionCode, resolver.toBindings(owner, handler, arguments, context.ruleCode()), context);
    }

    private Call run(BusinessFunction handler, String functionCode, Map<String, Binding> bindings, RuleExecutionContext context) {
        String owner = "函数 " + functionCode;
        // 实参解析（受控路径取值、FIELD 实参的字段解析）失败：记入调用记录后原样向上报错（异常类型与消息不变），不会当成 false
        long start = System.nanoTime();
        Map<String, Object> arguments;
        try {
            arguments = resolver.resolve(owner, handler, bindings, context);
        } catch (RuntimeException ex) {
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            context.record(new Call(functionCode, handler.handlerCode(), Map.of(), null, null, handler.dataSource(), elapsed(start), false, reason));
            throw ex;
        }
        arguments = Collections.unmodifiableMap(arguments);
        // 缓存键含 Handler 与全部有效参数（合作方、客户、决策时间等），不会跨交易、跨客户、跨合作方串用
        String key = handler.handlerCode() + "\n" + canonical(handler, arguments);
        Optional<Call> memo = context.memo(key);
        if (memo.isPresent()) {
            Call reused = memo.get().reused();
            context.record(reused);
            return reused;
        }
        start = System.nanoTime();
        Call call;
        try {
            BusinessFunction.Outcome outcome = handler.execute(arguments, context);
            if (outcome == null || outcome.value() == null)
                throw new IllegalStateException("没有返回结果");
            if (!matchesReturnType(handler.returnType(), outcome.value()))
                throw new IllegalStateException("返回类型与声明的 " + handler.returnType() + " 不一致：" + outcome.value().getClass().getSimpleName());
            call = new Call(functionCode, handler.handlerCode(), shown(arguments), outcome.value(), outcome.recordId(), handler.dataSource(),
                    elapsed(start), false, null);
        } catch (RuntimeException ex) {
            // 失败的调用同样记入调用记录（带异常信息），便于试算和校验过程展示；随后原样报错，不会当成「未命中」
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            context.record(new Call(functionCode, handler.handlerCode(), shown(arguments), null, null, handler.dataSource(),
                    elapsed(start), false, reason));
            throw new FunctionExecutionException(owner + " 执行失败：" + reason, ex);
        }
        // 只读函数才在本次判定内复用；失败的结果不缓存
        if (handler.readOnly()) context.remember(key, call);
        context.record(call);
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
        return String.valueOf(argument);
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
        arguments.forEach((code, value) -> shown.put(code, value instanceof RuleCollection collection ? view(collection) : value));
        return Collections.unmodifiableMap(shown);
    }

    private static boolean matchesReturnType(String type, Object value) {
        return switch (type) {
            case "BOOLEAN" -> value instanceof Boolean;
            case "STRING" -> value instanceof String;
            case "DECIMAL" -> value instanceof BigDecimal;
            case "INTEGER" -> value instanceof Long || value instanceof Integer || value instanceof BigInteger;
            case "DATE" -> value instanceof LocalDateTime || value instanceof LocalDate;
            default -> false;
        };
    }
}
