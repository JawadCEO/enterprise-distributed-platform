package com.edp.iot.state.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    private final FailureClassifier classifier = new FailureClassifier();

    @Test
    void databaseUnreachableBlocksInPlace() {
        assertThat(classifier.classify(new CannotGetJdbcConnectionException("down")))
                .isEqualTo(FailureClassifier.Kind.DEPENDENCY_DOWN);
    }

    @Test
    void timeoutsGoToRetryTiers() {
        assertThat(classifier.classify(new QueryTimeoutException("slow"))).isEqualTo(FailureClassifier.Kind.TRANSIENT);
    }

    @Test
    void badDataGoesToDeadLetters() {
        assertThat(classifier.classify(new DataIntegrityViolationException("bad"))).isEqualTo(FailureClassifier.Kind.PERMANENT);
        assertThat(classifier.classify(new IllegalArgumentException("bug"))).isEqualTo(FailureClassifier.Kind.PERMANENT);
    }
}
