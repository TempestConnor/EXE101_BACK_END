package com.exe101.listing.config;

import com.exe101.listing.service.ListingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

// Costs change only through configuration, so they are compared once per start, before the web server accepts
// requests. Requests read the stored CostReviewRequired flag instead.
@Slf4j
@Component
@RequiredArgsConstructor
public class CostReviewStartup implements SmartInitializingSingleton {
    private final ListingService listingService;

    @Override
    public void afterSingletonsInstantiated() {
        int flagged = listingService.flagCostChanges();
        log.info("[LISTING] - ACTION: costReviewStartup: flagged: {}", flagged);
    }
}
