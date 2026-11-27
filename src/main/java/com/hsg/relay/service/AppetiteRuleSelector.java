package com.hsg.relay.service;

import com.hsg.relay.domain.Appetite;
import com.hsg.relay.function.BusinessFunction;
import com.hsg.relay.function.RuleExecutionContext;
import com.hsg.relay.repo.AppetiteRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 按合作方、流转类别、交易币种和受益行国家查询最精确的生效准入规则。 */
@Service
public class AppetiteRuleSelector implements BusinessFunction {
    private static final List<Parameter> PARAMETERS = List.of(
            new Parameter("providerCode", "STRING", "CANDIDATE", true, "candidate.providerCode"),
            new Parameter("flowCategory", "STRING", "CANDIDATE", true, "candidate.flowCategory"),
            new Parameter("paymentCurrency", "STRING", "TRANSACTION", true, "transaction.paymentCurrency"),
            new Parameter("beneficiaryBankCountry", "STRING", "TRANSACTION", true, "transaction.beneficiaryBankCountry"),
            new Parameter("decisionTime", "DATE", "CONTEXT", false, "context.decisionTime"));

    private final AppetiteRepository appetites;

    public AppetiteRuleSelector(AppetiteRepository appetites) {
        this.appetites = appetites;
    }

    @Override public String handlerCode() { return "appetiteRuleSelector"; }
    @Override public String returnType() { return "STRING"; }
    @Override public String dataSource() { return "APPETITE"; }
    @Override public List<Parameter> parameters() { return PARAMETERS; }

    @Override
    public Outcome execute(Map<String, Object> arguments, RuleExecutionContext context) {
        String provider = (String) arguments.get("providerCode");
        String flow = (String) arguments.get("flowCategory");
        String currency = (String) arguments.get("paymentCurrency");
        String country = (String) arguments.get("beneficiaryBankCountry");
        LocalDateTime now = arguments.get("decisionTime") instanceof LocalDateTime date ? date : context.decisionTime();
        return appetites.findAll().stream()
                .filter(a -> a.enabled && provider.equals(a.providerCode) && flow.equals(a.flowCategory)
                        && a.effectiveFrom != null && !now.isBefore(a.effectiveFrom)
                        && (a.effectiveTo == null || now.isBefore(a.effectiveTo))
                        && scope(a.currency, currency) && scope(a.bankCountry, country))
                .max(Comparator.comparingInt((Appetite a) -> specificity(a, currency, country))
                        .thenComparing(a -> a.id, Comparator.reverseOrder()))
                .map(a -> new Outcome(a.action, a.id.toString()))
                .orElseGet(() -> Outcome.of("NOT_SET"));
    }

    private static boolean scope(String configured, String actual) {
        return "ALL".equals(configured) || configured.equalsIgnoreCase(actual);
    }

    private static int specificity(Appetite a, String currency, String country) {
        return (a.currency.equalsIgnoreCase(currency) ? 2 : 0)
                + (a.bankCountry.equalsIgnoreCase(country) ? 1 : 0);
    }
}
