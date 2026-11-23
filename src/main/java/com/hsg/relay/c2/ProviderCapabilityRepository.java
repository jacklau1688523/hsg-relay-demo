package com.hsg.relay.c2;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ProviderCapabilityRepository extends JpaRepository<ProviderCapability,Long> {
 List<ProviderCapability> findByProviderCode(String providerCode);
 void deleteByProviderCode(String providerCode);
}
