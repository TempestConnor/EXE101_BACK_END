package com.exe101.listing.controller;

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
import com.exe101.listing.mapper.ListingMapperImpl;
import com.exe101.listing.model.Listing;
import com.exe101.listing.model.ListingStatus;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.service.ListingService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ListingController.class, ListingAdminController.class})
@Import({ListingMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class ListingControllerTest {
    private static String createBody = """
        {"artworkId":10,"listingType":"DIGITAL","name":"Sunset download","price":50000}
        """;
    private static String updateBody = """
        {"name":"Sunset download","price":60000,"version":"AAAAAAAAAAE="}
        """;
    private static String costReviewBody = """
        {"artistMarkup":30000,"version":"AAAAAAAAAAE="}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private ListingService listings;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void create_listing_returns_201_ok() throws Exception {
        // given
        var token = customerToken();
        var created = Listing.builder()
            .withListingId(20L)
            .withArtworkId(10L)
            .withListingType(ListingType.DIGITAL)
            .withName("Sunset download")
            .withStatus(ListingStatus.DRAFT)
            .withCurrencyCode("VND")
            .withPrice(new BigDecimal("50000.0000"))
            .withCreatedAt(OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC))
            .withVersion(new byte[] {0, 0, 0, 0, 0, 0, 0, 1})
            .withAssets(List.of())
            .build();
        when(listings.create(eq(42L), any())).thenReturn(created);

        // when / then
        mvc.perform(post("/listings").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.listingId").value(20))
            .andExpect(jsonPath("$.listingType").value("DIGITAL"))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.currencyCode").value("VND"))
            .andExpect(jsonPath("$.price").value(50000))
            .andExpect(jsonPath("$.purchasable").value(false))
            .andExpect(jsonPath("$.version").value("AAAAAAAAAAE="))
            .andExpect(jsonPath("$.assets").isEmpty());
    }

    @Test
    void create_listing_invalid_body_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/listings").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"artworkId":10,"listingType":"DIGITAL","name":" ","price":50000.5}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(listings);
    }

    @Test
    void update_listing_version_conflict_ko() throws Exception {
        // given
        var token = customerToken();
        when(listings.update(eq(42L), eq(20L), any()))
            .thenThrow(new AppException(AppErrorMessage.LISTING_VERSION_CONFLICT));

        // when / then
        mvc.perform(put("/listings/20").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(updateBody))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("LISTING_VERSION_CONFLICT"));
    }

    @Test
    void confirm_review_returns_updated_listing_ok() throws Exception {
        // given
        var token = customerToken();
        var confirmed = Listing.builder()
            .withListingId(20L)
            .withArtworkId(10L)
            .withListingType(ListingType.PHYSICAL)
            .withName("Sunset A4 print")
            .withStatus(ListingStatus.PUBLISHED)
            .withCurrencyCode("VND")
            .withPrice(new BigDecimal("110000.0000"))
            .withManufacturingCost(new BigDecimal("80000.0000"))
            .withArtistMarkup(new BigDecimal("30000.0000"))
            .withCostReviewRequired(false)
            .withPurchasable(true)
            .withCreatedAt(OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC))
            .withVersion(new byte[] {0, 0, 0, 0, 0, 0, 0, 2})
            .withAssets(List.of())
            .build();
        when(listings.confirmCostReview(eq(42L), eq(20L), any())).thenReturn(confirmed);

        // when / then
        mvc.perform(put("/listings/20/cost-review").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(costReviewBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.listingId").value(20))
            .andExpect(jsonPath("$.price").value(110000))
            .andExpect(jsonPath("$.manufacturingCost").value(80000))
            .andExpect(jsonPath("$.artistMarkup").value(30000))
            .andExpect(jsonPath("$.costReviewRequired").value(false))
            .andExpect(jsonPath("$.purchasable").value(true))
            .andExpect(jsonPath("$.version").value("AAAAAAAAAAI="));
    }

    @Test
    void confirm_review_invalid_body_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(put("/listings/20/cost-review").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"artistMarkup":-1.5}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(listings);
    }

    @Test
    void confirm_review_version_conflict_ko() throws Exception {
        // given
        var token = customerToken();
        when(listings.confirmCostReview(eq(42L), eq(20L), any()))
            .thenThrow(new AppException(AppErrorMessage.LISTING_VERSION_CONFLICT));

        // when / then
        mvc.perform(put("/listings/20/cost-review").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(costReviewBody))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("LISTING_VERSION_CONFLICT"));
    }

    @Test
    void listing_endpoints_anonymous_ko() throws Exception {
        // given
        var path = "/listings/20";

        // when / then
        mvc.perform(get(path))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(listings);
    }

    @Test
    void admin_moderate_requires_admin_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(put("/admin/listings/20/status").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"status":"MODERATED","reason":"Copyright claim"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(listings);
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
