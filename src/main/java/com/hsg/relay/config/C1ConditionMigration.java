package com.hsg.relay.config;

import com.hsg.relay.domain.C1ConditionConfig;
import com.hsg.relay.repo.AppetiteRepository;
import com.hsg.relay.repo.C1ConditionConfigRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.Comparator;

/** 将旧页面保存在准入规则上的第一棵条件树迁到新的 C1 全局过滤配置。 */
@Configuration
public class C1ConditionMigration {
    @Bean
    @Order(20)
    CommandLineRunner migrateC1Condition(C1ConditionConfigRepository configs, AppetiteRepository appetites) {
        return args -> {
            if (configs.existsById(1L)) return;
            C1ConditionConfig config = new C1ConditionConfig();
            appetites.findAll().stream()
                    .filter(row -> row.conditionJson != null && !row.conditionJson.isBlank())
                    .min(Comparator.comparing(row -> row.id))
                    .ifPresent(row -> config.conditionJson = row.conditionJson);
            configs.save(config);
        };
    }
}
