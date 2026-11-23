package com.hsg.relay.c2;

import jakarta.persistence.*;

@Entity
@Table(name="relay_capability_tag")
public class CapabilityTag {
    @Id @Column(length=64) public String tagCode;
    @Column(nullable=false,length=120) public String tagName;
    protected CapabilityTag() {}
    public CapabilityTag(String code,String name){tagCode=code;tagName=name;}
}
