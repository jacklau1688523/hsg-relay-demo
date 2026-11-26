package com.hsg.relay.repo;

import com.hsg.relay.domain.RuleAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleAuditRepository extends JpaRepository<RuleAudit, Long> {
    /** 某一类对象最近的审计（按写入顺序倒序）。 */
    List<RuleAudit> findTop500ByObjectTypeOrderByIdDesc(String objectType);
}
