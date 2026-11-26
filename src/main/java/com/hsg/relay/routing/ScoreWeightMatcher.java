package com.hsg.relay.routing;

import com.hsg.relay.domain.RoutingScoreWeight;
import com.hsg.relay.repo.RoutingScoreWeightRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

import static com.hsg.relay.domain.RoutingScoreWeight.*;

/**
 * 权重匹配服务：按「交易类型 + 客户层级」为交易选出唯一一套生效权重，降级顺序固定为
 * <pre>
 * P1  交易类型 = 当前交易类型，客户层级 = 当前客户层级
 * P2  交易类型 = 当前交易类型，客户层级 = ALL
 * P3  交易类型 = OTHER，          客户层级 = ALL
 * </pre>
 * 找到第一个有效配置后立即选用，不再继续降级；任何情况下都不按分数高低重新选择、不随机、不默认 0 权重、不使用未发布配置。
 * 「有效」= 已发布（或历史上发布过、后被停用）且决策时间落在它的有效时间段内；草稿从不参与匹配。
 * 三级都没有有效配置时抛出 {@link IllegalStateException}（明确配置异常）。
 * <p>
 * 缓存：进程内一份不可变快照，包含全部已发布 / 已停用版本及其有效时间段。匹配只在内存里完成，不依赖 TTL；
 * 「未来生效时间」靠有效时间段判断（决策时间到了自然命中），不需要等缓存过期。配置发布、停用后整体重算快照并原子替换；
 * 一笔交易的全部候选共用同一个 {@link Match} 对象，所以权重版本一定相同。
 * 快照加载后做完整性校验（权重合法、同一组的有效时间段不重叠）；校验失败抛异常并保留上一次有效的快照，不会静默使用错误权重。
 * 快照是单实例的：多实例部署时需要由发布动作通知各实例刷新（本次未实现，见说明）。
 */
@Component
public class ScoreWeightMatcher {
    public static final String P1 = "P1", P2 = "P2", P3 = "P3";

    /** 一套权重（整数百分比，合计 100）。 */
    public record Weights(int cost, int speed, int preference, int commercial) {
        public int sum() {
            return cost + speed + preference + commercial;
        }
    }

    /** 匹配到的配置：不可变快照值，一笔交易内所有候选共用。 */
    public record Match(long configId, int versionNo, String transactionType, String clientTier, String level,
                        Weights weights, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
    }

    /** 一级匹配的判断过程（模拟匹配用）。 */
    public record Step(String level, String transactionType, String clientTier, String hit, String note) {
    }

    /** 完整匹配过程：命中的配置 + 三级的逐步判断；没有命中时 error 说明原因。 */
    public record Trace(String transactionType, String clientTier, LocalDateTime at, Match matched, List<Step> steps, String error) {
        public boolean hit() {
            return matched != null;
        }
    }

    /** 快照里的一条版本及其有效时间段 [effectiveFrom, effectiveTo)。 */
    private record Entry(long id, int versionNo, String transactionType, String clientTier, Weights weights,
                         LocalDateTime effectiveFrom, LocalDateTime effectiveTo) {
        boolean empty() {
            return effectiveTo != null && !effectiveTo.isAfter(effectiveFrom);
        }

        boolean covers(LocalDateTime time) {
            return !effectiveFrom.isAfter(time) && (effectiveTo == null || time.isBefore(effectiveTo));
        }
    }

    private record Snapshot(List<Entry> entries) {
    }

    /** 三级的键：P1 / P2 / P3。 */
    static final List<String> LEVELS = List.of(P1, P2, P3);
    private final RoutingScoreWeightRepository repo;
    private final Clock clock;
    private final Object lock = new Object();
    private volatile Snapshot snapshot;

    public ScoreWeightMatcher(RoutingScoreWeightRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /** 取完整匹配过程（命中或未命中都返回，不抛配置缺失异常）。at 为空 = 业务时钟的当前时间。 */
    public Trace trace(String transactionType, String clientTier, LocalDateTime at) {
        if (transactionType == null || transactionType.isBlank() || clientTier == null || clientTier.isBlank())
            throw new IllegalArgumentException("交易类型和客户层级不能为空");
        LocalDateTime time = at == null ? LocalDateTime.now(clock) : at;
        List<Step> steps = new ArrayList<>(3);
        Match hit = null;
        String[][] keys = {{transactionType, clientTier}, {transactionType, ALL_TIERS}, {FALLBACK_TYPE, ALL_TIERS}};
        for (int i = 0; i < keys.length; i++) {
            String level = LEVELS.get(i);
            if (hit != null) {
                steps.add(new Step(level, keys[i][0], keys[i][1], "SKIPPED", "未判断：已在 " + hit.level() + " 命中，不再降级"));
                continue;
            }
            Optional<Entry> found = effective(current().entries(), keys[i][0], keys[i][1], time);
            if (found.isPresent()) hit = toMatch(found.get(), level);
            steps.add(new Step(level, keys[i][0], keys[i][1], found.isPresent() ? "HIT" : "MISS",
                    found.map(e -> "命中：" + e.transactionType() + " + " + e.clientTier() + "（v" + e.versionNo() + "，" + describe(e.effectiveFrom(), e.effectiveTo()) + "）")
                            .orElse("没有当前生效的已发布配置")));
        }
        String error = hit == null ? "没有可用的评分权重配置：" + transactionType + " + " + clientTier + "、"
                + transactionType + " + " + ALL_TIERS + "、" + FALLBACK_TYPE + " + " + ALL_TIERS + " 在 " + time + " 都没有生效的已发布配置" : null;
        return new Trace(transactionType, clientTier, time, hit, List.copyOf(steps), error);
    }

    /** 为一笔交易匹配权重；三级都没有有效配置时抛出配置异常。 */
    public Match require(String transactionType, String clientTier, LocalDateTime at) {
        Trace trace = trace(transactionType, clientTier, at);
        if (!trace.hit()) throw new IllegalStateException(trace.error());
        return trace.matched();
    }

    /** 重新加载快照并原子替换。配置发布、停用后以及应用启动（默认权重写入）后调用。 */
    public void refresh() {
        synchronized (lock) {
            List<Entry> entries;
            try {
                entries = repo.findByStatusIn(List.of(PUBLISHED, DISABLED)).stream()
                        .filter(r -> r.effectiveFrom != null)
                        .map(r -> new Entry(r.id, r.versionNo, r.transactionType, r.clientTier,
                                new Weights(r.w1Cost, r.w2Speed, r.w3Preference, r.w4Commercial), r.effectiveFrom, r.effectiveTo))
                        .toList();
            } catch (RuntimeException ex) {
                throw new IllegalStateException("评分权重配置读取失败，保留上一次的权重快照：" + ex.getMessage(), ex);
            }
            validate(entries);
            snapshot = new Snapshot(entries);
        }
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (s == null) {
            synchronized (lock) {
                s = snapshot;
                if (s == null) {
                    refresh();
                    s = snapshot;
                }
            }
        }
        return s;
    }

    /** 快照完整性校验：不合法直接抛异常，不生成快照。 */
    private static void validate(List<Entry> entries) {
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : entries) {
            Weights w = e.weights();
            if (w.cost() < 0 || w.cost() > 100 || w.speed() < 0 || w.speed() > 100 || w.preference() < 0 || w.preference() > 100 || w.commercial() < 0 || w.commercial() > 100 || w.sum() != 100)
                throw new IllegalStateException("评分权重配置校验失败：" + e.transactionType() + " + " + e.clientTier() + " v" + e.versionNo() + " 的权重 " + w + " 不合法");
            if (e.effectiveFrom() == null)
                throw new IllegalStateException("评分权重配置校验失败：" + e.transactionType() + " + " + e.clientTier() + " v" + e.versionNo() + " 没有生效时间");
            groups.computeIfAbsent(e.transactionType() + "+" + e.clientTier(), k -> new ArrayList<>()).add(e);
        }
        for (var group : groups.entrySet())
            for (int i = 0; i < group.getValue().size(); i++)
                for (int j = i + 1; j < group.getValue().size(); j++) {
                    Entry a = group.getValue().get(i), b = group.getValue().get(j);
                    if (a.empty() || b.empty()) continue;
                    boolean overlap = (a.effectiveTo() == null || b.effectiveFrom().isBefore(a.effectiveTo()))
                            && (b.effectiveTo() == null || a.effectiveFrom().isBefore(b.effectiveTo()));
                    if (overlap)
                        throw new IllegalStateException("评分权重配置冲突：" + group.getKey() + " 的 v" + a.versionNo() + " 与 v" + b.versionNo() + " 有效时间段重叠，不能确定该用哪一套权重");
                }
    }

    /** 某一级在决策时间生效的配置；同一级出现多条即配置异常，不随机选择。 */
    private static Optional<Entry> effective(List<Entry> entries, String transactionType, String clientTier, LocalDateTime time) {
        List<Entry> hits = entries.stream().filter(e -> e.transactionType().equals(transactionType) && e.clientTier().equals(clientTier) && e.covers(time)).toList();
        if (hits.size() > 1)
            throw new IllegalStateException("评分权重配置冲突：" + transactionType + " + " + clientTier + " 在 " + time + " 有 " + hits.size() + " 个生效版本，不能随机选择");
        return hits.stream().findFirst();
    }

    private static Match toMatch(Entry e, String level) {
        return new Match(e.id(), e.versionNo(), e.transactionType(), e.clientTier(), level, e.weights(), e.effectiveFrom(), e.effectiveTo());
    }

    private static String describe(LocalDateTime from, LocalDateTime to) {
        return "生效自 " + from + (to == null ? "，长期有效" : "，至 " + to);
    }

    /** 快照中的版本数（测试与排查用）。 */
    public int size() {
        return current().entries().size();
    }

    /** OTHER + ALL 当前是否生效（没有兜底时页面提示）。 */
    public boolean fallbackEffective() {
        return effective(current().entries(), FALLBACK_TYPE, ALL_TIERS, LocalDateTime.now(clock)).isPresent();
    }
}
