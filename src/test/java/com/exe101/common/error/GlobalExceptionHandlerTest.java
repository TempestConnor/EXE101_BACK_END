package com.exe101.common.error;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Spring's own errors, raised by a probe controller outside the security chain, so only the handler is exercised.
class GlobalExceptionHandlerTest {
    private static long uploadLimitBytes = 200L * 1024 * 1024;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void upload_too_large_ko() throws Exception {
        // given / when / then
        // Thrown while the multipart request is parsed, before any controller code runs.
        mvc.perform(post("/probe/upload"))
            .andExpect(status().is(413))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void wrong_method_ko() throws Exception {
        // given / when / then
        mvc.perform(post("/probe/read"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void wrong_content_type_ko() throws Exception {
        // given / when / then
        mvc.perform(post("/probe/json").contentType(MediaType.TEXT_PLAIN).content("name=value"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void malformed_body_still_invalid_request_ko() throws Exception {
        // given / when / then
        mvc.perform(post("/probe/json").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/read")
        public Map<String, String> read() {
            return Map.of("status", "ok");
        }

        @PostMapping(value = "/probe/json", consumes = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, String> json(@RequestBody Map<String, String> body) {
            return body;
        }

        @PostMapping("/probe/upload")
        public Map<String, String> upload() {
            throw new MaxUploadSizeExceededException(uploadLimitBytes);
        }
    }
}
