package com.hsg.relay.repo;

import com.hsg.relay.domain.C1PathVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface C1PathVersionRepository extends JpaRepository<C1PathVersion, Long> {
    Optional<C1PathVersion> findTopByOrderByVersionDesc();

    Optional<C1PathVersion> findByVersion(int version);

    List<C1PathVersion> findAllByOrderByVersionDesc();

    /** 全部已发布版本，按版本号升序（引用检查按发布先后逐个扫描）。 */
    List<C1PathVersion> findAllByOrderByVersionAsc();
}
