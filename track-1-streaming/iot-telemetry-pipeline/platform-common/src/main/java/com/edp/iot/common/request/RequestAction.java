package com.edp.iot.common.request;

import com.edp.iot.common.line.LineState;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The line requests of FR-4 and FR-10 and the line states each one is allowed from (data.md section 2.2). */
public enum RequestAction {
    ACTIVATE(EnumSet.of(LineState.INVENTORY), LineState.ACTIVE),
    SUSPEND(EnumSet.of(LineState.ACTIVE), LineState.SUSPENDED),
    RESTORE(EnumSet.of(LineState.SUSPENDED), LineState.ACTIVE),
    DEACTIVATE(EnumSet.of(LineState.ACTIVE, LineState.SUSPENDED), LineState.DEACTIVATED),
    /**
     * Remote SIM profile download and switch (FR-10, Flow E), embedded and integrated SIMs only. The
     * line keeps its state; on success it carries the new profile.
     */
    SWITCH_PROFILE(EnumSet.of(LineState.ACTIVE, LineState.INVENTORY), null);

    private final Set<LineState> allowedFrom;
    private final LineState target;

    RequestAction(Set<LineState> allowedFrom, LineState target) {
        this.allowedFrom = allowedFrom;
        this.target = target;
    }

    public boolean allowedFrom(LineState state) {
        return allowedFrom.contains(state);
    }

    /** The line state when the network confirms the request, which came from {@code previous}. */
    public LineState target(LineState previous) {
        return target == null ? previous : target;
    }

    /** True for an action that only an embedded or integrated SIM supports. */
    public boolean needsProgrammableSim() {
        return this == SWITCH_PROFILE;
    }

    /** The action named in a URL such as {@code /v1/lines/{iccid}/restore}, or {@code /profile}. */
    public static Optional<RequestAction> fromPath(String path) {
        if ("profile".equals(path)) {
            return Optional.of(SWITCH_PROFILE);
        }
        try {
            return Optional.of(valueOf(path.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
