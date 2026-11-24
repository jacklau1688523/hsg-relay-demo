package com.hsg.relay.provider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 合作方管理接口的 HTTP 契约：删除只接受 DELETE 动词，版本号从查询参数读取，校验失败返回 400 + error
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProviderManagementControllerTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ProviderManagementService service;

    @Test
    void deleteRequiresTheVersionAsQueryParameterAndReportsRejectsAs400() throws Exception {
        var created = service.create(new ProviderManagementService.Input("PROV-API", "测试合作方", "OTHER_BANK", "NOT_LIVE", "API", true, 20,
                null, null, null, null, null, List.of("LOCAL_CLEARING"), null));
        String version = String.valueOf(created.provider().version);

        mvc.perform(get("/api/provider-management/PROV-API/impact")).andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities").value(1)).andExpect(jsonPath("$.reaches").value(0));
        mvc.perform(delete("/api/provider-management/PROV-API")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("合作方版本已变化，请刷新后重试"));
        mvc.perform(delete("/api/provider-management/PROV-API").param("version", "99")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("合作方版本已变化，请刷新后重试"));
        mvc.perform(post("/api/provider-management/PROV-API").param("version", version)).andExpect(status().isMethodNotAllowed());

        mvc.perform(delete("/api/provider-management/PROV-API").param("version", version)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("PROV-API")).andExpect(jsonPath("$.deleted").value(true));

        mvc.perform(get("/api/provider-management/PROV-API")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("合作方不存在"));
        mvc.perform(get("/api/provider-management/PROV-API/impact")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("合作方不存在"));
        mvc.perform(delete("/api/provider-management/PROV-API").param("version", version)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("合作方不存在"));
    }
}
