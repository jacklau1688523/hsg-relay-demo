package com.hsg.relay.config;

import com.hsg.relay.routing.RoutingScoreWeightService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 综合评分权重的 7 条默认配置（全局）：应用启动时幂等初始化，逻辑见 {@link RoutingScoreWeightService#seedDefaults()}。
 * 某个交易类型在 ALL 层级下已有任何记录（含用户发布的新版本、已停用的版本）就不再写入，所以重复启动不会重复插入，
 * 也不会覆盖用户修改并发布的配置。
 */
@Configuration
public class RoutingScoreWeightSeed {
    @Bean
    ApplicationListener<ApplicationReadyEvent> seedScoreWeights(RoutingScoreWeightService service) {
        return event -> service.seedDefaults();
    }
}
