package com.hsg.relay.function;

import java.util.List;
import java.util.Map;

/**
 * 业务函数的 Java Handler 白名单接口。函数库元数据（relay_business_function）只登记编码、名称等描述信息，
 * 真正能执行的是实现了本接口并注册为 Spring Bean 的类，按 handlerCode 与元数据绑定；
 * 元数据不会生成代码，没有注册 Handler 的函数不能进入条件。
 */
public interface BusinessFunction {
    /**
     * 参数声明：code / type（STRING、INTEGER、DECIMAL、BOOLEAN、DATE、STRING_SET 集合）/ source（CANDIDATE、TRANSACTION、CONTEXT、FIELD、ANY）/
     * 是否必填 / 未绑定时取默认值的受控路径。
     * source 为 FIELD 的参数只能绑定数据解析字段；ANY 表示四种来源都可以，但必须显式绑定，不按参数名推断取值路径。
     */
    record Parameter(String code, String type, String source, boolean required, String defaultPath) {
        public Parameter(String code, String type, String source, boolean required) {
            this(code, type, source, required, null);
        }
    }

    /** 返回值：value 为返回结果；recordId 为命中的业务记录 ID（供试算展示，可空）。 */
    record Outcome(Object value, String recordId) {
        public static Outcome of(Object value) {
            return new Outcome(value, null);
        }
    }

    String handlerCode();

    /**
     * BOOLEAN / STRING / INTEGER / DECIMAL / DATE，或 OBJECT（结构返回：组合计算函数一次给出多个输出，由执行器读取，
     * 不能直接比较，所以不能作为条件节点，例如 C5 的 calculateValueDate）。
     */
    String returnType();

    /** 业务数据来源编码，例如 CLIENT_EXCLUSION，供试算展示。 */
    String dataSource();

    List<Parameter> parameters();

    /** 只读查询：不得扣款、冻结额度或写入业务数据。失败时抛异常，不能返回「未命中」。 */
    Outcome execute(Map<String, Object> arguments, RuleExecutionContext context);

    /** 只读函数：同一次求值内相同参数复用结果。有副作用的函数须返回 false。 */
    default boolean readOnly() {
        return true;
    }
}
