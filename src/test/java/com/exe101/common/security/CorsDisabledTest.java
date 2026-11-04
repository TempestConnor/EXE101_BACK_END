package com.exe101.common.security;

import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.controller.CustomerController;
import com.exe101.customer.mapper.CustomerMapperImpl;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerLoginServiceImpl;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The default: no origins configured. MockMvc's requests come from http://localhost, so an Origin of
// http://localhost:3000 looks cross-origin, as a request forwarded by the Vite dev proxy does.
@WebMvcTest(CustomerController.class)
@Import({CustomerMapperImpl.class, CustomerLoginServiceImpl.class, CustomerTokenConfiguration.class,
    SecurityConfiguration.class, CustomerTokenIssuer.class, CustomerAuthenticationConverter.class,
    SecurityProblemHandler.class, GlobalExceptionHandler.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, StaffTokenIssuer.class})
class CorsDisabledTest {
    private static String devOrigin = "http://localhost:3000";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer issuer;
    @MockitoBean
    private CustomerService customers;
    @MockitoBean
    private StaffService staff;

    @Test
    void preflight_no_origins_configured_ko() throws Exception {
        // given / when / then
        // No allow-origin header, so the browser refuses the cross-origin call.
        mvc.perform(options("/customers/me")
                .header("Origin", devOrigin)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(customers);
    }

    @Test
    void proxied_request_with_origin_ok() throws Exception {
        // given
        when(customers.requireActive(42L)).thenReturn(Customer.builder()
            .withUserId(42L)
            .withEmail("buyer@example.com")
            .withDisplayName("Buyer")
            .withCreatedAt(OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC))
            .build());
        var token = issuer.issue(42L).getAccessToken();

        // when / then
        // A request the dev proxy forwards still carries the browser's Origin; it is served as before.
        mvc.perform(get("/customers/me").header("Origin", devOrigin).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
            .andExpect(jsonPath("$.userId").value(42));
    }
}
