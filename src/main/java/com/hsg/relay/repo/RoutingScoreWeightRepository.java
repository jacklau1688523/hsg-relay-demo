package com.hsg.relay.repo;

import com.hsg.relay.domain.RoutingScoreWeight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RoutingScoreWeightRepository extends JpaRepository<RoutingScoreWeight, Long> {
    List<RoutingScoreWeight> findByTransactionTypeAndClientTierOrderByVersionNoDesc(String transactionType, String clientTier);

    Optional<RoutingScoreWeight> findByTransactionTypeAndClientTierAndStatus(String transactionType, String clientTier, String status);

    boolean existsByTransactionTypeAndClientTier(String transactionType, String clientTier);

    List<RoutingScoreWeight> findAllByOrderByTransactionTypeAscClientTierAscVersionNoDesc();

    List<RoutingScoreWeight> findByStatusIn(Collection<String> statuses);
}
