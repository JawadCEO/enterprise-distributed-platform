-- Creates the partitions needed for the retention window plus a few hours ahead, and drops
-- partitions that fell out of retention. Safe to call from several instances at once: only the
-- caller that gets the advisory lock does the work.
--
-- telemetry:     hourly partitions named telemetry_YYYYMMDDHH (UTC)
-- status_change: monthly partitions named status_change_YYYYMM (UTC)

CREATE OR REPLACE FUNCTION iot_maintain_partitions(
    p_telemetry_hours integer DEFAULT 48,
    p_status_days     integer DEFAULT 90,
    p_ahead_hours     integer DEFAULT 6)
RETURNS void
LANGUAGE plpgsql
SET timezone = 'UTC'
AS $$
DECLARE
    v_start  timestamptz;
    v_part   record;
    v_bound  timestamptz;
BEGIN
    IF NOT pg_try_advisory_xact_lock(hashtext('iot_maintain_partitions')) THEN
        RETURN;
    END IF;

    -- telemetry: one partition per hour, from the start of retention to p_ahead_hours ahead
    FOR v_start IN
        SELECT generate_series(date_trunc('hour', now()) - make_interval(hours => p_telemetry_hours),
                               date_trunc('hour', now()) + make_interval(hours => p_ahead_hours),
                               interval '1 hour')
    LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF telemetry FOR VALUES FROM (%L) TO (%L)',
                       'telemetry_' || to_char(v_start, 'YYYYMMDDHH24'), v_start, v_start + interval '1 hour');
    END LOOP;

    FOR v_part IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 'telemetry'
    LOOP
        v_bound := to_timestamp(substr(v_part.relname, length('telemetry_') + 1), 'YYYYMMDDHH24') + interval '1 hour';
        IF v_bound <= now() - make_interval(hours => p_telemetry_hours) THEN
            EXECUTE format('DROP TABLE IF EXISTS %I', v_part.relname);
        END IF;
    END LOOP;

    -- status_change: one partition per month, from the start of retention to next month
    FOR v_start IN
        SELECT generate_series(date_trunc('month', now() - make_interval(days => p_status_days)),
                               date_trunc('month', now()) + interval '1 month',
                               interval '1 month')
    LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF status_change FOR VALUES FROM (%L) TO (%L)',
                       'status_change_' || to_char(v_start, 'YYYYMM'), v_start, v_start + interval '1 month');
    END LOOP;

    FOR v_part IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        JOIN pg_class p ON p.oid = i.inhparent
        WHERE p.relname = 'status_change'
    LOOP
        v_bound := to_timestamp(substr(v_part.relname, length('status_change_') + 1), 'YYYYMM') + interval '1 month';
        IF v_bound <= now() - make_interval(days => p_status_days) THEN
            EXECUTE format('DROP TABLE IF EXISTS %I', v_part.relname);
        END IF;
    END LOOP;
END;
$$;

SELECT iot_maintain_partitions();
