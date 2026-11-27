package com.hsg.relay.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class CheckSwitch {
    @Id
    public String code;
    public boolean enabled;

    protected CheckSwitch() {}

    public CheckSwitch(String code, boolean enabled) {
        this.code = code;
        this.enabled = enabled;
    }
}
