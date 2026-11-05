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
import com.exe101.payment.model.AttemptLookup;
import com.exe101.payment.model.AttemptStatus;
import com.exe101.payment.service.PaymentService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentLookupController.class)
@Import({PaymentMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class PaymentLookupControllerTest {
    private static String ref = "a".repeat(32);
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private PaymentService payments;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void lookup_ok() throws Exception {
        // given
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());
        var token = customerIssuer.issue(42L).getAccessToken();
        when(payments.findAttempt(42L, ref)).thenReturn(AttemptLookup.builder()
            .withAttemptRef(ref)
            .withStatus(AttemptStatus.SUCCEEDED)
            .withOrderId(300L)
            .build());

        // when / then
        mvc.perform(get("/payments/attempts/" + ref).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.attemptRef").value(ref))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.orderId").value(300))
            .andExpect(jsonPath("$.commissionId").isEmpty());
    }

    @Test
    void lookup_anonymous_ko() throws Exception {
        // given / when / then
        mvc.perform(get("/payments/attempts/" + ref))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(payments);
    }
}
