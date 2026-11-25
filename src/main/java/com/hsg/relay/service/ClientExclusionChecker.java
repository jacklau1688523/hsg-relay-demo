package com.hsg.relay.service;

import com.hsg.relay.repo.ClientExclusionRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ClientExclusionChecker {
    private final ClientExclusionRepository exclusions;

    public ClientExclusionChecker(ClientExclusionRepository exclusions) {
        this.exclusions = exclusions;
    }

    public boolean clientExcluded(String providerCode, String clientId, LocalDateTime now) {
        return exclusions.findAll().stream().anyMatch(e -> providerCode.equals(e.providerCode) && clientId.equals(e.clientId)
                && e.effectiveFrom != null && !now.isBefore(e.effectiveFrom) && (e.effectiveTo == null || now.isBefore(e.effectiveTo)));
    }
}
