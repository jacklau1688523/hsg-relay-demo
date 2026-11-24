package com.hsg.relay.repo;

import com.hsg.relay.domain.Appetite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AppetiteRepository extends JpaRepository<Appetite, Long> {
    List<Appetite> findByProviderCode(String providerCode);
}
