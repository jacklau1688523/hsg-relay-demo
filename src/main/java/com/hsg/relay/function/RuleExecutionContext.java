package com.hsg.relay.function;

import com.hsg.relay.function.field.FieldValueService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;

/**
 * 一次规则求值的上下文（请求级）。每次 C1 判定 / C5 评分新建一个，不跨交易、客户、合作方复用。
 * <p>函数参数和字段绑定只能从这里登记的受控路径按名称取值（candidate.* / transaction.* / context.*），不执行任何表达式。
 * 同时承载本次求值内只读函数的结果：相同 Handler + 相同有效参数只真正执行一次，见 {@link FunctionExecutionService}。
 */
public final class RuleExecutionContext {
    /**
     * 取值路径的分组（name 即页面和接口里用的编码）：CANDIDATE 当前正在评估的候选路由、TRANSACTION 当前支付交易的数据、
     * CONTEXT 规则引擎执行过程中的上下文数据；label 是日志里展示的中文名称。
     */
    public enum Group {
        CANDIDATE("当前正在评估的候选路由"),
        TRANSACTION("当前支付交易的数据"),
        CONTEXT("规则引擎执行过程中的上下文数据");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 受控取值路径：name 形如 candidate.providerCode；type 为 STRING / DECIMAL / INTEGER / DATE；label 供页面展示。 */
    public record Path(String name, Group group, String type, String label) {
    }

    /** C1 执行器登记的取值路径（代码级白名单，不从请求或配置读取）。 */
    public static final List<Path> C1_PATHS = List.of(
            new Path("candidate.providerCode", Group.CANDIDATE, "STRING", "候选合作方编码"),
            new Path("candidate.flowCategory", Group.CANDIDATE, "STRING", "当前流转类别"),
            new Path("transaction.clientId", Group.TRANSACTION, "STRING", "客户编号"),
            new Path("transaction.paymentCurrency", Group.TRANSACTION, "STRING", "交易币种"),
            new Path("transaction.beneficiaryBankCountry", Group.TRANSACTION, "STRING", "受益行国家"),
            new Path("transaction.amountUsd", Group.TRANSACTION, "DECIMAL", "本笔金额 USD"),
            new Path("context.decisionTime", Group.CONTEXT, "DATE", "决策时间"),
            new Path("context.monthKey", Group.CONTEXT, "STRING", "决策月份 YYYY-MM"),
            new Path("context.ruleCode", Group.CONTEXT, "STRING", "规则编码"));

    /**
     * C5 评分执行器登记的取值路径：候选合作方、与起息日有关的交易要素、决策时间，以及 C3 最终选中的覆盖行（行号 + C3 条件版本）。
     * 评分组里的函数条件只能从这里取值；requestedValueDate / clientId 没有上送时为空。
     */
    public static final List<Path> C5_PATHS = List.of(
            new Path("candidate.providerCode", Group.CANDIDATE, "STRING", "候选合作方编码"),
            new Path("transaction.clientId", Group.TRANSACTION, "STRING", "客户编号"),
            new Path("transaction.paymentCurrency", Group.TRANSACTION, "STRING", "交易币种"),
            new Path("transaction.requestedValueDate", Group.TRANSACTION, "DATE", "请求起息日"),
            new Path("context.decisionTime", Group.CONTEXT, "DATE", "决策时间"),
            new Path("context.monthKey", Group.CONTEXT, "STRING", "决策月份 YYYY-MM"),
            new Path("context.matchedReachRowId", Group.CONTEXT, "INTEGER", "C3 选中的覆盖行行号"),
            new Path("context.matchedReachRuleVersion", Group.CONTEXT, "INTEGER", "C3 条件版本"),
            new Path("context.ruleCode", Group.CONTEXT, "STRING", "规则编码"));

    private final String ruleCode;
    private final List<Path> paths;
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, FunctionExecutionService.Call> memo = new HashMap<>();
    private final List<FunctionExecutionService.Call> calls = new ArrayList<>();
    // 请求级数据缓存：元数据字段取值、业务集合，按含上下文的 key 只加载一次
    private final Map<String, Object> data = new HashMap<>();
    private final List<FieldValueService.Resolution> resolutions = new ArrayList<>();

    private RuleExecutionContext(String ruleCode, List<Path> paths) {
        this.ruleCode = ruleCode;
        this.paths = paths;
    }

    /** 可在条件里使用函数节点的规则（有各自的受控路径和执行上下文）。 */
    public static final Set<String> FUNCTION_RULES = Set.of("C1", "C5");

    /** 某条规则可用的受控路径；尚未接入函数的规则返回空清单。 */
    public static List<Path> paths(String ruleCode) {
        return "C1".equals(ruleCode) ? C1_PATHS : "C5".equals(ruleCode) ? C5_PATHS : List.of();
    }

    /**
     * C5 评分上下文：候选合作方、交易要素和决策时间都来自本次路由请求。决策时间按业务时区（香港）的本地时间登记，
     * 截单判断时再换算到覆盖行自己的时区。
     */
    public static RuleExecutionContext c5(String providerCode, String clientId, String paymentCurrency,
                                          LocalDate requestedValueDate, LocalDateTime decisionTime) {
        if (decisionTime == null) throw new IllegalArgumentException("决策时间不能为空");
        RuleExecutionContext context = new RuleExecutionContext("C5", C5_PATHS);
        context.values.put("candidate.providerCode", providerCode);
        context.values.put("transaction.clientId", clientId);
        context.values.put("transaction.paymentCurrency", paymentCurrency);
        context.values.put("transaction.requestedValueDate", requestedValueDate);
        context.values.put("context.decisionTime", decisionTime);
        context.values.put("context.monthKey", YearMonth.from(decisionTime).toString());
        context.values.put("context.ruleCode", "C5");
        return context;
    }

    /** C1 判定上下文：候选合作方、交易要素和决策时间都来自本次模拟请求，不接受条件配置覆盖。 */
    public static RuleExecutionContext c1(String providerCode, String clientId, String paymentCurrency,
                                          String beneficiaryBankCountry, BigDecimal amountUsd, LocalDateTime decisionTime) {
        if (decisionTime == null) throw new IllegalArgumentException("决策时间不能为空");
        RuleExecutionContext context = new RuleExecutionContext("C1", C1_PATHS);
        context.values.put("candidate.providerCode", providerCode);
        context.values.put("transaction.clientId", clientId);
        context.values.put("transaction.paymentCurrency", paymentCurrency);
        context.values.put("transaction.beneficiaryBankCountry", beneficiaryBankCountry);
        context.values.put("transaction.amountUsd", amountUsd);
        context.values.put("context.decisionTime", decisionTime);
        context.values.put("context.monthKey", YearMonth.from(decisionTime).toString());
        context.values.put("context.ruleCode", "C1");
        return context;
    }

    public String ruleCode() {
        return ruleCode;
    }

    public List<Path> paths() {
        return paths;
    }

    public boolean has(String path) {
        return path != null && paths.stream().anyMatch(p -> p.name().equals(path));
    }

    /** 设置已登记路径的取值（执行器逐个流转类别求值时更新 candidate.flowCategory）。 */
    public RuleExecutionContext set(String path, Object value) {
        if (!has(path)) throw new IllegalArgumentException("未登记的取值路径：" + path);
        values.put(path, value);
        return this;
    }

    /** 按名称读取受控路径的取值；未登记的路径直接报错。 */
    public Object value(String path) {
        if (!has(path)) throw new IllegalArgumentException("未登记的取值路径：" + path);
        return values.get(path);
    }

    /**
     * 已登记路径的当前取值，按分组归类、按登记顺序排列，供求值日志按 CANDIDATE / TRANSACTION / CONTEXT 三组展示；
     * 还没有取值的路径也在其中（值为 null）。
     */
    public Map<Group, Map<String, Object>> valuesByGroup() {
        Map<Group, Map<String, Object>> result = new EnumMap<>(Group.class);
        for (Path path : paths)
            result.computeIfAbsent(path.group(), group -> new LinkedHashMap<>()).put(path.name(), values.get(path.name()));
        return result;
    }

    public LocalDateTime decisionTime() {
        return (LocalDateTime) values.get("context.decisionTime");
    }

    Optional<FunctionExecutionService.Call> memo(String key) {
        return Optional.ofNullable(memo.get(key));
    }

    void remember(String key, FunctionExecutionService.Call call) {
        memo.putIfAbsent(key, call);
    }

    void record(FunctionExecutionService.Call call) {
        calls.add(call);
    }

    /** 本次求值内的全部函数调用记录（含复用结果的调用），顺序与执行顺序一致。 */
    public List<FunctionExecutionService.Call> calls() {
        return List.copyOf(calls);
    }

    /** 请求级缓存里是否已有该键的数据。 */
    public boolean hasData(String key) {
        return data.containsKey(key);
    }

    /** 读取请求级缓存里的数据；没有时返回 null（加载请走 {@link #data(String, Supplier)}）。 */
    public Object data(String key) {
        return data.get(key);
    }

    /**
     * 请求级数据缓存：相同 key 只加载一次，之后在本次求值内复用。key 须包含隔离业务数据所需的上下文
     * （合作方、字段、决策时间等）。加载失败（抛异常）不缓存；loader 返回 null 视为未加载，直接报错。
     */
    public Object data(String key, Supplier<Object> loader) {
        Object cached = data.get(key);
        if (cached != null) return cached;
        Object value = loader.get();
        if (value == null) throw new IllegalStateException("请求级数据缓存不接受空结果：" + key);
        Object earlier = data.putIfAbsent(key, value);
        return earlier != null ? earlier : value;
    }

    /**
     * 执行器直接交给函数使用的数据（如 C3 选中的覆盖行及其日历）：放进请求级缓存，同一键只能提供一次。
     * 与 {@link #data(String, Supplier)} 的区别是不查询任何数据源；函数按约定的键读取，读不到就报错，不自行挑选。
     */
    public RuleExecutionContext supply(String key, Object value) {
        if (key == null || key.isBlank() || value == null) throw new IllegalArgumentException("执行器提供的数据不能为空：" + key);
        if (data.putIfAbsent(key, value) != null) throw new IllegalStateException("执行器提供的数据重复：" + key);
        return this;
    }

    /** 记录一次数据字段解析（字段编码、解析器、耗时、是否复用），供求值日志和审计使用。 */
    public void resolved(FieldValueService.Resolution resolution) {
        resolutions.add(resolution);
    }

    /** 本次求值内的数据字段解析记录，顺序与解析顺序一致（复用的也在其中）。 */
    public List<FieldValueService.Resolution> resolutions() {
        return List.copyOf(resolutions);
    }
}
