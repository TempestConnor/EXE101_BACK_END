package com.exe101.refund.controller;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.refund.mapper.RefundMapperImpl;
import com.exe101.refund.model.Refund;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.service.RefundService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RefundRequestController.class, RefundController.class, RefundAdminController.class})
@Import({RefundMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class RefundControllerTest {
    private static String requestBody = """
        {"reason":"Print arrived torn"}
        """;
    private static String approveBody = """
        {"decision":"APPROVE","reason":"Damaged in transit"}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @MockitoBean
    private RefundService refunds;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void request_refund_returns_201_ok() throws Exception {
        // given
        var token = customerToken();
        when(refunds.request(42L, 300L, 310L, "Print arrived torn")).thenReturn(Refund.builder()
            .withRefundRequestId(500L)
            .withOrderItemId(310L)
            .withRequestedByUserId(42L)
            .withReason("Print arrived torn")
            .withStatus(RefundStatus.REQUESTED)
            .withRequestedAt(OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.UTC))
            .build());

        // when / then
        mvc.perform(post("/orders/300/items/310/refund-requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.refundRequestId").value(500))
            .andExpect(jsonPath("$.orderItemId").value(310))
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.reason").value("Print arrived torn"))
            .andExpect(jsonPath("$.transfer").doesNotExist());
    }

    @Test
    void request_refund_blank_reason_returns_400_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/orders/300/items/310/refund-requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"   "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(refunds);
    }

    @Test
    void request_refund_staff_token_returns_403_ko() throws Exception {
        // given
        var token = staffToken("ADMIN");

        // when / then
        mvc.perform(post("/orders/300/items/310/refund-requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(requestBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(refunds);
    }

    @Test
    void decide_refund_moderator_returns_403_ko() throws Exception {
        // given
        var token = staffToken("MODERATOR");

        // when / then
        mvc.perform(post("/admin/refund-requests/500/decision").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(approveBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(refunds);
    }

    @Test
    void decide_refund_unknown_decision_returns_400_ko() throws Exception {
        // given
        var token = staffToken("ADMIN");

        // when / then
        mvc.perform(post("/admin/refund-requests/500/decision").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"decision":"MAYBE"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(refunds);
    }

    @Test
    void record_transfer_reference_in_use_returns_409_ko() throws Exception {
        // given
        var token = staffToken("ADMIN");
        when(refunds.recordTransferOutcome(eq(7L), eq(600L), any()))
            .thenThrow(new AppException(AppErrorMessage.REFUND_REFERENCE_IN_USE));

        // when / then
        mvc.perform(post("/admin/refund-transfers/600/status").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"status":"SUCCEEDED","transferReference":"VCB-20261101-0042"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("REFUND_REFERENCE_IN_USE"));
    }

    private String staffToken(String role) {
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "staff", role));

        return staffIssuer.issue(7L).getAccessToken();
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
