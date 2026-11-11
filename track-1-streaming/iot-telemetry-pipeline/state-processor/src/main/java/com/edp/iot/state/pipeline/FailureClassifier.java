package com.edp.iot.state.pipeline;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.NonTransientDataAccessException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientException;

/**
 * Sorts failures into the three paths of the failure table (HLD 3.3):
 * <ul>
 *   <li><b>dependency down</b>: the database cannot be reached at all. Nothing can succeed, so the
 *       batch is retried in place and events wait in Kafka.</li>
 *   <li><b>transient</b>: this event failed for a reason that may pass (timeout, deadlock). It goes to
 *       the retry tiers so the events behind it keep flowing.</li>
 *   <li><b>permanent</b>: retrying will not help. It goes to the dead-letter topic.</li>
 * </ul>
 */
public class FailureClassifier {

    public enum Kind { DEPENDENCY_DOWN, TRANSIENT, PERMANENT }

    public Kind classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException
                    || t instanceof CannotCreateTransactionException
                    || t instanceof DataAccessResourceFailureException) {
                return Kind.DEPENDENCY_DOWN;
            }
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException
                    || t instanceof RecoverableDataAccessException
                    || t instanceof SQLTransientException) {
                return Kind.TRANSIENT;
            }
            if (t instanceof NonTransientDataAccessException) {
                return Kind.PERMANENT;
            }
        }
        return Kind.PERMANENT;
    }
}
