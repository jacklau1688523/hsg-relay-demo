package com.hsg.relay.service;

import java.util.List;

/**
 * C1 合作方动态规则路径配置（一份配置 = 一棵路径清单）。
 * <ul>
 *   <li>ruleCode 固定 C1；branchMode 固定 FIRST_MATCH：按候选合作方 {@code candidate.providerCode} 选路径。</li>
 *   <li>每条路径 {@link Path} 绑定若干合作方，有自己的条件树 conditionAst 和失败原因码 failureReasonCode；
 *       命中路径后只执行这条路径的条件树，不满足即按它的失败原因码过滤，不再落到其它路径。</li>
 *   <li>DEFAULT 路径显式存在、不绑定合作方：没有任何启用路径绑定该合作方时走它；它同样要过自己的条件树，不是自动通过。</li>
 *   <li>路径执行完之后，C1 必检（客户分类 COMPLETE、客户排除、准入规则匹配、个案审批、MTD 上限）对每条路径都执行。</li>
 * </ul>
 * version 是路径所在已发布版本的版本号：草稿里为空，发布时写入快照。
 */
public record C1PathRule(String ruleCode, String branchMode, List<Path> paths) {
    public static final String RULE_CODE = "C1";
    public static final String BRANCH_MODE = "FIRST_MATCH";
    public static final String DEFAULT_PATH = "DEFAULT";

    public C1PathRule {
        paths = paths == null ? List.of() : paths;
    }

    public record Path(String pathCode, String pathName, List<String> providerCodes, Integer priority, Boolean enabled,
                       ExpressionService.Node conditionAst, String failureReasonCode, Integer version) {
        public Path {
            providerCodes = providerCodes == null ? List.of() : providerCodes;
        }

        /** 路径是否启用（缺省视为启用，与规范化后的配置一致）。 */
        public boolean on() {
            return Boolean.TRUE.equals(enabled);
        }
    }
}
