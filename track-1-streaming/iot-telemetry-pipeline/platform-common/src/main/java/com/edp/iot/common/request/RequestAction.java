package com.edp.iot.common.request;

import com.edp.iot.common.line.LineState;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The line requests of FR-4 and the line states each one is allowed from (data.md section 2.2). */
public enum RequestAction {
    ACTIVATE(EnumSet.of(LineState.INVENTORY), LineState.ACTIVE),
    SUSPEND(EnumSet.of(LineState.ACTIVE), LineState.SUSPENDED),
    RESTORE(EnumSet.of(LineState.SUSPENDED), LineState.ACTIVE),
    DEACTIVATE(EnumSet.of(LineState.ACTIVE, LineState.SUSPENDED), LineState.DEACTIVATED);

    private final Set<LineState> allowedFrom;
    private final LineState target;

    RequestAction(Set<LineState> allowedFrom, LineState target) {
        this.allowedFrom = allowedFrom;
        this.target = target;
    }

    public boolean allowedFrom(LineState state) {
        return allowedFrom.contains(state);
    }

    /** The line state when the network confirms the request. */
    public LineState target() {
        return target;
    }

    /** The action named in a URL such as {@code /v1/lines/{iccid}/restore}. */
    public static Optional<RequestAction> fromPath(String path) {
        try {
            return Optional.of(valueOf(path.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
