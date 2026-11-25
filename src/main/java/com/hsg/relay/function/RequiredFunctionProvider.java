package com.hsg.relay.function;

import java.util.Map;

/**
 * 规则执行器声明自己在代码里强制落实的函数（系统必检，REQUIRED）。
 * 这份清单只读：函数库元数据、页面配置都改不了它；执行器不依赖条件树里有没有对应节点。
 * 没有被任何执行器声明的函数是可选函数（OPTIONAL），由用户添加或删除。
 */
public interface RequiredFunctionProvider {
    String ruleCode();

    /** 函数编码 → 执行器强制落实的说明（含原因码）。 */
    Map<String, String> requiredFunctions();
}
