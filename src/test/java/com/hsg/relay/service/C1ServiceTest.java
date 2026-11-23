package com.hsg.relay.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:relaytest;MODE=MySQL;DATABASE_TO_UPPER=false;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class C1ServiceTest {
 @Autowired C1Service service;
 @Test void caseByCaseWithApprovalPasses(){
  var result=service.evaluate(new C1Service.Simulation("C10001","PROV-HO","USD","KG",new BigDecimal("150000")));
  assertThat(result.passed()).isTrue();assertThat(result.reasonCode()).isEqualTo("PASS");
 }
 @Test void pspRejectedByTierOneBank(){
  var result=service.evaluate(new C1Service.Simulation("C10001","PROV-T1","USD","KG",new BigDecimal("150000")));
  assertThat(result.passed()).isFalse();assertThat(result.reasonCode()).isEqualTo("APPETITE_EXCLUDED");
 }
 @Test void incompleteClientRejected(){
  var result=service.evaluate(new C1Service.Simulation("C10003","PROV-T1","USD","KG",new BigDecimal("150000")));
  assertThat(result.reasonCode()).isEqualTo("CLIENT_NOT_CLASSIFIED");
 }
 @Test void missingRuleDefaultsToDeny(){
  var result=service.evaluate(new C1Service.Simulation("C10002","PROV-HO","USD","CN",new BigDecimal("100")));
  assertThat(result.reasonCode()).isEqualTo("APPETITE_NOT_SET");
 }
}
