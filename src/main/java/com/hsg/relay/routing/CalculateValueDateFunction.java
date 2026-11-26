package com.hsg.relay.routing;

import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.RuleExecutionContext;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * C5 组合计算函数 calculateValueDate：按 C3 最终选中的覆盖行（截单时间、时区、工作日偏移、节假日日历）
 * 算出最早可实现起息日 earliestValueDate、候选起息日 candidateValueDate、延迟工作日数 delayBusinessDays。
 * <p>日期计算只在 {@link C5Calculator} 里；本类是它在函数库里的 Handler，由 C5 评分执行器对每个候选路由调用一次，
 * 结果写入评分上下文供全部评分组使用（条件树里不重复计算、不重复查日历）。
 * <p>覆盖行和日历不是标量，不走参数绑定：执行器通过 {@link RuleExecutionContext#supply} 把 {@link ReachMatch}
 * 放进求值上下文（键 {@link ReachMatch#CONTEXT_KEY}），本函数只读取并核对行号、C3 条件版本与候选合作方是否一致，
 * 不一致直接报错，绝不自行挑选覆盖行。
 * <p>返回类型 OBJECT（多个输出字段的结构），不能作为条件节点直接比较。
 */
@Component
public class CalculateValueDateFunction implements BusinessFunction {
    public static final String CODE = "calculateValueDate";
    /** 决策时间登记所用的业务时区；截单判断时再由 C5Calculator 换算到覆盖行自己的时区。 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");

    static final List<Parameter> PARAMETERS = List.of(
            new Parameter("providerCode", "STRING", "CANDIDATE", true, "candidate.providerCode"),
            new Parameter("paymentCurrency", "STRING", "TRANSACTION", false, "transaction.paymentCurrency"),
            new Parameter("decisionTime", "DATE", "CONTEXT", true, "context.decisionTime"),
            new Parameter("matchedReachRowId", "INTEGER", "CONTEXT", true, "context.matchedReachRowId"),
            new Parameter("matchedReachRuleVersion", "INTEGER", "CONTEXT", true, "context.matchedReachRuleVersion"),
            // 可选：HSG 上送的请求起息日；没有上送时不传，按业务规格默认取决策日的香港日期
            new Parameter("requestedValueDate", "DATE", "TRANSACTION", false));

    @Override
    public String handlerCode() {
        return CODE;
    }

    @Override
    public String returnType() {
        return "OBJECT";
    }

    @Override
    public String dataSource() {
        return "覆盖行配置（截单时间 / 时区 / 工作日偏移 / 节假日）";
    }

    @Override
    public List<Parameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        String owner = "函数 " + CODE;
        if (!(context.data(ReachMatch.CONTEXT_KEY) instanceof ReachMatch match))
            throw new IllegalStateException(owner + " 缺少 C3 选中的覆盖行匹配结果，不能计算起息日");
        long rowId = whole(owner, "matchedReachRowId", arguments.get("matchedReachRowId"));
        long ruleVersion = whole(owner, "matchedReachRuleVersion", arguments.get("matchedReachRuleVersion"));
        if (rowId != match.rowIndex())
            throw new IllegalStateException(owner + " 的覆盖行行号（" + rowId + "）与 C3 选中的行（#" + match.rowIndex() + "）不一致");
        if (ruleVersion != match.c3ConditionVersion())
            throw new IllegalStateException(owner + " 的 C3 条件版本（" + ruleVersion + "）与匹配记录（v" + match.c3ConditionVersion() + "）不一致");
        if (arguments.get("providerCode") instanceof String provider && !match.reach().provider().equals(provider))
            throw new IllegalStateException(owner + " 的候选合作方（" + provider + "）与覆盖行所属合作方（" + match.reach().provider() + "）不一致");
        if (!(arguments.get("decisionTime") instanceof LocalDateTime decisionTime))
            throw new IllegalStateException(owner + " 缺少决策时间");
        boolean defaulted = !arguments.containsKey("requestedValueDate");
        LocalDate requested = defaulted ? null : date(owner, arguments.get("requestedValueDate"));

        C5Calculator.Result result = C5Calculator.calculate(match.reach(), decisionTime.atZone(BUSINESS_ZONE), requested);
        return new Outcome(new Output(result, match.rowIndex(), match.c3ConditionVersion(), defaulted), "REACH#" + match.rowIndex());
    }

    /**
     * 函数输出。前三个是约定的输出字段，其余为过程说明（供试算展示和审计）：
     * earliestValueDate 最早可实现起息日；candidateValueDate 候选起息日；delayBusinessDays 延迟工作日数。
     */
    public record Output(C5Calculator.Result calculation, int matchedReachRowId, int matchedReachRuleVersion, boolean requestedValueDateDefaulted) {
        public String earliestValueDate() {
            return calculation.earliestValueDate().toString();
        }

        public String candidateValueDate() {
            return calculation.candidateValueDate().toString();
        }

        public long delayBusinessDays() {
            return calculation.delayBusinessDays();
        }
    }

    private static long whole(String owner, String name, Object value) {
        if (value instanceof Number number) return number.longValue();
        throw new IllegalStateException(owner + " 缺少参数 " + name);
    }

    private static LocalDate date(String owner, Object value) {
        if (value instanceof LocalDateTime time) return time.toLocalDate();
        if (value instanceof LocalDate day) return day;
        throw new IllegalStateException(owner + " 的参数 requestedValueDate 不是日期");
    }
}
