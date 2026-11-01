package com.exe101.payment.service;

import com.exe101.address.service.AddressService;
import com.exe101.cart.mapper.CartMapperImpl;
import com.exe101.cart.service.CartService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.service.CustomerService;
import com.exe101.entitlement.service.EntitlementService;
import com.exe101.entity.MarketplaceOrder;
import com.exe101.entity.Payment;
import com.exe101.entity.PaymentAttempt;
import com.exe101.entity.ProviderEvent;
import com.exe101.fulfillment.service.FulfillmentService;
import com.exe101.ledger.service.LedgerService;
import com.exe101.listing.service.ListingService;
import com.exe101.order.config.OrderProperties;
import com.exe101.order.mapper.OrderEntityMapperImpl;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.repository.ItemFulfillmentRepository;
import com.exe101.order.repository.ItemRefundRepository;
import com.exe101.order.repository.OrderItemAssetRepository;
import com.exe101.order.repository.OrderItemRepository;
import com.exe101.order.repository.OrderRepository;
import com.exe101.order.repository.OrderShippingAddressRepository;
import com.exe101.order.repository.PaymentRepository;
import com.exe101.order.service.OrderServiceImpl;
import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.mapper.PaymentEntityMapperImpl;
import com.exe101.payment.model.EventOutcome;
import com.exe101.payment.model.VnpayReply;
import com.exe101.payment.repository.PaymentAttemptRepository;
import com.exe101.payment.repository.ProviderEventRepository;
import com.exe101.proceeds.service.ProceedsService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Payments run against the real Order service, so the settlement decisions are those of production; only
// repositories and unrelated services are mocked. Notifications are signed here independently of the gateway, as
// VNPay signs them. Lenient: each test uses part of the shared persistence.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {
    private static long buyerId = 42L;
    private static long orderId = 300L;
    private static long paymentId = 400L;
    private static String tmnCode = "TESTTMN1";
    private static String hashSecret = "test-only-vnpay-hash-secret";
    private static String payUrl = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";
    private static String total = "151000.0000";
    private static String totalTimes100 = "15100000";
    private static DateTimeFormatter vnpayTime = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    @Mock
    private PaymentAttemptRepository attemptRepository;
    @Mock
    private ProviderEventRepository eventRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository itemRepository;
    @Mock
    private OrderItemAssetRepository assetRepository;
    @Mock
    private OrderShippingAddressRepository shippingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ItemFulfillmentRepository fulfillmentRepository;
    @Mock
    private ItemRefundRepository refundRepository;
    @Mock
    private CartService cartService;
    @Mock
    private ListingService listingService;
    @Mock
    private AddressService addressService;
    @Mock
    private CustomerService customerService;
    @Mock
    private EntitlementService entitlementService;
    @Mock
    private FulfillmentService fulfillmentService;
    @Mock
    private ProceedsService proceedsService;
    @Mock
    private LedgerService ledgerService;
    private PaymentServiceImpl service;
    private MarketplaceOrder order;
    private Payment payment;
    private List<PaymentAttempt> savedAttempts;
    private List<ProviderEvent> savedEvents;
    private Map<String, PaymentAttempt> attempts;

    @BeforeEach
    void setUp() {
        var orderService = new OrderServiceImpl(orderRepository, itemRepository, assetRepository, shippingRepository,
            paymentRepository, fulfillmentRepository, refundRepository, new OrderEntityMapperImpl(), cartService,
            new CartMapperImpl(),
            listingService, addressService, customerService,
            new OrderProperties(Duration.ofMinutes(30), Duration.ofMinutes(1)));
        var gateway = new VnpayGateway(new VnpayProperties(tmnCode, hashSecret, URI.create(payUrl),
            URI.create("http://localhost:3000/payment/return")));
        service = new PaymentServiceImpl(attemptRepository, eventRepository, new PaymentEntityMapperImpl(),
            orderService, gateway, entitlementService, fulfillmentService, proceedsService, ledgerService);
        order = order("PENDING_PAYMENT", now().plusMinutes(20));
        payment = payment("PENDING");
        savedAttempts = new ArrayList<>();
        savedEvents = new ArrayList<>();
        attempts = new HashMap<>();
        givenPersistence();
    }

    @Test
    void start_attempt_for_pending_order_ok() {
        // given
        var clientIp = "203.0.113.7";

        // when
        var result = service.start(buyerId, orderId, clientIp);

        // then
        var attempt = savedAttempts.getFirst();
        var url = result.getPaymentUrl();
        var query = url.substring(payUrl.length() + 1, url.indexOf("&vnp_SecureHash="));
        var signature = url.substring(url.indexOf("&vnp_SecureHash=") + "&vnp_SecureHash=".length());
        var fields = fields(query);
        assertAll(
            () -> assertThat(attempt.getPaymentId()).isEqualTo(paymentId),
            () -> assertThat(attempt.getProviderName()).isEqualTo("VNPAY"),
            () -> assertThat(attempt.getStatus()).isEqualTo("INITIATED"),
            () -> assertThat(attempt.getProviderAttemptRef()).matches("[0-9a-f]{32}"),
            () -> assertThat(attempt.getProviderTransactionRef()).isNull(),
            () -> assertThat(result.getAttemptId()).isEqualTo(attempt.getAttemptId()),
            () -> assertThat(result.getExpiresAt()).isEqualTo(order.getExpiresAt()),
            () -> assertThat(url).startsWith(payUrl + "?"),
            () -> assertThat(fields).containsEntry("vnp_Amount", totalTimes100)
                .containsEntry("vnp_CurrCode", "VND")
                .containsEntry("vnp_TmnCode", tmnCode)
                .containsEntry("vnp_TxnRef", attempt.getProviderAttemptRef())
                .containsEntry("vnp_IpAddr", clientIp)
                .containsEntry("vnp_ExpireDate", order.getExpiresAt().withOffsetSameInstant(ZoneOffset.ofHours(7))
                    .format(vnpayTime)),
            () -> assertThat(signature).isEqualTo(hmac(query)),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING")
        );
    }

    @Test
    void start_attempt_after_failed_attempt_ok() {
        // given
        // The earlier attempt failed: the Order and Payment are still pending, which is all a new attempt needs.
        var failedRef = "a".repeat(32);
        givenAttempt(failedRef, "INITIATED");
        service.handleVnpayNotification(signed(notification(failedRef, totalTimes100, "24")));

        // when
        var result = service.start(buyerId, orderId, "203.0.113.7");

        // then
        assertAll(
            () -> assertThat(savedAttempts).hasSize(1),
            () -> assertThat(attempt(failedRef).getStatus()).isEqualTo("FAILED"),
            () -> assertThat(savedAttempts.getFirst().getStatus()).isEqualTo("INITIATED"),
            () -> assertThat(savedAttempts.getFirst().getProviderAttemptRef()).isNotEqualTo(failedRef),
            () -> assertThat(result.getPaymentUrl()).contains("vnp_TxnRef=" + savedAttempts.getFirst()
                .getProviderAttemptRef()),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT")
        );
    }

    @Test
    void start_attempt_for_expired_order_ko() {
        // given
        // The window has closed but the expiry job has not run yet: ExpiresAt decides.
        order.setExpiresAt(now().minusMinutes(1));

        // when / then
        assertThatThrownBy(() -> service.start(buyerId, orderId, "203.0.113.7"))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.ORDER_NOT_PAYABLE));
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void start_attempt_for_paid_order_ko() {
        // given
        order.setStatus("PAID");
        order.setPaidAt(now().minusMinutes(5));
        payment.setStatus("SUCCEEDED");
        payment.setSucceededAt(now().minusMinutes(5));

        // when / then
        assertThatThrownBy(() -> service.start(buyerId, orderId, "203.0.113.7"))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.ORDER_NOT_PAYABLE));
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void start_attempt_for_other_customers_order_ko() {
        // given
        var otherCustomerId = 77L;

        // when / then
        assertThatThrownBy(() -> service.start(otherCustomerId, orderId, "203.0.113.7"))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.ORDER_NOT_FOUND));
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void start_attempt_for_missing_order_ko() {
        // given
        var missingOrderId = 999L;

        // when / then
        assertThatThrownBy(() -> service.start(buyerId, missingOrderId, "203.0.113.7"))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.ORDER_NOT_FOUND));
        verify(attemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void handle_success_callback_ok() {
        // given
        var ref = "b".repeat(32);
        givenAttempt(ref, "INITIATED");

        // when
        var result = service.handleVnpayNotification(signed(notification(ref, totalTimes100, "00")));

        // then
        var attempt = attempt(ref);
        var event = savedEvents.getFirst();
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
            () -> assertThat(order.getStatus()).isEqualTo("PAID"),
            () -> assertThat(order.getPaidAt()).isNotNull(),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(payment.getSucceededAt()).isEqualTo(order.getPaidAt()),
            () -> assertThat(attempt.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(attempt.getProviderTransactionRef()).isEqualTo("14123456"),
            () -> assertThat(attempt.getConfirmedAt()).isEqualTo(order.getPaidAt()),
            () -> assertThat(event.getProviderName()).isEqualTo("VNPAY"),
            () -> assertThat(event.getExternalEventId()).isEqualTo(ref + ":14123456:00:00"),
            () -> assertThat(event.getAttemptId()).isEqualTo(attempt.getAttemptId()),
            () -> assertThat(event.getOutcome()).isEqualTo("PROCESSED"),
            () -> assertThat(event.getProcessedAt()).isNotNull()
        );
    }

    @Test
    void handle_duplicate_success_callback_ok() {
        // given
        var ref = "c".repeat(32);
        var earlier = new ProviderEvent("VNPAY", ref + ":14123456:00:00", 1L, now(), now(), "PROCESSED");
        when(eventRepository.findWithLockByProviderNameAndExternalEventId("VNPAY", ref + ":14123456:00:00"))
            .thenReturn(Optional.of(earlier));

        // when
        var result = service.handleVnpayNotification(signed(notification(ref, totalTimes100, "00")));

        // then
        assertAll(
            () -> assertThat(result.isDuplicate()).isTrue(),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.ALREADY_CONFIRMED),
            () -> assertThat(savedEvents).isEmpty(),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT")
        );
        verify(attemptRepository, never()).findWithLockByProviderNameAndProviderAttemptRef(anyString(), anyString());
        verify(attemptRepository, never()).saveAndFlush(any());
        verify(orderRepository, never()).findWithLockByOrderId(anyLong());
    }

    @Test
    void handle_failure_callback_ok() {
        // given
        // 24: the buyer cancelled on the VNPay page.
        var ref = "d".repeat(32);
        givenAttempt(ref, "INITIATED");

        // when
        var result = service.handleVnpayNotification(signed(notification(ref, totalTimes100, "24")));

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.PROCESSED),
            () -> assertThat(attempt(ref).getStatus()).isEqualTo("FAILED"),
            () -> assertThat(attempt(ref).getProviderTransactionRef()).isNull(),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(savedEvents.getFirst().getOutcome()).isEqualTo("PROCESSED")
        );
        verify(orderRepository, never()).findWithLockByOrderId(anyLong());
    }

    @Test
    void handle_success_after_failed_attempt_ok() {
        // given
        var failedRef = "e".repeat(32);
        var paidRef = "f".repeat(32);
        givenAttempt(failedRef, "INITIATED");
        givenAttempt(paidRef, "INITIATED");
        service.handleVnpayNotification(signed(notification(failedRef, totalTimes100, "24")));

        // when
        var result = service.handleVnpayNotification(signed(withTransaction(notification(paidRef, totalTimes100,
            "00"), "14123457")));

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.CONFIRMED),
            () -> assertThat(attempt(failedRef).getStatus()).isEqualTo("FAILED"),
            () -> assertThat(attempt(paidRef).getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(order.getStatus()).isEqualTo("PAID"),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED")
        );
    }

    @Test
    void handle_callback_with_invalid_signature_ko() {
        // given
        // Signed for the right amount, then altered to a different one.
        var ref = "1".repeat(32);
        givenAttempt(ref, "INITIATED");
        var parameters = signed(notification(ref, totalTimes100, "00"));
        parameters.put("vnp_Amount", "100");

        // when
        var result = service.handleVnpayNotification(parameters);

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.INVALID_SIGNATURE),
            () -> assertThat(result.getOutcome()).isNull(),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(attempt(ref).getStatus()).isEqualTo("INITIATED")
        );
        verifyNoInteractions(eventRepository);
        verify(attemptRepository, never()).findWithLockByProviderNameAndProviderAttemptRef(anyString(), anyString());
    }

    @Test
    void handle_success_with_wrong_amount_ko() {
        // given
        var ref = "2".repeat(32);
        givenAttempt(ref, "INITIATED");

        // when
        var result = service.handleVnpayNotification(signed(notification(ref, "15000000", "00")));

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.INVALID_AMOUNT),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(attempt(ref).getStatus()).isEqualTo("EXCEPTION"),
            () -> assertThat(savedEvents.getFirst().getOutcome()).isEqualTo("EXCEPTION"),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(order.getPaidAt()).isNull(),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING")
        );
        verify(orderRepository, never()).saveAndFlush(any());
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void handle_success_after_expiry_ko() {
        // given
        // The window has closed; the expiry job has not marked the Order yet.
        var ref = "3".repeat(32);
        givenAttempt(ref, "INITIATED");
        order.setExpiresAt(now().minusSeconds(5));

        // when
        var result = service.handleVnpayNotification(signed(notification(ref, totalTimes100, "00")));

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.ALREADY_CONFIRMED),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(order.getPaidAt()).isNull(),
            () -> assertThat(payment.getStatus()).isEqualTo("EXCEPTION"),
            () -> assertThat(payment.getSucceededAt()).isNull(),
            () -> assertThat(attempt(ref).getStatus()).isEqualTo("EXCEPTION"),
            () -> assertThat(attempt(ref).getProviderTransactionRef()).isEqualTo("14123456"),
            () -> assertThat(savedEvents.getFirst().getOutcome()).isEqualTo("EXCEPTION")
        );
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void exception_payment_creates_no_proceeds_ko() {
        // given
        // One late success and one for the wrong amount; neither holds proceeds (§12.12).
        var lateRef = "4".repeat(32);
        var wrongAmountRef = "5".repeat(32);
        givenAttempt(lateRef, "INITIATED");
        givenAttempt(wrongAmountRef, "INITIATED");
        service.handleVnpayNotification(signed(notification(wrongAmountRef, "15000000", "00")));
        order.setExpiresAt(now().minusSeconds(5));

        // when
        var result = service.handleVnpayNotification(signed(withTransaction(notification(lateRef, totalTimes100,
            "00"), "14123458")));

        // then
        assertAll(
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT")
        );
        verifyNoInteractions(proceedsService);
    }

    @Test
    void handle_success_for_unknown_attempt_ko() {
        // given
        var unknownRef = "4".repeat(32);

        // when
        var result = service.handleVnpayNotification(signed(notification(unknownRef, totalTimes100, "00")));

        // then
        var event = savedEvents.getFirst();
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.ORDER_NOT_FOUND),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(event.getOutcome()).isEqualTo("EXCEPTION"),
            () -> assertThat(event.getAttemptId()).isNull(),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT"),
            () -> assertThat(payment.getStatus()).isEqualTo("PENDING")
        );
        verify(orderRepository, never()).findWithLockByOrderId(anyLong());
    }

    @Test
    void handle_second_attempt_success_ko() {
        // given
        var firstRef = "5".repeat(32);
        var secondRef = "6".repeat(32);
        givenAttempt(firstRef, "INITIATED");
        givenAttempt(secondRef, "INITIATED");
        service.handleVnpayNotification(signed(notification(firstRef, totalTimes100, "00")));
        var paidAt = order.getPaidAt();

        // when
        var result = service.handleVnpayNotification(signed(withTransaction(notification(secondRef, totalTimes100,
            "00"), "14123499")));

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.ALREADY_CONFIRMED),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.EXCEPTION),
            () -> assertThat(attempt(firstRef).getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(attempt(secondRef).getStatus()).isEqualTo("EXCEPTION"),
            () -> assertThat(order.getStatus()).isEqualTo("PAID"),
            () -> assertThat(order.getPaidAt()).isEqualTo(paidAt),
            () -> assertThat(payment.getStatus()).isEqualTo("SUCCEEDED"),
            () -> assertThat(savedEvents).extracting(ProviderEvent::getOutcome)
                .containsExactly("PROCESSED", "EXCEPTION")
        );
        verify(orderRepository).saveAndFlush(any());
    }

    @Test
    void handle_failure_for_unknown_attempt_ok() {
        // given
        var unknownRef = "7".repeat(32);

        // when
        var result = service.handleVnpayNotification(signed(notification(unknownRef, totalTimes100, "24")));

        // then
        assertAll(
            () -> assertThat(result.getReply()).isEqualTo(VnpayReply.ORDER_NOT_FOUND),
            () -> assertThat(result.getOutcome()).isEqualTo(EventOutcome.IGNORED),
            () -> assertThat(savedEvents.getFirst().getOutcome()).isEqualTo("IGNORED"),
            () -> assertThat(order.getStatus()).isEqualTo("PENDING_PAYMENT")
        );
    }

    // The Order and its Payment are single in-memory rows that every read returns and every save updates.
    @Test
    void payment_success_grants_entitlements_ok() {
        // given
        var ref = "8".repeat(32);
        givenAttempt(ref, "INITIATED");
        var granted = ArgumentCaptor.forClass(Order.class);

        // when
        service.handleVnpayNotification(signed(notification(ref, totalTimes100, "00")));

        // then
        verify(entitlementService).grantDigital(granted.capture());
        assertAll(
            () -> assertThat(granted.getValue().getOrderId()).isEqualTo(orderId),
            () -> assertThat(granted.getValue().getStatus()).isEqualTo(OrderStatus.PAID)
        );
    }

    // Wrong amount, success after ExpiresAt, success for an already paid Order, and a failure.
    @ParameterizedTest
    @CsvSource({
        "15000000, 00, 20, PENDING_PAYMENT, PENDING",
        "15100000, 00, -1, PENDING_PAYMENT, PENDING",
        "15100000, 00, 20, PAID, SUCCEEDED",
        "15100000, 24, 20, PENDING_PAYMENT, PENDING"
    })
    void payment_exception_does_not_grant_ko(
        String amount, String responseCode, int expiresInMinutes, String orderStatus, String paymentStatus
    ) {
        // given
        var ref = "9".repeat(32);
        givenAttempt(ref, "INITIATED");
        order.setStatus(orderStatus);
        order.setExpiresAt(now().plusMinutes(expiresInMinutes));
        payment.setStatus(paymentStatus);

        // when
        var result = service.handleVnpayNotification(signed(notification(ref, amount, responseCode)));

        // then
        assertThat(result.getReply()).isNotEqualTo(VnpayReply.INVALID_SIGNATURE);
        verifyNoInteractions(entitlementService);
    }

    private void givenPersistence() {
        when(orderRepository.findByOrderIdAndCustomerId(orderId, buyerId)).thenReturn(Optional.of(order));
        when(orderRepository.findWithLockByOrderId(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(itemRepository.findByOrderIdInOrderByOrderItemIdAsc(anyCollection())).thenReturn(List.of());
        when(shippingRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of());
        when(paymentRepository.findByOrderIdIn(anyCollection())).thenReturn(List.of(payment));
        when(paymentRepository.findOrderIdByPaymentId(paymentId)).thenReturn(Optional.of(orderId));
        when(paymentRepository.findWithLockByPaymentId(paymentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attemptRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PaymentAttempt saved = invocation.getArgument(0);

            if (saved.getAttemptId() == null) {
                saved.setAttemptId(500L + savedAttempts.size());
                savedAttempts.add(saved);
            }

            return saved;
        });
        when(eventRepository.findWithLockByProviderNameAndExternalEventId(anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(eventRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            savedEvents.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
    }

    private void givenAttempt(String ref, String status) {
        var attempt = new PaymentAttempt(paymentId, "VNPAY", ref, null, status, now().minusMinutes(1), null);
        attempt.setAttemptId(600L + ref.charAt(0));
        attempts.put(ref, attempt);
        when(attemptRepository.findWithLockByProviderNameAndProviderAttemptRef("VNPAY", ref))
            .thenReturn(Optional.of(attempt));
    }

    private PaymentAttempt attempt(String ref) {
        return attempts.get(ref);
    }

    private Map<String, String> notification(String ref, String amount, String responseCode) {
        var fields = new HashMap<String, String>();
        fields.put("vnp_TmnCode", tmnCode);
        fields.put("vnp_Amount", amount);
        fields.put("vnp_BankCode", "NCB");
        fields.put("vnp_OrderInfo", "Thanh toan don hang " + orderId);
        fields.put("vnp_PayDate", "20261101103000");
        fields.put("vnp_ResponseCode", responseCode);
        fields.put("vnp_TransactionNo", "00".equals(responseCode) ? "14123456" : "0");
        fields.put("vnp_TransactionStatus", "00".equals(responseCode) ? "00" : "02");
        fields.put("vnp_TxnRef", ref);

        return fields;
    }

    private Map<String, String> withTransaction(Map<String, String> fields, String transactionNo) {
        fields.put("vnp_TransactionNo", transactionNo);

        return fields;
    }

    // As VNPay signs: the vnp_ fields sorted by name, values URL-encoded, HMAC-SHA512 in hex.
    private Map<String, String> signed(Map<String, String> fields) {
        var query = new TreeMap<>(fields).entrySet().stream()
            .map(entry -> entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
            .collect(Collectors.joining("&"));
        var parameters = new HashMap<>(fields);
        parameters.put("vnp_SecureHashType", "HmacSHA512");
        parameters.put("vnp_SecureHash", hmac(query).toUpperCase());

        return parameters;
    }

    private String hmac(String data) {
        try {
            var mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));

            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, String> fields(String query) {
        return Arrays.stream(query.split("&"))
            .map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(pair -> pair[0],
                pair -> URLDecoder.decode(pair[1], StandardCharsets.US_ASCII)));
    }

    private MarketplaceOrder order(String status, OffsetDateTime expiresAt) {
        var entity = new MarketplaceOrder();
        entity.setOrderId(orderId);
        entity.setCustomerId(buyerId);
        entity.setStatus(status);
        entity.setCreatedAt(now().minusMinutes(10));
        entity.setExpiresAt(expiresAt);
        entity.setCurrencyCode("VND");
        entity.setItemsSubtotal(new BigDecimal("110000.0000"));
        entity.setShippingAmount(new BigDecimal("30000.0000"));
        entity.setTaxAmount(new BigDecimal("11000.0000"));
        entity.setTotalAmount(new BigDecimal(total));

        return entity;
    }

    private Payment payment(String status) {
        var entity = new Payment(orderId, null, new BigDecimal(total), "VND", status, null, now().minusMinutes(10),
            null);
        entity.setPaymentId(paymentId);

        return entity;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
