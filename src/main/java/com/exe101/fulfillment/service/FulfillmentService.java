package com.exe101.fulfillment.service;

import com.exe101.fulfillment.model.Fulfillment;
import com.exe101.fulfillment.model.FulfillmentChange;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.order.model.Order;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface FulfillmentService {
    List<Fulfillment> start(Order order);

    List<Fulfillment> list(FulfillmentStatus status);

    Fulfillment advance(Long staffId, Long orderItemId, FulfillmentChange change);

    List<Long> listWindowClosed();

    boolean grantIfWindowClosed(Long orderItemId);

    Optional<Fulfillment> findLocked(Long orderItemId);

    Fulfillment cancel(Long staffId, Long orderItemId, String reason);

    OffsetDateTime returnWindowClosesAt(OffsetDateTime deliveredAt);
}
