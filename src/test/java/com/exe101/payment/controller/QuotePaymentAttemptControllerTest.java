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
import java.math.BigDecimal;
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

@WebMvcTest(QuotePaymentAttemptController.class)
@Import({PaymentMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class QuotePaymentAttemptControllerTest {
    private static String paymentUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?vnp_Amount=5500200";
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
    void start_quote_payment_returns_url_ok() throws Exception {
        // given
        var token = customerToken();
        var expiresAt = OffsetDateTime.of(2026, 11, 2, 10, 30, 0, 0, ZoneOffset.UTC);
        when(payments.startQuote(eq(42L), eq(3L), eq(21L), anyString())).thenReturn(PaymentStart.builder()
            .withAttemptId(500L)
            .withCommissionId(3L)
            .withQuoteId(21L)
            .withAmount(new BigDecimal("55002.0000"))
            .withPaymentUrl(paymentUrl)
            .withExpiresAt(expiresAt)
            .build());

        // when / then
        mvc.perform(post("/commissions/3/quotes/21/payment-attempts").header("Authorization", "Bearer " + token)
                .with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                }))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attemptId").value(500))
            .andExpect(jsonPath("$.commissionId").value(3))
            .andExpect(jsonPath("$.quoteId").value(21))
            .andExpect(jsonPath("$.amount").value(55002.0))
            .andExpect(jsonPath("$.paymentUrl").value(paymentUrl))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.orderId").doesNotExist());
        verify(payments).startQuote(42L, 3L, 21L, "203.0.113.7");
    }

    @Test
    void start_quote_payment_staff_token_ko() throws Exception {
        // given
        var staffToken = staffIssuer.issue(7L).getAccessToken();
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        // when / then
        mvc.perform(post("/commissions/3/quotes/21/payment-attempts").header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(payments);
    }

    private String customerToken() {
        when(customers.requireActive(anyLong())).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
