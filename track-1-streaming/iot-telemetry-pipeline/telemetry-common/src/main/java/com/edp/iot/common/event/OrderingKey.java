package com.edp.iot.common.event;

/**
 * Per-device ordering key (ADR-005). A higher boot counter always wins; within one boot a higher
 * sequence wins. This keeps a device that reset its sequence on reboot from looking stale.
 */
public record OrderingKey(long bootId, long sequence) implements Comparable<OrderingKey> {

    @Override
    public int compareTo(OrderingKey other) {
        int byBoot = Long.compare(bootId, other.bootId);
        return byBoot != 0 ? byBoot : Long.compare(sequence, other.sequence);
    }

    public boolean isNewerThan(OrderingKey other) {
        return other == null || compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return bootId + ":" + sequence;
    }
}
