package com.exe101.fulfillment.controller;

import com.exe101.address.model.ShippingAddress;
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
import com.exe101.fulfillment.mapper.FulfillmentMapperImpl;
import com.exe101.fulfillment.model.Fulfillment;
import com.exe101.fulfillment.model.FulfillmentChange;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.fulfillment.service.FulfillmentService;
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

@WebMvcTest(FulfillmentAdminController.class)
@Import({FulfillmentMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class FulfillmentAdminControllerTest {
    private static String shipBody = """
        {"status":"SHIPPED","carrier":"VNPost","trackingReference":"EB123456789VN","reason":"Picked up"}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @MockitoBean
    private FulfillmentService fulfillments;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void transition_returns_fulfillment_ok() throws Exception {
        // given
        var token = adminToken();
        when(fulfillments.advance(eq(7L), eq(311L), any())).thenReturn(fulfillment(FulfillmentStatus.SHIPPED));

        // when / then
        mvc.perform(post("/admin/fulfillments/311/transitions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(shipBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.orderItemId").value(311))
            .andExpect(jsonPath("$.status").value("SHIPPED"))
            .andExpect(jsonPath("$.carrier").value("VNPost"))
            .andExpect(jsonPath("$.trackingReference").value("EB123456789VN"))
            .andExpect(jsonPath("$.deliveredAt").isEmpty())
            .andExpect(jsonPath("$.updatedAt").exists());
        var captor = ArgumentCaptor.forClass(FulfillmentChange.class);
        verify(fulfillments).advance(eq(7L), eq(311L), captor.capture());
        var change = captor.getValue();
        assertAll(
            () -> assertThat(change.getStatus()).isEqualTo(FulfillmentStatus.SHIPPED),
            () -> assertThat(change.getCarrier()).isEqualTo("VNPost"),
            () -> assertThat(change.getTrackingReference()).isEqualTo("EB123456789VN"),
            () -> assertThat(change.getReason()).isEqualTo("Picked up")
        );
    }

    @Test
    void transition_invalid_status_ko() throws Exception {
        // given
        var token = adminToken();

        // when / then
        mvc.perform(post("/admin/fulfillments/311/transitions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"status":"LOST"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(fulfillments);
    }

    @Test
    void transition_customer_token_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/admin/fulfillments/311/transitions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(shipBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(get("/admin/fulfillments").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden());
        verifyNoInteractions(fulfillments);
    }

    @Test
    void transition_conflict_ko() throws Exception {
        // given
        var token = adminToken();
        when(fulfillments.advance(eq(7L), eq(311L), any()))
            .thenThrow(new AppException(AppErrorMessage.FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED));

        // when / then
        mvc.perform(post("/admin/fulfillments/311/transitions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(shipBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("FULFILLMENT_STATUS_CHANGE_NOT_ALLOWED"));
    }

    @Test
    void list_by_status_ok() throws Exception {
        // given
        var token = adminToken();
        when(fulfillments.list(FulfillmentStatus.SHIPPED)).thenReturn(List.of(fulfillment(FulfillmentStatus.SHIPPED)));

        // when / then
        mvc.perform(get("/admin/fulfillments").param("status", "SHIPPED").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].orderItemId").value(311))
            .andExpect(jsonPath("$[0].status").value("SHIPPED"));
        verify(fulfillments).list(FulfillmentStatus.SHIPPED);
    }

    @Test
    void list_returns_item_and_shipping_ok() throws Exception {
        // given
        var token = adminToken();
        var row = fulfillment(FulfillmentStatus.IN_PRODUCTION);
        row.setOrderId(300L);
        row.setArtworkTitle("Sunset");
        row.setListingName("Sunset A4 print");
        row.setSpecifications("Fine-art print, A4");
        row.setQuantity(2);
        row.setShippingAddress(ShippingAddress.builder()
            .withSourceAddressId(9L)
            .withRecipientName("Nguyễn Văn A")
            .withPhone("0912345678")
            .withLine1("1 Tràng Tiền")
            .withProvinceCode("01")
            .withProvinceName("Hà Nội")
            .withCountryCode("VN")
            .build());
        when(fulfillments.list(null)).thenReturn(List.of(row));

        // when / then
        // The address is the Order's snapshot; the address-book ID it was copied from is not shown.
        mvc.perform(get("/admin/fulfillments").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].orderItemId").value(311))
            .andExpect(jsonPath("$[0].orderId").value(300))
            .andExpect(jsonPath("$[0].artworkTitle").value("Sunset"))
            .andExpect(jsonPath("$[0].listingName").value("Sunset A4 print"))
            .andExpect(jsonPath("$[0].specifications").value("Fine-art print, A4"))
            .andExpect(jsonPath("$[0].quantity").value(2))
            .andExpect(jsonPath("$[0].shippingAddress.recipientName").value("Nguyễn Văn A"))
            .andExpect(jsonPath("$[0].shippingAddress.phone").value("0912345678"))
            .andExpect(jsonPath("$[0].shippingAddress.line1").value("1 Tràng Tiền"))
            .andExpect(jsonPath("$[0].shippingAddress.provinceName").value("Hà Nội"))
            .andExpect(jsonPath("$[0].shippingAddress.countryCode").value("VN"))
            .andExpect(jsonPath("$[0].shippingAddress.sourceAddressId").doesNotExist());
    }

    private Fulfillment fulfillment(FulfillmentStatus status) {
        return Fulfillment.builder()
            .withOrderItemId(311L)
            .withStatus(status)
            .withCarrier("VNPost")
            .withTrackingReference("EB123456789VN")
            .withUpdatedAt(OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC))
            .build();
    }

    private String adminToken() {
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        return staffIssuer.issue(7L).getAccessToken();
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
