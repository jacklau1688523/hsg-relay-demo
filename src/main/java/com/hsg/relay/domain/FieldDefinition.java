package com.hsg.relay.domain;
import jakarta.persistence.*;
@Entity @Table(name="relay_rule_field") public class FieldDefinition {
 @Id @Column(length=64) public String fieldCode;
 @Column(nullable=false,length=80) public String fieldName;
 @Column(nullable=false,length=20) public String dataType;
 @Column(nullable=false,length=200) public String allowedOperators;
 @Column(nullable=false,length=100) public String allowedRules;
 @Column(length=1000) public String valueOptions;
 @Column(nullable=false) public boolean enabled=true;
 protected FieldDefinition(){}
 public FieldDefinition(String code,String name,String type,String operators,String rules){fieldCode=code;fieldName=name;dataType=type;allowedOperators=operators;allowedRules=rules;}
 public FieldDefinition(String code,String name,String type,String operators,String rules,String values){this(code,name,type,operators,rules);valueOptions=values;}
}
