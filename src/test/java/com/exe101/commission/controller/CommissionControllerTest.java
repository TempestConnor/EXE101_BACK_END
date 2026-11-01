package com.exe101.commission.controller;

import com.exe101.commission.mapper.CommissionMapperImpl;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.service.CommissionService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommissionController.class)
@Import({CommissionMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CommissionControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private CommissionService commissions;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void create_request_blank_description_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"artistId":9,"description":"   ","allowPublicDisplay":false}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(commissions);
    }

    @Test
    void create_request_ok() throws Exception {
        // given
        var token = customerToken();
        var created = Commission.builder()
            .withCommissionId(3L)
            .withCustomerId(42L)
            .withArtistId(9L)
            .withRequestDescription("Portrait of my cat")
            .withStatus(CommissionStatus.REQUESTED)
            .withCustomerNameSnapshot("Buyer")
            .withArtistNameSnapshot("Painter")
            .withAllowPublicDisplay(true)
            .withCreatedAt(OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC))
            .withAttachments(List.of())
            .build();
        when(commissions.request(eq(42L), any())).thenReturn(created);

        // when / then
        mvc.perform(post("/commissions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"artistId":9,"description":"Portrait of my cat","allowPublicDisplay":true}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.commissionId").value(3))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.artistId").value(9))
            .andExpect(jsonPath("$.allowPublicDisplay").value(true))
            .andExpect(jsonPath("$.attachments").isEmpty());
    }

    @Test
    void send_message_blank_body_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions/3/messages").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"body":"  "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(commissions);
    }

    @Test
    void send_message_too_long_ko() throws Exception {
        // given
        var token = customerToken();
        var body = "{\"body\":\"" + "x".repeat(5001) + "\"}";

        // when / then
        mvc.perform(post("/commissions/3/messages").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(commissions);
    }

    @Test
    void message_edit_not_supported_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(put("/commissions/3/messages").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"body":"Edited"}
                    """))
            .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/commissions/3/messages").header("Authorization", "Bearer " + token))
            .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(commissions);
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
