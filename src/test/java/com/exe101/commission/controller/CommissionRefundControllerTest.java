package com.exe101.commission.controller;

import com.exe101.commission.mapper.CommissionRefundMapperImpl;
import com.exe101.commission.model.CommissionRefund;
import com.exe101.commission.model.CommissionRefundStage;
import com.exe101.commission.service.CommissionRefundService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.refund.model.RefundStatus;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommissionRefundController.class)
@Import({CommissionRefundMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CommissionRefundControllerTest {
    private static OffsetDateTime requestedAt = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC);
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private CommissionRefundService refunds;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void set_percentage_returns_refund_ok() throws Exception {
        // given
        var token = customerToken();
        when(refunds.setPercent(42L, 3L, 61L, 70)).thenReturn(CommissionRefund.builder()
            .withRefundRequestId(61L)
            .withCommissionId(3L)
            .withStatus(RefundStatus.REQUESTED)
            .withStage(CommissionRefundStage.AWAITING_CUSTOMER)
            .withRefundPercent(70)
            .withReason("The deadline no longer works")
            .withRequestedAt(requestedAt)
            .build());

        // when / then
        mvc.perform(post("/commissions/3/refund-requests/61/percentage").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"percent":70}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refundRequestId").value(61))
            .andExpect(jsonPath("$.commissionId").value(3))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.stage").value("AWAITING_CUSTOMER"))
            .andExpect(jsonPath("$.refundPercent").value(70))
            .andExpect(jsonPath("$.decidedAt").doesNotExist())
            .andExpect(jsonPath("$.transfer").doesNotExist());
    }

    @Test
    void set_percentage_missing_body_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions/3/refund-requests/61/percentage").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/commissions/3/refund-requests/61/percentage").header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(refunds);
    }

    @Test
    void object_missing_reason_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions/3/refund-requests/61/objection").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"   "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(refunds);
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
