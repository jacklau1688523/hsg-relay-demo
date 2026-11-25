package com.hsg.relay.service;

import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.ProviderUsageRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 合作方月度累计金额（MTD）：「合作方 + 流转类别 + 月份」在 MTD 使用情况里的月累计金额合计，没有记录时为 0。
 * C1 执行器在命中设有月度上限的 ACCEPT 规则时强制调用（APPETITE_CAP_REACHED），也是函数库 getProviderMtd 的后端实现。
 * 只读：不占用、不扣减 MTD。
 */
@Service
public class ProviderMtdReader implements BusinessFunction {
    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("providerCode", "STRING", "CANDIDATE", true, null),
            new Parameter("flowCategory", "STRING", "CANDIDATE", true, null),
            new Parameter("monthKey", "STRING", "CONTEXT", false, "context.monthKey"));

    private final ProviderUsageRepository usages;

    public ProviderMtdReader(ProviderUsageRepository usages) {
        this.usages = usages;
    }

    @Override
    public String handlerCode() {
        return "providerMtdReader";
    }

    @Override
    public String returnType() {
        return "DECIMAL";
    }

    @Override
    public String dataSource() {
        return "PROVIDER_USAGE";
    }

    @Override
    public List<Parameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        List<com.hsg.relay.domain.ProviderUsage> rows = usages.findAll().stream()
                .filter(u -> arguments.get("providerCode").equals(u.providerCode) && arguments.get("flowCategory").equals(u.flowCategory)
                        && arguments.get("monthKey").equals(u.monthKey))
                .toList();
        BigDecimal total = rows.stream().map(u -> u.amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Outcome(total, rows.stream().map(u -> u.id.toString()).sorted().collect(Collectors.joining(",")));
    }
}
