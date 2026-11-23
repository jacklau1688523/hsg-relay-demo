package com.hsg.relay.routing;
import jakarta.persistence.*;
@Entity @Table(name="relay_routing_document")
public class RoutingDocument {
 @Id public String id;
 public long revision;
 @Lob @Column(nullable=false,length=10000000) public String payload;
 public RoutingDocument() {}
 public RoutingDocument(String id,long revision,String payload){this.id=id;this.revision=revision;this.payload=payload;}
}
