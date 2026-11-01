package com.exe101.payment.controller;

import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.payment.mapper.PaymentMapperImpl;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.NotificationResult;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.service.PaymentService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// VNPay calls without a token and reads RspCode from a 200 response, whatever the outcome.
@WebMvcTest(VnpayNotificationController.class)
@Import({PaymentMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class VnpayNotificationControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private PaymentService payments;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void callback_with_invalid_signature_ko() throws Exception {
        // given
        when(payments.handleVnpayNotification(any())).thenReturn(NotificationResult.builder()
            .withReply(VnpayReply.INVALID_SIGNATURE).build());

        // when / then
        mvc.perform(get("/payments/vnpay/ipn").param("vnp_TxnRef", "abc").param("vnp_Amount", "15100000")
                .param("vnp_SecureHash", "not-a-signature"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.RspCode").value("97"))
            .andExpect(jsonPath("$.Message").value("Invalid signature"));
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(payments).handleVnpayNotification(captor.capture());
        assertThat(captor.getValue()).containsEntry("vnp_TxnRef", "abc").containsEntry("vnp_SecureHash",
            "not-a-signature");
    }

    @Test
    void duplicate_callback_returns_ok() throws Exception {
        // given
        when(payments.handleVnpayNotification(any())).thenReturn(NotificationResult.builder()
            .withOutcome(EventOutcome.PROCESSED).withDuplicate(true).withReply(VnpayReply.ALREADY_CONFIRMED).build());

        // when / then
        mvc.perform(get("/payments/vnpay/ipn").param("vnp_TxnRef", "abc").param("vnp_SecureHash", "ab12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.RspCode").value("02"))
            .andExpect(jsonPath("$.Message").value("Order already confirmed"));
    }
}
