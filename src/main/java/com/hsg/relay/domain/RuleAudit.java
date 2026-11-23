package com.hsg.relay.domain;
import jakarta.persistence.*;
import java.time.*;
@Entity @Table(name="relay_rule_audit") public class RuleAudit {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Column(nullable=false,length=32) public String objectType;
 @Column(nullable=false,length=64) public String objectId;
 @Column(nullable=false,length=16) public String operation;
 @Column(nullable=false,length=64) public String operatorName;
 @Column(nullable=false) public LocalDateTime operatedAt;
 @Lob @Column(length=10000000) public String beforeJson;
 @Lob @Column(length=10000000) public String afterJson;
 protected RuleAudit() {}
 public RuleAudit(String type,String id,String op,String user,String before,String after){objectType=type;objectId=id;operation=op;operatorName=user;operatedAt=LocalDateTime.now();beforeJson=before;afterJson=after;}
}
