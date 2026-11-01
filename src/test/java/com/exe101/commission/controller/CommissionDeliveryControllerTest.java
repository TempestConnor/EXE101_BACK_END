package com.exe101.commission.controller;

import com.exe101.commission.mapper.CommissionDeliverableMapperImpl;
import com.exe101.commission.mapper.CommissionMapperImpl;
import com.exe101.commission.model.CommissionDeliverable;
import com.exe101.commission.model.DeliverableFile;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.service.CommissionDeliveryService;
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
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommissionDeliveryController.class)
@Import({CommissionDeliverableMapperImpl.class, CommissionMapperImpl.class, StaffTokenIssuer.class,
    StaffTokenConfiguration.class, StaffAuthenticationConverter.class, CustomerTokenIssuer.class,
    CustomerTokenConfiguration.class, CustomerAuthenticationConverter.class, SecurityConfiguration.class,
    SecurityProblemHandler.class, GlobalExceptionHandler.class})
class CommissionDeliveryControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private CommissionDeliveryService deliveries;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void submit_multipart_returns_201_ok() throws Exception {
        // given
        var token = customerToken();
        var request = new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE,
            "{\"notes\":\"Final render\"}".getBytes(StandardCharsets.UTF_8));
        var file = new MockMultipartFile("files", "cat.png", MediaType.IMAGE_PNG_VALUE, new byte[] {1, 2, 3});
        when(deliveries.submit(eq(42L), eq(3L), any())).thenReturn(deliverable());

        // when / then
        mvc.perform(multipart("/commissions/3/deliverables").file(request).file(file)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.deliverableId").value(31))
            .andExpect(jsonPath("$.commissionId").value(3))
            .andExpect(jsonPath("$.versionNo").value(1))
            .andExpect(jsonPath("$.notes").value("Final render"))
            .andExpect(jsonPath("$.files[0].fileId").value(70))
            .andExpect(jsonPath("$.files[0].originalName").value("cat.png"))
            .andExpect(jsonPath("$.files[0].contentType").value("image/png"))
            .andExpect(jsonPath("$.files[0].byteLength").value(3))
            .andExpect(jsonPath("$.files[0].storageKey").doesNotExist())
            .andExpect(jsonPath("$.revisionRequests").isEmpty());
        var submission = ArgumentCaptor.forClass(DeliverableSubmission.class);
        verify(deliveries).submit(eq(42L), eq(3L), submission.capture());
        assertAll(
            () -> assertThat(submission.getValue().getNotes()).isEqualTo("Final render"),
            () -> assertThat(submission.getValue().getUploads()).singleElement()
                .satisfies(upload -> assertThat(upload.getOriginalName()).isEqualTo("cat.png"))
        );
    }

    @Test
    void request_revision_blank_details_returns_400_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions/3/deliverables/31/revision-requests").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"details":"   "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(deliveries);
    }

    @Test
    void read_locked_file_returns_403_ko() throws Exception {
        // given
        var token = customerToken();
        when(deliveries.readFile(42L, 3L, 31L, 70L)).thenThrow(new AppException(AppErrorMessage.DELIVERABLE_LOCKED));

        // when / then
        mvc.perform(get("/commissions/3/deliverables/31/files/70/content").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("DELIVERABLE_LOCKED"));
    }

    private CommissionDeliverable deliverable() {
        return CommissionDeliverable.builder()
            .withDeliverableId(31L)
            .withCommissionId(3L)
            .withVersionNo(1)
            .withNotes("Final render")
            .withSubmittedAt(OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC))
            .withFiles(List.of(DeliverableFile.builder()
                .withFileId(70L)
                .withOriginalName("cat.png")
                .withContentType("image/png")
                .withByteLength(3)
                .build()))
            .withRevisionRequests(List.of())
            .build();
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
