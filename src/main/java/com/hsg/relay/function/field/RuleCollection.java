package com.hsg.relay.function.field;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 规则计算用的集合。由字段解析器（{@link FieldValueResolver}）产生，作为 STRING_SET 类型的字段取值和函数实参，
 * 通用函数（collectionContains / collectionIntersects）只通过它做成员查询，不关心数据来自哪张表。
 * <p>两种形态，语义一致：
 * <ul>
 *   <li>已加载：元素全部在内存里，{@link #lookup} 就是内存查询；</li>
 *   <li>成员查询：数据量大，不全量加载；{@link #lookup} 按单个元素查询，{@link #elements()} 直接报错而不是触发全量加载。</li>
 * </ul>
 * 空集合（已加载、0 个元素）与「未加载」严格区分：未加载的数据不会以集合的形式出现——解析器取不到数据时抛异常，不返回空集合。
 * 本类型只读，不产生任何业务副作用。
 */
public interface RuleCollection {
    /** 成员查询结果：hit 是否在集合里；recordId 命中的业务记录 ID（审计、试算展示用），未命中或集合不对应业务记录时为 null。 */
    record Lookup(boolean hit, String recordId) {
        public static final Lookup MISS = new Lookup(false, null);

        public static Lookup hit(String recordId) {
            return new Lookup(true, recordId);
        }
    }

    /** 展示形态（调用记录、试算、日志用）：不含完整名单，最多列 limit 个元素。 */
    record View(String source, boolean materialized, int size, List<Object> sample) {
    }

    /** 集合数据来源编码（如 CLIENT_EXCLUSION），供试算展示与审计。 */
    String source();

    /** 成员查询：element 是否在集合里，命中时带上业务记录 ID。 */
    Lookup lookup(Object element);

    default boolean contains(Object element) {
        return lookup(element).hit();
    }

    /** 是否已全量加载。 */
    boolean materialized();

    /**
     * 全部元素（只读，保持加载顺序）。只有已加载的集合可用；成员查询模式的集合在这里报错，不会隐式全量加载。
     */
    Set<Object> elements();

    /**
     * 本次求值内的身份标识，用作函数结果复用的缓存键的一部分：必须包含隔离业务数据所需的上下文
     * （合作方、决策时间等），不能只是元素个数。
     */
    String cacheKey();

    /** 日志与调用记录里的一行摘要：来源、元素个数、是否已加载，不输出完整名单。 */
    default String summary() {
        return source() + "（" + sizeLabel() + "）";
    }

    default View view(int limit) {
        List<Object> sample = new ArrayList<>();
        if (materialized()) {
            int shown = 0;
            for (Object element : elements()) {
                if (shown++ >= limit) break;
                sample.add(element);
            }
        }
        return new View(source(), materialized(), materialized() ? elements().size() : -1, List.copyOf(sample));
    }

    private String sizeLabel() {
        return materialized() ? elements().size() + " 个元素" : "成员查询模式，未全量加载";
    }

    /** 已加载的内存集合：元素不对应业务记录。 */
    static RuleCollection of(String source, Collection<?> elements) {
        if (elements == null) throw new IllegalArgumentException("集合 " + source + " 未加载（元素为 null）");
        Map<Object, String> members = new LinkedHashMap<>();
        for (Object element : elements)
            if (element != null) members.put(element, null);
        return new Loaded(source, members);
    }

    /** 已加载的内存集合：元素 → 对应的业务记录 ID（按放入顺序）。 */
    static RuleCollection of(String source, Map<?, ?> elementToRecordId) {
        if (elementToRecordId == null) throw new IllegalArgumentException("集合 " + source + " 未加载（元素为 null）");
        Map<Object, String> members = new LinkedHashMap<>();
        elementToRecordId.forEach((element, recordId) -> {
            if (element != null) members.put(element, recordId == null ? null : recordId.toString());
        });
        return new Loaded(source, members);
    }

    /** 内存集合。cacheKey 含来源、元素个数、元素本身（超长时截断）和元素哈希，不同合作方的集合不会串用。 */
    final class Loaded implements RuleCollection {
        private static final int KEY_LIMIT = 2000;
        private final String source;
        private final Map<Object, String> members;
        private final Set<Object> elements;
        private final String cacheKey;

        private Loaded(String source, Map<Object, String> members) {
            this.source = source;
            this.members = Collections.unmodifiableMap(members);
            this.elements = Collections.unmodifiableSet(new LinkedHashSet<>(members.keySet()));
            String joined = new TreeSet<>(members.keySet().stream().map(String::valueOf).toList()).toString();
            this.cacheKey = "inline:" + source + ":" + members.size() + ":" + members.keySet().hashCode() + ":"
                    + (joined.length() > KEY_LIMIT ? joined.substring(0, KEY_LIMIT) : joined);
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public Lookup lookup(Object element) {
            if (element == null || !members.containsKey(element)) return Lookup.MISS;
            return Lookup.hit(members.get(element));
        }

        @Override
        public boolean materialized() {
            return true;
        }

        @Override
        public Set<Object> elements() {
            return elements;
        }

        @Override
        public String cacheKey() {
            return cacheKey;
        }

        @Override
        public String toString() {
            return summary();
        }
    }
}
