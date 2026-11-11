package com.edp.iot.common.line;

/** How the SIM is built into the device (how-it-works.md section 4c). */
public enum SimType {
    /** Plastic card in a slot; can be moved to another device by hand. */
    REMOVABLE,
    /** Soldered eSIM (MFF2/eUICC); moving it means desoldering, so a move is a security signal. */
    EMBEDDED,
    /** iSIM inside the device's own chip; it cannot be moved at all. */
    INTEGRATED;

    public boolean canBeMovedByHand() {
        return this == REMOVABLE;
    }
}
