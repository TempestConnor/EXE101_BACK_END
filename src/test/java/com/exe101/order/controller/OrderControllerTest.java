package com.exe101.order.controller;

import com.exe101.address.model.ShippingAddress;
import com.exe101.cart.dto.CheckoutLineDTO;
import com.exe101.cart.dto.CheckoutPreviewDTO;
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
import com.exe101.listing.model.ListingType;
import com.exe101.order.mapper.OrderMapperImpl;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderAsset;
import com.exe101.order.model.OrderCommit;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.model.PaymentStatus;
import com.exe101.order.model.PaymentTarget;
import com.exe101.order.service.OrderService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@WebMvcTest(OrderController.class)
@Import({OrderMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class OrderControllerTest {
    private static String createBody = """
        {"cartVersion":"AAAAAAAAAAE=","addressId":9,"totalAmount":204006,
         "lines":[{"listingId":10,"quantity":1,"unitPrice":50005},{"listingId":11,"quantity":1,"unitPrice":110000}]}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @MockitoBean
    private OrderService orders;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void create_order_ok() throws Exception {
        // given
        var token = customerToken();
        when(orders.create(eq(42L), any())).thenReturn(order());

        // when / then
        mvc.perform(post("/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderId").value(300))
            .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
            .andExpect(jsonPath("$.expiresAt").exists())
            .andExpect(jsonPath("$.currencyCode").value("VND"))
            .andExpect(jsonPath("$.itemsSubtotal").value(110000))
            .andExpect(jsonPath("$.shippingAmount").value(30000))
            .andExpect(jsonPath("$.taxAmount").value(11000))
            .andExpect(jsonPath("$.totalAmount").value(151000))
            .andExpect(jsonPath("$.items[0].listingId").value(11))
            .andExpect(jsonPath("$.items[0].listingType").value("PHYSICAL"))
            .andExpect(jsonPath("$.items[0].artistName").value("Painter"))
            .andExpect(jsonPath("$.items[0].lineSubtotal").value(110000))
            .andExpect(jsonPath("$.items[0].assetIds[0]").value(1011))
            .andExpect(jsonPath("$.items[0].manufacturingCostPerUnit").doesNotExist())
            .andExpect(jsonPath("$.items[0].markupPerUnit").doesNotExist())
            .andExpect(jsonPath("$.items[0].artistProceeds").doesNotExist())
            .andExpect(jsonPath("$.shippingAddress.provinceName").value("Hà Nội"))
            .andExpect(jsonPath("$.payment.paymentId").value(400))
            .andExpect(jsonPath("$.payment.amount").value(151000))
            .andExpect(jsonPath("$.payment.status").value("PENDING"));
        var captor = ArgumentCaptor.forClass(OrderCommit.class);
        verify(orders).create(eq(42L), captor.capture());
        var commit = captor.getValue();
        assertAll(
            () -> assertThat(commit.getCartVersion()).containsExactly(0, 0, 0, 0, 0, 0, 0, 1),
            () -> assertThat(commit.getAddressId()).isEqualTo(9L),
            () -> assertThat(commit.getLines()).hasSize(2),
            () -> assertThat(commit.getTotalAmount()).isEqualByComparingTo("204006")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"addressId\":9,\"totalAmount\":1,"
            + "\"lines\":[{\"listingId\":10,\"quantity\":1,\"unitPrice\":1}]}",
        "{\"cartVersion\":\"not base64!\",\"totalAmount\":1,"
            + "\"lines\":[{\"listingId\":10,\"quantity\":1,\"unitPrice\":1}]}",
        "{\"cartVersion\":\"AAAAAAAAAAE=\",\"totalAmount\":1,\"lines\":[]}",
        "{\"cartVersion\":\"AAAAAAAAAAE=\","
            + "\"lines\":[{\"listingId\":10,\"quantity\":1,\"unitPrice\":1}]}",
        "{\"cartVersion\":\"AAAAAAAAAAE=\",\"totalAmount\":-1,"
            + "\"lines\":[{\"listingId\":10,\"quantity\":1,\"unitPrice\":1}]}",
        "{\"cartVersion\":\"AAAAAAAAAAE=\",\"totalAmount\":1,"
            + "\"lines\":[{\"listingId\":10,\"quantity\":0,\"unitPrice\":1}]}",
        "{\"cartVersion\":\"AAAAAAAAAAE=\",\"totalAmount\":1,"
            + "\"lines\":[{\"listingId\":10,\"quantity\":1.5,\"unitPrice\":1}]}",
        "{\"cartVersion\":\"AAAAAAAAAAE=\",\"totalAmount\":1,"
            + "\"lines\":[{\"listingId\":10,\"quantity\":1,\"unitPrice\":-1}]}"})
    void create_order_invalid_body_ko(String body) throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(orders);
    }

    @Test
    void create_order_price_changed_ko() throws Exception {
        // given
        var token = customerToken();
        var line = new CheckoutLineDTO(10L, "DIGITAL", "Sunset download", new BigDecimal("60000.0000"), 1,
            new BigDecimal("60000.0000"), new BigDecimal("6000.0000"), null);
        var preview = new CheckoutPreviewDTO("AAAAAAAAAAI=", "VND", List.of(line), new BigDecimal("60000.0000"),
            new BigDecimal("0.0000"), new BigDecimal("6000.0000"), new BigDecimal("66000.0000"), List.of(), true);
        when(orders.create(eq(42L), any())).thenThrow(new AppException(AppErrorMessage.CHECKOUT_CHANGED, preview));

        // when / then
        mvc.perform(post("/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("CHECKOUT_CHANGED"))
            .andExpect(jsonPath("$.details.cartVersion").value("AAAAAAAAAAI="))
            .andExpect(jsonPath("$.details.lines[0].unitPrice").value(60000))
            .andExpect(jsonPath("$.details.totalAmount").value(66000))
            .andExpect(jsonPath("$.details.committable").value(true));
    }

    @Test
    void create_order_staff_token_ko() throws Exception {
        // given
        var staffToken = staffIssuer.issue(7L).getAccessToken();
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        // when / then
        mvc.perform(post("/orders").header("Authorization", "Bearer " + staffToken)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(get("/orders").header("Authorization", "Bearer " + staffToken))
            .andExpect(status().isForbidden());
        verifyNoInteractions(orders);
    }

    @Test
    void create_order_unauthenticated_ko() throws Exception {
        // given / when / then
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get("/orders/300"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(orders);
    }

    @Test
    void get_order_not_found_ko() throws Exception {
        // given
        var token = customerToken();
        when(orders.get(42L, 300L)).thenThrow(new AppException(AppErrorMessage.ORDER_NOT_FOUND));

        // when / then
        mvc.perform(get("/orders/300").header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
    }

    private Order order() {
        var created = OffsetDateTime.of(2026, 10, 31, 8, 0, 0, 0, ZoneOffset.UTC);
        var item = OrderLine.builder().withOrderItemId(310L).withListingId(11L)
            .withListingType(ListingType.PHYSICAL)
            .withArtworkId(111L).withArtworkTitle("Sunset").withListingName("Sunset A4 print").withArtistId(500L)
            .withArtistName("Painter").withQuantity(1).withUnitPrice(new BigDecimal("110000.0000"))
            .withManufacturingCostPerUnit(new BigDecimal("80000.0000"))
            .withMarkupPerUnit(new BigDecimal("30000.0000"))
            .withArtistProceeds(new BigDecimal("30000.0000")).withLineSubtotal(new BigDecimal("110000.0000"))
            .withTaxAmount(new BigDecimal("11000.0000"))
            .withAssets(List.of(OrderAsset.builder().withListingAssetId(1011L).withFileId(2011L).build())).build();
        var shipping = ShippingAddress.builder().withRecipientName("Nguyễn Văn A").withProvinceCode("01")
            .withProvinceName("Hà Nội").withCountryCode("VN").build();
        var payment = PaymentTarget.builder().withPaymentId(400L).withOrderId(300L)
            .withAmount(new BigDecimal("151000.0000")).withCurrencyCode("VND").withStatus(PaymentStatus.PENDING)
            .build();

        return Order.builder().withOrderId(300L).withCustomerId(42L).withStatus(OrderStatus.PENDING_PAYMENT)
            .withCreatedAt(created).withExpiresAt(created.plusMinutes(30)).withCurrencyCode("VND")
            .withItemsSubtotal(new BigDecimal("110000.0000")).withShippingAmount(new BigDecimal("30000.0000"))
            .withTaxAmount(new BigDecimal("11000.0000")).withTotalAmount(new BigDecimal("151000.0000"))
            .withBillingName("Buyer").withItems(List.of(item)).withShippingAddress(shipping).withPayment(payment)
            .build();
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
