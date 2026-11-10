package com.edp.iot.common.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderingKeyTest {

    @Test
    void higherBootWinsEvenWithLowerSequence() {
        assertThat(new OrderingKey(5, 0).isNewerThan(new OrderingKey(4, 1_000_000))).isTrue();
    }

    @Test
    void withinOneBootHigherSequenceWins() {
        assertThat(new OrderingKey(5, 11).isNewerThan(new OrderingKey(5, 10))).isTrue();
        assertThat(new OrderingKey(5, 10).isNewerThan(new OrderingKey(5, 10))).isFalse();
    }

    @Test
    void anythingIsNewerThanNothing() {
        assertThat(new OrderingKey(0, 0).isNewerThan(null)).isTrue();
    }
}
