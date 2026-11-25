package com.hsg.relay.routing;

import com.hsg.relay.repo.ProviderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.IntStream;

import static com.hsg.relay.routing.RoutingConfig.Reach;

@Service
public class C3Service {
    private final RoutingService routing;
    private final ProviderRepository providers;
    private final C3ConditionService conditions;

    public C3Service(RoutingService r, ProviderRepository p, C3ConditionService conditions) {
        routing = r;
        providers = p;
        this.conditions = conditions;
    }

    public record RowView(int index, Reach reach) {
    }

    public record Listing(long revision, List<RowView> rows, Set<String> currencies) {
    }

    public record Save(Long revision, Integer index, String bankScope, Reach reach) {
    }

    public record Toggle(Long revision) {
    }

    public record Simulation(boolean passed, String reasonCode, long revision, Integer selectedIndex,
                             boolean coverageMatched,
                             Reach selectedReach, List<C3Matcher.Row> rows) {
    }

    public Listing list() {
        var cv = routing.config();
        return new Listing(cv.revision(), IntStream.range(0, cv.config().reaches().size()).mapToObj(i -> new RowView(i, cv.config().reaches().get(i))).toList(), cv.config().hkdRates().keySet());
    }

    @Transactional
    public Listing save(Save in) {
        if (in == null || in.revision() == null || in.reach() == null)
            throw new IllegalArgumentException("配置、版本号必填");
        if (!Set.of("ALL", "LIST").contains(in.bankScope() == null ? "" : in.bankScope()))
            throw new IllegalArgumentException("银行范围须为 ALL 或 LIST");
        if (in.reach().bics() == null || ("ALL".equals(in.bankScope()) && !in.reach().bics().isEmpty()) || ("LIST".equals(in.bankScope()) && in.reach().bics().isEmpty()))
            throw new IllegalArgumentException("ALL 范围不填 BIC；LIST 范围至少填写一个 BIC8");
        var cv = routing.config();
        if (in.revision() != cv.revision()) throw new IllegalArgumentException("配置已更新，请刷新后重试");
        var rows = new ArrayList<>(cv.config().reaches());
        if (in.index() == null) rows.add(in.reach());
        else {
            if (in.index() < 0 || in.index() >= rows.size()) throw new IllegalArgumentException("覆盖行不存在");
            rows.set(in.index(), in.reach());
        }
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withReaches(rows)));
        return list();
    }

    @Transactional
    public Listing toggle(int index, Toggle in) {
        var cv = routing.config();
        if (in == null || in.revision() == null || in.revision() != cv.revision())
            throw new IllegalArgumentException("配置已更新，请刷新后重试");
        if (index < 0 || index >= cv.config().reaches().size()) throw new IllegalArgumentException("覆盖行不存在");
        var rows = new ArrayList<>(cv.config().reaches());
        Reach r = rows.get(index);
        rows.set(index, new Reach(r.provider(), r.currency(), r.country(), r.region(), r.bics(), r.charges(), r.zone(), r.cutoff(), r.offsetDays(), r.perPaymentUsd(), r.dailyUsd(), r.holidays(), !r.active()));
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withReaches(rows)));
        return list();
    }

    @Transactional
    public Listing delete(int index, Toggle in) {
        var cv = routing.config();
        if (in == null || in.revision() == null || in.revision() != cv.revision())
            throw new IllegalArgumentException("配置已更新，请刷新后重试");
        if (index < 0 || index >= cv.config().reaches().size()) throw new IllegalArgumentException("覆盖行不存在");
        var rows = new ArrayList<>(cv.config().reaches());
        rows.remove(index);
        routing.saveConfig(new RoutingService.ConfigView(cv.revision(), cv.config().withReaches(rows)));
        return list();
    }

    public Simulation simulate(C3Matcher.Input in) {
        C3Matcher.validateInput(in);
        if (!providers.existsById(in.providerCode())) throw new IllegalArgumentException("合作方不存在");
        var cv = routing.config();
        var rows = C3Matcher.rows(cv.config().reaches(), in);
        var selected = conditions.select(cv.config().reaches(), in).orElse(null);
        return new Simulation(selected != null, selected == null ? "NO_REACH" : "PASS", cv.revision(), selected == null ? null : selected.index(),
                rows.stream().anyMatch(C3Matcher.Row::matched), selected == null ? null : selected.reach(), rows);
    }
}
