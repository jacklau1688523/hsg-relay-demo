package com.hsg.relay.repo;

import com.hsg.relay.domain.ClientExclusion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ClientExclusionRepository extends JpaRepository<ClientExclusion, Long> {
    /** 合作方在决策时间有效的排除记录条数：已到生效时间、未到失效时间（已过期、已解除的不算）。 */
    @Query("select count(e) from ClientExclusion e where e.providerCode = :providerCode"
            + " and e.effectiveFrom <= :at and (e.effectiveTo is null or e.effectiveTo > :at)")
    long countValidForProvider(@Param("providerCode") String providerCode, @Param("at") LocalDateTime at);

    /** 合作方在决策时间有效的全部排除记录，按记录 ID 升序。 */
    @Query("select e from ClientExclusion e where e.providerCode = :providerCode"
            + " and e.effectiveFrom <= :at and (e.effectiveTo is null or e.effectiveTo > :at) order by e.id")
    List<ClientExclusion> findValidForProvider(@Param("providerCode") String providerCode, @Param("at") LocalDateTime at);

    /** 成员查询：合作方对某个客户在决策时间有效的排除记录，按记录 ID 升序。 */
    @Query("select e from ClientExclusion e where e.providerCode = :providerCode and e.clientId = :clientId"
            + " and e.effectiveFrom <= :at and (e.effectiveTo is null or e.effectiveTo > :at) order by e.id")
    List<ClientExclusion> findValidForMember(@Param("providerCode") String providerCode, @Param("clientId") String clientId,
                                             @Param("at") LocalDateTime at);
}
