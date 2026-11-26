package com.hsg.relay.config;

import com.hsg.relay.c2.C2ProviderCollectionsResolver;
import com.hsg.relay.function.field.ElementSchema;
import com.hsg.relay.routing.ReachRows;
import com.hsg.relay.service.CostComponentsResolver;
import com.hsg.relay.service.MatchedPreferenceRulesResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OBJECT_LIST 字段可以引用的元素结构（白名单）：只来自代码，页面不能新建或修改。
 * 每个结构由提供该数据的解析器声明，属性名与取值口径跟着数据来源走。
 */
@Configuration
public class ElementSchemaConfig {
    /** C3：候选合作方的覆盖行（字段 providerReachRows）。 */
    @Bean
    ElementSchema reachRowSchema() {
        return ReachRows.SCHEMA;
    }

    /** C2：合作方限制记录（字段 providerRestrictions）。 */
    @Bean
    ElementSchema providerRestrictionSchema() {
        return C2ProviderCollectionsResolver.RESTRICTION_SCHEMA;
    }

    /** C7：预估成本构成（字段 costComponents）。 */
    @Bean
    ElementSchema costComponentSchema() {
        return CostComponentsResolver.SCHEMA;
    }

    /** C8：命中的偏好规则（字段 matchedPreferenceRules）。 */
    @Bean
    ElementSchema preferenceRuleSchema() {
        return MatchedPreferenceRulesResolver.SCHEMA;
    }
}
