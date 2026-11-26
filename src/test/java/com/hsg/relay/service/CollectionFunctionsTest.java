package com.hsg.relay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.api.FieldMetadataController;
import com.hsg.relay.domain.BusinessFunctionDefinition;
import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.function.BusinessFunctionRegistry;
import com.hsg.relay.function.FunctionExecutionService;
import com.hsg.relay.function.FunctionMetadataService;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.collection.CollectionFunction;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.function.field.FieldValueResolver;
import com.hsg.relay.function.field.FieldValueService;
import com.hsg.relay.function.field.RuleList;
import com.hsg.relay.repo.BusinessFunctionRepository;
import com.hsg.relay.repo.ClientExclusionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 27 个通用集合函数：Handler 计算、元数据登记、参数解析（各来源）和条件树（AST）执行。
 * 集合不能写成常量，所以数据字段由测试用的字段解析器提供（可切换数据 / 模拟未加载 / 模拟读取失败 / 统计解析次数）。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:relaycollectionfunctions;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class CollectionFunctionsTest {
    /** 27 个集合函数，按四个分类。 */
    private static final List<String> COMPARE = List.of("collectionContains", "collectionNotContains", "collectionContainsAny",
            "collectionContainsAll", "collectionIntersects", "collectionIsEmpty", "collectionSize");
    private static final List<String> OPERATION = List.of("collectionUnion", "collectionIntersection", "collectionDifference",
            "collectionDistinct", "collectionFilter");
    private static final List<String> AGGREGATE = List.of("collectionSum", "collectionCount", "collectionCountWhere",
            "collectionSumWhere", "collectionAvg", "collectionMin", "collectionMax");
    private static final List<String> RECORD = List.of("collectionAnyMatch", "collectionAllMatch", "collectionNoneMatch",
            "collectionFilterRecords", "collectionFirstMatch", "collectionCountMatch", "collectionGroupBy", "collectionSort");
    private static final List<String> ALL = concat(concat(concat(COMPARE, OPERATION), AGGREGATE), RECORD);
    private static final String EXCLUDED = "providerExcludedClientIds";

    private static final String NUMBERS = "testNumbers", NUMBER_CANDIDATES = "testNumberCandidates", DATES = "testDates",
            STRINGS = "testStrings", STRING_CANDIDATES = "testStringCandidates", ITEMS = "testItems", CATEGORY = "testCategory",
            PRIVATE = "testPrivateStrings", FRESH = "testNumbersFresh";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 10, 0);
    private static final LocalDateTime DAY_1 = LocalDateTime.of(2026, 11, 1, 0, 0), DAY_2 = LocalDateTime.of(2026, 11, 2, 0, 0),
            DAY_3 = LocalDateTime.of(2026, 11, 3, 0, 0);
    private static final Map<String, Object> SAMPLE = Map.of("providerCode", "PROV-GD", "clientId", "C10001");

    // ---------- 测试用的元素结构与数据 ----------

    /** 测试用对象集合的元素结构。 */
    static final ElementSchema TEST_ITEM = new ElementSchema("TEST_ITEM", "测试记录", List.of(
            new ElementSchema.Attribute("itemId", "记录 ID", ElementSchema.STRING),
            new ElementSchema.Attribute("name", "名称", ElementSchema.STRING),
            new ElementSchema.Attribute("category", "类别", ElementSchema.STRING),
            new ElementSchema.Attribute("amount", "金额", ElementSchema.DECIMAL),
            new ElementSchema.Attribute("cap", "上限", ElementSchema.DECIMAL),
            new ElementSchema.Attribute("rank", "排序号", ElementSchema.INTEGER),
            new ElementSchema.Attribute("day", "日期", ElementSchema.DATE, true),
            new ElementSchema.Attribute("bonus", "奖励（可为空）", ElementSchema.DECIMAL, true)), "itemId");

    /** 测试数据：按字段编码存放解析器应返回的集合；值为 Supplier 时在解析时调用。 */
    static class TestLists {
        final Map<String, Object> data = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        /** 模拟读取失败的字段。 */
        final Set<String> broken = ConcurrentHashMap.newKeySet();
        /** 模拟「没有加载」（解析器返回 null）的字段。 */
        final Set<String> unloaded = ConcurrentHashMap.newKeySet();

        int calls(String field) {
            AtomicInteger count = calls.get(field);
            return count == null ? 0 : count.get();
        }

        Object resolve(String field) {
            calls.computeIfAbsent(field, key -> new AtomicInteger()).incrementAndGet();
            if (broken.contains(field)) throw new IllegalStateException("模拟数据源读取失败：" + field);
            if (unloaded.contains(field)) return null;
            Object value = data.get(field);
            if (value instanceof Supplier<?> supplier) value = supplier.get();
            if (value == null) throw new IllegalStateException("测试数据没有准备：" + field);
            return value;
        }

        @SuppressWarnings("unchecked")
        <T> T get(String field) {
            return (T) data.get(field);
        }
    }

    @TestConfiguration
    static class Fixtures {
        @Bean
        ElementSchema testItemSchema() {
            return TEST_ITEM;
        }

        @Bean
        TestLists testLists() {
            return new TestLists();
        }

        @Bean
        FieldValueResolver testListResolver(TestLists lists) {
            return new FieldValueResolver() {
                @Override
                public String resolverCode() {
                    return "testListResolver";
                }

                @Override
                public Object resolve(String fieldCode, RuleExecutionContext context) {
                    return lists.resolve(fieldCode);
                }
            };
        }
    }

    @Autowired
    ExpressionService expressions;
    @Autowired
    FunctionExecutionService execution;
    @Autowired
    FunctionMetadataService functionMetadata;
    @Autowired
    BusinessFunctionRegistry registry;
    @Autowired
    BusinessFunctionRepository definitions;
    @Autowired
    ClientExclusionRepository exclusions;
    @Autowired
    FieldMetadataController fieldsApi;
    @Autowired
    TestLists lists;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void registerFieldsAndResetData() {
        register(NUMBERS, "数值清单", "NUMBER_LIST", "DECIMAL", null, "REQUEST");
        register(FRESH, "不缓存的数值清单", "NUMBER_LIST", "DECIMAL", null, "NONE");
        register(NUMBER_CANDIDATES, "数值候选", "NUMBER_LIST", "DECIMAL", null, "REQUEST");
        register(DATES, "日期清单", "DATE_LIST", "DATE", null, "REQUEST");
        register(STRINGS, "字符串清单", "STRING_LIST", "STRING", null, "REQUEST");
        register(STRING_CANDIDATES, "字符串候选", "STRING_LIST", "STRING", null, "REQUEST");
        register(PRIVATE, "个人数据清单", "STRING_LIST", "STRING", null, "REQUEST");
        register(ITEMS, "记录集合", "OBJECT_LIST", "OBJECT", "TEST_ITEM", "REQUEST");
        register(CATEGORY, "当前类别", "STRING", null, null, "REQUEST");
        lists.data.clear();
        lists.calls.clear();
        lists.broken.clear();
        lists.unloaded.clear();
        numbers("0.1", "0.2", "2.50", "1");
        lists.data.put(NUMBER_CANDIDATES, RuleList.of(NUMBER_CANDIDATES, "NUMBER_LIST", List.of(new BigDecimal("1.00"))));
        lists.data.put(DATES, RuleList.of(DATES, "DATE_LIST", List.of(DAY_1, DAY_2, DAY_3)));
        lists.data.put(STRINGS, RuleList.of(STRINGS, "STRING_LIST", List.of("HK", "CN", "HK", "US")));
        lists.data.put(STRING_CANDIDATES, RuleList.of(STRING_CANDIDATES, "STRING_LIST", List.of()));
        lists.data.put(PRIVATE, RuleList.ofPrivate(PRIVATE, "STRING_LIST", List.of("HK", "CN")));
        lists.data.put(FRESH, RuleList.of(FRESH, "NUMBER_LIST", List.of(BigDecimal.ONE, BigDecimal.TEN)));
        lists.data.put(CATEGORY, "X");
        items("A", "X", "10.5", "10", 2L, DAY_2, "B", "Y", "2.5", "5", 1L, DAY_1,
                "C", "X", "7", "7", 2L, null, "D", "Z", "0.1", "0.05", 3L, DAY_3);
    }

    /** 通过元数据接口登记数据解析字段（适用规则含 C1 / C2 / C3）。 */
    private void register(String code, String name, String type, String elementType, String schema, String cache) {
        // 字符串清单可做「包含」判断；数值 / 日期清单、对象集合只能作为函数参数（运算符留空）
        String operators = "STRING".equals(type) ? "EQ" : "STRING_LIST".equals(type) ? "CONTAINS" : "";
        fieldsApi.create(new FieldMetadataController.Input(code, name, type, operators, "C1,C2,C3", null, true, "DATA_RESOLVER", null,
                null, "testListResolver", elementType, "CANDIDATE", cache, schema));
    }

    private void numbers(String... values) {
        List<BigDecimal> list = Arrays.stream(values).map(BigDecimal::new).toList();
        lists.data.put(NUMBERS, RuleList.of(NUMBERS, "NUMBER_LIST", list));
    }

    /** 四条记录：A（X，10.5，排序号 2）、B（Y，2.5，1）、C（X，7，2，日期为空）、D（Z，0.1，3）。 */
    private void items(Object... spec) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < spec.length; i += 6)
            rows.add(item((String) spec[i], (String) spec[i + 1], (String) spec[i + 2], (String) spec[i + 3], (Long) spec[i + 4],
                    (LocalDateTime) spec[i + 5]));
        lists.data.put(ITEMS, RuleList.records(ITEMS, TEST_ITEM, rows));
    }

    private static Map<String, Object> item(String id, String category, String amount, String cap, Long rank, LocalDateTime day) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemId", id);
        row.put("name", "name-" + id);
        row.put("category", category);
        row.put("amount", new BigDecimal(amount));
        row.put("cap", new BigDecimal(cap));
        row.put("rank", rank);
        row.put("day", day);
        row.put("bonus", null);
        return row;
    }

    private static List<String> ids(RuleList list) {
        return list.sample(RuleList.MAX_ELEMENTS).stream().map(String::valueOf).toList();
    }

    // ---------- 条件树与参数 ----------

    /** 可比较的函数节点（带运算符和期望值）。 */
    private static ExpressionService.Node node(String id, String code, Map<String, Object> arguments, String operator, Object value) {
        return new ExpressionService.Node("FUNCTION", null, null, null, operator, value, code, arguments, id);
    }

    /** 只被后置函数引用的函数节点（不带运算符和期望值）。 */
    private static ExpressionService.Node dep(String id, String code, Map<String, Object> arguments) {
        return node(id, code, arguments, null, null);
    }

    private static ExpressionService.Node group(String logic, ExpressionService.Node... children) {
        return new ExpressionService.Node(logic, List.of(children), null, null, null);
    }

    private static Map<String, Object> field(String code) {
        return Map.of("source", "FIELD", "fieldCode", code);
    }

    private static Map<String, Object> out(String nodeId) {
        return Map.of("source", "FUNCTION_OUTPUT", "nodeId", nodeId);
    }

    private static Map<String, Object> path(String source, String fieldCode) {
        return Map.of("source", source, "fieldCode", fieldCode);
    }

    private static Map<String, Object> konst(Object value) {
        return Map.of("source", "CONSTANT", "value", value);
    }

    private static Map<String, Object> sorted(String field, String direction) {
        return Map.of("source", "CONSTANT", "value", List.of(Map.of("field", field, "direction", direction)));
    }

    /** 谓词叶子：属性、运算符、比较对象（字面量或来源映射）。 */
    private static Map<String, Object> leaf(String field, String operator, Object value) {
        return Map.of("type", "ELEMENT", "field", field, "operator", operator, "value", value);
    }

    private static Map<String, Object> predicate(Object condition) {
        return Map.of("source", "PREDICATE", "condition", condition);
    }

    private static Map<String, Object> group(String logic, Object... children) {
        return Map.of("type", "GROUP", "logic", logic, "children", List.of(children));
    }

    private static Map<String, Object> on(String collectionParameter, Map<String, Object> collection, Map<String, Object> predicate,
                                          Object... extra) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(collectionParameter, collection);
        arguments.put("predicate", predicate);
        for (int i = 0; i < extra.length; i += 2) arguments.put((String) extra[i], extra[i + 1]);
        return arguments;
    }

    private static Map<String, Object> on(Map<String, Object> collection, Map<String, Object> predicate, Object... extra) {
        return on("collection", collection, predicate, extra);
    }

    private static RuleExecutionContext c1() {
        return RuleExecutionContext.c1("PROV-GD", "C10001", "USD", "KG", new BigDecimal("100"), NOW);
    }

    private static RuleExecutionContext c2(List<String> director, List<String> ubo) {
        return RuleExecutionContext.c2("PROV-GD", director, ubo, Map.of("transaction.paymentCurrency", "USD"), null, NOW);
    }

    private ExpressionService.Compiled compile(ExpressionService.Node tree, String rule) {
        return expressions.compileDetailed(tree, rule);
    }

    private boolean run(ExpressionService.Node tree, RuleExecutionContext context) {
        return expressions.evaluate(compile(tree, context.ruleCode()), Map.of(), context).matched();
    }

    /** 单个函数：按元数据校验参数后在上下文里调用；输出登记在 context.output(id)。 */
    private FunctionExecutionService.Call call(String id, String code, Map<String, Object> arguments, RuleExecutionContext context) {
        return execution.invoke(execution.validate(code, arguments, context.ruleCode()).at(id, code), context);
    }

    /** 函数节点的集合输出（OBJECT_LIST / 标量清单）。 */
    private static RuleList list(RuleExecutionContext context, String id) {
        return (RuleList) context.output(id);
    }

    /** 函数节点的分组输出（MAP）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(RuleExecutionContext context, String id) {
        return (Map<String, Object>) context.output(id);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    // ---------- 登记 ----------

    @Test
    void allTwentySevenFunctionsAreRegisteredWithMetadataAndHandlers() {
        assertThat(ALL).hasSize(27);
        assertThat(registry.handlerCodes()).containsAll(ALL);
        List<String> rules = RuleExecutionContext.FUNCTION_RULES.stream().sorted().toList();
        for (String code : ALL) {
            BusinessFunctionDefinition definition = definitions.findById(code).orElseThrow();
            assertThat(definition.enabled).as(code).isTrue();
            assertThat(definition.returnType).as(code).isEqualTo(registry.find(definition.handlerCode).orElseThrow().returnType());
            assertThat(definition.handlerCode).as(code).isEqualTo(code);
            assertThat(Arrays.asList(definition.allowedRules.split(","))).as(code).containsExactlyInAnyOrderElementsOf(rules);
            String category = COMPARE.contains(code) ? "COLLECTION_COMPARE" : OPERATION.contains(code) ? "COLLECTION_OPERATION"
                    : AGGREGATE.contains(code) ? "COLLECTION_AGGREGATE" : "COLLECTION_RECORD";
            // 升级前已写入的两行不改动分类；新装的取新分类
            if (!List.of("collectionContains", "collectionIntersects").contains(code))
                assertThat(definition.category).as(code).isEqualTo(category);
            assertThat(functionMetadata.isExecutable(code, "C1")).as(code).isTrue();
            assertThat(functionMetadata.isExecutable(code, "C4")).as(code).isFalse();
        }
        assertThat(definitions.findById("numberMin")).isPresent();
        // 选择器：返回 OBJECT / MAP 的两个函数不能直接比较、也没有后置函数接收，只可在函数库单独试算
        List<String> listed = functionMetadata.available("C1").functions().stream().map(FunctionMetadataService.FunctionView::functionCode).toList();
        List<String> referable = ALL.stream().filter(code -> !List.of("collectionFirstMatch", "collectionGroupBy").contains(code)).toList();
        assertThat(listed).containsAll(referable).doesNotContain("collectionFirstMatch", "collectionGroupBy");
        FunctionMetadataService.FunctionView sort = functionMetadata.available("C2").functions().stream()
                .filter(f -> f.functionCode().equals("collectionSort")).findFirst().orElseThrow();
        assertThat(sort.category()).isEqualTo("COLLECTION_RECORD");
        assertThat(sort.parameters()).extracting(FunctionMetadataService.ParameterView::code).containsExactly("collection", "sortFields");
        assertThat(sort.parameters().get(1).type()).isEqualTo("SORT_FIELDS");
        assertThat(sort.referable()).isTrue();
        assertThat(sort.operators()).isEmpty();
        // 元数据类型扩展与元素结构
        assertThat(functionMetadata.elementSchemas()).extracting(m -> String.valueOf(m.get("code"))).contains("TEST_ITEM", "REACH_ROW",
                "PROVIDER_RESTRICTION", "COST_COMPONENT", "PREFERENCE_RULE");
        assertThat(fieldsApi.resolvers()).contains("testListResolver");
        assertThat(fieldsApi.list()).extracting((FieldDefinition f) -> f.fieldCode).contains(NUMBERS, DATES, STRINGS, ITEMS, PRIVATE);
        // 未登记的类型 / 未登记的元素结构不能保存
        assertThatThrownBy(() -> register("testBadType", "坏类型", "COLLECTION", "", null, "REQUEST")).hasMessage("不支持的数据类型");
        assertThatThrownBy(() -> register("testBadSchema", "坏结构", "OBJECT_LIST", "OBJECT", "NO_SUCH_SCHEMA", "REQUEST"))
                .hasMessage("元素结构 NO_SUCH_SCHEMA 未登记，只能从已登记的结构里选择");
    }

    // ---------- 集合判断 ----------

    @Test
    void predicatesCompareWholeElementsAndDistinguishCase() {
        RuleExecutionContext context = c1();
        lists.data.put(STRING_CANDIDATES, RuleList.of(STRING_CANDIDATES, "STRING_LIST", List.of("XX", "US")));
        // 包含：整串相等、区分大小写
        assertThat(run(node("n", "collectionContains", Map.of("collection", field(STRINGS), "element", konst("HK")), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionContains", Map.of("collection", field(STRINGS), "element", konst("hk")), "EQ", true), context)).isFalse();
        assertThat(run(node("n", "collectionNotContains", Map.of("collection", field(STRINGS), "element", konst("hk")), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionNotContains", Map.of("collection", field(STRINGS), "element", konst("US")), "EQ", true), context)).isFalse();
        // 任一 / 全部
        assertThat(run(node("n", "collectionContainsAny", Map.of("collection", field(STRINGS), "candidates", field(STRING_CANDIDATES)), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionContainsAll", Map.of("collection", field(STRINGS), "required", field(STRING_CANDIDATES)), "EQ", true), context)).isFalse();
        lists.data.put(STRING_CANDIDATES, RuleList.of(STRING_CANDIDATES, "STRING_LIST", List.of("HK", "US")));
        context = c1();
        assertThat(run(node("n", "collectionContainsAll", Map.of("collection", field(STRINGS), "required", field(STRING_CANDIDATES)), "EQ", true), context)).isTrue();
        lists.data.put(STRING_CANDIDATES, RuleList.of(STRING_CANDIDATES, "STRING_LIST", List.of("hk")));
        context = c1();
        assertThat(run(node("n", "collectionContainsAll", Map.of("collection", field(STRINGS), "required", field(STRING_CANDIDATES)), "EQ", true), context)).isFalse();
        // 交集与个数：清单按元素计（重复各算一个）
        assertThat(run(node("n", "collectionIntersects", Map.of("leftCollection", field(STRINGS), "rightCollection", field(STRING_CANDIDATES)), "EQ", true), context)).isFalse();
        lists.data.put(STRING_CANDIDATES, RuleList.of(STRING_CANDIDATES, "STRING_LIST", List.of("US", "JP")));
        context = c1();
        assertThat(run(node("n", "collectionIntersects", Map.of("leftCollection", field(STRINGS), "rightCollection", field(STRING_CANDIDATES)), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionSize", Map.of("collection", field(STRINGS)), "EQ", 4), context)).isTrue();
        assertThat(run(node("n", "collectionIsEmpty", Map.of("collection", field(STRINGS)), "EQ", false), context)).isTrue();
        assertThat(run(node("n", "collectionCount", Map.of("collection", field(STRINGS)), "EQ", 4), context)).isTrue();
        // 整数 / 布尔输出可以继续用比较运算符；集合输出不行
        assertThat(run(node("n", "collectionSize", Map.of("collection", field(STRINGS)), "GTE", 3), context)).isTrue();
        assertThatThrownBy(() -> compile(node("n", "collectionSize", Map.of("collection", field(STRINGS)), "CONTAINS", "3"), "C1"))
                .hasMessageContaining("不支持运算符 CONTAINS");
    }

    @Test
    void numericElementsCompareByValueAndMixedElementTypesAreRejected() {
        RuleExecutionContext context = c1();
        // 1.00 与 1 按数值相等
        assertThat(run(node("n", "collectionContainsAny", Map.of("collection", field(NUMBERS), "candidates", field(NUMBER_CANDIDATES)), "EQ", true), context)).isTrue();
        lists.data.put(NUMBER_CANDIDATES, RuleList.of(NUMBER_CANDIDATES, "NUMBER_LIST", List.of(new BigDecimal("2.500"), new BigDecimal("1"))));
        context = c1();
        assertThat(run(node("n", "collectionContainsAll", Map.of("collection", field(NUMBERS), "required", field(NUMBER_CANDIDATES)), "EQ", true), context)).isTrue();
        lists.data.put(NUMBER_CANDIDATES, RuleList.of(NUMBER_CANDIDATES, "NUMBER_LIST", List.of(new BigDecimal("3"))));
        context = c1();
        assertThat(run(node("n", "collectionContainsAny", Map.of("collection", field(NUMBERS), "candidates", field(NUMBER_CANDIDATES)), "EQ", false), context)).isTrue();
        // 参数类型不匹配在保存 / 编译期拦下：数值清单不能给 STRING_SET 参数
        assertThatThrownBy(() -> compile(node("n", "collectionContains", Map.of("collection", field(NUMBERS), "element", konst("1")), "EQ", true), "C1"))
                .hasMessageContaining("与参数要求的 STRING_SET 不一致");
        // 元素类型不一致的两个清单：运行期报错，不会当成「不相交」
        RuleExecutionContext mixed = c1();
        assertThatThrownBy(() -> run(node("n", "collectionContainsAny", Map.of("collection", field(STRINGS), "candidates", field(NUMBERS)), "EQ", true), mixed))
                .isInstanceOf(FunctionExecutionService.FunctionExecutionException.class).hasMessageContaining("元素类型不一致");
    }

    @Test
    void emptyCollectionsFollowTheConfirmedEmptySemantics() {
        RuleExecutionContext context = c1();
        numbers();
        lists.data.put(STRING_CANDIDATES, RuleList.of(STRING_CANDIDATES, "STRING_LIST", List.of()));
        lists.data.put(DATES, RuleList.of(DATES, "DATE_LIST", List.of()));
        items();
        lists.data.put(STRINGS, RuleList.of(STRINGS, "STRING_LIST", List.of()));
        assertThat(run(node("n", "collectionContains", Map.of("collection", field(STRINGS), "element", konst("HK")), "EQ", false), context)).isTrue();
        assertThat(run(node("n", "collectionNotContains", Map.of("collection", field(STRINGS), "element", konst("HK")), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionIntersects", Map.of("leftCollection", field(STRINGS), "rightCollection", field(STRING_CANDIDATES)), "EQ", false), context)).isTrue();
        assertThat(run(node("n", "collectionContainsAny", Map.of("collection", field(STRINGS), "candidates", field(STRING_CANDIDATES)), "EQ", false), context)).isTrue();
        assertThat(run(node("n", "collectionContainsAll", Map.of("collection", field(STRINGS), "required", field(STRING_CANDIDATES)), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionIsEmpty", Map.of("collection", field(STRING_CANDIDATES)), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionSize", Map.of("collection", field(NUMBERS)), "EQ", 0), context)).isTrue();
        assertThat(run(node("n", "collectionCount", Map.of("collection", field(NUMBERS)), "EQ", 0), context)).isTrue();
        assertThat(run(node("n", "collectionCountWhere", on(field(NUMBERS), predicate(leaf("value", "GT", "0"))), "EQ", 0), context)).isTrue();
        assertThat(run(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "0"), context)).isTrue();
        assertThat(run(node("n", "collectionSumWhere", on(field(ITEMS), predicate(leaf("category", "EQ", "X")), "valueSelector", konst("amount")), "EQ", "0"), context)).isTrue();
        assertThat(run(node("n", "collectionAnyMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), "EQ", false), context)).isTrue();
        assertThat(run(node("n", "collectionAllMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionNoneMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), "EQ", true), context)).isTrue();
        assertThat(run(node("n", "collectionCountMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), "EQ", 0), context)).isTrue();
        // 空集合的派生结果仍是同类型的空集合；分组为空；第一条匹配属于「没有满足条件的记录」
        ExpressionService.Node tree = group("AND", dep("sort", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("rank", "ASC"))),
                dep("filter", "collectionFilterRecords", on(out("sort"), predicate(leaf("category", "EQ", "X")))),
                node("size", "collectionSize", Map.of("collection", out("filter")), "EQ", 0));
        assertThat(run(tree, context)).isTrue();
        assertThat(list(context, "filter").dataType()).isEqualTo("OBJECT_LIST");
        assertThat(list(context, "sort").isEmpty()).isTrue();
        call("group", "collectionGroupBy", Map.of("collection", field(ITEMS), "groupField", konst("category")), context);
        assertThat(map(context, "group")).isEmpty();
        assertThatThrownBy(() -> call("first", "collectionFirstMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), context))
                .isInstanceOf(FunctionExecutionService.FunctionExecutionException.class).hasMessageContaining("没有满足条件的记录");
        // 平均值 / 最小 / 最大：空集合属于「没有结果」，报错而不返回 0
        for (String code : List.of("collectionAvg", "collectionMin", "collectionMax"))
            assertThatThrownBy(() -> run(node("n", code, Map.of("collection", field(NUMBERS)), "EQ", "0"), context))
                    .isInstanceOf(FunctionExecutionService.FunctionExecutionException.class).hasMessageContaining("集合为空，没有可计算的");
    }

    // ---------- 集合运算 ----------

    @Test
    void setOperationsKeepFirstOccurrenceOrderAndFollowTheLeftType() {
        RuleExecutionContext context = c2(List.of("HK", "CN"), List.of("CN", "US"));
        Map<String, Object> director = path("TRANSACTION", "directorNationalities"), ubo = path("TRANSACTION", "uboNationalities");
        ExpressionService.Node tree = group("AND",
                dep("union", "collectionUnion", Map.of("leftCollection", director, "rightCollection", ubo)),
                dep("distinct", "collectionDistinct", Map.of("collection", out("union"))),
                dep("intersection", "collectionIntersection", Map.of("leftCollection", director, "rightCollection", ubo)),
                dep("difference", "collectionDifference", Map.of("leftCollection", director, "rightCollection", ubo)),
                node("size", "collectionSize", Map.of("collection", out("distinct")), "EQ", 3),
                node("intersects", "collectionSize", Map.of("collection", out("intersection")), "EQ", 1),
                node("leftOnly", "collectionSize", Map.of("collection", out("difference")), "EQ", 1),
                node("contains", "collectionContains", Map.of("collection", out("union"), "element", konst("US")), "EQ", true));
        assertThat(run(tree, context)).isTrue();
        RuleList union = list(context, "union");
        assertThat(union.dataType()).isEqualTo("STRING_LIST");
        assertThat(union.elements()).containsExactly("HK", "CN", "CN", "US");
        assertThat(list(context, "distinct").elements()).containsExactly("HK", "CN", "US");
        assertThat(list(context, "intersection").elements()).containsExactly("CN");
        assertThat(list(context, "difference").elements()).containsExactly("HK");
        // 差集有方向：B 减 A
        assertThat(run(group("AND", dep("reverse", "collectionDifference", Map.of("leftCollection", ubo, "rightCollection", director)),
                node("n", "collectionContains", Map.of("collection", out("reverse"), "element", konst("HK")), "EQ", false)), context)).isTrue();
        // 交集任一方为空 → false；已确认的空集合运算结果为空
        RuleExecutionContext empty = c2(List.of(), List.of("CN"));
        assertThat(run(group("AND", dep("intersection", "collectionIntersection", Map.of("leftCollection", director, "rightCollection", ubo)),
                node("n", "collectionSize", Map.of("collection", out("intersection")), "EQ", 0)), empty)).isTrue();
        assertThat(list(empty, "intersection").isEmpty()).isTrue();
    }

    @Test
    void distinctKeepsFirstOccurrenceAndTreatsCaseVariantsAsDifferent() {
        RuleExecutionContext context = c1();
        lists.data.put(STRINGS, RuleList.of(STRINGS, "STRING_LIST", List.of("A", "b", "a", "B", "b")));
        assertThat(run(group("AND", dep("d", "collectionDistinct", Map.of("collection", field(STRINGS))),
                node("n", "collectionSize", Map.of("collection", out("d")), "EQ", 4)), context)).isTrue();
        assertThat(((RuleList) list(context, "d")).elements()).containsExactly("A", "b", "a", "B");
        // 字符串集合（已去重）：size 按去重后的成员计
        exclusions.save(new ClientExclusion("C10001", "PROV-GD", "test", NOW.minusDays(1), null));
        exclusions.save(new ClientExclusion("C10002", "PROV-GD", "test", NOW.minusDays(1), null));
        assertThat(run(node("n", "collectionSize", Map.of("collection", field(EXCLUDED)), "EQ", 2), context)).isTrue();
    }

    @Test
    void filterKeepsOrderForStringNumberAndDateLists() {
        RuleExecutionContext context = c1();
        assertThat(run(group("AND", dep("strings", "collectionFilter", on(field(STRINGS), predicate(leaf("value", "CONTAINS", "H")))),
                dep("numbers", "collectionFilter", on(field(NUMBERS), predicate(leaf("value", "GT", "0.2")))),
                dep("dates", "collectionFilter", on(field(DATES), predicate(leaf("value", "GT", "2026-11-01")))),
                node("a", "collectionSize", Map.of("collection", out("strings")), "EQ", 2),
                node("b", "collectionSize", Map.of("collection", out("numbers")), "EQ", 2),
                node("c", "collectionSize", Map.of("collection", out("dates")), "EQ", 2)), context)).isTrue();
        assertThat(((RuleList) list(context, "strings")).elements()).containsExactly("HK", "HK");
        assertThat(((RuleList) list(context, "numbers")).elements()).containsExactly(new BigDecimal("2.50"), new BigDecimal("1"));
        assertThat(((RuleList) list(context, "dates")).elements()).containsExactly(DAY_2, DAY_3);
        RuleList numbers = list(context, "numbers");
        assertThat(numbers.dataType()).isEqualTo("NUMBER_LIST");
        assertThat(numbers.source()).isEqualTo("FUNCTION:collectionFilter");
        // 标量清单的谓词属性固定写 value；写别的名字在编译期报错
        assertThatThrownBy(() -> compile(dep("f", "collectionFilter", on(field(NUMBERS), predicate(leaf("amount", "GT", "1")))), "C1"))
                .hasMessageContaining("标量集合按元素本身判断");
        // 过滤结果可以继续参与聚合
        assertThat(run(group("AND", dep("small", "collectionFilter", on(field(NUMBERS), predicate(leaf("value", "LTE", "0.2")))),
                node("sum", "collectionSum", Map.of("collection", out("small")), "EQ", "0.3")), context)).isTrue();
    }

    // ---------- 聚合 ----------

    @Test
    void aggregatesUseBigDecimalAndOneDivisionPrecision() {
        RuleExecutionContext context = c1();
        ExpressionService.Node tree = group("AND",
                node("sum", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "3.8"),
                node("count", "collectionCount", Map.of("collection", field(NUMBERS)), "EQ", 4),
                node("min", "collectionMin", Map.of("collection", field(NUMBERS)), "EQ", "0.1"),
                node("max", "collectionMax", Map.of("collection", field(NUMBERS)), "EQ", "2.5"),
                node("items", "collectionSum", Map.of("collection", field(ITEMS), "valueSelector", konst("amount")), "EQ", "20.1"),
                node("rank", "collectionMax", Map.of("collection", field(ITEMS), "valueSelector", konst("rank")), "EQ", "3"));
        for (ExpressionService.Node single : tree.children()) assertThat(run(group("AND", single), c1())).as(single.id()).isTrue();
        assertThat(run(tree, context)).isTrue();
        // 平均值：统一保留 6 位小数、四舍五入；返回值按数值精确校验。
        // （0.95 这类字面量在 Aviator 5.4.1 里被解析成偏一个最低位的 double，条件「== 0.95」会判为假——这是引擎原有的问题，
        //   现有字段条件同样受影响；是否开启精确十进制解析须由业务方决定，这里不让测试依赖它。）
        assertThat(run(group("AND", node("avg", "collectionAvg", Map.of("collection", field(NUMBERS)), "GT", "0")), c1())).isTrue();
        RuleExecutionContext averaged = c1();
        assertThat(run(group("AND", node("avg", "collectionAvg", Map.of("collection", field(NUMBERS)), "GT", "0")), averaged)).isTrue();
        BigDecimal average = (BigDecimal) averaged.callOf("avg").value();
        assertThat(average).isEqualByComparingTo("0.95");
        assertThat(average.toPlainString()).isEqualTo("0.950000");
        assertThat(average.scale()).isEqualTo(6);
        numbers("1", "2", "2");
        RuleExecutionContext thirds = c1();
        assertThat(run(group("AND", node("avg", "collectionAvg", Map.of("collection", field(NUMBERS)), "GT", "0")), thirds)).isTrue();
        assertThat(((BigDecimal) thirds.callOf("avg").value()).toPlainString()).isEqualTo("1.666667");
        // 0.1 + 0.2：BigDecimal 精确相加，不是浮点近似
        numbers("0.1", "0.2");
        context = c1();
        assertThat(run(group("AND", node("sum", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "0.3")), context)).isTrue();
        assertThat(((BigDecimal) context.callOf("sum").value()).toPlainString()).isEqualTo("0.3");
        assertThat(((BigDecimal) context.callOf("sum").value()).scale()).isEqualTo(1);
    }

    @Test
    void aggregatesRejectNonNumericInputsAndDoNotSkipNullValues() {
        RuleExecutionContext context = c1();
        // 字符串清单不能求和：运行期报错
        assertThatThrownBy(() -> run(node("n", "collectionSum", Map.of("collection", field(STRINGS)), "EQ", "0"), context))
                .isInstanceOf(FunctionExecutionService.FunctionExecutionException.class).hasMessageContaining("应为数值清单 NUMBER_LIST 或对象集合");
        // 对象集合必须指定取值属性；属性须在元素结构内且为数值
        assertThatThrownBy(() -> run(node("n", "collectionSum", Map.of("collection", field(ITEMS)), "EQ", "0"), context))
                .hasMessageContaining("必填：对象集合须指定参与汇总的属性");
        assertThatThrownBy(() -> run(node("n", "collectionSum", Map.of("collection", field(ITEMS), "valueSelector", konst("nope")), "EQ", "0"), context))
                .hasMessageContaining("不在元素结构 TEST_ITEM 里");
        assertThatThrownBy(() -> run(node("n", "collectionSum", Map.of("collection", field(ITEMS), "valueSelector", konst("name")), "EQ", "0"), context))
                .hasMessageContaining("不是数值，不能汇总");
        // 必填属性为空：记录在载入时就被拒绝（数据不完整）
        Map<String, Object> nullAmount = item("B", "Y", "2.5", "5", 1L, DAY_1);
        nullAmount.put("amount", null);
        assertThatThrownBy(() -> RuleList.records(ITEMS, TEST_ITEM, List.of(item("A", "X", "10.5", "10", 2L, DAY_2), nullAmount)))
                .hasMessageContaining("缺少属性 TEST_ITEM.amount（数据不完整）");
        // 允许为空的数值属性：汇总时报错，不会静默跳过
        assertThatThrownBy(() -> run(node("n", "collectionSum", Map.of("collection", field(ITEMS), "valueSelector", konst("bonus")), "EQ", "0"), context))
                .hasMessageContaining("第 1 条记录的属性 bonus 为空，不能参与汇总（不会静默忽略）");
        assertThatThrownBy(() -> run(node("n", "collectionMax", Map.of("collection", field(ITEMS), "valueSelector", konst("bonus")), "EQ", "0"), c1()))
                .hasMessageContaining("第 1 条记录的属性 bonus 为空，不能参与汇总（不会静默忽略）");
        // 清单里的 null 元素在载入时就被拒绝
        assertThatThrownBy(() -> RuleList.of(NUMBERS, "NUMBER_LIST", Arrays.asList(BigDecimal.ONE, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RuleList.of(STRINGS, "STRING_LIST", Arrays.asList("HK", null))).isInstanceOf(IllegalArgumentException.class);
        // 记录缺属性
        assertThatThrownBy(() -> RuleList.records(ITEMS, TEST_ITEM, List.of(Map.of("itemId", "A")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countWhereAndSumWhereEvaluateThePredicatePerElement() {
        RuleExecutionContext context = c1();
        ExpressionService.Node tree = group("AND",
                node("count", "collectionCountWhere", on(field(NUMBERS), predicate(leaf("value", "GT", "0.2"))), "EQ", 2),
                node("sum", "collectionSumWhere", on(field(ITEMS), predicate(leaf("category", "EQ", "X")), "valueSelector", konst("amount")), "EQ", "17.5"),
                node("countX", "collectionCountMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), "EQ", 2),
                node("none", "collectionSumWhere", on(field(ITEMS), predicate(leaf("category", "EQ", "Q")), "valueSelector", konst("amount")), "EQ", "0"));
        assertThat(run(tree, context)).isTrue();
        assertThat(context.callOf("count").value()).isEqualTo(2L);
        assertThat(context.callOf("countX").value()).isEqualTo(2L);
        // 比较对象没有取值：报错，不会按「不满足」处理
        lists.unloaded.add(CATEGORY);
        assertThatThrownBy(() -> run(node("n", "collectionCountMatch", on(field(ITEMS), predicate(leaf("category", "EQ", path("FIELD", CATEGORY)))), "EQ", 2), context))
                .hasMessageContaining("没有返回结果（数据未加载）");
        lists.unloaded.clear();
        // 必填的谓词不能缺
        assertThatThrownBy(() -> compile(node("n", "collectionSumWhere", Map.of("collection", field(ITEMS)), "EQ", "0"), "C1"))
                .hasMessage("函数 collectionSumWhere 缺少必填参数：predicate");
    }

    // ---------- 记录匹配 ----------

    @Test
    void recordMatchingEvaluatesEachRecordAsAWhole() {
        RuleExecutionContext context = c1();
        // 没有一条记录同时满足「类别 X 且金额小于 5」：A 是 X 但金额 10.5，B 金额 2.5 但类别 Y——不能跨记录拼接
        Map<String, Object> spliced = predicate(group("AND", leaf("category", "EQ", "X"), leaf("amount", "LT", "5")));
        ExpressionService.Node tree = group("AND",
                dep("filtered", "collectionFilterRecords", on(field(ITEMS), spliced)),
                node("any", "collectionAnyMatch", on(field(ITEMS), spliced), "EQ", false),
                node("all", "collectionAllMatch", on(field(ITEMS), spliced), "EQ", false),
                node("none", "collectionNoneMatch", on(field(ITEMS), spliced), "EQ", true),
                node("count", "collectionCountMatch", on(field(ITEMS), spliced), "EQ", 0),
                node("size", "collectionSize", Map.of("collection", out("filtered")), "EQ", 0),
                // 整条记录满足的组合条件
                node("anyWhole", "collectionAnyMatch", on(field(ITEMS), predicate(group("AND", leaf("category", "EQ", "Y"), leaf("amount", "LT", "5")))), "EQ", true),
                node("allPositive", "collectionAllMatch", on(field(ITEMS), predicate(leaf("amount", "GT", "0"))), "EQ", true),
                node("noneQ", "collectionNoneMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "Q"))), "EQ", true));
        assertThat(run(tree, context)).isTrue();
        assertThat(((RuleList) list(context, "filtered")).isEmpty()).isTrue();
        assertThat(((RuleList) list(context, "filtered")).dataType()).isEqualTo("OBJECT_LIST");
        // 筛选结果保留原顺序，可继续被后置函数引用
        assertThat(run(group("AND", dep("x", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("category", "EQ", "X")))),
                node("n", "collectionCountMatch", on(out("x"), predicate(leaf("amount", "GTE", "7"))), "EQ", 2)), context)).isTrue();
        assertThat(ids(list(context, "x"))).containsExactly("A", "C");
        // 对象集合的谓词须写元素结构的属性名，不能用 value
        assertThatThrownBy(() -> compile(dep("x", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("value", "EQ", "X")))), "C1"))
                .hasMessageContaining("不能用 value");
    }

    // ---------- 谓词 ----------

    @Test
    void nestedPredicatesMixAndOrAndBindOtherElementAttributes() {
        RuleExecutionContext context = c1();
        // (类别 X 且金额 > 8) 或 (类别不是 X 且排序号 <= 1)：只有 A、B
        Map<String, Object> nested = predicate(group("OR",
                group("AND", leaf("category", "EQ", "X"), leaf("amount", "GT", "8")),
                group("AND", leaf("category", "NE", "X"), leaf("rank", "LTE", "1"))));
        assertThat(run(node("n", "collectionCountMatch", on(field(ITEMS), nested), "EQ", 2), context)).isTrue();
        // 多值：IN / NOT_IN；CONTAINS 按子串
        assertThat(run(node("n", "collectionCountMatch", on(field(ITEMS), predicate(leaf("category", "IN", List.of("X", "Z")))), "EQ", 3), context)).isTrue();
        assertThat(run(node("n", "collectionCountMatch", on(field(ITEMS), predicate(leaf("name", "CONTAINS", "name-C"))), "EQ", 1), context)).isTrue();
        // CURRENT_ELEMENT：拿当前记录的另一个属性做比较对象（金额 > 自己的上限：A、D）
        Map<String, Object> over = predicate(leaf("amount", "GT", Map.of("source", "CURRENT_ELEMENT", "fieldCode", "cap")));
        assertThat(run(node("n", "collectionCountMatch", on(field(ITEMS), over), "EQ", 2), context)).isTrue();
        // 超过自身上限的是 A、D，按金额降序排在最前的是 A
        assertThat(run(group("AND", dep("f", "collectionFilterRecords", on(field(ITEMS), over)),
                dep("top", "collectionSort", Map.of("collection", out("f"), "sortFields", sorted("amount", "DESC"))),
                node("n", "collectionCountMatch", on(out("top"), predicate(leaf("itemId", "EQ", "A"))), "EQ", 1)), context)).isTrue();
        assertThat(ids(list(context, "f"))).containsExactly("A", "D");
        assertThat(ids(list(context, "top"))).containsExactly("A", "D");
        // CURRENT_ELEMENT 只能用于对象集合，且类型须一致
        assertThatThrownBy(() -> compile(dep("f", "collectionFilter", on(field(NUMBERS), predicate(leaf("value", "GT",
                Map.of("source", "CURRENT_ELEMENT", "fieldCode", "cap"))))), "C1")).hasMessageContaining("只能用于对象集合");
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("amount", "GT",
                Map.of("source", "CURRENT_ELEMENT", "fieldCode", "name"))))), "C1")).hasMessageContaining("类型不一致");
        // 谓词里暂不支持函数输出；缺少比较对象 / 运算符不适用 / 属性不在结构里 / 嵌套过深，都在编译期拦下
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("category", "EQ",
                out("p"))))), "C1")).hasMessageContaining("不能用于谓词");
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(Map.of("type", "ELEMENT", "field", "category", "operator", "EQ")))), "C1"))
                .hasMessageContaining("缺少比较对象 value");
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("amount", "CONTAINS", "1")))), "C1"))
                .hasMessageContaining("不支持运算符 CONTAINS");
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("nope", "EQ", "1")))), "C1"))
                .isInstanceOf(IllegalArgumentException.class);
        Object nestedDeep;
        Object current = leaf("category", "EQ", "X");
        for (int i = 0; i < 7; i++) current = group("AND", current);
        nestedDeep = current;
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(nestedDeep))), "C1"))
                .hasMessageContaining("最多允许 6 层");
    }

    @Test
    void predicateOperandsComeFromControlledSources() {
        RuleExecutionContext context = c1();
        // 受控路径：交易金额、决策时间；数据字段：当前类别；常量
        ExpressionService.Node tree = group("AND",
                node("amount", "collectionCountMatch", on(field(ITEMS), predicate(leaf("amount", "LT", path("TRANSACTION", "amountUsd")))), "EQ", 4),
                node("day", "collectionCountMatch", on(field(ITEMS), predicate(leaf("day", "LT", path("CONTEXT", "decisionTime")))), "EQ", 3),
                node("category", "collectionCountMatch", on(field(ITEMS), predicate(leaf("category", "EQ", path("FIELD", CATEGORY)))), "EQ", 2),
                node("currency", "collectionCountMatch", on(field(ITEMS), predicate(leaf("name", "NE", path("TRANSACTION", "paymentCurrency")))), "EQ", 4));
        assertThat(run(tree, context)).isTrue();
        // 日期为空的记录：该叶子不成立，也不报错（C 被排除）
        assertThat(context.callOf("day").value()).isEqualTo(3L);
        // 路径没有取值：报错，不按「不满足」处理
        RuleExecutionContext missing = c1();
        missing.set("transaction.amountUsd", null);
        assertThatThrownBy(() -> run(node("n", "collectionCountMatch", on(field(ITEMS), predicate(leaf("amount", "LT", path("TRANSACTION", "amountUsd")))), "EQ", 4), missing))
                .hasMessageContaining("的比较对象没有取值，不能参与比较");
        // 类型须一致；路径须已登记；常量按属性类型校验
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("amount", "GT", path("CONTEXT", "decisionTime"))))), "C1"))
                .hasMessageContaining("类型应为 DECIMAL");
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("name", "EQ", path("TRANSACTION", "noSuchPath"))))), "C1"))
                .hasMessageContaining("不受控的取值路径：transaction.noSuchPath");
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("amount", "GT", konst("abc"))))), "C1"))
                .hasMessageContaining("应为 DECIMAL");
        // RULE_ROW：C3 逐行求值时，用当前覆盖行的属性做比较对象（排序号 > 匹配精度 2：只有 D）
        RuleExecutionContext c3 = RuleExecutionContext.c3("PROV-GD", Map.of("transaction.paymentCurrency", "USD",
                "transaction.beneficiaryBankCountry", "KG", "transaction.beneficiaryBankBic", "ICICIHKHH", "transaction.chargeOption", "SHA"), 3L, 1, NOW);
        c3.ruleRow(Map.ofEntries(Map.entry("rowId", (Object) 1L), Map.entry("provider", (Object) "PROV-GD"), Map.entry("currency", (Object) "USD"),
                Map.entry("country", (Object) "ALL"), Map.entry("region", (Object) "ALL"), Map.entry("enabled", (Object) true),
                Map.entry("currencyMatched", (Object) true), Map.entry("countryMatched", (Object) true), Map.entry("regionMatched", (Object) true),
                Map.entry("bankScopeMatched", (Object) true), Map.entry("chargeOptionMatched", (Object) true), Map.entry("matched", (Object) true),
                Map.entry("specificity", (Object) 2L)));
        assertThat(run(node("n", "collectionCountMatch", on(field(ITEMS), predicate(leaf("rank", "GT", path("RULE_ROW", "specificity")))), "EQ", 1), c3)).isTrue();
        assertThatThrownBy(() -> compile(dep("f", "collectionFilterRecords", on(field(ITEMS), predicate(leaf("rank", "GT", path("RULE_ROW", "specificity"))))), "C1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 排序 / 第一条 / 分组 ----------

    @Test
    void sortIsMultiFieldStableAndPutsNullsLastWithoutMutatingTheInput() {
        RuleExecutionContext context = c1(), first = context;
        RuleList original = lists.get(ITEMS);
        List<Object> before = List.copyOf(original.elements());
        assertThat(run(group("AND", dep("sorted", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("rank", "ASC"))),
                node("n", "collectionSize", Map.of("collection", out("sorted")), "EQ", 4)), first)).isTrue();
        assertThat(ids(list(first, "sorted"))).containsExactly("B", "A", "C", "D");
        // 多字段：排序号升序，金额降序
        lists.data.put(ITEMS, RuleList.records(ITEMS, TEST_ITEM, List.of(
                item("A", "X", "10.5", "10", 2L, DAY_2), item("B", "Y", "2.5", "5", 1L, DAY_1), item("C", "X", "7", "7", 2L, null),
                item("D", "Z", "0.1", "0.05", 3L, DAY_3))));
        context = c1();
        assertThat(run(group("AND", dep("multi", "collectionSort", Map.of("collection", field(ITEMS), "sortFields",
                Map.of("source", "CONSTANT", "value", List.of(Map.of("field", "rank", "direction", "ASC"), Map.of("field", "amount", "direction", "DESC"))))),
                node("n", "collectionCountMatch", on(out("multi"), predicate(leaf("itemId", "NE", "none"))), "EQ", 4)), context)).isTrue();
        assertThat(ids(list(context, "multi"))).containsExactly("B", "A", "C", "D");
        // 稳定：排序键相同的记录保持原有先后
        assertThat(run(group("AND", dep("byCategory", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("category", "ASC"))),
                node("n", "collectionSize", Map.of("collection", out("byCategory")), "EQ", 4)), context)).isTrue();
        assertThat(ids(list(context, "byCategory"))).containsExactly("A", "C", "B", "D");
        // 空值排在最后：升序、降序都一样
        assertThat(run(group("AND", dep("dayAsc", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("day", "ASC"))),
                dep("dayDesc", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("day", "DESC"))),
                node("asc", "collectionSize", Map.of("collection", out("dayAsc")), "EQ", 4),
                node("desc", "collectionSize", Map.of("collection", out("dayDesc")), "EQ", 4)), context)).isTrue();
        assertThat(ids(list(context, "dayAsc"))).containsExactly("B", "A", "D", "C");
        assertThat(ids(list(context, "dayDesc"))).containsExactly("D", "A", "B", "C");
        // 不改变原集合：原清单顺序未变，派生结果是另一个对象
        RuleList current = lists.get(ITEMS);
        assertThat(current.elements()).isEqualTo(before);
        assertThat(list(first, "sorted")).isNotSameAs(original);
        assertThatThrownBy(() -> compile(dep("s", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("nope", "ASC"))), "C1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> compile(dep("s", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", konst(List.of()))), "C1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void firstMatchHonoursPriorityAndSeparatesNoMatchFromFailure() {
        RuleExecutionContext context = c1();
        // 返回 OBJECT 的函数没有后置函数接收：不能放进条件树，只能在函数库里单独试算
        assertThatThrownBy(() -> compile(group("AND", dep("first", "collectionFirstMatch", on(field(ITEMS), predicate(leaf("category", "NE", "Q")))),
                node("n", "collectionIsEmpty", Map.of("collection", field(STRINGS)), "EQ", false)), "C1"))
                .hasMessageContaining("函数节点 first（collectionFirstMatch）的输出没有被任何条件或后置函数引用");
        // 没有排序字段：按原顺序第一条满足条件的记录
        FunctionExecutionService.Call first = call("first", "collectionFirstMatch", on(field(ITEMS), predicate(leaf("category", "NE", "Q"))), context);
        assertThat(first.recordId()).isEqualTo("A");
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) context.output("first");
        assertThat(record).containsEntry("itemId", "A").containsEntry("amount", new BigDecimal("10.5"));
        assertThat(first.value()).isNotSameAs(record);
        RuleList source = lists.get(ITEMS);
        assertThat(record).isSameAs(source.elements().get(0));
        // 显式排序 = 优先级：金额升序后，第一条满足「金额 >= 2.5」的是 B
        FunctionExecutionService.Call byPriority = call("first", "collectionFirstMatch",
                on(field(ITEMS), predicate(leaf("amount", "GTE", "2.5")), "sortFields", sorted("amount", "ASC")), context);
        assertThat(byPriority.recordId()).isEqualTo("B");
        // 没有满足条件的记录：受控的「未命中」（NoMatch），不是读取失败
        assertThatThrownBy(() -> call("first", "collectionFirstMatch", on(field(ITEMS), predicate(leaf("category", "EQ", "Q"))), context))
                .isInstanceOf(FunctionExecutionService.FunctionExecutionException.class).hasMessageContaining("没有满足条件的记录");
        assertThat(context.failureOf("first")).isInstanceOf(CollectionFunction.NoMatch.class);
        assertThat(context.callOf("first").error()).contains("没有满足条件的记录");
        // 不是对象集合
        assertThatThrownBy(() -> call("first", "collectionFirstMatch", on(field(NUMBERS), predicate(leaf("value", "GT", "0"))), context))
                .hasMessageContaining("应为对象集合 OBJECT_LIST");
    }

    @Test
    void groupByReturnsAnUnmodifiableMapOfGroups() {
        RuleExecutionContext context = c1();
        // 返回 MAP 的函数没有后置函数接收：不能放进条件树
        assertThatThrownBy(() -> compile(group("AND", dep("group", "collectionGroupBy", Map.of("collection", field(ITEMS), "groupField", konst("category"))),
                node("n", "collectionIsEmpty", Map.of("collection", field(STRINGS)), "EQ", false)), "C1"))
                .hasMessageContaining("函数节点 group（collectionGroupBy）的输出没有被任何条件或后置函数引用");
        call("group", "collectionGroupBy", Map.of("collection", field(ITEMS), "groupField", konst("category")), context);
        Map<String, Object> groups = map(context, "group");
        assertThat(groups.keySet()).containsExactly("X", "Y", "Z");
        assertThat(ids((RuleList) groups.get("X"))).containsExactly("A", "C");
        assertThat(ids((RuleList) groups.get("Z"))).containsExactly("D");
        assertThatThrownBy(() -> groups.put("NEW", null)).isInstanceOf(UnsupportedOperationException.class);
        // 属性为空的记录归入「（空）」组；可选谓词先筛选
        call("day", "collectionGroupBy", Map.of("collection", field(ITEMS), "groupField", konst("day")), context);
        assertThat(map(context, "day").keySet()).containsExactly("2026-11-02", "2026-11-01", "（空）", "2026-11-03");
        assertThat(ids((RuleList) map(context, "day").get("（空）"))).containsExactly("C");
        call("top", "collectionGroupBy", Map.of("collection", field(ITEMS), "groupField", konst("category"),
                "predicate", predicate(leaf("rank", "LTE", "2"))), context);
        assertThat(map(context, "top").keySet()).containsExactly("X", "Y");
        assertThatThrownBy(() -> call("group", "collectionGroupBy", Map.of("collection", field(ITEMS), "groupField", konst("nope")), context))
                .hasMessageContaining("不在元素结构 TEST_ITEM 里");
        // 没有集合参数可供分组：编译期拦下
        assertThatThrownBy(() -> compile(node("n", "collectionGroupBy", Map.of("groupField", konst("category")), "EQ", true), "C1"))
                .hasMessage("函数 collectionGroupBy 缺少必填参数：collection");
    }

    // ---------- 串联 ----------

    @Test
    void functionOutputsChainInDependencyOrderAndKeepAnAuditTrail() {
        RuleExecutionContext context = c1();
        ExpressionService.Node tree = group("AND",
                dep("sort", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("amount", "DESC"))),
                dep("x", "collectionFilterRecords", on(out("sort"), predicate(leaf("category", "EQ", "X")))),
                node("sum", "collectionSumWhere", on(out("x"), predicate(leaf("amount", "GTE", "7")), "valueSelector", konst("amount")), "EQ", "17.5"),
                node("capped", "numberMin", Map.of("value", out("sum"), "cap", konst("10")), "EQ", "10"));
        ExpressionService.Compiled compiled = compile(tree, "C1");
        assertThat(expressions.evaluate(compiled, Map.of(), context).matched()).isTrue();
        assertThat(ids(list(context, "sort"))).containsExactly("A", "C", "B", "D");
        assertThat(ids(list(context, "x"))).containsExactly("A", "C");
        assertThat(((RuleList) list(context, "x")).dataType()).isEqualTo("OBJECT_LIST");
        // 执行顺序：被引用的前置函数在前；调用记录按同样的顺序
        assertThat(compiled.nodes().stream().map(FunctionExecutionService.Validated::nodeId).toList()).containsExactly("sort", "x", "sum", "capped");
        assertThat(context.calls().stream().map(FunctionExecutionService.Call::functionCode).toList())
                .containsExactly("collectionSort", "collectionFilterRecords", "collectionSumWhere", "numberMin");
        assertThat((BigDecimal) context.callOf("capped").value()).isEqualByComparingTo("10");
        assertThat(compiled.readable()).contains("collectionSumWhere");
        assertThat(context.calls()).allMatch(c -> !c.cached() && c.error() == null);
        // 执行过的函数节点输出都登记在上下文里；没有执行的节点不能读取输出
        assertThat(list(context, "sort")).isSameAs(context.callOf("sort").value());
    }

    @Test
    void cyclesMissingNodesAndUnusableOutputsAreRejectedAtSaveTime() {
        String[] pair = {"collectionSize", "collectionCount"};
        // 循环引用
        ExpressionService.Node a = node("a", pair[0], Map.of("collection", out("b")), "EQ", 1);
        ExpressionService.Node b = node("b", pair[1], Map.of("collection", out("a")), "EQ", 1);
        assertThatThrownBy(() -> compile(group("AND", a, b), "C1")).hasMessageContaining("函数节点循环引用：a → b → a");
        // 引用的节点不存在 / 没写 nodeId
        assertThatThrownBy(() -> compile(group("AND", node("c", "collectionSize", Map.of("collection", out("ghost")), "EQ", 1)), "C1"))
                .hasMessage("引用的前置函数节点不存在：条件树里没有 id 为 ghost 的函数节点");
        assertThatThrownBy(() -> compile(node("c", "collectionSize", Map.of("collection", Map.of("source", "FUNCTION_OUTPUT", "fieldCode", "x")), "EQ", 1), "C1"))
                .hasMessage("函数 collectionSize 的参数 collection 的来源 FUNCTION_OUTPUT 缺少 nodeId");
        // 输出没有被引用
        assertThatThrownBy(() -> compile(group("AND", dep("p", "collectionDistinct", Map.of("collection", field(STRINGS))),
                node("n", "collectionIsEmpty", Map.of("collection", field(STRINGS)), "EQ", true)), "C1"))
                .hasMessageContaining("函数节点 p（collectionDistinct）的输出没有被任何条件或后置函数引用");
        // 根节点 / 条件组里只有被引用的函数
        assertThatThrownBy(() -> compile(dep("p", "collectionDistinct", Map.of("collection", field(STRINGS))), "C1"))
                .hasMessageContaining("条件树的根不能只是被引用的函数节点");
        assertThatThrownBy(() -> compile(group("AND", dep("p", "collectionDistinct", Map.of("collection", field(STRINGS))),
                dep("q", "collectionDistinct", Map.of("collection", out("p")))), "C1")).hasMessageContaining("须至少有一个可判断的条件");
        // 返回集合的函数不能直接比较
        assertThatThrownBy(() -> compile(node("c", "collectionDistinct", Map.of("collection", field(STRINGS)), "EQ", true), "C1"))
                .hasMessageContaining("不能直接比较；该节点只能被后置函数引用");
        // 类型不匹配：整数输出不能当集合
        assertThatThrownBy(() -> compile(group("AND", dep("p", "collectionSize", Map.of("collection", field(STRINGS))),
                node("c", "collectionCount", Map.of("collection", out("p")), "EQ", 1)), "C1"))
                .hasMessageContaining("类型应为 COLLECTION，不能引用前置函数 p（INTEGER）");
        // 集合不能写成常量；一棵树最多 20 个函数节点
        assertThatThrownBy(() -> compile(node("c", "collectionCount", Map.of("collection", konst(List.of(1, 2))), "EQ", 2), "C1"))
                .hasMessageContaining("不能使用常量");
        // 单个条件组最多 20 个子条件，所以分两层放 22 个函数节点
        List<ExpressionService.Node> left = IntStream.range(0, 11)
                .mapToObj(i -> node("n" + i, "collectionIsEmpty", Map.of("collection", field(STRINGS)), "EQ", false)).toList();
        List<ExpressionService.Node> right = IntStream.range(11, 22)
                .mapToObj(i -> node("n" + i, "collectionIsEmpty", Map.of("collection", field(STRINGS)), "EQ", false)).toList();
        assertThatThrownBy(() -> compile(group("AND", group("AND", left.toArray(new ExpressionService.Node[0])),
                group("AND", right.toArray(new ExpressionService.Node[0]))), "C1")).hasMessageContaining("最多允许 20 个函数节点");
        assertThat(compile(group("AND", group("AND", left.toArray(new ExpressionService.Node[0])),
                group("AND", right.subList(0, 9).toArray(new ExpressionService.Node[0]))), "C1").nodes()).hasSize(20);
    }

    @Test
    void disabledUnregisteredAndInapplicableFunctionsAreRejected() {
        assertThat(run(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "3.8"), c1())).isTrue();
        // 停用：不能再被新条件引用
        BusinessFunctionDefinition definition = definitions.findById("collectionSum").orElseThrow();
        definition.enabled = false;
        definitions.save(definition);
        assertThatThrownBy(() -> compile(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "0"), "C1"))
                .hasMessage("函数 collectionSum 已停用，不能用于条件");
        definition.enabled = true;
        // Handler 没有注册（白名单之外）
        definition.handlerCode = "ghostHandler";
        definitions.save(definition);
        assertThatThrownBy(() -> compile(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "0"), "C1"))
                .hasMessage("函数 collectionSum 的后端实现 ghostHandler 尚未注册，不能用于条件");
        definition.handlerCode = "collectionSum";
        // 返回类型与实现不一致
        definition.returnType = "INTEGER";
        definitions.save(definition);
        assertThatThrownBy(() -> compile(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "0"), "C1"))
                .hasMessageContaining("与后端实现（DECIMAL）不一致");
        definition.returnType = "DECIMAL";
        // 适用规则不含当前规则
        definition.allowedRules = "C2";
        definitions.save(definition);
        assertThatThrownBy(() -> compile(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "0"), "C1"))
                .hasMessage("函数 collectionSum 不适用于当前规则 C1");
        definition.allowedRules = "C1,C2,C3,C5,C7,C8";
        definitions.save(definition);
        assertThat(run(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "3.8"), c1())).isTrue();
    }

    @Test
    void unloadedOrFailedDataIsAnErrorNotAnEmptyCollection() {
        RuleExecutionContext context = c1();
        // 读取失败
        lists.broken.add(NUMBERS);
        assertThatThrownBy(() -> run(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "0"), context))
                .isInstanceOf(FunctionExecutionService.FunctionExecutionException.class)
                .hasMessage("函数 collectionSum 执行失败：参数 collection 的字段 testNumbers 取数失败：模拟数据源读取失败：testNumbers");
        assertThat(context.callOf("n").error()).contains("模拟数据源读取失败");
        assertThat(context.failureOf("n")).isNotInstanceOf(CollectionFunction.NoMatch.class);
        // 读取失败不会缓存：恢复后可以重新取数
        lists.broken.remove(NUMBERS);
        assertThat(run(node("n", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "3.8"), context)).isTrue();
        // 解析器没有返回结果（数据未加载）
        lists.unloaded.add(STRINGS);
        assertThatThrownBy(() -> run(node("n", "collectionIsEmpty", Map.of("collection", field(STRINGS)), "EQ", true), context))
                .hasMessageContaining("没有返回结果（数据未加载），不能按空集合处理");
        assertThatThrownBy(() -> run(node("n", "collectionContains", Map.of("collection", field(STRINGS), "element", konst("HK")), "EQ", false), context))
                .hasMessageContaining("数据未加载");
        lists.unloaded.clear();
        // 交易要素没有上送：参数取值为空，不会按空清单 / 空串处理
        RuleExecutionContext noNationalities = c2(null, List.of("CN"));
        assertThatThrownBy(() -> run(node("n", "collectionIntersects", Map.of("leftCollection", path("TRANSACTION", "directorNationalities"),
                "rightCollection", path("TRANSACTION", "uboNationalities")), "EQ", false), noNationalities))
                .hasMessageContaining("取值为空");
        // 缺少必填参数
        assertThatThrownBy(() -> compile(node("n", "collectionContains", Map.of("collection", field(STRINGS)), "EQ", true), "C1"))
                .hasMessage("函数 collectionContains 缺少必填参数：element");
        // 解析出的数据形态与字段声明不符
        lists.data.put(NUMBERS, RuleList.of(NUMBERS, "STRING_LIST", List.of("1")));
        assertThatThrownBy(() -> run(node("n", "collectionCount", Map.of("collection", field(NUMBERS)), "EQ", 1), c1()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("字段 testNumbers");
    }

    @Test
    void fieldResultsAreCachedPerEvaluationAndNeverAcrossContexts() {
        ExpressionService.Node tree = group("AND",
                node("sum", "collectionSum", Map.of("collection", field(NUMBERS)), "GT", "0"),
                node("count", "collectionCount", Map.of("collection", field(NUMBERS)), "GT", "0"),
                node("freshSum", "collectionSum", Map.of("collection", field(FRESH)), "GT", "0"),
                node("freshCount", "collectionCount", Map.of("collection", field(FRESH)), "GT", "0"));
        RuleExecutionContext first = c1();
        assertThat(run(tree, first)).isTrue();
        // REQUEST：同一次求值内同一字段只解析一次；NONE：每次都重新解析
        assertThat(lists.calls(NUMBERS)).isEqualTo(1);
        assertThat(lists.calls(FRESH)).isEqualTo(2);
        assertThat(first.resolutions().stream().filter(r -> r.fieldCode().equals(NUMBERS)).map(FieldValueService.Resolution::cached).toList())
                .containsExactly(false, true);
        // 新的判定重新取数：不同交易、不同合作方之间不串用
        RuleExecutionContext second = RuleExecutionContext.c1("PROV-W", "C20002", "USD", "KG", new BigDecimal("100"), NOW);
        assertThat(run(tree, second)).isTrue();
        assertThat(lists.calls(NUMBERS)).isEqualTo(2);
        assertThat(second.resolutions().stream().filter(r -> r.fieldCode().equals(NUMBERS)).map(FieldValueService.Resolution::cached).toList())
                .containsExactly(false, true);
        numbers("5");
        RuleExecutionContext third = c1();
        assertThat(run(tree, third)).isTrue();
        assertThat((BigDecimal) third.callOf("sum").value()).isEqualByComparingTo("5");
        // 同一次求值里参数完全相同的函数调用复用结果（记录标为复用）
        RuleExecutionContext memo = c1();
        assertThat(run(group("AND", node("a", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "5"),
                node("b", "collectionSum", Map.of("collection", field(NUMBERS)), "EQ", "5")), memo)).isTrue();
        assertThat(memo.callOf("b").cached()).isTrue();
    }

    // ---------- 个人数据 / 规模 ----------

    @Test
    void sensitiveListsAreMaskedInPreviewsButStillComparable() {
        RuleList people = lists.get(PRIVATE);
        assertThat(people.sensitive()).isTrue();
        assertThat(people.view().get("masked")).isEqualTo("个人数据清单，不列明细");
        assertThat(people.view().get("sample")).isEqualTo(List.of());
        assertThat(people.summary()).doesNotContain("HK");
        // 个人数据清单可以照常比较；派生结果（去重、并集）仍是个人数据
        RuleExecutionContext context = c2(List.of("HK", "CN"), List.of("CN", "US"));
        assertThat(run(group("AND",
                dep("union", "collectionUnion", Map.of("leftCollection", path("TRANSACTION", "directorNationalities"),
                        "rightCollection", path("TRANSACTION", "uboNationalities"))),
                dep("distinct", "collectionDistinct", Map.of("collection", field(PRIVATE))),
                node("size", "collectionSize", Map.of("collection", out("union")), "EQ", 4),
                node("distinctSize", "collectionSize", Map.of("collection", out("distinct")), "EQ", 2),
                node("n", "collectionContains", Map.of("collection", field(PRIVATE), "element", konst("CN")), "EQ", true)), context)).isTrue();
        RuleList union = list(context, "union");
        assertThat(union.sensitive()).isTrue();
        assertThat(union.elements()).containsExactly("HK", "CN", "CN", "US");
        assertThat(union.view().get("sample")).isEqualTo(List.of());
        assertThat(union.view().get("masked")).isEqualTo("个人数据清单，不列明细");
        assertThat(((RuleList) list(context, "distinct")).sensitive()).isTrue();
        assertThat(((RuleList) context.value("transaction.directorNationalities")).sensitive()).isTrue();
        // 调用记录里的展示形态同样不列明细
        assertThat(((Map<?, ?>) context.callOf("size").arguments().get("collection")).get("sample")).isEqualTo(List.of());
        assertThat(((Map<?, ?>) context.callOf("size").arguments().get("collection")).get("size")).isEqualTo(4);
    }

    @Test
    void oversizedCollectionsAreRejectedRatherThanLoaded() {
        lists.data.put(NUMBERS, (Supplier<Object>) () -> RuleList.of(NUMBERS, "NUMBER_LIST",
                IntStream.range(0, RuleList.MAX_ELEMENTS + 1).mapToObj(BigDecimal::valueOf).toList()));
        assertThatThrownBy(() -> run(node("n", "collectionCount", Map.of("collection", field(NUMBERS)), "EQ", 1), c1()))
                .hasMessageContaining("的元素个数 10001 超过上限 10000");
    }

    // ---------- 兼容 / 审计 ----------

    @Test
    void legacyCollectionContainsJsonAndTheOldFivePartNodeStillWork() throws Exception {
        exclusions.save(new ClientExclusion("C10001", "PROV-GD", "sanction", NOW.minusDays(1), null));
        // 升级前保存的条件：函数节点没有 type / id，参数是显式来源映射，同组里还有旧版字段条件
        String legacy = "{\"logic\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"USD\"},"
                + "{\"type\":\"FUNCTION\",\"functionCode\":\"collectionContains\",\"arguments\":{"
                + "\"collection\":{\"source\":\"FIELD\",\"fieldCode\":\"providerExcludedClientIds\"},"
                + "\"element\":{\"source\":\"TRANSACTION\",\"fieldCode\":\"clientId\"}},\"operator\":\"EQ\",\"value\":true}]}";
        ExpressionService.Node tree = json.readValue(legacy, ExpressionService.Node.class);
        assertThat(expressions.compile(tree, "C1")).isNotBlank();
        assertThat(expressions.evaluate(compile(tree, "C1"), Map.of("paymentCurrency", "USD"), c1()).matched()).isTrue();
        assertThat(expressions.evaluate(compile(tree, "C1"), Map.of("paymentCurrency", "CNY"), c1()).matched()).isFalse();
        // 旧版写法的 $ 路径参数（clientExcluded）与集合函数并存
        String both = "{\"logic\":\"AND\",\"children\":[{\"type\":\"FUNCTION\",\"functionCode\":\"clientExcluded\",\"arguments\":"
                + "{\"providerCode\":\"$candidate.providerCode\",\"clientId\":\"$transaction.clientId\",\"decisionTime\":\"$context.decisionTime\"},"
                + "\"operator\":\"EQ\",\"value\":false},{\"id\":\"size\",\"type\":\"FUNCTION\",\"functionCode\":\"collectionSize\",\"arguments\":"
                + "{\"collection\":{\"source\":\"FIELD\",\"fieldCode\":\"providerExcludedClientIds\"}},\"operator\":\"EQ\",\"value\":1}]}";
        ExpressionService.Node mixed = json.readValue(both, ExpressionService.Node.class);
        // 客户在排除名单里：clientExcluded 为 true、名单个数为 1，两个函数节点都不满足 → 附加条件不通过
        assertThat(run(mixed, c1())).isFalse();
    }

    @Test
    void simulationAndEvaluationReportArgumentsValuesAndStatus() {
        // 单个函数试算：参数来源、类型、输入规模、返回值、状态
        FunctionMetadataService.FunctionSimulation size = functionMetadata.simulate("collectionCountMatch",
                on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), null, null, SAMPLE, "C1");
        assertThat(size.status()).isEqualTo("SUCCESS");
        assertThat(size.value()).isEqualTo(2L);
        assertThat(size.ruleCode()).isEqualTo("C1");
        assertThat(size.bindings()).containsEntry("collection", "testItems");
        assertThat(size.detail().handlerCode()).isEqualTo("collectionCountMatch");
        assertThat(size.detail().returnType()).isEqualTo("INTEGER");
        FunctionExecutionService.Detail.Argument collection = size.detail().arguments().get(0);
        assertThat(collection.parameter()).isEqualTo("collection");
        assertThat(collection.type()).isEqualTo("COLLECTION");
        assertThat(collection.source()).isEqualTo("FIELD");
        assertThat(collection.target()).isEqualTo("testItems");
        assertThat(collection.inputSize()).isEqualTo(4);
        assertThat(size.detail().arguments().get(1).type()).isEqualTo("PREDICATE");
        // 带运算符的试算：给出条件结果
        FunctionMetadataService.FunctionSimulation compared = functionMetadata.simulate("collectionCountMatch",
                on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), "EQ", 2, SAMPLE, "C1");
        assertThat(compared.status()).isEqualTo("MATCHED");
        assertThat(compared.matched()).isTrue();
        assertThat(compared.expression()).contains("collectionCountMatch");
        // 返回 OBJECT_LIST 的函数：明细只给总数和记录 ID，不带整份记录
        FunctionMetadataService.FunctionSimulation sorted = functionMetadata.simulate("collectionSort",
                Map.of("collection", field(ITEMS), "sortFields", sorted("amount", "DESC")), null, null, SAMPLE, "C1");
        assertThat(sorted.status()).isEqualTo("SUCCESS");
        RuleList shown = (RuleList) sorted.value();
        assertThat(shown.view().get("sample")).isEqualTo(List.of("A", "C", "B", "D"));
        FunctionMetadataService.FunctionSimulation grouped = functionMetadata.simulate("collectionGroupBy",
                Map.of("collection", field(ITEMS), "groupField", konst("category")), null, null, SAMPLE, "C1");
        assertThat(grouped.status()).isEqualTo("SUCCESS");
        assertThat(grouped.detail().returnType()).isEqualTo("MAP");
        // 「没有满足条件的记录」是受控结果（NO_MATCH），数据读取失败是 FAILED
        FunctionMetadataService.FunctionSimulation noMatch = functionMetadata.simulate("collectionFirstMatch",
                on(field(ITEMS), predicate(leaf("category", "EQ", "Q"))), null, null, SAMPLE, "C1");
        assertThat(noMatch.status()).isEqualTo("NO_MATCH");
        assertThat(noMatch.error()).contains("没有满足条件的记录");
        lists.broken.add(ITEMS);
        FunctionMetadataService.FunctionSimulation failed = functionMetadata.simulate("collectionFirstMatch",
                on(field(ITEMS), predicate(leaf("category", "EQ", "X"))), null, null, SAMPLE, "C1");
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.error()).contains("模拟数据源读取失败");
        lists.broken.clear();
        // 整棵条件树试算：按执行顺序给出每个函数节点的明细（来源、类型、输入规模、返回值、状态）
        FunctionMetadataService.Evaluation evaluation = functionMetadata.evaluate("C1", group("AND",
                dep("sort", "collectionSort", Map.of("collection", field(ITEMS), "sortFields", sorted("rank", "ASC"))),
                dep("x", "collectionFilterRecords", on(out("sort"), predicate(leaf("category", "EQ", "X")))),
                node("n", "collectionSumWhere", on(out("x"), predicate(leaf("amount", "GTE", "7")), "valueSelector", konst("amount")), "LTE", "20")),
                SAMPLE);
        assertThat(evaluation.matched()).isTrue();
        assertThat(evaluation.error()).isNull();
        assertThat(evaluation.readable()).contains("collectionSumWhere");
        assertThat(evaluation.functions()).extracting(FunctionExecutionService.Detail::nodeId).containsExactly("sort", "x", "n");
        assertThat(evaluation.functions()).extracting(FunctionExecutionService.Detail::status).containsExactly("SUCCESS", "SUCCESS", "SUCCESS");
        FunctionExecutionService.Detail sum = evaluation.functions().get(2);
        assertThat(sum.arguments()).extracting(FunctionExecutionService.Detail.Argument::source).containsExactly("FUNCTION_OUTPUT", "PREDICATE", "CONSTANT");
        assertThat(sum.arguments().get(0).target()).isEqualTo("x");
        assertThat(sum.arguments().get(0).inputSize()).isEqualTo(2);
        assertThat((BigDecimal) sum.value()).isEqualByComparingTo("17.5");
        // 条件树不合法：编译期错误原样报出，不会去试算
        assertThatThrownBy(() -> functionMetadata.evaluate("C1", node("n", "collectionSum", Map.of("collection", out("ghost")), "GT", "1"), SAMPLE))
                .hasMessage("引用的前置函数节点不存在：条件树里没有 id 为 ghost 的函数节点");
        // 数据读取失败：matched 为空并给出原因，不会当成 false
        lists.broken.add(ITEMS);
        FunctionMetadataService.Evaluation failedTree = functionMetadata.evaluate("C1",
                node("n", "collectionSum", Map.of("collection", field(ITEMS), "valueSelector", konst("amount")), "GT", "1"), SAMPLE);
        assertThat(failedTree.matched()).isNull();
        assertThat(failedTree.error()).contains("模拟数据源读取失败");
        assertThat(failedTree.functions().get(0).status()).isEqualTo("FAILED");
    }
}
