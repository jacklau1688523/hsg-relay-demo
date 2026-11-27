package com.hsg.relay.service;

import com.hsg.relay.domain.CheckSwitch;
import com.hsg.relay.repo.CheckSwitchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class CheckSwitchService {
    private static final Set<String> CODES = Set.of("C1", "C2", "C3", "C4", "C6", "C10");
    private final CheckSwitchRepository repository;

    public CheckSwitchService(CheckSwitchRepository repository) {
        this.repository = repository;
    }

    public record State(String code, boolean enabled) {}

    public boolean enabled(String code) {
        validate(code);
        return repository.findById(code).map(row -> row.enabled).orElse(true);
    }

    public State get(String code) {
        return new State(code, enabled(code));
    }

    @Transactional
    public State save(String code, Boolean enabled) {
        validate(code);
        if (enabled == null) throw new IllegalArgumentException("启用状态必填");
        repository.save(new CheckSwitch(code, enabled));
        return new State(code, enabled);
    }

    private static void validate(String code) {
        if (!CODES.contains(code)) throw new IllegalArgumentException("不支持的校验项：" + code);
    }
}
