package com.exe101.cart.controller;

import com.exe101.cart.mapper.CartMapperImpl;
import com.exe101.cart.model.CheckoutLine;
import com.exe101.cart.model.CheckoutPreview;
import com.exe101.cart.model.CheckoutProblem;
import com.exe101.cart.service.CartService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.listing.model.ListingType;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.model.Staff;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CartController.class)
@Import({CartMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CartControllerTest {
    private static String addBody = """
        {"listingId":10,"quantity":1}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @MockitoBean
    private CartService carts;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void cart_requires_customer_token_ko() throws Exception {
        // given
        var staffToken = staffIssuer.issue(7L).getAccessToken();
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        // when / then
        mvc.perform(get("/cart"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/cart/items").contentType(MediaType.APPLICATION_JSON).content(addBody))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/cart/items/10").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":2}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/cart/items/10"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/cart/checkout-preview"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/cart").header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(carts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"listingId\":10}", "{\"listingId\":10,\"quantity\":0}",
        "{\"listingId\":10,\"quantity\":-1}", "{\"listingId\":10,\"quantity\":1.5}",
        "{\"listingId\":10,\"quantity\":\"two\"}"})
    void add_item_invalid_quantity_ko(String body) throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/cart/items").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(carts);
    }

    @Test
    void preview_response_shape_ok() throws Exception {
        // given
        var token = customerToken();
        var line = CheckoutLine.builder().withListingId(20L).withListingType(ListingType.PHYSICAL)
            .withName("Sunset A4 print").withUnitPrice(new BigDecimal("110000.0000")).withQuantity(2)
            .withLineTotal(new BigDecimal("220000.0000")).withTaxAmount(new BigDecimal("0.0000")).build();
        var preview = CheckoutPreview.builder().withCartVersion(new byte[] {0, 0, 0, 0, 0, 0, 0, 1})
            .withCurrencyCode("VND").withLines(List.of(line)).withItemsSubtotal(new BigDecimal("220000.0000"))
            .withShippingAmount(new BigDecimal("30000.0000")).withTaxAmount(new BigDecimal("0.0000"))
            .withTotalAmount(new BigDecimal("250000.0000"))
            .withProblems(List.of(CheckoutProblem.ADDRESS_REQUIRED)).withCommittable(false).build();
        when(carts.preview(42L, null)).thenReturn(preview);

        // when / then
        mvc.perform(get("/cart/checkout-preview").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cartVersion").value("AAAAAAAAAAE="))
            .andExpect(jsonPath("$.currencyCode").value("VND"))
            .andExpect(jsonPath("$.lines[0].listingId").value(20))
            .andExpect(jsonPath("$.lines[0].listingType").value("PHYSICAL"))
            .andExpect(jsonPath("$.lines[0].unitPrice").value(110000))
            .andExpect(jsonPath("$.lines[0].quantity").value(2))
            .andExpect(jsonPath("$.lines[0].lineTotal").value(220000))
            .andExpect(jsonPath("$.lines[0].taxAmount").value(0))
            .andExpect(jsonPath("$.lines[0].manufacturingCost").doesNotExist())
            .andExpect(jsonPath("$.itemsSubtotal").value(220000))
            .andExpect(jsonPath("$.shippingAmount").value(30000))
            .andExpect(jsonPath("$.taxAmount").value(0))
            .andExpect(jsonPath("$.totalAmount").value(250000))
            .andExpect(jsonPath("$.problems[0]").value("ADDRESS_REQUIRED"))
            .andExpect(jsonPath("$.committable").value(false));
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
