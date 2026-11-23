package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class C2ServiceTest {
    @Autowired
    C2Service c2;
    @Autowired
    C2ExpressionCompiler compiler;
    @Autowired
    ObjectMapper mapper;

    private C2Service.Simulation request(String provider, String currency, String bankCountry, List<String> nationalities) {
        return new C2Service.Simulation(provider, currency, currency, bankCountry, bankCountry, null, "HK", "HK", null, null, nationalities);
    }

    @Test
    void regulatoryRuleRequiresOnshoreCapability() {
        C2Service.Result failed = c2.simulate(request("PROV-HO", "CNY", "CN", List.of()));
        assertFalse(failed.passed());
        assertEquals("REG_RULE_NOT_MET", failed.reasonCode());
        assertEquals("CNY_ONSHORE", failed.regulatoryChecks().get(0).missingTags().get(0));
        C2Service.Result passed = c2.simulate(request("PROV-T1", "CNY", "CN", List.of()));
        assertTrue(passed.passed());
    }

    @Test
    void wiseCountryAndNationalityRestrictionReturnOrderedReasons() {
        C2Service.Result result = c2.simulate(request("PROV-W", "USD", "KG", List.of("RU")));
        assertFalse(result.passed());
        assertEquals("RESTRICTED_COUNTRY", result.reasonCode());
        assertTrue(result.hits().stream().anyMatch(h -> "RESTRICTED_NATIONALITY".equals(h.reasonCode())));
    }

    @Test
    void nestedConditionGroupSupportsAndOr() throws Exception {
        var tree = mapper.readTree("""
                {"operator":"OR","children":[
                  {"operator":"AND","children":[
                    {"field":"paymentCurrency","operator":"EQ","value":"CNY"},
                    {"field":"beneficiaryBankCountry","operator":"EQ","value":"CN"}
                  ]},
                  {"field":"purpose","operator":"EQ","value":"GOODS"}
                ]}
                """);
        assertTrue(compiler.evaluate(tree, Map.of("paymentCurrency", "USD", "beneficiaryBankCountry", "HK", "purpose", "GOODS")));
        assertFalse(compiler.evaluate(tree, Map.of("paymentCurrency", "USD", "beneficiaryBankCountry", "HK", "purpose", "SALARY")));
    }

    @Test
    void fieldWhitelistRejectsArbitraryField() throws Exception {
        var tree = mapper.readTree("{\"field\":\"java.lang.Runtime\",\"operator\":\"EQ\",\"value\":\"CNY\"}");
        assertThrows(IllegalArgumentException.class, () -> compiler.compile(tree));
    }
}
