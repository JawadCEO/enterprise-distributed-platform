-- Device registry, latest status, status history, telemetry and dead letters.
-- Telemetry and status_change are range-partitioned by time; partitions are managed by
-- iot_maintain_partitions() in V2.

CREATE TABLE tenant (
    tenant_id          text        PRIMARY KEY,
    name               text        NOT NULL,
    rate_limit_per_sec integer     NOT NULL DEFAULT 1000 CHECK (rate_limit_per_sec > 0),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE device_type (
    device_type          text    PRIMARY KEY,
    heartbeat_seconds    integer NOT NULL CHECK (heartbeat_seconds > 0),
    battery_degraded_pct integer NOT NULL DEFAULT 15,
    signal_degraded_dbm  integer NOT NULL DEFAULT -110
);

INSERT INTO device_type (device_type, heartbeat_seconds) VALUES
    ('TRACKER', 60),
    ('METER', 900),
    ('SENSOR', 300);

CREATE TABLE device (
    device_id         text        PRIMARY KEY,
    tenant_id         text        NOT NULL REFERENCES tenant (tenant_id),
    device_type       text        NOT NULL REFERENCES device_type (device_type),
    firmware          text,
    heartbeat_seconds integer     CHECK (heartbeat_seconds > 0),   -- NULL means the type default
    home_region       text        NOT NULL DEFAULT 'local',
    credential_hash   text        NOT NULL,                        -- SHA-256 hex of the device secret
    active            boolean     NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX device_tenant_idx ON device (tenant_id, device_id);

-- One row per device. Updated only by an event with a higher (last_boot_id, last_sequence).
CREATE TABLE device_status (
    device_id     text        PRIMARY KEY REFERENCES device (device_id),
    tenant_id     text        NOT NULL,
    status        text        NOT NULL CHECK (status IN ('ONLINE', 'DEGRADED', 'FAULT', 'OFFLINE')),
    last_boot_id  bigint      NOT NULL,
    last_sequence bigint      NOT NULL,
    last_seen     timestamptz NOT NULL,
    status_since  timestamptz NOT NULL,
    metrics       jsonb       NOT NULL DEFAULT '{}',
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- "Devices by status" for one tenant, paged by device_id.
CREATE INDEX device_status_tenant_status_idx ON device_status (tenant_id, status, device_id);

-- Raw telemetry, kept 48 hours in hourly partitions. The unique key includes received_at because
-- PostgreSQL requires the partition column in it: it catches a redelivered Kafka record, while a
-- device resend (new received_at) is caught by the Redis duplicate key (ADR-006).
CREATE TABLE telemetry (
    device_id   text        NOT NULL,
    tenant_id   text        NOT NULL,
    boot_id     bigint      NOT NULL,
    sequence    bigint      NOT NULL,
    received_at timestamptz NOT NULL,
    device_time timestamptz,
    event_type  text        NOT NULL,
    event_id    uuid,
    metrics     jsonb       NOT NULL DEFAULT '{}',
    UNIQUE (device_id, boot_id, sequence, received_at)
) PARTITION BY RANGE (received_at);

CREATE INDEX telemetry_device_time_idx ON telemetry (device_id, received_at DESC);

-- Status history, kept 90 days in monthly partitions. Doubles as the outbox for
-- device.status-changed: published_at stays NULL until the event is on Kafka.
CREATE TABLE status_change (
    change_id    uuid        NOT NULL,
    device_id    text        NOT NULL,
    tenant_id    text        NOT NULL,
    from_status  text,
    to_status    text        NOT NULL,
    cause        text        NOT NULL,
    boot_id      bigint      NOT NULL,
    sequence     bigint      NOT NULL,
    changed_at   timestamptz NOT NULL,
    published_at timestamptz,
    PRIMARY KEY (change_id, changed_at)
) PARTITION BY RANGE (changed_at);

CREATE INDEX status_change_device_idx ON status_change (device_id, changed_at DESC);
CREATE INDEX status_change_unpublished_idx ON status_change (changed_at) WHERE published_at IS NULL;

-- Events that could not be processed, kept 30 days.
CREATE TABLE dead_letter (
    dead_letter_id   uuid        PRIMARY KEY,
    source_topic     text        NOT NULL,
    device_id        text,
    tenant_id        text,
    reason_code      text        NOT NULL,
    reason           text,
    attempts         integer     NOT NULL,
    first_failure_at timestamptz NOT NULL,
    last_failure_at  timestamptz NOT NULL,
    payload          text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    replay_count     integer     NOT NULL DEFAULT 0,
    last_replayed_at timestamptz
);

CREATE INDEX dead_letter_created_idx ON dead_letter (created_at DESC, dead_letter_id);
CREATE INDEX dead_letter_reason_idx ON dead_letter (reason_code, created_at DESC);
CREATE INDEX dead_letter_tenant_idx ON dead_letter (tenant_id, created_at DESC);
