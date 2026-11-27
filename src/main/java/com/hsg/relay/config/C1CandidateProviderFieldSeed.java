package com.hsg.relay.config;

import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/** Make the current candidate provider available to C1 condition trees on existing databases too. */
@Configuration
public class C1CandidateProviderFieldSeed {
    @Bean
    @Order(2)
    CommandLineRunner seedC1CandidateProviderField(FieldDefinitionRepository fields) {
        return args -> {
            if (!fields.existsById("candidateProviderCode")) {
                FieldDefinition field = new FieldDefinition("candidateProviderCode", "候选合作方", "STRING", "EQ,NE", "C1");
                field.executionScope = "CANDIDATE";
                fields.save(field);
            }
        };
    }
}
