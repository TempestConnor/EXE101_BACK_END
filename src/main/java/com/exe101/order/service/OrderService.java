package com.exe101.order.service;

import com.exe101.order.model.Order;
import com.exe101.order.model.OrderCommit;
import com.exe101.order.model.PaymentSettlement;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public interface OrderService {
    Order create(Long userId, OrderCommit commit);

    Order get(Long userId, Long orderId);

    List<Order> listOwn(Long userId);

    int expireDue();

    Order getPayable(Long userId, Long orderId);

    PaymentSettlement settlePayment(Long paymentId, BigDecimal amount, OffsetDateTime receivedAt);
}
