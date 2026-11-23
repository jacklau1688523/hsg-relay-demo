package com.hsg.relay.domain;
import jakarta.persistence.*;
@Entity @Table(name="relay_client_profile") public class ClientProfile {
 @Id @Column(length=64) public String clientId;
 @Column(nullable=false,length=24) public String classificationStatus;
 // 简化演示：逗号分隔流转类别；生产应与 HSG 客户档案服务集成
 @Column(length=255) public String flowCategories;
 protected ClientProfile() {}
 public ClientProfile(String id,String state,String flows){clientId=id;classificationStatus=state;flowCategories=flows;}
}
