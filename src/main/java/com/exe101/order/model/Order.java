package com.exe101.order.model;

import com.exe101.address.model.ShippingAddress;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder(setterPrefix = "with")
public class Order {
    private Long orderId;
    private Long customerId;
    private OrderStatus status;
    private OffsetDateTime createdAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime paidAt;
    private String currencyCode;
    private BigDecimal itemsSubtotal;
    private BigDecimal shippingAmount;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private String billingName;
    private String billingDetails;
    private String customerName;
    private String customerEmail;
    private List<OrderLine> items;
    private ShippingAddress shippingAddress;
    private PaymentTarget payment;
}
