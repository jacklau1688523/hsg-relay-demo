package com.hsg.relay.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.*;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.function.FunctionTypes;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.service.ExpressionService;
import com.hsg.relay.repo.*;
import com.hsg.relay.service.ClientExclusionChecker;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/function-metadata")
public class BusinessFunctionController {
    /** 函数库可以登记的 Handler：下面这批已规划的后端实现，加上代码里已注册的 Handler（BusinessFunctionRegistry）。 */
    private static final List<String> HANDLERS = List.of("clientExclusionChecker", "caseApprovalChecker", "appetiteRuleSelector",
            "restrictionMatcher", "providerCapabilityChecker", "reachRowMatcher", "bestReachSelector",
            "providerMtdReader", "availableFundingCalculator", "paymentLimitChecker", "totalCostCalculator",
            "commercialScoreCalculator", "fxWindowChecker");
    private final BusinessFunctionRepository functions;
    private final ClientExclusionChecker exclusionChecker;
    private final RuleAuditRepository audit;
    private final ObjectMapper json;
    private final FunctionMetadataService metadata;

    public BusinessFunctionController(BusinessFunctionRepository functions, ClientExclusionChecker exclusionChecker,
                                      RuleAuditRepository audit, ObjectMapper json, FunctionMetadataService metadata) {
        this.functions = functions;
        this.exclusionChecker = exclusionChecker;
        this.audit = audit;
        this.json = json;
        this.metadata = metadata;
    }

    public record Input(String functionCode, String functionName, String category, String returnType,
                        String allowedRules, String executionScope, String cacheStrategy, String handlerCode,
                        String dataSource, String parametersJson, String description, boolean enabled) {}
    public record ExclusionCheck(String providerCode, String clientId) {}

    /** 函数试算入参：arguments 参数绑定（同条件节点），operator / value 为比较方式和期望值，sample 为右侧模拟面板的要素。 */
    public record SimulationInput(Map<String, Object> arguments, String operator, Object value, Map<String, Object> sample,
                                  String ruleCode) {}

    @PostConstruct
    @Transactional
    public void seed() {
        if (functions.count() != 0) return;
        addSeed("clientExcluded", "客户是否被合作方排除", "客户与准入", "BOOLEAN", "C1", "CANDIDATE", "REQUEST", "clientExclusionChecker", "CLIENT_EXCLUSION", "[ {\"code\":\"providerCode\",\"type\":\"STRING\",\"source\":\"CANDIDATE\"}, {\"code\":\"clientId\",\"type\":\"STRING\",\"source\":\"TRANSACTION\"} ]", "查询合作方详情中维护的客户排除记录，按生效时间判断。", true);
        addSeed("hasValidCaseApproval", "是否存在有效个案审批", "客户与准入", "BOOLEAN", "C1", "CANDIDATE", "REQUEST", "caseApprovalChecker", "CASE_APPROVAL", "[]", "按合作方、客户及流转类别查找有效审批。", true);
        addSeed("findBestAppetiteRule", "查找最优准入规则", "客户与准入", "STRING", "C1", "CANDIDATE", "REQUEST", "appetiteRuleSelector", "APPETITE", "[]", "按币种和国家优先级选择准入规则。", true);
        addSeed("restrictionHit", "合作方限制命中检查", "合规与能力", "BOOLEAN", "C2", "CANDIDATE", "REQUEST", "restrictionMatcher", "PROVIDER_RESTRICTION", "[]", "检查合作方限制配置。", true);
        addSeed("hasProviderCapability", "合作方能力标签检查", "合规与能力", "BOOLEAN", "C2", "CANDIDATE", "REQUEST", "providerCapabilityChecker", "PROVIDER_CAPABILITY", "[]", "检查能力标签。", true);
        addSeed("reachRowMatches", "覆盖行匹配", "覆盖与限额", "BOOLEAN", "C3", "CANDIDATE", "REQUEST", "reachRowMatcher", "REACH_TABLE", "[]", "判断覆盖行是否匹配交易。", true);
        addSeed("findBestReachRow", "最精确覆盖行选择", "覆盖与限额", "STRING", "C3,C5,C6", "CANDIDATE", "REQUEST", "bestReachSelector", "REACH_TABLE", "[]", "按地区、国家、银行范围选最精确行。", true);
        addSeed("getProviderMtd", "合作方月度累计金额", "客户与准入", "DECIMAL", "C1,C9", "CANDIDATE", "REQUEST", "providerMtdReader", "PROVIDER_USAGE", "[]", "读取该合作方当前月累计金额。", true);
        addSeed("getAvailableFunding", "合作方可用资金", "头寸与限额", "DECIMAL", "C4", "CANDIDATE", "REQUEST", "availableFundingCalculator", "NOSTRO_BALANCE", "[]", "计算往来账可用余额。", true);
        addSeed("checkPaymentLimit", "交易限额检查", "头寸与限额", "BOOLEAN", "C6", "CANDIDATE", "REQUEST", "paymentLimitChecker", "PAYMENT_LIMIT", "[]", "检查单笔及当日限额。", true);
        addSeed("calculateTotalCost", "综合交易成本计算", "评分与外汇", "DECIMAL", "C7", "CANDIDATE", "REQUEST", "totalCostCalculator", "FEE_CONFIG", "[]", "计算综合费用。", true);
        addSeed("calculateCommercialScore", "商业履约评分", "评分与外汇", "DECIMAL", "C9", "CANDIDATE", "REQUEST", "commercialScoreCalculator", "COMMERCIAL_AGREEMENT", "[]", "按协议和月度进度评分。", true);
        addSeed("checkFxWindow", "外汇窗口检查", "评分与外汇", "BOOLEAN", "C10", "CANDIDATE", "REQUEST", "fxWindowChecker", "FX_WINDOW", "[]", "判断外汇窗口是否可用。", true);
    }

    private void addSeed(String code, String name, String category, String type, String rules, String scope,
                         String cache, String handler, String source, String params, String description, boolean enabled) {
        functions.save(convert(new Input(code, name, category, type, rules, scope, cache, handler, source, params, description, enabled)));
    }

    /** 函数库列表：每项带 usagePolicy（REQUIRED 系统必检 / OPTIONAL 可选）和 implemented（后端实现是否已注册）。 */
    @GetMapping
    public List<BusinessFunctionDefinition> list() {
        return functions.findAll().stream().sorted(Comparator.comparing(f -> f.functionCode)).map(metadata::decorate).toList();
    }

    /** 整棵条件树的试算：ruleCode 不传时按 C1；tree 为条件树，sample 为试算要素。 */
    public record EvaluateInput(String ruleCode, ExpressionService.Node tree, Map<String, Object> sample) {}

    /** 函数参数可以使用的取值来源（页面下拉用）。 */
    @GetMapping("/argument-sources")
    public List<Map<String, String>> argumentSources() {
        return FunctionMetadataService.ARGUMENT_SOURCES;
    }

    /** 可绑定的 Java Handler：已规划的一批，加上代码里已注册的。 */
    @GetMapping("/handlers")
    public List<String> handlers() {
        List<String> result = new ArrayList<>(HANDLERS);
        metadata.registeredHandlers().forEach(code -> { if (!result.contains(code)) result.add(code); });
        return result;
    }

    /** 「添加函数」选择器：受控路径、系统必检清单、该规则可添加的函数、可绑定为参数的数据字段、元素结构。C1 / C2 / C3 / C5 / C7 / C8。 */
    @GetMapping("/available")
    public FunctionMetadataService.Catalog available(@RequestParam String ruleCode) {
        return metadata.available(ruleCode);
    }

    /**
     * 函数试算：解析入参并调用函数，返回入参、返回值、条件结果、数据来源、耗时、异常、命中记录 ID 和逐项明细
     * （实参来源、类型、输入规模、返回类型、状态）。
     */
    @PostMapping("/{code}/simulate")
    public FunctionMetadataService.FunctionSimulation simulate(@PathVariable String code, @RequestBody SimulationInput input) {
        if (input == null) throw new IllegalArgumentException("试算参数不能为空");
        return metadata.simulate(code, input.arguments(), input.operator(), input.value(), input.sample(), input.ruleCode());
    }

    /** 整棵条件树试算：校验并执行树里的全部函数节点（含前置函数输出的引用），返回条件结果和每个函数节点的调用明细。 */
    @PostMapping("/evaluate")
    public FunctionMetadataService.Evaluation evaluate(@RequestBody EvaluateInput input) {
        if (input == null) throw new IllegalArgumentException("试算参数不能为空");
        return metadata.evaluate(input.ruleCode(), input.tree(), input.sample());
    }

    @PostMapping
    @Transactional
    public BusinessFunctionDefinition create(@RequestBody Input input) {
        BusinessFunctionDefinition value = convert(input);
        if (functions.existsById(value.functionCode)) throw new IllegalArgumentException("函数编码已存在");
        functions.save(value);
        metadata.decorate(value);
        writeAudit(value.functionCode, "CREATE", null, value);
        return value;
    }

    @PutMapping("/{code}")
    @Transactional
    public BusinessFunctionDefinition update(@PathVariable String code, @RequestBody Input input) {
        BusinessFunctionDefinition old = functions.findById(code).orElseThrow(() -> new IllegalArgumentException("函数不存在"));
        BusinessFunctionDefinition value = convert(input);
        if (!code.equals(value.functionCode)) throw new IllegalArgumentException("函数编码不可修改");
        String before = serialize(metadata.decorate(old));
        old.functionName = value.functionName;
        old.category = value.category;
        old.returnType = value.returnType;
        old.allowedRules = value.allowedRules;
        old.executionScope = value.executionScope;
        old.cacheStrategy = value.cacheStrategy;
        old.handlerCode = value.handlerCode;
        old.dataSource = value.dataSource;
        old.parametersJson = value.parametersJson;
        old.description = value.description;
        old.enabled = value.enabled;
        functions.save(old);
        metadata.decorate(old);
        writeAudit(code, "UPDATE", before, old);
        return old;
    }

    @PostMapping("/{code}/toggle")
    @Transactional
    public BusinessFunctionDefinition toggle(@PathVariable String code) {
        BusinessFunctionDefinition value = functions.findById(code).orElseThrow(() -> new IllegalArgumentException("函数不存在"));
        String before = serialize(metadata.decorate(value));
        value.enabled = !value.enabled;
        functions.save(value);
        metadata.decorate(value);
        writeAudit(code, value.enabled ? "ENABLE" : "DISABLE", before, value);
        return value;
    }

    /**
     * 系统必检（REQUIRED）函数不能删除：C1 执行器在代码里强制落实，删除绕不过，只能停用。
     * 已被字段绑定或已保存的 C1 条件引用的函数同样不能删除。
     */
    @DeleteMapping("/{code}")
    @Transactional
    public Map<String, String> delete(@PathVariable String code) {
        BusinessFunctionDefinition value = functions.findById(code).orElseThrow(() -> new IllegalArgumentException("函数不存在"));
        if (metadata.isRequired(code)) throw new IllegalArgumentException("C1 正在使用该系统必检函数，请停用而非删除");
        List<String> references = metadata.references(code);
        if (!references.isEmpty()) throw new IllegalArgumentException("函数已被引用，不能删除：" + String.join("、", references.stream().limit(5).toList()));
        writeAudit(code, "DELETE", serialize(value), null);
        functions.delete(value);
        return Map.of("deleted", code);
    }

    @PostMapping("/clientExcluded/check")
    public Map<String, Object> checkClientExcluded(@RequestBody ExclusionCheck req) {
        BusinessFunctionDefinition definition = functions.findById("clientExcluded").orElseThrow(() -> new IllegalArgumentException("函数未配置"));
        if (!definition.enabled) throw new IllegalArgumentException("函数已停用");
        if (req == null || blank(req.providerCode()) || blank(req.clientId())) throw new IllegalArgumentException("合作方和客户不能为空");
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Hong_Kong"));
        boolean hit = exclusionChecker.clientExcluded(req.providerCode(), req.clientId(), now);
        return Map.of("functionCode", "clientExcluded", "value", hit, "dataSource", "CLIENT_EXCLUSION");
    }

    private BusinessFunctionDefinition convert(Input in) {
        if (in == null) throw new IllegalArgumentException("函数信息不能为空");
        String code = trim(in.functionCode());
        if (!code.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("函数编码格式不正确");
        if (blank(in.functionName()) || in.functionName().length() > 80) throw new IllegalArgumentException("函数名称为必填项，最多80位");
        if (blank(in.category()) || in.category().length() > 40) throw new IllegalArgumentException("函数分类为必填项，最多40位");
        // 返回类型：标量、OBJECT，加上集合 / 分组类型（COLLECTION、STRING_SET、STRING_LIST、NUMBER_LIST、DATE_LIST、OBJECT_LIST、MAP），白名单见 FunctionTypes
        if (!FunctionTypes.RETURN_TYPES.contains(in.returnType() == null ? "" : in.returnType())) throw new IllegalArgumentException("返回类型无效");
        List<String> rules = Arrays.stream(trim(in.allowedRules()).split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (rules.isEmpty() || rules.stream().anyMatch(s -> !s.matches("C([1-9]|10)"))) throw new IllegalArgumentException("请选择 C1–C10 适用规则");
        // 集合函数的分类：只能适用于已接入函数节点的规则（有受控路径和执行上下文）
        if (trim(in.category()).startsWith("COLLECTION_") && !RuleExecutionContext.FUNCTION_RULES.containsAll(rules))
            throw new IllegalArgumentException("集合类函数只能适用于 " + String.join(" / ", RuleExecutionContext.FUNCTION_RULES.stream().sorted().toList())
                    + "，实际适用规则：" + String.join(",", rules));
        if (!List.of("CANDIDATE", "TRANSACTION", "GLOBAL").contains(in.executionScope())) throw new IllegalArgumentException("执行作用域无效");
        if (!List.of("REQUEST", "NONE").contains(in.cacheStrategy())) throw new IllegalArgumentException("缓存策略无效");
        if (!HANDLERS.contains(in.handlerCode()) && !metadata.registeredHandlers().contains(in.handlerCode()))
            throw new IllegalArgumentException("只能绑定已注册的 Java Handler");
        JsonNode params;
        try { params = json.readTree(blank(in.parametersJson()) ? "[]" : in.parametersJson()); }
        catch (Exception e) { throw new IllegalArgumentException("输入参数不是有效 JSON"); }
        if (!params.isArray() || params.size() > 20) throw new IllegalArgumentException("输入参数须为数组，最多20项");
        Set<String> codes = new HashSet<>();
        for (JsonNode p : params) {
            String paramCode = p.path("code").asText();
            if (!paramCode.matches("[A-Za-z][A-Za-z0-9_]{0,63}") || !codes.add(paramCode)
                    || !FunctionTypes.PARAMETER_TYPES.contains(p.path("type").asText())
                    || !List.of("CANDIDATE", "TRANSACTION", "CONTEXT", "FIELD", "ANY").contains(p.path("source").asText()))
                throw new IllegalArgumentException("参数编码、类型或来源无效，且编码不可重复");
        }
        if (trim(in.dataSource()).length() > 100 || trim(in.description()).length() > 2000) throw new IllegalArgumentException("数据来源或说明过长");
        BusinessFunctionDefinition f = new BusinessFunctionDefinition();
        f.functionCode = code;
        f.functionName = trim(in.functionName());
        f.category = trim(in.category());
        f.returnType = in.returnType();
        f.allowedRules = String.join(",", rules);
        f.executionScope = in.executionScope();
        f.cacheStrategy = in.cacheStrategy();
        f.handlerCode = in.handlerCode();
        f.dataSource = trim(in.dataSource());
        f.parametersJson = params.toString();
        f.description = trim(in.description());
        f.enabled = in.enabled();
        return f;
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static boolean blank(String s) { return trim(s).isEmpty(); }
    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("审计序列化失败", e); }
    }
    private void writeAudit(String code, String action, String before, Object after) {
        audit.save(new RuleAudit("BUSINESS_FUNCTION", code, action, "demo.operator", before, serialize(after)));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
