package com.exe101.proceeds.service;

import com.exe101.commission.model.QuoteSettlement;
import com.exe101.order.model.Order;
import com.exe101.proceeds.model.Proceeds;
import com.exe101.proceeds.model.Release;
import com.exe101.proceeds.model.ReleaseOutcome;
import com.exe101.proceeds.model.ReleaseStatus;
import java.util.List;
import java.util.Optional;

public interface ProceedsService {
    List<Proceeds> recordPaid(Order order);

    Optional<Proceeds> holdQuote(QuoteSettlement settlement);

    Optional<Proceeds> releasePhysical(Long orderItemId);

    Optional<Proceeds> findLockedByOrderItem(Long orderItemId);

    Proceeds voidHeld(Long orderItemId);

    Release release(Long staffId, Long proceedsId);

    Release recordOutcome(Long staffId, Long payoutId, ReleaseOutcome outcome);

    List<Release> list(ReleaseStatus status);

    List<Proceeds> listOwn(Long userId);

    List<Release> listOwnReleases(Long userId);
}
