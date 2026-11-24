package com.hsg.relay.repo;

import com.hsg.relay.domain.ProviderUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProviderUsageRepository extends JpaRepository<ProviderUsage, Long> {
    List<ProviderUsage> findByProviderCode(String providerCode);
}
