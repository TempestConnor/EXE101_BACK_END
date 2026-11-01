package com.exe101.fulfillment.config;

import com.exe101.fulfillment.service.FulfillmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Each item is granted in its own transaction, so one failure neither undoes nor blocks the others; a failed item
// stays a candidate for the next run.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnWindowJob {
    private final FulfillmentService fulfillmentService;

    @Scheduled(
        initialDelayString = "${app.fulfillment.grant-interval}",
        fixedDelayString = "${app.fulfillment.grant-interval}")
    public void grantClosedWindows() {
        List<Long> candidates = fulfillmentService.listWindowClosed();
        int granted = 0;

        for (Long orderItemId : candidates) {
            try {
                if (fulfillmentService.grantIfWindowClosed(orderItemId)) {
                    granted++;
                }
            } catch (RuntimeException exception) {
                log.error("[FULFILLMENT] - ACTION: returnWindowJob: orderItemId: {}: failure: {}", orderItemId,
                    exception.getClass().getSimpleName());
            }
        }

        if (granted > 0) {
            log.info("[FULFILLMENT] - ACTION: returnWindowJob: granted: {}", granted);
        }
    }
}
