package com.hsg.relay.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.c2.C2FieldCatalog;
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
    @Autowired FieldMetadataController controller;
    @Autowired C2FieldCatalog c2Fields;
    @Autowired ExpressionService expressions;
    @Autowired FieldDefinitionRepository repository;
    @Autowired ObjectMapper json;

    @Test void newFieldFeedsExpressionAndC2PickerUntilDisabled() {
        String code="testRegionFlag";
        var input=new FieldMetadataController.Input(code,"测试地区标志","ENUM","EQ,NE,IN","C2,C8","YES,NO",true);
        controller.create(input);
        assertTrue(c2Fields.all().stream().anyMatch(f->f.fieldCode().equals(code)));
        var node=new ExpressionService.Node(null,null,code,"EQ","YES");
        var tree=new ExpressionService.Node("AND",List.of(node),null,null,null);
        assertTrue(expressions.preview(new ExpressionService.Preview(tree,Map.of(code,"YES"),"C2")).matched());
        var invalidTree=new ExpressionService.Node("AND",List.of(new ExpressionService.Node(null,null,code,"EQ","MAYBE")),null,null,null);
        assertThrows(IllegalArgumentException.class,()->expressions.preview(new ExpressionService.Preview(invalidTree,Map.of(code,"MAYBE"),"C2")));
        assertThrows(IllegalArgumentException.class,()->expressions.preview(new ExpressionService.Preview(tree,Map.of(code,"YES"),"C5")));
        controller.toggle(code);
        assertFalse(c2Fields.all().stream().anyMatch(f->f.fieldCode().equals(code)));
        assertThrows(IllegalArgumentException.class,()->expressions.preview(new ExpressionService.Preview(tree,Map.of(code,"YES"),"C2")));
    }

    @Test void invalidCodeCannotBecomeAviatorIdentifier() {
        var input=new FieldMetadataController.Input("a-b","不安全字段","STRING","EQ","C2","",true);
        assertThrows(IllegalArgumentException.class,()->controller.create(input));
        var unsupported=new FieldMetadataController.Input("numericC2","C2 数字字段","DECIMAL","GTE","C2","",true);
        assertThrows(IllegalArgumentException.class,()->controller.create(unsupported));
    }
}
