package com.hsg.relay.domain;
import jakarta.persistence.*;
import java.time.*;
@Entity @Table(name="relay_client_exclusion") public class ClientExclusion {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,length=64) public String clientId;
 @Column(nullable=false,length=32) public String providerCode;
 @Column(nullable=false,length=512) public String reason;
 @Column(nullable=false) public LocalDateTime effectiveFrom;
 public LocalDateTime effectiveTo;
 protected ClientExclusion() {}
 public ClientExclusion(String c,String p,String reason,LocalDateTime from,LocalDateTime to){this.clientId=c;this.providerCode=p;this.reason=reason;this.effectiveFrom=from;this.effectiveTo=to;}
}
