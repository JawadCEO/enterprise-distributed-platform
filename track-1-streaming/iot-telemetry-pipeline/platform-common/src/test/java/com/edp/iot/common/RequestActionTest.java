package com.edp.iot.common;

import com.edp.iot.common.line.LineState;
import com.edp.iot.common.request.RequestAction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RequestActionTest {

    @Test
    void eachActionIsAllowedOnlyFromItsStates() {
        assertThat(RequestAction.ACTIVATE.allowedFrom(LineState.INVENTORY)).isTrue();
        assertThat(RequestAction.ACTIVATE.allowedFrom(LineState.ACTIVE)).isFalse();
        assertThat(RequestAction.SUSPEND.allowedFrom(LineState.ACTIVE)).isTrue();
        assertThat(RequestAction.SUSPEND.allowedFrom(LineState.SUSPENDED)).isFalse();
        assertThat(RequestAction.RESTORE.allowedFrom(LineState.SUSPENDED)).isTrue();
        assertThat(RequestAction.RESTORE.allowedFrom(LineState.ACTIVE)).isFalse();
        assertThat(RequestAction.DEACTIVATE.allowedFrom(LineState.ACTIVE)).isTrue();
        assertThat(RequestAction.DEACTIVATE.allowedFrom(LineState.SUSPENDED)).isTrue();
        assertThat(RequestAction.DEACTIVATE.allowedFrom(LineState.INVENTORY)).isFalse();
        assertThat(RequestAction.DEACTIVATE.allowedFrom(LineState.PENDING)).isFalse();
    }

    @Test
    void targetsAndPathNames() {
        assertThat(RequestAction.RESTORE.target(LineState.SUSPENDED)).isEqualTo(LineState.ACTIVE);
        assertThat(RequestAction.DEACTIVATE.target(LineState.ACTIVE)).isEqualTo(LineState.DEACTIVATED);
        assertThat(RequestAction.fromPath("restore")).contains(RequestAction.RESTORE);
        assertThat(RequestAction.fromPath("reboot")).isEmpty();
        assertThat(RequestAction.fromPath("profile")).contains(RequestAction.SWITCH_PROFILE);
    }

    @Test
    void aProfileSwitchKeepsTheLineStateAndNeedsAProgrammableSim() {
        assertThat(RequestAction.SWITCH_PROFILE.allowedFrom(LineState.ACTIVE)).isTrue();
        assertThat(RequestAction.SWITCH_PROFILE.allowedFrom(LineState.INVENTORY)).isTrue();
        assertThat(RequestAction.SWITCH_PROFILE.allowedFrom(LineState.SUSPENDED)).isFalse();
        assertThat(RequestAction.SWITCH_PROFILE.target(LineState.INVENTORY)).isEqualTo(LineState.INVENTORY);
        assertThat(RequestAction.SWITCH_PROFILE.target(LineState.ACTIVE)).isEqualTo(LineState.ACTIVE);
        assertThat(RequestAction.SWITCH_PROFILE.needsProgrammableSim()).isTrue();
        assertThat(RequestAction.SUSPEND.needsProgrammableSim()).isFalse();
    }
}
