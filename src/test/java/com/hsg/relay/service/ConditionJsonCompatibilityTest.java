package com.hsg.relay.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.api.FieldMetadataController;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 条件树与字段定义请求体的 JSON 兼容：旧版保存的 JSON 没有 type / 取值来源，照常读取；新版形态可往返。
 * 这里用默认配置的 ObjectMapper（遇到未知属性会报错），确认节点上没有多余的输出属性。
 */
class ConditionJsonCompatibilityTest {
    private static final String LEGACY = "{\"logic\":\"AND\",\"children\":[{\"field\":\"paymentCurrency\",\"operator\":\"EQ\",\"value\":\"USD\"},"
            + "{\"logic\":\"OR\",\"children\":[{\"field\":\"beneficiaryBankCountry\",\"operator\":\"EQ\",\"value\":\"KG\"}]}]}";
    private static final String FUNCTION = "{\"type\":\"FUNCTION\",\"functionCode\":\"clientExcluded\",\"arguments\":{\"providerCode\":\"$candidate.providerCode\","
            + "\"clientId\":\"$transaction.clientId\",\"decisionTime\":\"$context.decisionTime\"},\"operator\":\"EQ\",\"value\":false}";
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacyJsonWithoutTypeIsReadAsGroupsAndFields() throws Exception {
        ExpressionService.Node tree = json.readValue(LEGACY, ExpressionService.Node.class);
        assertThat(tree.type()).isEqualTo("GROUP");
        assertThat(tree.logic()).isEqualTo("AND");
        assertThat(tree.children()).extracting(ExpressionService.Node::type).containsExactly("FIELD", "GROUP");
        assertThat(tree.children().get(0).field()).isEqualTo("paymentCurrency");
        assertThat(tree.children().get(0).value()).isEqualTo("USD");
        assertThat(tree.children().get(0).functionCode()).isNull();
        assertThat(tree.children().get(0).isFunction()).isFalse();
        assertThat(tree.children().get(1).children().get(0).type()).isEqualTo("FIELD");
    }

    @Test
    void functionNodeRoundTrips() throws Exception {
        ExpressionService.Node node = json.readValue(FUNCTION, ExpressionService.Node.class);
        assertThat(node.type()).isEqualTo("FUNCTION");
        assertThat(node.isFunction()).isTrue();
        assertThat(node.functionCode()).isEqualTo("clientExcluded");
        assertThat(node.operator()).isEqualTo("EQ");
        assertThat(node.value()).isEqualTo(false);
        assertThat(node.arguments()).containsExactlyInAnyOrderEntriesOf(Map.of("providerCode", "$candidate.providerCode",
                "clientId", "$transaction.clientId", "decisionTime", "$context.decisionTime"));
        assertThat(node.children()).isNull();

        // 没写 type 但带 functionCode 的节点同样按函数节点读取
        assertThat(json.readValue(FUNCTION.replace("\"type\":\"FUNCTION\",", ""), ExpressionService.Node.class).isFunction()).isTrue();
    }

    @Test
    void serializedTreeCarriesOnlyNodeProperties() throws Exception {
        // 保存条件时由服务端序列化节点：带 type，不多出其它属性，再次读取结果一致
        ExpressionService.Node tree = json.readValue(LEGACY, ExpressionService.Node.class);
        JsonNode out = json.valueToTree(tree);
        assertThat(out.path("type").asText()).isEqualTo("GROUP");
        assertThat(out.fieldNames()).toIterable().containsExactlyInAnyOrder("type", "logic", "children", "field", "operator", "value", "functionCode", "arguments");
        assertThat(out.has("function")).isFalse();
        assertThat(json.readValue(json.writeValueAsString(tree), ExpressionService.Node.class)).isEqualTo(tree);

        ExpressionService.Node function = json.readValue(FUNCTION, ExpressionService.Node.class);
        assertThat(json.readValue(json.writeValueAsString(function), ExpressionService.Node.class)).isEqualTo(function);
    }

    @Test
    void fieldInputAcceptsLegacyAndBindingPayloads() throws Exception {
        FieldMetadataController.Input legacy = json.readValue("{\"fieldCode\":\"testFlag\",\"fieldName\":\"标记\",\"dataType\":\"BOOLEAN\","
                + "\"allowedOperators\":\"EQ,NE\",\"allowedRules\":\"C1\",\"valueOptions\":\"\",\"enabled\":true}", FieldMetadataController.Input.class);
        assertThat(legacy.valueSource()).isNull();
        assertThat(legacy.functionCode()).isNull();
        assertThat(legacy.argumentBindings()).isNull();

        FieldMetadataController.Input bound = json.readValue("{\"fieldCode\":\"testFlag\",\"fieldName\":\"标记\",\"dataType\":\"BOOLEAN\","
                + "\"allowedOperators\":\"EQ,NE\",\"allowedRules\":\"C1\",\"enabled\":false,\"valueSource\":\"FUNCTION\",\"functionCode\":\"clientExcluded\","
                + "\"argumentBindings\":{\"clientId\":\"$transaction.clientId\"}}", FieldMetadataController.Input.class);
        assertThat(bound.enabled()).isFalse();
        assertThat(bound.valueSource()).isEqualTo("FUNCTION");
        assertThat(bound.functionCode()).isEqualTo("clientExcluded");
        assertThat(bound.argumentBindings().isObject()).isTrue();
        assertThat(bound.argumentBindings().path("clientId").asText()).isEqualTo("$transaction.clientId");

        // 代码里的旧版构造（字段七参 / 条件节点五参）与 JSON 读取结果一致
        assertThat(new FieldMetadataController.Input("testFlag", "标记", "BOOLEAN", "EQ,NE", "C1", "", true)).isEqualTo(legacy);
        ExpressionService.Node legacyNode = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "a", "EQ", "x")), null, null, null);
        ExpressionService.Node typedNode = new ExpressionService.Node("GROUP", "AND", List.of(new ExpressionService.Node("FIELD", null, null, "a", "EQ", "x", null, null)), null, null, null, null, null);
        assertThat(legacyNode).isEqualTo(typedNode);
    }
}
