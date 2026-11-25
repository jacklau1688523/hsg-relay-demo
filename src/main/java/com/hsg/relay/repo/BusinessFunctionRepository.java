package com.hsg.relay.repo;

import com.hsg.relay.domain.BusinessFunctionDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessFunctionRepository extends JpaRepository<BusinessFunctionDefinition, String> {}
