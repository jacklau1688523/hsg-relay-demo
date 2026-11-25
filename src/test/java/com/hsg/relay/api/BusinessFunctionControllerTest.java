package com.hsg.relay.api;

import com.hsg.relay.domain.ClientExclusion;
import com.hsg.relay.repo.ClientExclusionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessFunctionControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ClientExclusionRepository exclusions;

    @Test
    void exclusionFunctionReadsProviderManagedRecords() throws Exception {
        exclusions.save(new ClientExclusion("FN-CLIENT", "PROV-T1", "test", LocalDateTime.now(ZoneId.of("Asia/Hong_Kong")).minusDays(1), null));
        mvc.perform(post("/api/function-metadata/clientExcluded/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerCode\":\"PROV-T1\",\"clientId\":\"FN-CLIENT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(true));
        mvc.perform(post("/api/function-metadata/clientExcluded/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"providerCode\":\"PROV-HO\",\"clientId\":\"FN-CLIENT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(false));
    }

    @Test
    void onlyWhitelistedHandlerCanBeSaved() throws Exception {
        String body = """
                {"functionCode":"customCheck","functionName":"自定义检查","category":"客户与准入",
                "returnType":"BOOLEAN","allowedRules":"C1","executionScope":"CANDIDATE",
                "cacheStrategy":"REQUEST","handlerCode":"java.lang.Runtime","parametersJson":"[]","enabled":true}
                """;
        mvc.perform(post("/api/function-metadata").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("只能绑定已注册的 Java Handler"));
    }
}
