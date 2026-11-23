package com.hsg.relay.c2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsg.relay.domain.FieldDefinition;
import com.hsg.relay.repo.FieldDefinitionRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.*;
import java.time.*;
import java.util.*;

@Configuration
public class C2DemoData {
    @Bean ApplicationListener<ApplicationReadyEvent> seedC2(RegulatoryRuleRepository regs,ProviderRestrictionRepository restrictions,
            CapabilityTagRepository tagRepo,ProviderCapabilityRepository caps,FieldDefinitionRepository fields,ObjectMapper mapper){return event->{
        // All C2 selectable fields and operators are DB metadata rather than code-side frontend definitions.
        register(fields,"paymentCurrency","交易币种","CNY,USD,HKD,KGS,CNH,EUR,AUD");
        register(fields,"debitCurrency","扣账币种","CNY,USD,HKD,KGS,CNH,EUR,AUD");
        register(fields,"beneficiaryBankCountry","受益行国家","CN,KG,RU,HK,US,SG,AE,TR,KZ,AM");
        register(fields,"beneficiaryAddressCountry","受益人地址国家","CN,KG,RU,HK,US,SG,AE,TR,KZ,AM");
        register(fields,"beneficiaryRegion","受益人地区编码","CN-GD,CN-BJ,HK-HK,KG-GB,RU-MOW");
        register(fields,"orderingCountry","发起方国家","CN,KG,RU,HK,US,SG,AE,TR,KZ,AM");
        register(fields,"clientCountry","付款客户国家","CN,KG,RU,HK,US,SG,AE,TR,KZ,AM");
        register(fields,"intermediaryCountry","中间行国家","CN,KG,RU,HK,US,SG,AE,TR,KZ,AM");
        register(fields,"purpose","交易用途代码","GOODS,SERVICES,SALARY,INVESTMENT,OTHER");
        Map<String,String> tags=new LinkedHashMap<>();
        tags.put("SWIFT","SWIFT 支付能力");tags.put("CNY_ONSHORE","人民币境内清算能力");
        tags.put("LOCAL_CLEARING","本地清算能力");tags.put("CROSS_BORDER_CNY","跨境人民币能力");
        for(var x:tags.entrySet())if(!tagRepo.existsById(x.getKey()))tagRepo.save(new CapabilityTag(x.getKey(),x.getValue()));
        if(caps.count()==0){caps.save(new ProviderCapability("PROV-T1","SWIFT"));caps.save(new ProviderCapability("PROV-T1","CNY_ONSHORE"));caps.save(new ProviderCapability("PROV-HO","SWIFT"));caps.save(new ProviderCapability("PROV-W","LOCAL_CLEARING"));}
        LocalDateTime from=LocalDateTime.of(2025,10,1,0,0);
        if(regs.count()==0){
            RegulatoryRule r=new RegulatoryRule();r.ruleId="REG-001";r.ruleName="CNY 境内清算能力要求";
            r.conditionJson="""
                {"operator":"AND","children":[
                 {"field":"paymentCurrency","operator":"EQ","value":"CNY"},
                 {"field":"beneficiaryBankCountry","operator":"EQ","value":"CN"}
                ]}
                """;
            r.requiredTagsJson="[\"CNY_ONSHORE\"]";r.effectiveFrom=from;
            r.remark="示例：CNY + 受益行 CN 时，合作方需具备 CNY_ONSHORE";
            regs.save(r);
        }
        if(restrictions.count()==0){
            restriction(restrictions,"PROV-W","BENEFICIARY_COUNTRY","KG,RU",from);
            restriction(restrictions,"PROV-W","DIRECTOR_UBO_NATIONALITY","KG,RU",from);
            restriction(restrictions,"PROV-W","ORDERING_COUNTRY","KG,RU",from);
            restriction(restrictions,"PROV-W","INTERMEDIARY_COUNTRY","KG,RU",from);
            restriction(restrictions,"PROV-W","CURRENCY","KGS",from);
        }
    };}
    private static void register(FieldDefinitionRepository repo,String code,String label,String options){
        FieldDefinition field=repo.findById(code).orElse(null);
        if(field==null)repo.save(new FieldDefinition(code,label,"ENUM","EQ,NE,IN","C2",options));
    }
    private static void restriction(ProviderRestrictionRepository repo,String provider,String type,String values,LocalDateTime from){
        ProviderRestriction r=new ProviderRestriction();r.providerCode=provider;r.restrictionType=type;r.blockedValues=values;r.effectiveFrom=from;repo.save(r);
    }
}
