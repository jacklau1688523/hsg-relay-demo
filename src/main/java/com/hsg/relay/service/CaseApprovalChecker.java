package com.hsg.relay.service;

import com.hsg.relay.domain.CaseApproval;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.CaseApprovalRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 个案审批查询：合作方详情里「合作方 + 客户 + 流转类别」在决策时间有效的审批记录。
 * C1 执行器在命中 CASE_BY_CASE 规则时强制调用（APPETITE_APPROVAL_MISSING），也是函数库 hasValidCaseApproval 的后端实现。
 */
@Service
public class CaseApprovalChecker implements BusinessFunction {
    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("providerCode", "STRING", "CANDIDATE", true, null),
            new Parameter("clientId", "STRING", "TRANSACTION", true, null),
            new Parameter("flowCategory", "STRING", "CANDIDATE", true, null),
            new Parameter("decisionTime", "DATE", "CONTEXT", false, "context.decisionTime"));

    private final CaseApprovalRepository approvals;

    public CaseApprovalChecker(CaseApprovalRepository approvals) {
        this.approvals = approvals;
    }

    @Override
    public String handlerCode() {
        return "caseApprovalChecker";
    }

    @Override
    public String returnType() {
        return "BOOLEAN";
    }

    @Override
    public String dataSource() {
        return "CASE_APPROVAL";
    }

    @Override
    public List<Parameter> parameters() {
        return PARAMETERS;
    }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        Object decisionTime = arguments.get("decisionTime");
        LocalDateTime now = decisionTime instanceof LocalDateTime time ? time : context.decisionTime();
        // 有效审批：已到生效时间且未到失效时间
        return approvals.findAll().stream()
                .filter(x -> arguments.get("clientId").equals(x.clientId) && arguments.get("providerCode").equals(x.providerCode)
                        && arguments.get("flowCategory").equals(x.flowCategory)
                        && x.effectiveFrom != null && x.expiresAt != null && !now.isBefore(x.effectiveFrom) && now.isBefore(x.expiresAt))
                .min(Comparator.comparing(x -> x.id))
                .map(x -> new Outcome(true, x.id.toString()))
                .orElseGet(() -> Outcome.of(false));
    }
}
