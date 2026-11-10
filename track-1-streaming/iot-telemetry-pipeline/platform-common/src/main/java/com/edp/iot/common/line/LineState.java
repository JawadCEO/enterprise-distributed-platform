package com.edp.iot.common.line;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Line state (FR-2, data.md section 5). Changes only along the allowed transitions; anything else
 * is rejected and logged by the caller.
 */
public enum LineState {
    INVENTORY,
    PENDING,
    ACTIVE,
    SUSPENDED,
    DEACTIVATED;

    private static final Map<LineState, Set<LineState>> ALLOWED = Map.of(
            INVENTORY, EnumSet.of(PENDING, ACTIVE),
            PENDING, EnumSet.of(INVENTORY, ACTIVE, SUSPENDED, DEACTIVATED),
            ACTIVE, EnumSet.of(PENDING, SUSPENDED),
            SUSPENDED, EnumSet.of(PENDING),
            DEACTIVATED, EnumSet.noneOf(LineState.class));

    /**
     * {@code INVENTORY -> ACTIVE} is allowed only for an operator importing SIMs that are already
     * live on the network; customers always go through a request ({@code PENDING}). {@code
     * ACTIVE -> SUSPENDED} without a request is the automatic suspension on a SIM move (FR-18).
     */
    public boolean canMoveTo(LineState next) {
        return ALLOWED.get(this).contains(next);
    }
}
