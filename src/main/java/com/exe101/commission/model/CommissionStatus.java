package com.exe101.commission.model;

import java.util.EnumSet;
import java.util.Set;

public enum CommissionStatus {
    REQUESTED,
    QUOTED,
    AWAITING_PAYMENT,
    IN_PROGRESS,
    SUBMITTED_FOR_REVIEW,
    REVISION_REQUESTED,
    COMPLETED,
    DECLINED,
    CANCELLED,
    DISPUTED;

    // Closed Commissions keep their thread and files readable but accept nothing new.
    private static Set<CommissionStatus> closed = EnumSet.of(COMPLETED, DECLINED, CANCELLED);

    public boolean isClosed() {
        return closed.contains(this);
    }
}
