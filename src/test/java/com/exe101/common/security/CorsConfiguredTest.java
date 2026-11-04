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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A storefront on its own origin; any endpoint goes through the same security chain, /customers/me stands for all.
@WebMvcTest(controllers = CustomerController.class, properties = "app.cors.allowed-origins=https://shop.example.com")
@Import({CustomerMapperImpl.class, CustomerLoginServiceImpl.class, CustomerTokenConfiguration.class,
    SecurityConfiguration.class, CustomerTokenIssuer.class, CustomerAuthenticationConverter.class,
    SecurityProblemHandler.class, GlobalExceptionHandler.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, StaffTokenIssuer.class})
class CorsConfiguredTest {
    private static String storefront = "https://shop.example.com";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer issuer;
    @MockitoBean
    private CustomerService customers;
    @MockitoBean
    private StaffService staff;

    @Test
    void preflight_allowed_origin_ok() throws Exception {
        // given / when / then
        // Answered before authorization: a preflight carries no token.
        mvc.perform(options("/customers/me")
                .header("Origin", storefront)
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", storefront))
            .andExpect(header().string("Access-Control-Allow-Methods", containsString("GET")))
            .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        verifyNoInteractions(customers);
    }

    @Test
    void preflight_other_origin_ko() throws Exception {
        // given / when / then
        mvc.perform(options("/customers/me")
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(customers);
    }

    @Test
    void request_allowed_origin_has_header_ok() throws Exception {
        // given
        when(customers.requireActive(42L)).thenReturn(Customer.builder()
            .withUserId(42L)
            .withEmail("buyer@example.com")
            .withDisplayName("Buyer")
            .withCreatedAt(OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC))
            .build());
        var token = issuer.issue(42L).getAccessToken();

        // when / then
        mvc.perform(get("/customers/me").header("Origin", storefront).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", storefront))
            .andExpect(jsonPath("$.userId").value(42))
            .andExpect(jsonPath("$.email").value("buyer@example.com"));
    }
}
