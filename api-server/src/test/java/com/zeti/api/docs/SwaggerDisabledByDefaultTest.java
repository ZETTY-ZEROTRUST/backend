package com.zeti.api.docs;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 운영 기본값: API 문서가 노출되지 않는다. */
@SpringBootTest
@AutoConfigureMockMvc
class SwaggerDisabledByDefaultTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocsNotExposedByDefault() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
