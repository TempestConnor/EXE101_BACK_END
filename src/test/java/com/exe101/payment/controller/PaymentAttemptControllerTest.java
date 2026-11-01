package com.exe101.payment.controller;

import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.payment.mapper.PaymentMapperImpl;
import com.exe101.payment.model.PaymentStart;
import com.exe101.payment.service.PaymentService;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentAttemptController.class)
@Import({PaymentMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class PaymentAttemptControllerTest {
    private static String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=15100000";
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @MockitoBean
    private PaymentService payments;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void start_attempt_returns_created_ok() throws Exception {
        // given
        var token = customerToken();
        var expiresAt = OffsetDateTime.of(2026, 11, 1, 10, 30, 0, 0, ZoneOffset.UTC);
        when(payments.start(eq(42L), eq(300L), anyString())).thenReturn(PaymentStart.builder()
            .withAttemptId(500L).withOrderId(300L).withPaymentUrl(paymentUrl).withExpiresAt(expiresAt).build());

        // when / then
        mvc.perform(post("/orders/300/payment-attempts").header("Authorization", "Bearer " + token)
                .with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                }))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptId").value(500))
            .andExpect(jsonPath("$.orderId").value(300))
            .andExpect(jsonPath("$.paymentUrl").value(paymentUrl))
            .andExpect(jsonPath("$.expiresAt").exists());
        verify(payments).start(42L, 300L, "203.0.113.7");
    }

    @Test
    void start_attempt_with_staff_token_ko() throws Exception {
        // given
        var staffToken = staffIssuer.issue(7L).getAccessToken();
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        // when / then
        mvc.perform(post("/orders/300/payment-attempts").header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(payments);
    }

    @Test
    void start_attempt_without_token_ko() throws Exception {
        // given
        var path = "/orders/300/payment-attempts";

        // when / then
        mvc.perform(post(path))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(payments);
    }

    private String customerToken() {
        when(customers.requireActive(anyLong())).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
