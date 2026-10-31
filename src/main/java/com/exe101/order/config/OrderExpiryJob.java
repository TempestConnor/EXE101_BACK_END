package com.exe101.order.config;

import com.exe101.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Whether an Order can still be paid depends on ExpiresAt, not on this job, so a late run extends nothing.
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryJob {
    private final OrderService orderService;

    @Scheduled(initialDelayString = "${app.orders.expiry-interval}", fixedDelayString = "${app.orders.expiry-interval}")
    public void expire() {
        int expired = orderService.expireDue();

        if (expired > 0) {
            log.info("[ORDER] - ACTION: expiryJob: expired: {}", expired);
        }
    }
}
