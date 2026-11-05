package com.exe101.staff.controller;

import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.mapper.StaffMapperImpl;
import com.exe101.staff.model.Staff;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffLoginServiceImpl;
import com.exe101.staff.service.StaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StaffController.class)
@Import({StaffMapperImpl.class, StaffLoginServiceImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenConfiguration.class, CustomerAuthenticationConverter.class,
    SecurityConfiguration.class, SecurityProblemHandler.class, GlobalExceptionHandler.class})
class StaffControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    @Qualifier("staffJwtDecoder")
    private JwtDecoder staffDecoder;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void staff_login_ok() throws Exception {
        // given
        when(staff.authenticate(any())).thenReturn(new Staff(7L, "admin", "ADMIN"));

        // when
        var result = mvc.perform(post("/staff/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"loginName":"admin","password":"long-password"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(7200))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andReturn();
        var token = jsonMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
        var decoded = staffDecoder.decode(token);

        // then
        assertAll(
            () -> assertThat(decoded.getSubject()).isEqualTo("7"),
            () -> assertThat(decoded.getClaimAsString("iss")).isEqualTo(StaffTokenIssuer.issuer),
            () -> assertThat(decoded.getClaims()).doesNotContainKeys("role", "roles", "loginName")
        );
    }
}
