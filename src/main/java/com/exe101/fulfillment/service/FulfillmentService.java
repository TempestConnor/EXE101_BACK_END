package com.exe101.fulfillment.service;

import com.exe101.fulfillment.model.Fulfillment;
import com.exe101.fulfillment.model.FulfillmentChange;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.order.model.Order;
import java.util.List;

public interface FulfillmentService {
    List<Fulfillment> start(Order order);

    List<Fulfillment> list(FulfillmentStatus status);

    Fulfillment advance(Long staffId, Long orderItemId, FulfillmentChange change);

    List<Long> listWindowClosed();

    boolean grantIfWindowClosed(Long orderItemId);
}
