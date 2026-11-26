package com.hsg.relay.service;

import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.function.field.FieldValueResolver;
import com.hsg.relay.function.field.RuleList;
import com.hsg.relay.routing.C3Matcher;
import com.hsg.relay.routing.ReachRows;
import com.hsg.relay.routing.RoutingService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 元数据字段 providerReachRows 的解析器：当前候选合作方在本笔交易下的全部覆盖行（对象集合，元素结构 REACH_ROW）。
 * <p>覆盖行取自当前路由配置（与 C3 条件求值同一个配置版本），命中判断复用 {@link C3Matcher#rows}，
 * 不另写一套匹配逻辑；C3「最精确覆盖行」的选择算法不在这里，也不被这里替代。
 * 交易要素（币种、受益行国家、地区、BIC、扣费方式）取自规则上下文的受控路径，缺少必填要素时报错，不返回空集合。
 */
@Component
public class ProviderReachRowsResolver implements FieldValueResolver {
    public static final String RESOLVER_CODE = "providerReachRowsResolver";
    public static final String FIELD_CODE = "providerReachRows";

    private final RoutingService routing;

    // RoutingService → C1Service → ExpressionService → FieldValueService → 字段解析器：这里延迟取，避免循环依赖
    public ProviderReachRowsResolver(@Lazy RoutingService routing) {
        this.routing = routing;
    }

    @Override
    public String resolverCode() {
        return RESOLVER_CODE;
    }

    @Override
    public RuleList resolve(String fieldCode, RuleExecutionContext context) {
        if (!FIELD_CODE.equals(fieldCode))
            throw new IllegalArgumentException("字段解析器 " + RESOLVER_CODE + " 不支持字段 " + fieldCode);
        if (!"C3".equals(context.ruleCode()))
            throw new IllegalStateException("字段 " + FIELD_CODE + " 只能用于 C3，当前规则为 " + context.ruleCode());
        String provider = text(context, "candidate.providerCode");
        C3Matcher.Input input = new C3Matcher.Input(provider,
                required(context, "transaction.paymentCurrency"), required(context, "transaction.beneficiaryBankCountry"),
                text(context, "transaction.beneficiaryRegion"), required(context, "transaction.beneficiaryBankBic"),
                required(context, "transaction.chargeOption"));
        RoutingService.ConfigView config = routing.config();
        // C3 条件求值带着开始求值时的配置版本；求值途中路由配置被改过就报错，不会用新配置的覆盖行配旧版本继续判断
        Object expected = context.value("context.configRevision");
        if (expected instanceof Number revision && revision.longValue() != config.revision())
            throw new IllegalStateException("字段 " + FIELD_CODE + "：路由配置在求值途中已变更（条件按版本 " + revision + " 编译，当前版本 "
                    + config.revision() + "），请重新发起判定");
        return RuleList.records("REACH_TABLE", ReachRows.SCHEMA, ReachRows.rows(config.config().reaches(), input));
    }

    /** 必填的交易要素：没有取值时报错（不会按「不命中」或空集合处理）。 */
    private static String required(RuleExecutionContext context, String path) {
        String value = text(context, path);
        if (value.isEmpty())
            throw new IllegalStateException("字段 " + FIELD_CODE + " 需要交易要素 " + path + "，本次没有取值，无法判断覆盖行");
        return value;
    }

    private static String text(RuleExecutionContext context, String path) {
        Object value = context.value(path);
        return value == null ? "" : value.toString().trim();
    }
}
