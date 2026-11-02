package com.exe101.commission.controller;

import com.exe101.commission.mapper.CommissionCancellationMapperImpl;
import com.exe101.commission.mapper.CommissionDeliverableMapperImpl;
import com.exe101.commission.mapper.CommissionMapperImpl;
import com.exe101.commission.mapper.CommissionQuoteMapperImpl;
import com.exe101.commission.model.CancellationRequest;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionDeliverable;
import com.exe101.commission.model.CommissionDispute;
import com.exe101.commission.model.CommissionMessage;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.DisputeEvidence;
import com.exe101.commission.model.DisputeOutcome;
import com.exe101.commission.model.DisputeResolution;
import com.exe101.commission.model.DisputeStatus;
import com.exe101.commission.service.CommissionDisputeService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommissionDisputeAdminController.class)
@Import({CommissionCancellationMapperImpl.class, CommissionMapperImpl.class, CommissionQuoteMapperImpl.class,
    CommissionDeliverableMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CommissionDisputeAdminControllerTest {
    private static OffsetDateTime openedAt = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC);
    @Autowired
    private MockMvc mvc;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private CommissionDisputeService disputes;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void admin_dispute_endpoints_require_staff_token_ko() throws Exception {
        // given
        var customerToken = customerIssuer.issue(42L).getAccessToken();
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        // when / then
        mvc.perform(get("/admin/commission-disputes").header("Authorization", "Bearer " + customerToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(post("/admin/commission-disputes/91/resolution").header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"outcome":"CONTINUE","reason":"Keep working"}
                    """))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/commission-disputes/91/review"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(disputes);
    }

    @Test
    void resolve_cancel_refund_body_ok() throws Exception {
        // given
        var token = adminToken();
        when(disputes.resolve(eq(7L), eq(91L), any())).thenReturn(dispute(DisputeStatus.RESOLVED)
            .withOutcome(DisputeOutcome.CANCEL_REFUND)
            .withDecidedByStaffId(7L)
            .withResolutionReason("Artist left the team")
            .withResolvedAt(openedAt.plusDays(1))
            .build());

        // when / then
        mvc.perform(post("/admin/commission-disputes/91/resolution").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"outcome":"CANCEL_REFUND","reason":"Artist left the team","refundPercent":40}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.disputeId").value(91))
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.outcome").value("CANCEL_REFUND"))
            .andExpect(jsonPath("$.decidedByStaffId").value(7))
            .andExpect(jsonPath("$.resolutionReason").value("Artist left the team"));
        var resolution = ArgumentCaptor.forClass(DisputeResolution.class);
        verify(disputes).resolve(eq(7L), eq(91L), resolution.capture());
        assertAll(
            () -> assertThat(resolution.getValue().getOutcome()).isEqualTo(DisputeOutcome.CANCEL_REFUND),
            () -> assertThat(resolution.getValue().getReason()).isEqualTo("Artist left the team"),
            () -> assertThat(resolution.getValue().getRefundPercent()).isEqualTo(40)
        );
    }

    @Test
    void resolve_dispute_invalid_outcome_400_ko() throws Exception {
        // given
        var token = adminToken();

        // when / then
        mvc.perform(post("/admin/commission-disputes/91/resolution").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"outcome":"REFUND_HALF","reason":"Split it"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(disputes);
    }

    @Test
    void list_disputes_by_status_ok() throws Exception {
        // given
        var token = adminToken();
        when(disputes.listForStaff(DisputeStatus.OPEN)).thenReturn(List.of(dispute(DisputeStatus.OPEN).build()));

        // when / then
        mvc.perform(get("/admin/commission-disputes").param("status", "OPEN")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].disputeId").value(91))
            .andExpect(jsonPath("$[0].commissionId").value(3))
            .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void read_dispute_evidence_ok() throws Exception {
        // given
        var token = adminToken();
        when(disputes.evidence(91L)).thenReturn(DisputeEvidence.builder()
            .withDispute(dispute(DisputeStatus.UNDER_REVIEW).build())
            .withCommission(Commission.builder()
                .withCommissionId(3L)
                .withCustomerId(42L)
                .withArtistId(9L)
                .withStatus(CommissionStatus.DISPUTED)
                .withCreatedAt(openedAt)
                .build())
            .withMessages(List.of(CommissionMessage.builder()
                .withMessageId(21L)
                .withSenderUserId(42L)
                .withBody("Any news?")
                .withSentAt(openedAt)
                .build()))
            .withQuotes(List.of())
            .withDeliverables(List.of(CommissionDeliverable.builder()
                .withDeliverableId(31L)
                .withCommissionId(3L)
                .withVersionNo(1)
                .withSubmittedAt(openedAt)
                .withFiles(List.of())
                .withRevisionRequests(List.of())
                .build()))
            .withCancellationRequests(List.of(CancellationRequest.builder()
                .withCancellationId(81L)
                .withCommissionId(3L)
                .withRequestedByUserId(42L)
                .withReason("Too slow")
                .withStatus(CancellationStatus.REJECTED)
                .withCreatedAt(openedAt)
                .build()))
            .build());

        // when / then
        mvc.perform(get("/admin/commission-disputes/91").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.dispute.status").value("UNDER_REVIEW"))
            .andExpect(jsonPath("$.commission.status").value("DISPUTED"))
            .andExpect(jsonPath("$.messages[0].body").value("Any news?"))
            .andExpect(jsonPath("$.quotes").isEmpty())
            .andExpect(jsonPath("$.deliverables[0].versionNo").value(1))
            .andExpect(jsonPath("$.cancellationRequests[0].status").value("REJECTED"));
    }

    private CommissionDispute.CommissionDisputeBuilder dispute(DisputeStatus status) {
        return CommissionDispute.builder()
            .withDisputeId(91L)
            .withCommissionId(3L)
            .withOpenedByUserId(42L)
            .withReason("No reply for two weeks")
            .withStatus(status)
            .withOpenedAt(openedAt);
    }

    private String adminToken() {
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        return staffIssuer.issue(7L).getAccessToken();
    }
}
