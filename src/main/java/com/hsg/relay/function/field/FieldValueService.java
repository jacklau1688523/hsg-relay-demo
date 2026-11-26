package com.hsg.relay.function.field;

import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.FieldResolver;
import com.hsg.relay.function.FunctionArgumentResolver;
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
    private final ElementSchemaRegistry schemas;

    public FieldValueService(FieldDefinitionRepository fields, FieldResolverRegistry registry, ElementSchemaRegistry schemas) {
        this.fields = fields;
        this.registry = registry;
        this.schemas = schemas;
    }

    public static boolean isSet(String dataType) {
        return STRING_SET.equals(dataType);
    }

    /** 集合类型字段的元素结构（OBJECT_LIST 必填且须已登记；其它类型为 null）。 */
    public ElementSchema schema(FieldDefinition field) {
        if (field == null || field.elementSchema == null || field.elementSchema.isBlank()) return null;
        return schemas.require(field.elementSchema.trim());
    }

    /** OBJECT_LIST 字段必须配置已登记的元素结构；没有配置时报参数错误并说明原因。 */
    public static ElementSchema requireSchema(ElementSchemaRegistry schemas, FieldDefinition field) {
        if (field.elementSchema == null || field.elementSchema.isBlank())
            throw new IllegalArgumentException("字段 " + field.fieldCode + " 为 OBJECT_LIST，须配置元素结构 elementSchema");
        return schemas.require(field.elementSchema.trim());
    }

    /**
     * 保存 / 编译条件时的校验：字段须可用于 ruleCode 的函数参数——已启用、适用该规则、取值来源为数据解析、
     * 解析器已注册、类型与参数类型一致。任何一项不满足都抛参数错误并说明原因，返回校验通过的字段定义。
     */
    public FieldDefinition check(String fieldCode, String parameterType, String ruleCode) {
        String owner = "字段 " + (fieldCode == null || fieldCode.isBlank() ? "（空）" : fieldCode);
        if (!RuleExecutionContext.FUNCTION_RULES.contains(ruleCode == null ? "" : ruleCode))
            throw new IllegalArgumentException(owner + "：数据解析字段只能用于已接入函数节点的规则（"
                    + String.join(" / ", RuleExecutionContext.FUNCTION_RULES.stream().sorted().toList()) + "），不能用于 " + ruleCode);
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
        if (!FunctionArgumentResolver.compatible(parameterType, field.dataType))
            throw new IllegalArgumentException(owner + " 类型为 " + field.dataType + "，与参数要求的 " + parameterType + " 不一致");
        if (RuleList.OBJECT_LIST.equals(field.dataType)) requireSchema(schemas, field);
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
        checkShape(field, value);
        // 解析失败（抛异常）不会走到这里，所以失败的结果不会被缓存
        if (reuse) {
            Object resolved = value;
            value = context.data(key, () -> resolved);
        }
        context.resolved(new Resolution(field.fieldCode, field.resolverCode, elapsed, false));
        return value;
    }

    /**
     * 解析器返回值的形态须与字段类型一致：STRING_SET 为可按成员查询的集合（或已加载的字符串清单）；
     * 其它集合类型为同类型的 {@link RuleList}，OBJECT_LIST 的元素结构须与字段配置一致。不一致按读取失败报错。
     */
    private void checkShape(FieldDefinition field, Object value) {
        String owner = "字段 " + field.fieldCode;
        String type = field.dataType;
        if (STRING_SET.equals(type)) {
            if (value instanceof RuleCollection || value instanceof RuleList list && list.elementType().equals("STRING")) return;
        } else if (RuleList.isCollection(type)) {
            if (value instanceof RuleList list && list.dataType().equals(type)) {
                String configured = field.elementSchema == null || field.elementSchema.isBlank() ? null : field.elementSchema.trim();
                if (!RuleList.OBJECT_LIST.equals(type) || configured != null && configured.equals(list.elementSchema())) return;
                throw new IllegalStateException(owner + " 为 OBJECT_LIST，解析器返回的元素结构 " + list.elementSchema() + " 与字段配置的 " + configured + " 不一致");
            }
        } else if (!RuleList.isCollection(type)) {
            // 非集合类型：标量取值不在这里做形态检查，由使用方按参数类型校验
            return;
        }
        throw new IllegalStateException(owner + " 为集合类型 " + type + "，但解析器返回了 " + value.getClass().getSimpleName());
    }

    /**
     * 请求级缓存键：规则、字段、解析器，加上上下文里的候选合作方、当前客户、决策时间。
     * 上下文本身一次判定一个，键里再带上这些取值，保证不同合作方 / 客户 / 决策时间的同名字段不会互相串用。
     */
    private static String cacheKey(FieldDefinition field, RuleExecutionContext context) {
        return "FIELD|" + context.ruleCode() + "|" + field.fieldCode + "|" + field.resolverCode
                + "|" + (context.has("candidate.providerCode") ? context.value("candidate.providerCode") : null)
                + "|" + (context.has("transaction.clientId") ? context.value("transaction.clientId") : null)
                // C2 逐条监管规则求值时复用同一个上下文：按正在判断的监管规则隔离（如「监管规则要求的能力标签」）；其它规则没有这条路径
                + "|" + (context.has("context.regulatoryRuleId") ? context.value("context.regulatoryRuleId") : null)
                + "|" + context.decisionTime();
    }

    private static List<String> rules(FieldDefinition field) {
        return Arrays.stream((field.allowedRules == null ? "" : field.allowedRules).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
