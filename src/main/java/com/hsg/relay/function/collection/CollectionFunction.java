package com.hsg.relay.function.collection;

import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.ElementPredicate;
import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.SortField;
import com.hsg.relay.function.field.ElementSchema;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 集合函数的公共部分：Handler 编码即函数编码；dataSource 固定为 ARGUMENT（只对传入的集合做计算，不查询业务表）；
 * 子类只声明参数并实现计算。全部只读、无副作用，输入集合不被修改（需要变换时在副本上进行）。
 */
public abstract class CollectionFunction implements BusinessFunction {
    /** 参数来源 ANY：受控路径 / 数据字段 / 常量 / 前置函数输出都可以，但必须显式绑定。 */
    protected static final String ANY = "ANY";
    /** 参数类型常量（与 FunctionTypes 一致，简写）。 */
    protected static final String STRING = "STRING", DECIMAL = "DECIMAL", COLLECTION = "COLLECTION",
            PREDICATE = "PREDICATE", SORT_FIELDS = "SORT_FIELDS";

    private final String code;
    private final String returnType;
    private final String follows;
    private final List<Parameter> parameters;

    /** follows：返回类型跟随的参数编码（返回 COLLECTION 的函数用），其余为 null。 */
    protected CollectionFunction(String code, String returnType, String follows, Parameter... parameters) {
        this.code = code;
        this.returnType = returnType;
        this.follows = follows;
        this.parameters = List.of(Arrays.copyOf(parameters, parameters.length));
    }

    /** 参数声明：code / type / 是否必填 / 中文名；来源一律 ANY。 */
    protected static Parameter p(String code, String type, boolean required, String label) {
        return new Parameter(code, type, ANY, required, null, label);
    }

    /** 元素参数：类型跟随集合参数的元素类型（STRING / DECIMAL / DATE）。 */
    protected static Parameter e(String code, boolean required, String label) {
        return p(code, "ELEMENT", required, label);
    }

    @Override
    public String handlerCode() {
        return code;
    }

    @Override
    public String returnType() {
        return returnType;
    }

    @Override
    public String typeFollows() {
        return follows;
    }

    @Override
    public String dataSource() {
        return "ARGUMENT";
    }

    @Override
    public List<Parameter> parameters() {
        return parameters;
    }

    // ---------- 取参 ----------

    protected String owner() {
        return "函数 " + code;
    }

    /** 集合实参；没有绑定或不是集合时由 {@link CollectionInput#of} 报错。 */
    protected CollectionInput collection(String parameter, Map<String, Object> arguments) {
        return CollectionInput.of(owner(), parameter, arguments.get(parameter));
    }

    /** 谓词实参；必填的谓词没有绑定时报错（不会按「全部满足」处理）。 */
    protected ElementPredicate predicate(String parameter, Map<String, Object> arguments) {
        Object value = arguments.get(parameter);
        if (!(value instanceof ElementPredicate predicate))
            throw new IllegalArgumentException(owner() + " 的参数 " + parameter + " 缺少谓词条件");
        return predicate;
    }

    /** 可选谓词：没有绑定时返回 null（表示不按条件筛选）。 */
    protected ElementPredicate optionalPredicate(String parameter, Map<String, Object> arguments) {
        return arguments.get(parameter) instanceof ElementPredicate predicate ? predicate : null;
    }

    /** 字符串实参（非空）。 */
    protected String text(String parameter, Map<String, Object> arguments) {
        Object value = arguments.get(parameter);
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException(owner() + " 的参数 " + parameter + " 缺少取值");
        return text;
    }

    /** 排序字段实参（清单已按元素结构校验过）。 */
    @SuppressWarnings("unchecked")
    protected List<SortField> sortFields(String parameter, Map<String, Object> arguments) {
        Object value = arguments.get(parameter);
        if (!(value instanceof List<?> fields))
            throw new IllegalArgumentException(owner() + " 的参数 " + parameter + " 缺少排序字段");
        return (List<SortField>) fields;
    }

    /**
     * 受控的执行失败：不会当成 false、0 或空集合。与「数据未加载」「取数失败」一样让本次求值失败，
     * 由调用方决定如何处理；本函数只读，不产生任何业务副作用。
     */
    protected FunctionExecutionService.FunctionExecutionException failure(String message) {
        return new FunctionExecutionService.FunctionExecutionException(owner() + " 执行失败：" + message);
    }

    /** 汇总类函数在集合为空、没有可计算的结果时使用（AVG / MIN / MAX）。 */
    protected FunctionExecutionService.FunctionExecutionException noResult(String what) {
        return failure("集合为空，没有可计算的" + what);
    }

    /**
     * 「没有满足条件的记录」：与取数失败、执行异常区分开的受控结果。
     * 调用方可以单独识别这种情况（它不是数据或配置错误），本次求值仍按失败处理，不会返回 null 或一条不存在的记录。
     */
    public static final class NoMatch extends FunctionExecutionService.FunctionExecutionException {
        NoMatch(String message) {
            super(message);
        }
    }

    /** 对象记录展示用的属性值：日期只到天，其余原样；空值为 null。 */
    protected static Object display(Object value) {
        return value == null ? null : ElementSchema.display(value);
    }
}
