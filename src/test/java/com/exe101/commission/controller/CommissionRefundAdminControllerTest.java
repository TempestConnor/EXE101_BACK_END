package com.exe101.commission.controller;

import com.exe101.commission.mapper.CommissionRefundMapperImpl;
import com.exe101.commission.model.CommissionRefund;
import com.exe101.commission.model.CommissionRefundDecision;
import com.exe101.commission.service.CommissionRefundService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.model.Transfer;
import com.exe101.refund.model.TransferStatus;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.model.Staff;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommissionRefundAdminController.class)
@Import({CommissionRefundMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CommissionRefundAdminControllerTest {
    private static OffsetDateTime requestedAt = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC);
    @Autowired
    private MockMvc mvc;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @MockitoBean
    private CommissionRefundService refunds;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void admin_list_by_status_ok() throws Exception {
        // given
        var token = adminToken();
        when(refunds.listForStaff(RefundStatus.REQUESTED)).thenReturn(List.of(CommissionRefund.builder()
            .withRefundRequestId(61L)
            .withCommissionId(3L)
            .withStatus(RefundStatus.REQUESTED)
            .withReason("The deadline no longer works")
            .withRequestedAt(requestedAt)
            .build()));

        // when / then
        mvc.perform(get("/admin/commission-refund-requests").param("status", "REQUESTED")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].refundRequestId").value(61))
            .andExpect(jsonPath("$[0].status").value("REQUESTED"))
            .andExpect(jsonPath("$[0].refundPercent").doesNotExist());
    }

    @Test
    void admin_set_percentage_ok() throws Exception {
        // given
        var token = adminToken();
        when(refunds.decide(eq(7L), eq(61L), any())).thenReturn(CommissionRefund.builder()
            .withRefundRequestId(61L)
            .withCommissionId(3L)
            .withStatus(RefundStatus.APPROVED)
            .withRefundPercent(100)
            .withReason("The deadline no longer works")
            .withRequestedAt(requestedAt)
            .withDecidedAt(requestedAt.plusDays(1))
            .withDecisionReason("Nothing was delivered")
            .withTransfer(Transfer.builder()
                .withTransferId(800L)
                .withAmount(new BigDecimal("55002.0000"))
                .withCurrencyCode("VND")
                .withStatus(TransferStatus.PENDING)
                .withCreatedAt(requestedAt.plusDays(1))
                .build())
            .build());

        // when / then
        mvc.perform(post("/admin/commission-refund-requests/61/decision").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"percent":100,"reason":"Nothing was delivered"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED"))
            .andExpect(jsonPath("$.refundPercent").value(100))
            .andExpect(jsonPath("$.stage").doesNotExist())
            .andExpect(jsonPath("$.transfer.transferId").value(800))
            .andExpect(jsonPath("$.transfer.amount").value(55002))
            .andExpect(jsonPath("$.transfer.status").value("PENDING"));
        var decision = ArgumentCaptor.forClass(CommissionRefundDecision.class);
        verify(refunds).decide(eq(7L), eq(61L), decision.capture());
        assertAll(
            () -> assertThat(decision.getValue().getPercent()).isEqualTo(100),
            () -> assertThat(decision.getValue().getReason()).isEqualTo("Nothing was delivered")
        );
    }

    private String adminToken() {
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        return staffIssuer.issue(7L).getAccessToken();
    }
}
