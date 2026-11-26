package com.hsg.relay.function.field;

import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.FieldResolver;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * 数据解析字段（valueSource = DATA_RESOLVER）的取值：按字段元数据找到解析器，在本次求值的上下文里解析。
 * <p>缓存是请求级的，只存在于 {@link RuleExecutionContext} 里：一次判定内同一字段只解析一次，
 * 系统必检和条件树引用同一份数据时共用，不会跨合作方、跨客户、跨交易串用。
 * 元数据与实际业务数据分离：这里每次按 fieldCode 读字段定义，字段定义停用、改类型、换解析器后立即生效。
 */
@Service
public class FieldValueService {
    public static final String STRING_SET = "STRING_SET";

    /** 一次字段解析的记录：字段、解析器、耗时、是否复用了本次求值内的结果（审计与求值日志用）。 */
    public record Resolution(String fieldCode, String resolverCode, long durationMs, boolean cached) {
    }

    private final FieldDefinitionRepository fields;
    private final FieldResolverRegistry registry;

    public FieldValueService(FieldDefinitionRepository fields, FieldResolverRegistry registry) {
        this.fields = fields;
        this.registry = registry;
    }

    public static boolean isSet(String dataType) {
        return STRING_SET.equals(dataType);
    }

    /**
     * 保存 / 编译条件时的校验：字段须可用于 ruleCode 的函数参数——已启用、适用该规则、取值来源为数据解析、
     * 解析器已注册、类型与参数类型一致。任何一项不满足都抛参数错误并说明原因，返回校验通过的字段定义。
     */
    public FieldDefinition check(String fieldCode, String parameterType, String ruleCode) {
        String owner = "字段 " + (fieldCode == null || fieldCode.isBlank() ? "（空）" : fieldCode);
        if (RuleExecutionContext.paths(ruleCode).isEmpty())
            throw new IllegalArgumentException(owner + "：数据解析字段目前只能用于 C1 的条件，不能用于 " + ruleCode);
        FieldDefinition field = fieldCode == null ? null : fields.findById(fieldCode).orElse(null);
        if (field == null) throw new IllegalArgumentException(owner + " 不存在");
        if (!field.enabled) throw new IllegalArgumentException(owner + " 已停用，不能用于条件");
        if (!rules(field).contains(ruleCode)) throw new IllegalArgumentException(owner + " 不适用于当前规则 " + ruleCode);
        if (!FieldResolver.isDataResolved(field))
            throw new IllegalArgumentException(owner + " 的取值来源不是数据解析（DATA_RESOLVER），不能作为 FIELD 参数");
        if (field.resolverCode == null || field.resolverCode.isBlank())
            throw new IllegalArgumentException(owner + " 没有配置字段解析器 resolverCode");
        if (!registry.isRegistered(field.resolverCode))
            throw new IllegalArgumentException(owner + " 的字段解析器 " + field.resolverCode + " 尚未注册");
        if (!field.dataType.equals(parameterType))
            throw new IllegalArgumentException(owner + " 类型为 " + field.dataType + "，与参数要求的 " + parameterType + " 不一致");
        return field;
    }

    /** 按字段编码解析；字段当前不可用时抛参数错误（不会退回请求取值或空值）。 */
    public Object value(String fieldCode, RuleExecutionContext context) {
        FieldDefinition field = fieldCode == null ? null : fields.findById(fieldCode).orElse(null);
        if (field == null) throw new IllegalArgumentException("字段 " + fieldCode + " 不存在，无法解析取值");
        return value(field, context);
    }

    /**
     * 解析字段在本次求值中的取值。字段须适用于上下文的规则、已启用、配置完整；集合类型的返回值必须是 {@link RuleCollection}。
     * 解析器返回 null 视为「未加载」，抛异常而不是当成空集合。
     */
    public Object value(FieldDefinition field, RuleExecutionContext context) {
        String owner = "字段 " + field.fieldCode;
        if (context == null) throw new IllegalArgumentException(owner + " 由字段解析器取值，只能在规则执行中求值");
        if (!field.enabled || !rules(field).contains(context.ruleCode()))
            throw new IllegalArgumentException(owner + " 不适用于当前规则或已停用，无法解析取值");
        if (!FieldResolver.isDataResolved(field) || field.resolverCode == null || field.resolverCode.isBlank())
            throw new IllegalArgumentException(owner + " 没有配置字段解析器，无法解析取值");
        FieldValueResolver resolver = registry.find(field.resolverCode)
                .orElseThrow(() -> new IllegalArgumentException(owner + " 的字段解析器 " + field.resolverCode + " 尚未注册，无法解析取值"));
        // 执行作用域 CANDIDATE 的字段依赖当前候选合作方：没有候选合作方时不能解析，避免把别的合作方的数据算进来
        if ("CANDIDATE".equals(field.executionScope) && context.value("candidate.providerCode") == null)
            throw new IllegalStateException(owner + " 的执行作用域为 CANDIDATE，但当前上下文没有候选合作方，无法解析取值");
        String key = cacheKey(field, context);
        // cachePolicy = REQUEST：本次求值内复用；NONE：每次都重新解析
        boolean reuse = !"NONE".equals(field.cachePolicy);
        if (reuse && context.hasData(key)) {
            context.resolved(new Resolution(field.fieldCode, field.resolverCode, 0, true));
            return context.data(key);
        }
        long start = System.nanoTime();
        Object value = resolver.resolve(field.fieldCode, context);
        long elapsed = (System.nanoTime() - start) / 1_000_000;
        if (value == null)
            throw new IllegalStateException(owner + " 的解析器 " + field.resolverCode + " 没有返回结果（数据未加载），不能按空集合处理");
        if (isSet(field.dataType) && !(value instanceof RuleCollection))
            throw new IllegalStateException(owner + " 为集合类型，但解析器返回了 " + value.getClass().getSimpleName());
        // 解析失败（抛异常）不会走到这里，所以失败的结果不会被缓存
        if (reuse) {
            Object resolved = value;
            value = context.data(key, () -> resolved);
        }
        context.resolved(new Resolution(field.fieldCode, field.resolverCode, elapsed, false));
        return value;
    }

    /**
     * 请求级缓存键：规则、字段、解析器，加上上下文里的候选合作方、当前客户、决策时间。
     * 上下文本身一次判定一个，键里再带上这些取值，保证不同合作方 / 客户 / 决策时间的同名字段不会互相串用。
     */
    private static String cacheKey(FieldDefinition field, RuleExecutionContext context) {
        return "FIELD|" + context.ruleCode() + "|" + field.fieldCode + "|" + field.resolverCode
                + "|" + context.value("candidate.providerCode") + "|" + context.value("transaction.clientId")
                + "|" + context.decisionTime();
    }

    private static List<String> rules(FieldDefinition field) {
        return Arrays.stream((field.allowedRules == null ? "" : field.allowedRules).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
