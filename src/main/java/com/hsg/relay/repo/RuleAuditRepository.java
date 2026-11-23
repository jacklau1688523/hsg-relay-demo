package com.hsg.relay.repo;

import com.hsg.relay.domain.RuleAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleAuditRepository extends JpaRepository<RuleAudit, Long> {
}
