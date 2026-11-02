package com.exe101.commission.controller;

import com.exe101.commission.mapper.CommissionCancellationMapperImpl;
import com.exe101.commission.mapper.CommissionDeliverableMapperImpl;
import com.exe101.commission.mapper.CommissionMapperImpl;
import com.exe101.commission.mapper.CommissionQuoteMapperImpl;
import com.exe101.commission.model.CancellationRequest;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionDispute;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.DisputeStatus;
import com.exe101.commission.service.CommissionCancellationService;
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

@WebMvcTest({CommissionCancellationController.class, CommissionDisputeController.class})
@Import({CommissionCancellationMapperImpl.class, CommissionMapperImpl.class, CommissionQuoteMapperImpl.class,
    CommissionDeliverableMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CommissionCancellationControllerTest {
    private static OffsetDateTime createdAt = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC);
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private CommissionCancellationService cancellations;
    @MockitoBean
    private CommissionDisputeService disputes;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void request_cancellation_returns_201_ok() throws Exception {
        // given
        var token = customerToken();
        when(cancellations.request(42L, 3L, "The deadline no longer works", null)).thenReturn(CancellationRequest.builder()
            .withCancellationId(81L)
            .withCommissionId(3L)
            .withRequestedByUserId(42L)
            .withReason("The deadline no longer works")
            .withStatus(CancellationStatus.PENDING)
            .withCreatedAt(createdAt)
            .build());

        // when / then
        mvc.perform(post("/commissions/3/cancellation-requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"The deadline no longer works"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.cancellationId").value(81))
            .andExpect(jsonPath("$.commissionId").value(3))
            .andExpect(jsonPath("$.requestedByUserId").value(42))
            .andExpect(jsonPath("$.reason").value("The deadline no longer works"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.resolvedAt").doesNotExist());
    }

    @Test
    void request_with_percentage_returns_201_ok() throws Exception {
        // given
        var token = customerToken();
        when(cancellations.request(42L, 3L, "I can no longer finish it", 70)).thenReturn(CancellationRequest.builder()
            .withCancellationId(81L)
            .withCommissionId(3L)
            .withRequestedByUserId(42L)
            .withReason("I can no longer finish it")
            .withStatus(CancellationStatus.PENDING)
            .withCreatedAt(createdAt)
            .withRefundPercent(70)
            .build());

        // when / then
        mvc.perform(post("/commissions/3/cancellation-requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"I can no longer finish it","refundPercent":70}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.refundPercent").value(70));
    }

    @Test
    void offer_missing_percentage_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions/3/cancellation-requests/81/refund-offer")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(cancellations);
    }

    @Test
    void request_cancellation_invalid_body_400_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions/3/cancellation-requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"   "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(cancellations);
    }

    @Test
    void cancel_returns_commission_summary_ok() throws Exception {
        // given
        var token = customerToken();
        when(cancellations.cancel(42L, 3L, "Changed my mind")).thenReturn(Commission.builder()
            .withCommissionId(3L)
            .withCustomerId(42L)
            .withArtistId(9L)
            .withStatus(CommissionStatus.CANCELLED)
            .withCustomerNameSnapshot("Buyer")
            .withArtistNameSnapshot("Painter")
            .withCreatedAt(createdAt)
            .build());

        // when / then
        mvc.perform(post("/commissions/3/cancel").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"Changed my mind"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commissionId").value(3))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.requestDescription").doesNotExist());
    }

    @Test
    void open_dispute_returns_201_ok() throws Exception {
        // given
        var token = customerToken();
        when(disputes.open(42L, 3L, "No reply for two weeks")).thenReturn(CommissionDispute.builder()
            .withDisputeId(91L)
            .withCommissionId(3L)
            .withOpenedByUserId(42L)
            .withReason("No reply for two weeks")
            .withStatus(DisputeStatus.OPEN)
            .withOpenedAt(createdAt)
            .build());

        // when / then
        mvc.perform(post("/commissions/3/disputes").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"No reply for two weeks"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.disputeId").value(91))
            .andExpect(jsonPath("$.openedByUserId").value(42))
            .andExpect(jsonPath("$.status").value("OPEN"))
            .andExpect(jsonPath("$.outcome").doesNotExist());
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
