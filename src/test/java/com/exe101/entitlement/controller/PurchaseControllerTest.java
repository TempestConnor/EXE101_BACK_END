package com.exe101.entitlement.controller;

import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.entitlement.mapper.PurchaseMapperImpl;
import com.exe101.entitlement.model.Purchase;
import com.exe101.entitlement.model.PurchasedFile;
import com.exe101.entitlement.service.EntitlementService;
import com.exe101.listing.model.ListingType;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.model.Staff;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PurchaseController.class)
@Import({PurchaseMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class PurchaseControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @MockitoBean
    private EntitlementService entitlements;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void list_purchases_ok() throws Exception {
        // given
        var token = customerToken();
        var file = PurchasedFile.builder()
            .withAssetId(30L)
            .withFileName("sunset.psd")
            .withContentType("image/vnd.adobe.photoshop")
            .withByteLength(2048)
            .build();
        var purchase = Purchase.builder()
            .withEntitlementId(900L)
            .withGrantedAt(OffsetDateTime.of(2026, 11, 1, 10, 30, 0, 0, ZoneOffset.UTC))
            .withOrderId(300L)
            .withOrderItemId(310L)
            .withListingId(20L)
            .withListingType(ListingType.DIGITAL)
            .withArtworkTitle("Sunset")
            .withListingName("Sunset download")
            .withArtistName("Painter")
            .withFiles(List.of(file))
            .build();
        when(entitlements.listOwn(42L)).thenReturn(List.of(purchase));

        // when / then
        mvc.perform(get("/purchases").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].entitlementId").value(900))
            .andExpect(jsonPath("$[0].grantedAt").exists())
            .andExpect(jsonPath("$[0].orderId").value(300))
            .andExpect(jsonPath("$[0].orderItemId").value(310))
            .andExpect(jsonPath("$[0].listingId").value(20))
            .andExpect(jsonPath("$[0].listingType").value("DIGITAL"))
            .andExpect(jsonPath("$[0].artworkTitle").value("Sunset"))
            .andExpect(jsonPath("$[0].listingName").value("Sunset download"))
            .andExpect(jsonPath("$[0].artistName").value("Painter"))
            .andExpect(jsonPath("$[0].files[0].assetId").value(30))
            .andExpect(jsonPath("$[0].files[0].fileName").value("sunset.psd"))
            .andExpect(jsonPath("$[0].files[0].contentType").value("image/vnd.adobe.photoshop"))
            .andExpect(jsonPath("$[0].files[0].byteLength").value(2048));
    }

    @Test
    void list_purchases_staff_ko() throws Exception {
        // given
        var staffToken = staffIssuer.issue(7L).getAccessToken();
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        // when / then
        mvc.perform(get("/purchases").header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(entitlements);
    }

    @Test
    void list_purchases_unauthenticated_ko() throws Exception {
        // given
        var path = "/purchases";

        // when / then
        mvc.perform(get(path))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(entitlements);
    }

    private String customerToken() {
        when(customers.requireActive(anyLong())).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
