package com.hsg.relay.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 业务时钟：香港时区（HKG）。评分规则的生效时间、草稿更新时间和模拟试算的「当前时间」默认值都从这里取，
 * 测试里用 @Primary 的固定 Clock 替换，不依赖系统时间。
 */
@Configuration
public class BusinessClockConfig {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");

    @Bean
    public Clock businessClock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
