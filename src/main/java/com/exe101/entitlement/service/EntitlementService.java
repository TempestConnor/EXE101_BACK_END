package com.exe101.entitlement.service;

import com.exe101.entitlement.model.Entitlement;
import com.exe101.entitlement.model.Purchase;
import com.exe101.order.model.Order;
import java.util.List;

public interface EntitlementService {
    List<Entitlement> grantDigital(Order order);

    List<Purchase> listOwn(Long userId);
}
