package com.hsg.relay.routing;

import jakarta.persistence.*;

@Entity
public class C3ConditionConfig {
    @Id public Long id = 1L;
    @Lob public String conditionJson;
    public int versionNo = 1;
}
