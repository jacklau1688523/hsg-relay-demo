package com.hsg.relay.routing;

/**
 * C7 成本评分的执行异常：阶段顺序被破坏（成本未全部完成就聚合、决策结果未完成就读取）、候选成本计算失败、
 * 结果类型不符或为负数等。它表示这次决策的 C7 无法给出可信结果，整笔路由按执行异常上报——
 * 不会把失败当成 0 成本、0 分，也不会悄悄把候选从可路由集合里剔除。
 * 继承 IllegalStateException：接口层按参数 / 状态错误返回 400，与其它规则执行器的报错口径一致。
 */
public class C7ExecutionException extends IllegalStateException {
    public C7ExecutionException(String message) {
        super(message);
    }

    public C7ExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
