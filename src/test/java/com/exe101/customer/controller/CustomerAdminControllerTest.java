package com.exe101.customer.controller;

import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.mapper.CustomerMapperImpl;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.model.Staff;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerAdminController.class)
@Import({CustomerMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CustomerAdminControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private CustomerService customers;
    @MockitoBean
    private StaffService staff;

    @Test
    void lookup_returns_customer_ok() throws Exception {
        // given
        var token = adminToken();
        when(customers.findByEmail("buyer@example.com")).thenReturn(Customer.builder()
            .withUserId(42L)
            .withEmail("buyer@example.com")
            .withDisplayName("Buyer")
            .withStatus("ACTIVE")
            .withCreatedAt(OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC))
            .build());

        // when / then
        mvc.perform(get("/admin/customers").param("email", "buyer@example.com")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(42))
            .andExpect(jsonPath("$.email").value("buyer@example.com"))
            .andExpect(jsonPath("$.displayName").value("Buyer"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    void lookup_customer_token_ko() throws Exception {
        // given
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());
        var token = customerIssuer.issue(42L).getAccessToken();

        // when / then
        mvc.perform(get("/admin/customers").param("email", "buyer@example.com")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verify(customers, never()).findByEmail(any());
    }

    @Test
    void lookup_missing_email_ko() throws Exception {
        // given
        var token = adminToken();

        // when / then
        mvc.perform(get("/admin/customers").header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verify(customers, never()).findByEmail(any());
    }

    private String adminToken() {
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        return staffIssuer.issue(7L).getAccessToken();
    }
}
