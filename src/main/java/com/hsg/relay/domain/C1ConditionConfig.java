package com.hsg.relay.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

/** C1 页面维护的全局过滤触发条件；不隶属于某一条合作方准入规则。 */
@Entity
public class C1ConditionConfig {
    @Id
    public Long id = 1L;
    @Lob
    public String conditionJson;
    public int versionNo = 1;
}
