package com.zeti.bff.docs;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zeti.bff.support.BffIntegrationTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** BFF 문서: 기본은 비노출, 로컬에서 켜면 CSRF 헤더 스킴과 BFF 경로가 명세에 나온다. */
class BffSwaggerExposureTest {

    @Nested
    class Disabled extends BffIntegrationTest {
        @Autowired
        MockMvc mockMvc;

        @Test
        void docsNotExposedByDefault() throws Exception {
            mockMvc.perform(get("/bff/v3/api-docs")).andExpect(status().isNotFound());
        }
    }

    @Nested
    @TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    class Enabled extends BffIntegrationTest {
        @Autowired
        MockMvc mockMvc;

        @Test
        void docsExposeCsrfSchemeAndBffPaths() throws Exception {
            mockMvc.perform(get("/bff/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("X-CSRF-Token")))
                    .andExpect(content().string(containsString("/bff/login")));
        }
    }
}
