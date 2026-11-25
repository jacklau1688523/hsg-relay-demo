package com.hsg.relay.c2;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

@Entity
public class C2ConditionConfig {
    @Id public Long id = 1L;
    @Lob public String conditionJson;
    public int versionNo = 1;
}
