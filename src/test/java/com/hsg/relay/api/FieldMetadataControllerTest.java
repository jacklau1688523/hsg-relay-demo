package com.hsg.relay.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.c2.C2FieldCatalog;
import com.hsg.relay.c2.C2ConditionConfig;
import com.hsg.relay.c2.C2ConditionConfigRepository;
import com.hsg.relay.repo.FieldDefinitionRepository;
import com.hsg.relay.service.ExpressionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class FieldMetadataControllerTest {
    @Autowired
    FieldMetadataController controller;
    @Autowired
    C2FieldCatalog c2Fields;
    @Autowired
    ExpressionService expressions;
    @Autowired
    FieldDefinitionRepository repository;
    @Autowired
    ObjectMapper json;
    @Autowired
    C2ConditionConfigRepository c2Conditions;

    @Test
    void deletesUnusedFieldButRejectsFieldReferencedBySavedCondition() {
        controller.create(new FieldMetadataController.Input("testDeleteUnused", "可删字段", "BOOLEAN", "EQ", "C2", "", true));
        controller.delete("testDeleteUnused");
        assertFalse(repository.existsById("testDeleteUnused"));

        controller.create(new FieldMetadataController.Input("testDeleteUsed", "已引用字段", "BOOLEAN", "EQ", "C2", "", true));
        C2ConditionConfig condition = new C2ConditionConfig();
        condition.conditionJson = "{\"logic\":\"AND\",\"children\":[{\"field\":\"testDeleteUsed\",\"operator\":\"EQ\",\"value\":\"true\"}]}";
        c2Conditions.save(condition);
        assertThrows(IllegalArgumentException.class, () -> controller.delete("testDeleteUsed"));
        assertTrue(repository.existsById("testDeleteUsed"));
    }

    @Test
    void newFieldFeedsExpressionAndC2PickerUntilDisabled() {
        String code = "testRegionFlag";
        var input = new FieldMetadataController.Input(code, "测试地区标志", "ENUM", "EQ,NE,IN", "C2,C8", "YES,NO", true);
        controller.create(input);
        assertTrue(c2Fields.all().stream().anyMatch(f -> f.fieldCode().equals(code)));
        var node = new ExpressionService.Node(null, null, code, "EQ", "YES");
        var tree = new ExpressionService.Node("AND", List.of(node), null, null, null);
        assertTrue(expressions.preview(new ExpressionService.Preview(tree, Map.of(code, "YES"), "C2")).matched());
        var invalidTree = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, code, "EQ", "MAYBE")), null, null, null);
        assertThrows(IllegalArgumentException.class, () -> expressions.preview(new ExpressionService.Preview(invalidTree, Map.of(code, "MAYBE"), "C2")));
        assertThrows(IllegalArgumentException.class, () -> expressions.preview(new ExpressionService.Preview(tree, Map.of(code, "YES"), "C5")));
        controller.toggle(code);
        assertFalse(c2Fields.all().stream().anyMatch(f -> f.fieldCode().equals(code)));
        assertThrows(IllegalArgumentException.class, () -> expressions.preview(new ExpressionService.Preview(tree, Map.of(code, "YES"), "C2")));
    }

    @Test
    void invalidCodeCannotBecomeAviatorIdentifier() {
        var input = new FieldMetadataController.Input("a-b", "不安全字段", "STRING", "EQ", "C2", "", true);
        assertThrows(IllegalArgumentException.class, () -> controller.create(input));
    }

    @Test
    void booleanAndNumericFieldsCanBeMarkedForC2() {
        // C2 勾选只决定字段是否进入试算菜单和 C2 模拟的输入项，试算本身按类型比较，所以不再限定字段类型
        controller.create(new FieldMetadataController.Input("testC2Flag", "C2 布尔字段", "BOOLEAN", "EQ,NE", "C2", "", true));
        controller.create(new FieldMetadataController.Input("testC2Score", "C2 数字字段", "DECIMAL", "GTE", "C2", "", true));
        assertTrue(c2Fields.all().stream().anyMatch(f -> f.fieldCode().equals("testC2Flag")));
        var flag = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "testC2Flag", "EQ", "true")), null, null, null);
        assertTrue(expressions.preview(new ExpressionService.Preview(flag, Map.of("testC2Flag", "true"), "C2")).matched());
        assertFalse(expressions.preview(new ExpressionService.Preview(flag, Map.of("testC2Flag", "false"), "C2")).matched());
    }

    @Test
    void previewComparesValuesByFieldType() {
        // 页面控件提交的取值都是字符串："true" 须按布尔字段转换后再比较，否则与 true 比较会静默不命中
        controller.create(new FieldMetadataController.Input("testHighRisk", "高风险标记", "BOOLEAN", "EQ,NE", "C1,C8", "", true));
        var flag = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "testHighRisk", "EQ", "true")), null, null, null);
        assertTrue(expressions.preview(new ExpressionService.Preview(flag, Map.of("testHighRisk", true), "C1")).matched());
        assertTrue(expressions.preview(new ExpressionService.Preview(flag, Map.of("testHighRisk", "true"), "C1")).matched());
        assertFalse(expressions.preview(new ExpressionService.Preview(flag, Map.of("testHighRisk", "false"), "C1")).matched());
        assertFalse(expressions.preview(new ExpressionService.Preview(flag, Map.of("testHighRisk", " "), "C1")).matched());
        // 取值与字段类型不符：报错并指出字段，而不是静默不命中
        var badFlag = assertThrows(IllegalArgumentException.class, () -> expressions.preview(new ExpressionService.Preview(flag, Map.of("testHighRisk", "YES"), "C1")));
        assertEquals("字段 testHighRisk 的取值只能是 true 或 false：YES", badFlag.getMessage());

        controller.create(new FieldMetadataController.Input("testRiskScore", "风险评分", "DECIMAL", "GTE", "C1,C8", "", true));
        var score = new ExpressionService.Node("AND", List.of(new ExpressionService.Node(null, null, "testRiskScore", "GTE", "5")), null, null, null);
        assertTrue(expressions.preview(new ExpressionService.Preview(score, Map.of("testRiskScore", " 7.5 "), "C1")).matched());
        assertFalse(expressions.preview(new ExpressionService.Preview(score, Map.of("testRiskScore", "4"), "C1")).matched());
        assertFalse(expressions.preview(new ExpressionService.Preview(score, Map.of("testRiskScore", ""), "C1")).matched());
        var badScore = assertThrows(IllegalArgumentException.class, () -> expressions.preview(new ExpressionService.Preview(score, Map.of("testRiskScore", "abc"), "C1")));
        assertEquals("字段 testRiskScore 的取值不是有效数字：abc", badScore.getMessage());
    }
}
