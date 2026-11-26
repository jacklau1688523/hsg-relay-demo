package com.hsg.relay.function;

import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.function.field.RuleList;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.function.Supplier;

/**
 * 一次规则求值的上下文（请求级）。每次 C1 / C2 / C3 / C7 / C8 判定、C5 评分新建一个，不跨交易、客户、合作方复用。
 * <p>函数参数和字段绑定只能从这里登记的受控路径按名称取值（candidate.* / transaction.* / context.* / ruleRow.*），不执行任何表达式。
 * 同时承载本次求值内只读函数的结果：相同 Handler + 相同有效参数只真正执行一次，见 {@link FunctionExecutionService}；
 * 以及函数节点的输出（按节点 id 暂存，供后置函数以 FUNCTION_OUTPUT 引用）。
 */
public final class RuleExecutionContext {
    /**
     * 取值路径的分组（name 即页面和接口里用的编码）：CANDIDATE 当前正在评估的候选路由、TRANSACTION 当前支付交易的数据、
     * CONTEXT 规则引擎执行过程中的上下文数据；label 是日志里展示的中文名称。
     */
    public enum Group {
        CANDIDATE("当前正在评估的候选路由"),
        TRANSACTION("当前支付交易的数据"),
        CONTEXT("规则引擎执行过程中的上下文数据"),
        /** C3 条件逐行求值时的当前覆盖行（属性取自元素结构 REACH_ROW，只读）。 */
        RULE_ROW("当前正在判断的覆盖行");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * 受控取值路径：name 形如 candidate.providerCode；type 为 STRING / DECIMAL / INTEGER / DATE / BOOLEAN / STRING_LIST；label 供页面展示。
     */
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

    /**
     * C2 判定上下文：候选合作方、交易要素（国籍为 STRING_LIST）、决策时间；context.regulatoryRuleId 是正在判断附加触发条件的监管规则
     * （判断 C2 全局附加条件时为空）。
     */
    public static final List<Path> C2_PATHS = List.of(
            new Path("candidate.providerCode", Group.CANDIDATE, "STRING", "候选合作方编码"),
            new Path("transaction.directorNationalities", Group.TRANSACTION, "STRING_LIST", "客户董事国籍"),
            new Path("transaction.uboNationalities", Group.TRANSACTION, "STRING_LIST", "客户 UBO 国籍"),
            new Path("transaction.paymentCurrency", Group.TRANSACTION, "STRING", "交易币种"),
            new Path("transaction.debitCurrency", Group.TRANSACTION, "STRING", "扣款币种"),
            new Path("transaction.beneficiaryBankCountry", Group.TRANSACTION, "STRING", "受益行国家"),
            new Path("transaction.beneficiaryAddressCountry", Group.TRANSACTION, "STRING", "受益人地址国家"),
            new Path("transaction.beneficiaryRegion", Group.TRANSACTION, "STRING", "受益人地区"),
            new Path("transaction.orderingCountry", Group.TRANSACTION, "STRING", "汇款人国家"),
            new Path("transaction.clientCountry", Group.TRANSACTION, "STRING", "客户所在国家"),
            new Path("transaction.intermediaryCountry", Group.TRANSACTION, "STRING", "中间行国家"),
            new Path("transaction.purpose", Group.TRANSACTION, "STRING", "付款目的"),
            new Path("context.decisionTime", Group.CONTEXT, "DATE", "决策时间"),
            new Path("context.monthKey", Group.CONTEXT, "STRING", "决策月份 YYYY-MM"),
            new Path("context.ruleCode", Group.CONTEXT, "STRING", "规则编码"),
            new Path("context.regulatoryRuleId", Group.CONTEXT, "STRING", "正在判断附加触发条件的监管规则编码"));

    /** C3 覆盖行（元素结构 REACH_ROW）在条件里可以按 RULE_ROW 来源引用的属性，登记为 ruleRow.* 路径。 */
    public static final List<Path> RULE_ROW_PATHS = List.of(
            new Path("ruleRow.rowId", Group.RULE_ROW, "INTEGER", "覆盖行行号"),
            new Path("ruleRow.provider", Group.RULE_ROW, "STRING", "覆盖行合作方"),
            new Path("ruleRow.currency", Group.RULE_ROW, "STRING", "覆盖行币种"),
            new Path("ruleRow.country", Group.RULE_ROW, "STRING", "覆盖行受益行国家"),
            new Path("ruleRow.region", Group.RULE_ROW, "STRING", "覆盖行地区"),
            new Path("ruleRow.enabled", Group.RULE_ROW, "BOOLEAN", "覆盖行是否启用"),
            new Path("ruleRow.currencyMatched", Group.RULE_ROW, "BOOLEAN", "币种是否命中"),
            new Path("ruleRow.countryMatched", Group.RULE_ROW, "BOOLEAN", "国家是否命中"),
            new Path("ruleRow.regionMatched", Group.RULE_ROW, "BOOLEAN", "地区是否命中"),
            new Path("ruleRow.bankScopeMatched", Group.RULE_ROW, "BOOLEAN", "BIC 范围是否命中"),
            new Path("ruleRow.chargeOptionMatched", Group.RULE_ROW, "BOOLEAN", "扣费方式是否命中"),
            new Path("ruleRow.matched", Group.RULE_ROW, "BOOLEAN", "整行是否命中"),
            new Path("ruleRow.specificity", Group.RULE_ROW, "INTEGER", "匹配精度"));

    /** C3 条件求值上下文：候选合作方、覆盖行匹配要素、决策时间与配置版本；当前覆盖行的属性按 ruleRow.* 登记。 */
    public static final List<Path> C3_PATHS = concat(List.of(
            new Path("candidate.providerCode", Group.CANDIDATE, "STRING", "候选合作方编码"),
            new Path("transaction.paymentCurrency", Group.TRANSACTION, "STRING", "交易币种"),
            new Path("transaction.beneficiaryBankCountry", Group.TRANSACTION, "STRING", "受益行国家"),
            new Path("transaction.beneficiaryRegion", Group.TRANSACTION, "STRING", "受益人地区"),
            new Path("transaction.beneficiaryBankBic", Group.TRANSACTION, "STRING", "受益行 BIC"),
            new Path("transaction.chargeOption", Group.TRANSACTION, "STRING", "扣费方式"),
            new Path("context.decisionTime", Group.CONTEXT, "DATE", "决策时间"),
            new Path("context.monthKey", Group.CONTEXT, "STRING", "决策月份 YYYY-MM"),
            new Path("context.ruleCode", Group.CONTEXT, "STRING", "规则编码"),
            new Path("context.configRevision", Group.CONTEXT, "INTEGER", "路由配置版本"),
            new Path("context.c3ConditionVersion", Group.CONTEXT, "INTEGER", "C3 条件版本")), RULE_ROW_PATHS);

    /** C7 成本预估上下文：候选合作方、金额（交易币种 / 港币 / 美元等价）、扣费方式与决策时间。 */
    public static final List<Path> C7_PATHS = List.of(
            new Path("candidate.providerCode", Group.CANDIDATE, "STRING", "候选合作方编码"),
            new Path("transaction.clientId", Group.TRANSACTION, "STRING", "客户编号"),
            new Path("transaction.paymentCurrency", Group.TRANSACTION, "STRING", "交易币种"),
            new Path("transaction.debitCurrency", Group.TRANSACTION, "STRING", "扣款币种"),
            new Path("transaction.amount", Group.TRANSACTION, "DECIMAL", "交易金额（交易币种）"),
            new Path("transaction.amountHkd", Group.TRANSACTION, "DECIMAL", "交易金额（港币等价）"),
            new Path("transaction.amountUsd", Group.TRANSACTION, "DECIMAL", "交易金额（美元等价）"),
            new Path("transaction.chargeOption", Group.TRANSACTION, "STRING", "扣费方式"),
            new Path("context.decisionTime", Group.CONTEXT, "DATE", "决策时间"),
            new Path("context.monthKey", Group.CONTEXT, "STRING", "决策月份 YYYY-MM"),
            new Path("context.ruleCode", Group.CONTEXT, "STRING", "规则编码"));

    /** C8 偏好规则上下文：候选合作方、交易要素（金额为美元等价）、客户分层与决策时间。 */
    public static final List<Path> C8_PATHS = List.of(
            new Path("candidate.providerCode", Group.CANDIDATE, "STRING", "候选合作方编码"),
            new Path("transaction.clientId", Group.TRANSACTION, "STRING", "客户编号"),
            new Path("transaction.paymentCurrency", Group.TRANSACTION, "STRING", "交易币种"),
            new Path("transaction.beneficiaryBankCountry", Group.TRANSACTION, "STRING", "受益行国家"),
            new Path("transaction.beneficiaryRegion", Group.TRANSACTION, "STRING", "受益人地区"),
            new Path("transaction.amountUsd", Group.TRANSACTION, "DECIMAL", "本笔金额 USD"),
            new Path("transaction.paymentType", Group.TRANSACTION, "STRING", "交易类型"),
            new Path("transaction.clientSegment", Group.TRANSACTION, "STRING", "客户分层"),
            new Path("context.decisionTime", Group.CONTEXT, "DATE", "决策时间"),
            new Path("context.monthKey", Group.CONTEXT, "STRING", "决策月份 YYYY-MM"),
            new Path("context.ruleCode", Group.CONTEXT, "STRING", "规则编码"));

    private static List<Path> concat(List<Path> head, List<Path> tail) {
        List<Path> all = new ArrayList<>(head);
        all.addAll(tail);
        return List.copyOf(all);
    }

    private final String ruleCode;
    private final List<Path> paths;
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, FunctionExecutionService.Call> memo = new HashMap<>();
    // 复用结果对应的真实返回值（调用记录里的返回值是展示形态）
    private final Map<String, Object> memoValues = new HashMap<>();
    private final List<FunctionExecutionService.Call> calls = new ArrayList<>();
    // 函数节点 id → 最近一次调用记录（成功、复用或失败），供求值日志和条件树试算按节点给出明细
    private final Map<String, FunctionExecutionService.Call> nodeCalls = new HashMap<>();
    // 函数节点 id → 最近一次失败的异常（实参解析失败或函数执行失败），供试算区分「没有满足条件的记录」与真正的失败
    private final Map<String, Throwable> nodeFailures = new HashMap<>();
    // 请求级数据缓存：元数据字段取值、业务集合，按含上下文的 key 只加载一次
    private final Map<String, Object> data = new HashMap<>();
    // 函数节点的输出：按节点 id 暂存，供后置函数以 FUNCTION_OUTPUT 引用；只在本次求值内有效
    private final Map<String, Object> outputs = new HashMap<>();
    private final List<FieldValueService.Resolution> resolutions = new ArrayList<>();

    private RuleExecutionContext(String ruleCode, List<Path> paths) {
        this.ruleCode = ruleCode;
        this.paths = paths;
    }

    /** 可在条件里使用函数节点的规则（有各自的受控路径和执行上下文）。 */
    public static final Set<String> FUNCTION_RULES = Set.of("C1", "C2", "C3", "C5", "C7", "C8");

    /** 某条规则可用的受控路径；尚未接入函数的规则返回空清单。 */
    public static List<Path> paths(String ruleCode) {
        if (ruleCode == null) return List.of();
        return switch (ruleCode) {
            case "C1" -> C1_PATHS;
            case "C2" -> C2_PATHS;
            case "C3" -> C3_PATHS;
            case "C5" -> C5_PATHS;
            case "C7" -> C7_PATHS;
            case "C8" -> C8_PATHS;
            default -> List.of();
        };
    }

    /**
     * 通用工厂：按规则编码登记受控路径，并写入给定的取值（键须是该规则已登记的路径，否则报错；值为空表示本次没有取值）。
     * decisionTime 由调用方给出。
     */
    public static RuleExecutionContext of(String ruleCode, Map<String, Object> values, LocalDateTime decisionTime) {
        if (decisionTime == null) throw new IllegalArgumentException("决策时间不能为空");
        if (paths(ruleCode).isEmpty()) throw new IllegalArgumentException("规则 " + ruleCode + " 尚未接入函数节点");
        RuleExecutionContext context = new RuleExecutionContext(ruleCode, paths(ruleCode));
        for (Map.Entry<String, Object> entry : values.entrySet()) context.set(entry.getKey(), entry.getValue());
        context.values.put("context.decisionTime", decisionTime);
        context.values.put("context.monthKey", YearMonth.from(decisionTime).toString());
        context.values.put("context.ruleCode", ruleCode);
        return context;
    }

    /**
     * C2 判定上下文。directorNationalities / uboNationalities 为本次交易的国籍清单（没有上送时为 null，
     * 与 C2 的「未提供时沿用合并清单」一致，由调用方决定是否传入合并后的清单）。
     */
    public static RuleExecutionContext c2(String providerCode, List<String> directorNationalities, List<String> uboNationalities,
                                          Map<String, Object> transaction, String regulatoryRuleId, LocalDateTime decisionTime) {
        if (decisionTime == null) throw new IllegalArgumentException("决策时间不能为空");
        RuleExecutionContext context = new RuleExecutionContext("C2", C2_PATHS);
        context.values.put("candidate.providerCode", providerCode);
        context.values.put("transaction.directorNationalities", collection("C2", "transaction.directorNationalities", directorNationalities));
        context.values.put("transaction.uboNationalities", collection("C2", "transaction.uboNationalities", uboNationalities));
        for (Map.Entry<String, Object> entry : transaction.entrySet()) context.set(entry.getKey(), entry.getValue());
        context.values.put("context.regulatoryRuleId", regulatoryRuleId);
        context.values.put("context.decisionTime", decisionTime);
        context.values.put("context.monthKey", YearMonth.from(decisionTime).toString());
        context.values.put("context.ruleCode", "C2");
        return context;
    }

    /**
     * C3 条件求值上下文：按覆盖行匹配要素建一次，逐行求值时用 {@link #ruleRow} 派生出带当前行属性的上下文。
     */
    public static RuleExecutionContext c3(String providerCode, Map<String, Object> transaction, long configRevision,
                                          int conditionVersion, LocalDateTime decisionTime) {
        if (decisionTime == null) throw new IllegalArgumentException("决策时间不能为空");
        RuleExecutionContext context = new RuleExecutionContext("C3", C3_PATHS);
        context.values.put("candidate.providerCode", providerCode);
        for (Map.Entry<String, Object> entry : transaction.entrySet()) context.set(entry.getKey(), entry.getValue());
        context.values.put("context.configRevision", configRevision);
        context.values.put("context.c3ConditionVersion", (long) conditionVersion);
        context.values.put("context.decisionTime", decisionTime);
        context.values.put("context.monthKey", YearMonth.from(decisionTime).toString());
        context.values.put("context.ruleCode", "C3");
        return context;
    }

    /** 把当前覆盖行的属性写入 ruleRow.* 路径（同一上下文逐行复用；属性名须是 REACH_ROW 结构里的属性）。 */
    public RuleExecutionContext ruleRow(Map<String, Object> row) {
        for (Path path : RULE_ROW_PATHS) {
            String attribute = path.name().substring("ruleRow.".length());
            if (!row.containsKey(attribute)) throw new IllegalArgumentException("覆盖行缺少属性 " + attribute);
            values.put(path.name(), row.get(attribute));
        }
        return this;
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
        values.put(path, collection(ruleCode, path, value));
        return this;
    }

    /**
     * 字符串清单路径（如客户董事 / UBO 国籍）的取值包成只读清单：函数参数只接受 RuleList，不接受原始 List；
     * 这类清单是个人数据，展示时只给个数。值为 null（本次没有上送）保持 null，不会变成空清单。
     */
    private static Object collection(String ruleCode, String path, Object value) {
        if (value == null || value instanceof RuleList || !(value instanceof Collection<?> items)) return value;
        String type = paths(ruleCode).stream().filter(p -> p.name().equals(path)).map(Path::type).findFirst().orElse(null);
        return RuleList.STRING_LIST.equals(type) ? RuleList.ofPrivate(path, RuleList.STRING_LIST, items) : value;
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

    /** 记住一次成功调用的结果与真实返回值（调用记录里的返回值是展示形态，不能当输出用），供同一上下文内复用。 */
    void remember(String key, FunctionExecutionService.Call call, Object value) {
        memo.putIfAbsent(key, call);
        memoValues.putIfAbsent(key, value);
    }

    /** 复用结果时取回真实返回值（重新登记为函数节点的输出）。 */
    Object memoValue(String key) {
        return memoValues.get(key);
    }

    void record(FunctionExecutionService.Call call) {
        calls.add(call);
    }

    /** 记录一次调用；nodeId 不为空时同时登记为该函数节点的调用记录（后一次覆盖前一次）。 */
    void record(String nodeId, FunctionExecutionService.Call call) {
        calls.add(call);
        if (nodeId != null && !nodeId.isBlank()) nodeCalls.put(nodeId, call);
    }

    /** 记住某个函数节点最近一次失败的异常。 */
    void failed(String nodeId, Throwable failure) {
        if (nodeId != null && !nodeId.isBlank()) nodeFailures.put(nodeId, failure);
    }

    /** 某个函数节点最近一次失败的异常（原始异常）；没有失败过时为空。 */
    public Throwable failureOf(String nodeId) {
        return nodeId == null ? null : nodeFailures.get(nodeId);
    }

    /** 某个函数节点最近一次的调用记录；该节点在本次求值里没有执行过时为空。 */
    public FunctionExecutionService.Call callOf(String nodeId) {
        return nodeId == null ? null : nodeCalls.get(nodeId);
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

    // ---------- 函数输出（FUNCTION_OUTPUT） ----------

    /** 暂存函数节点的输出；同一节点在一次求值内只产生一次输出。 */
    public RuleExecutionContext output(String nodeId, Object value) {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("函数节点缺少 id");
        if (value == null) throw new IllegalStateException("函数节点 " + nodeId + " 没有输出");
        outputs.putIfAbsent(nodeId, value);
        return this;
    }

    /** 读取函数节点的输出；前置节点还没有执行时直接报错（不会当成空值）。 */
    public Object output(String nodeId) {
        if (nodeId == null || !outputs.containsKey(nodeId))
            throw new IllegalStateException("前置函数节点 " + nodeId + " 尚未执行，不能引用它的输出");
        return outputs.get(nodeId);
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
