package com.edp.iot.common.state;

import com.edp.iot.common.event.OrderingKey;
import com.edp.iot.common.line.Connection;

import java.time.Instant;
import java.util.Map;

/**
 * The stored current device state of one line, as held in {@code device_state} (FR-16). There is
 * one row per line; it is created by the first event for the line.
 *
 * @param deviceKey        last applied {@code (bootId, sequence)}, for the device in {@code imei}
 * @param lastNetworkTime  network time of the last applied network event
 * @param connectionSince  when {@code connection} last changed, on the connection clock (receive
 *                         time for device events, network time for network events)
 * @param metrics          latest battery, signal, temperature, firmware and fault code
 */
public record DeviceState(
        String iccid,
        String customerId,
        Connection connection,
        String connectionReason,
        Instant connectionSince,
        Instant lastSeen,
        String imei,
        String region,
        OrderingKey deviceKey,
        Instant lastNetworkTime,
        Map<String, Object> metrics) {

    public DeviceState {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }
}
