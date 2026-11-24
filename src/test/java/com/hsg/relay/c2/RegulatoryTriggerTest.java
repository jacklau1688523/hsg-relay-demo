package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.service.ExpressionService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// 附加触发条件（按字段库构建）的解析与摘要；求值语义在 C2RegulatoryRuleTest 里连同字段库一起验证
class RegulatoryTriggerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ExpressionService.Node read(String json) throws Exception {
        return mapper.readValue(json, ExpressionService.Node.class);
    }

    @Test
    void noConditionIsNothingToEnforce() {
        assertNull(RegulatoryTrigger.parse(null, mapper));
        assertNull(RegulatoryTrigger.parse("  ", mapper));
        assertNull(RegulatoryTrigger.text(null));
        assertNull(RegulatoryTrigger.problem(null, null));
        assertTrue(RegulatoryTrigger.evaluate(null, Map.of(), null).matched());
    }

    @Test
    void textDescribesGroupsAndLeaves() throws Exception {
        var tree = read("""
                {"logic":"OR","children":[
                  {"field":"purpose","operator":"IN","value":"GOODS,SERVICES"},
                  {"logic":"AND","children":[{"field":"debitCurrency","operator":"NE","value":"USD"}]},
                  {"children":[],"field":"c2TestScore","operator":"LTE","value":"5.5"}
                ]}
                """);
        assertEquals("(purpose 属于 GOODS,SERVICES 或 (debitCurrency ≠ USD) 或 c2TestScore ≤ 5.5)", RegulatoryTrigger.text(tree));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(IllegalStateException.class, () -> RegulatoryTrigger.parse("{not json", mapper));
    }
}
