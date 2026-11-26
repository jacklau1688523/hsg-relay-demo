package com.hsg.relay.repo;

import com.hsg.relay.domain.ScoreRuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScoreRuleVersionRepository extends JpaRepository<ScoreRuleVersion, Long> {
    /** 某一时刻生效的版本：生效时间不晚于 at 的最高版本（没有则为空）。 */
    Optional<ScoreRuleVersion> findTopByRuleCodeAndEffectiveFromLessThanEqualOrderByEffectiveFromDescVersionDesc(String ruleCode, LocalDateTime at);

    Optional<ScoreRuleVersion> findByRuleCodeAndVersion(String ruleCode, int version);

    List<ScoreRuleVersion> findByRuleCodeOrderByVersionDesc(String ruleCode);

    Optional<ScoreRuleVersion> findTopByRuleCodeOrderByVersionDesc(String ruleCode);
}
