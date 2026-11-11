-- Creates the line_history partitions needed for the retention window plus a few days ahead, and
-- drops partitions that fell out of retention. Safe to call from several instances at once: only
-- the caller that gets the advisory lock does the work. Partitions are named line_history_YYYYMMDD
-- (UTC). Also deletes usage records and security events past their retention.

CREATE OR REPLACE FUNCTION iot_maintain_partitions(
    p_history_days integer DEFAULT 90,
    p_ahead_days   integer DEFAULT 3)
RETURNS void
LANGUAGE plpgsql
SET timezone = 'UTC'
AS $$
DECLARE
    v_start timestamptz;
    v_part  record;
    v_bound timestamptz;
BEGIN
    IF NOT pg_try_advisory_xact_lock(hashtext('iot_maintain_partitions')) THEN
        RETURN;
    END IF;

    FOR v_start IN
        SELECT generate_series(date_trunc('day', now()) - make_interval(days => p_history_days),
                               date_trunc('day', now()) + make_interval(days => p_ahead_days),
                               interval '1 day')
    LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF line_history FOR VALUES FROM (%L) TO (%L)',
                       'line_history_' || to_char(v_start, 'YYYYMMDD'), v_start, v_start + interval '1 day');
    END LOOP;

    FOR v_part IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 'line_history'
    LOOP
        v_bound := to_timestamp(substr(v_part.relname, length('line_history_') + 1), 'YYYYMMDD') + interval '1 day';
        IF v_bound <= now() - make_interval(days => p_history_days) THEN
            EXECUTE format('DROP TABLE IF EXISTS %I', v_part.relname);
        END IF;
    END LOOP;

    DELETE FROM usage_record WHERE network_time < now() - interval '13 months';
    DELETE FROM security_event WHERE at < now() - interval '1 year';
END;
$$;

SELECT iot_maintain_partitions();
