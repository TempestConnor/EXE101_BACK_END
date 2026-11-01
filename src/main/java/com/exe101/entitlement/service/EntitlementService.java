package com.exe101.entitlement.service;

import com.exe101.entitlement.model.Entitlement;
import com.exe101.entitlement.model.Purchase;
import com.exe101.order.model.Order;
import java.util.List;
import java.util.Optional;

public interface EntitlementService {
    List<Entitlement> grantDigital(Order order);

    Optional<Entitlement> grantIncluded(Order order, Long orderItemId);

    boolean isGranted(Long orderItemId);

    List<Purchase> listOwn(Long userId);
}
