package com.hsg.relay.c2;

import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * DB-backed field whitelist for C2. The operator list and value options come from relay_rule_field.
 */
@Component
public class C2FieldCatalog {
    public record Field(String fieldCode, String fieldName, String dataType, List<String> operators,
                        List<String> options) {
    }

    private final FieldDefinitionRepository repository;

    public C2FieldCatalog(FieldDefinitionRepository repository) {
        this.repository = repository;
    }

    public List<Field> all() {
        return repository.findAll().stream().filter(f -> f.enabled && List.of(f.allowedRules.split(",")).contains("C2"))
                .sorted(Comparator.comparing(x -> x.fieldCode)).map(this::convert).toList();
    }

    private Field convert(FieldDefinition field) {
        return new Field(field.fieldCode, field.fieldName, field.dataType,
                Arrays.stream(field.allowedOperators.split(",")).map(String::trim).toList(),
                field.valueOptions == null ? List.of() : Arrays.stream(field.valueOptions.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
    }

    public Field require(String code) {
        return all().stream().filter(x -> x.fieldCode.equals(code)).findFirst().orElseThrow(() -> new IllegalArgumentException("C2 字段库中未启用的字段: " + code));
    }
}
