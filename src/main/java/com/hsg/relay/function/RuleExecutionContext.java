package com.hsg.relay.function;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * 一次规则求值的上下文（请求级）。每次 C1 判定新建一个，不跨交易、客户、合作方复用。
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

    /** 受控取值路径：name 形如 candidate.providerCode；type 为 STRING / DECIMAL / DATE；label 供页面展示。 */
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

    private final String ruleCode;
    private final List<Path> paths;
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, FunctionExecutionService.Call> memo = new HashMap<>();
    private final List<FunctionExecutionService.Call> calls = new ArrayList<>();

    private RuleExecutionContext(String ruleCode, List<Path> paths) {
        this.ruleCode = ruleCode;
        this.paths = paths;
    }

    /** 某条规则可用的受控路径；尚未接入函数的规则返回空清单。 */
    public static List<Path> paths(String ruleCode) {
        return "C1".equals(ruleCode) ? C1_PATHS : List.of();
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
}
