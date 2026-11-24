package com.hsg.relay.c2;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProviderRestrictionRepository extends JpaRepository<ProviderRestriction, Long> {
    List<ProviderRestriction> findByProviderCode(String providerCode);
}
