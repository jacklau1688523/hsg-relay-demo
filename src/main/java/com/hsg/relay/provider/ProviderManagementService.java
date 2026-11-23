package com.hsg.relay.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.*;
import com.hsg.relay.repo.*;
import com.hsg.relay.routing.*;
import com.hsg.relay.c2.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class ProviderManagementService {
    private final ProviderRepository providers;
    private final AppetiteRepository appetites;
    private final RoutingService routing;
    private final C2Service c2;
    private final RuleAuditRepository audit;
    private final ObjectMapper json;
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");
    private static final Set<String> TYPES = Set.of("TIER1_BANK", "OTHER_BANK", "HEAD_OFFICE", "FINTECH", "NETWORK");
    private static final Set<String> CHANNELS = Set.of("SWIFT", "API", "FILE", "MANUAL", "BOOK");

    public ProviderManagementService(ProviderRepository p, AppetiteRepository a, RoutingService r, C2Service c, RuleAuditRepository au, ObjectMapper j) {
        providers = p;
        appetites = a;
        routing = r;
        c2 = c;
        audit = au;
        json = j;
    }

    public record Check(String key, String label, boolean passed, String detail) {
    }

    public record View(Provider provider, List<String> capabilityTags, List<Check> checks, boolean ready,
                       String reviewStatus) {
    }

    public record Input(String code, String name, String providerType, String status, String channel,
                        Boolean fundingCheckEnabled,
                        Integer priority, String bic, String nostroAccount, String glCode, String relationshipOwner,
                        LocalDate appetiteReviewDate,
                        List<String> capabilityTags, Long version) {
    }

    public record Related(List<RoutingConfig.Reach> reaches, List<RoutingConfig.Fee> fees, List<Appetite> appetites,
                          List<ProviderRestriction> restrictions, List<RoutingConfig.FxWindow> fxWindows) {
    }

    private Provider find(String code) {
        return providers.findById(code).orElseThrow(() -> new IllegalArgumentException("合作方不存在"));
    }

    private List<Check> checks(Provider p) {
        var cfg = routing.config().config();
        LocalDateTime now = LocalDateTime.now(HK);
        return List.of(
                new Check("reach", "业务覆盖", cfg.reaches().stream().anyMatch(r -> r.provider().equals(p.code) && r.active()), "至少一条启用覆盖行"),
                new Check("fee", "手续费", cfg.fees().stream().anyMatch(f -> f.provider().equals(p.code)), "至少一条手续费配置"),
                new Check("appetite", "业务准入", appetites.findAll().stream().anyMatch(a -> a.providerCode.equals(p.code) && a.enabled && a.effectiveFrom != null && !now.isBefore(a.effectiveFrom) && (a.effectiveTo == null || now.isBefore(a.effectiveTo))), "至少一条当前生效的准入行"),
                new Check("nostro", "HSG 往来账号", !blank(p.nostroAccount), "状态为 LIVE 时必填"),
                new Check("gl", "HSG 总账编码", !blank(p.glCode), "状态为 LIVE 时必填"),
                new Check("bic", "SWIFT BIC", !"SWIFT".equals(p.channel) || validBic(p.bic), "SWIFT 通道需8或11位 BIC"));
    }

    public List<View> list() {
        return providers.findAll().stream().sorted(Comparator.comparing(p -> p.code)).map(this::view).toList();
    }

    public View get(String code) {
        return view(find(code));
    }

    private View view(Provider p) {
        var checks = checks(p);
        LocalDate today = LocalDate.now(HK);
        String review = p.appetiteReviewDate == null ? "NOT_SET" : p.appetiteReviewDate.isBefore(today) ? "OVERDUE" : !p.appetiteReviewDate.isAfter(today.plusDays(30)) ? "DUE_SOON" : "CURRENT";
        return new View(p, c2.providerCapabilities(p.code), checks, checks.stream().allMatch(Check::passed), review);
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("审计序列化失败", e);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean validBic(String s) {
        return s != null && s.matches("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?");
    }

    private static String optional(String s, int length, String label) {
        if (blank(s)) return null;
        String v = s.trim();
        if (v.length() > length) throw new IllegalArgumentException(label + "超出长度限制");
        return v;
    }

    @Transactional
    public View create(Input in) {
        if (in == null || blank(in.code())) throw new IllegalArgumentException("合作方编码必填");
        if (providers.existsById(in.code())) throw new IllegalArgumentException("合作方编码已存在");
        return save(null, in);
    }

    @Transactional
    public View update(String code, Input in) {
        Provider p = find(code);
        if (in == null || !code.equals(in.code())) throw new IllegalArgumentException("合作方编码不可修改");
        if (in.version() == null || in.version() != p.version)
            throw new IllegalArgumentException("合作方版本已变化，请刷新后重试");
        return save(p, in);
    }

    private View save(Provider previous, Input in) {
        if (in.code() == null || !in.code().matches("[A-Z0-9_-]{1,12}"))
            throw new IllegalArgumentException("合作方编码须为1–12位大写字母、数字、下划线或短横线");
        if (blank(in.name()) || in.name().trim().length() > 80)
            throw new IllegalArgumentException("合作方名称必填，最长80字符");
        if (in.providerType() == null || !TYPES.contains(in.providerType()))
            throw new IllegalArgumentException("合作方类型无效");
        if (in.status() == null || !Set.of("LIVE", "NOT_LIVE").contains(in.status()))
            throw new IllegalArgumentException("合作方状态无效");
        if (in.channel() == null || !CHANNELS.contains(in.channel()))
            throw new IllegalArgumentException("报文通道无效");
        if (in.priority() == null || in.priority() < 1 || in.priority() > 99)
            throw new IllegalArgumentException("优先级必须为1–99");
        if (in.fundingCheckEnabled() == null) throw new IllegalArgumentException("资金校验开关必填");
        String bic = optional(in.bic(), 11, "BIC");
        if ((!blank(bic) && !validBic(bic)) || ("SWIFT".equals(in.channel()) && !validBic(bic)))
            throw new IllegalArgumentException("SWIFT 通道 BIC 必填，BIC 格式应为8或11位");
        if (in.capabilityTags() == null || in.capabilityTags().size() > 20 || new HashSet<>(in.capabilityTags()).size() != in.capabilityTags().size() || in.capabilityTags().stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("能力标签列表必填，不可重复，最多20项");
        Set<String> known = new HashSet<>(c2.capabilityTags().stream().map(t -> t.tagCode).toList());
        if (!known.containsAll(in.capabilityTags())) throw new IllegalArgumentException("能力标签无效");
        String before = previous == null ? null : encode(view(previous));
        Provider p = previous == null ? new Provider(in.code(), in.name().trim(), in.providerType(), in.channel(), in.fundingCheckEnabled()) : previous;
        p.name = in.name().trim();
        p.providerType = in.providerType();
        p.status = in.status();
        p.channel = in.channel();
        p.fundingCheckEnabled = in.fundingCheckEnabled();
        p.priority = in.priority();
        p.bic = bic;
        p.nostroAccount = optional(in.nostroAccount(), 64, "往来账号");
        p.glCode = optional(in.glCode(), 64, "总账编码");
        p.relationshipOwner = optional(in.relationshipOwner(), 64, "负责人用户编码");
        p.appetiteReviewDate = in.appetiteReviewDate();
        if ("LIVE".equals(p.status)) {
            List<String> missing = checks(p).stream().filter(x -> !x.passed()).map(Check::label).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("上线条件未齐备：" + String.join("、", missing));
        }
        providers.saveAndFlush(p);
        List<String> current = c2.providerCapabilities(p.code);
        if (!new HashSet<>(current).equals(new HashSet<>(in.capabilityTags())))
            c2.setProviderCapabilities(p.code, new C2Service.CapabilitiesInput(in.capabilityTags()));
        View after = view(p);
        audit.save(new RuleAudit("PROVIDER", p.code, previous == null ? "CREATE" : "UPDATE", "demo.operator", before, encode(after)));
        return after;
    }

    public Related related(String code) {
        find(code);
        var cfg = routing.config().config();
        return new Related(cfg.reaches().stream().filter(r -> r.provider().equals(code)).toList(), cfg.fees().stream().filter(f -> f.provider().equals(code)).toList(), appetites.findAll().stream().filter(a -> a.providerCode.equals(code)).toList(), c2.restrictions().stream().filter(r -> r.providerCode.equals(code)).toList(), cfg.fxWindows().stream().filter(w -> w.provider().equals(code) || w.provider().equals("TREASURY")).toList());
    }

    public List<RuleAudit> history(String code) {
        find(code);
        return audit.findAll().stream().filter(a -> code.equals(a.objectId) && Set.of("PROVIDER", "PROVIDER_CAPABILITY").contains(a.objectType)).sorted(Comparator.comparing((RuleAudit a) -> a.id).reversed()).limit(100).toList();
    }
}
