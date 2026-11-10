-- Carrier IoT platform schema (data.md section 6): customers, lines (SIMs), device state, usage,
-- line history and dead letters. line_history is range-partitioned by day; partitions are managed
-- by iot_maintain_partitions() in V2.

CREATE TABLE customer (
    customer_id              text        PRIMARY KEY,
    name                     text        NOT NULL,
    rate_limit_per_sec       integer     NOT NULL DEFAULT 1000 CHECK (rate_limit_per_sec > 0),
    -- FR-18: suspend an embedded or integrated SIM automatically when it shows up in another device
    auto_suspend_on_sim_move boolean     NOT NULL DEFAULT false,
    -- FR-5: requests are refused while the account is BLOCKED (used by customer requests, part 2b)
    account_status           text        NOT NULL DEFAULT 'GOOD' CHECK (account_status IN ('GOOD', 'BLOCKED')),
    created_at               timestamptz NOT NULL DEFAULT now(),
    updated_at               timestamptz NOT NULL DEFAULT now()
);

-- Default keep-alive windows per kind of device [assumption, data.md section 5].
CREATE TABLE device_kind (
    device_kind        text    PRIMARY KEY,
    keep_alive_seconds integer NOT NULL CHECK (keep_alive_seconds > 0)
);

INSERT INTO device_kind (device_kind, keep_alive_seconds) VALUES
    ('TRACKER', 300),
    ('METER', 3600),
    ('FRIDGE_SENSOR', 900);

-- One row per SIM. The ICCID is the line ID everywhere: Kafka key, Redis key, API path.
CREATE TABLE line (
    iccid                text        PRIMARY KEY CHECK (iccid ~ '^[0-9]{18,22}$'),
    customer_id          text        NOT NULL REFERENCES customer (customer_id),
    sim_type             text        NOT NULL CHECK (sim_type IN ('REMOVABLE', 'EMBEDDED', 'INTEGRATED')),
    device_kind          text        NOT NULL REFERENCES device_kind (device_kind),
    keep_alive_seconds   integer     CHECK (keep_alive_seconds > 0),   -- NULL means the kind's default
    plan_code            text        NOT NULL DEFAULT 'IOT-STANDARD',
    data_allowance_bytes bigint      NOT NULL DEFAULT 0 CHECK (data_allowance_bytes >= 0), -- 0 means no limit
    home_region          text        NOT NULL DEFAULT 'local',
    line_state           text        NOT NULL DEFAULT 'INVENTORY'
                                     CHECK (line_state IN ('INVENTORY', 'PENDING', 'ACTIVE', 'SUSPENDED', 'DEACTIVATED')),
    line_state_since     timestamptz NOT NULL DEFAULT now(),
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX line_customer_idx ON line (customer_id, iccid);
CREATE INDEX line_customer_state_idx ON line (customer_id, line_state, iccid);
CREATE INDEX line_customer_sim_idx ON line (customer_id, sim_type, iccid);

-- Current device state, one row per line (FR-16). Updated only by an event that is newer under the
-- ADR-005 rules; the processor holds the row lock while it decides.
CREATE TABLE device_state (
    iccid             text        PRIMARY KEY REFERENCES line (iccid),
    customer_id       text        NOT NULL,
    connection        text        NOT NULL CHECK (connection IN ('ONLINE', 'OFFLINE')),
    connection_reason text,
    connection_since  timestamptz NOT NULL,
    last_seen         timestamptz NOT NULL,
    imei              text,
    region            text,
    last_boot_id      bigint,
    last_sequence     bigint,
    last_network_time timestamptz,
    metrics           jsonb       NOT NULL DEFAULT '{}',
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX device_state_customer_conn_idx ON device_state (customer_id, connection, iccid);

-- Running usage total per line per billing cycle (calendar month, UTC). The two flags make each
-- threshold notification happen once per cycle (FR-25).
CREATE TABLE usage_cycle (
    iccid           text        NOT NULL REFERENCES line (iccid),
    cycle_start     date        NOT NULL,
    bytes_used      bigint      NOT NULL DEFAULT 0,
    allowance_bytes bigint      NOT NULL,
    notified_80     boolean     NOT NULL DEFAULT false,
    notified_100    boolean     NOT NULL DEFAULT false,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (iccid, cycle_start)
);

-- Every usage record, keyed by the sender's eventId, so a record is counted exactly once no matter
-- how often it is resent or replayed (ADR-006). Kept 13 months.
CREATE TABLE usage_record (
    event_id     uuid        PRIMARY KEY,
    iccid        text        NOT NULL,
    cycle_start  date        NOT NULL,
    bytes        bigint      NOT NULL CHECK (bytes >= 0),
    network_time timestamptz NOT NULL,
    received_at  timestamptz NOT NULL
);

CREATE INDEX usage_record_line_idx ON usage_record (iccid, network_time DESC);

-- Line history (FR-27), kept 90 days in daily partitions. Doubles as the outbox for line.changes:
-- published_at stays NULL until the change is on Kafka.
CREATE TABLE line_history (
    change_id    uuid        NOT NULL,
    iccid        text        NOT NULL,
    customer_id  text        NOT NULL,
    kind         text        NOT NULL,
    notify       boolean     NOT NULL,
    event_id     uuid,
    at           timestamptz NOT NULL,
    details      jsonb       NOT NULL DEFAULT '{}',
    published_at timestamptz,
    PRIMARY KEY (change_id, at)
) PARTITION BY RANGE (at);

CREATE INDEX line_history_line_idx ON line_history (iccid, at DESC);
CREATE INDEX line_history_unpublished_idx ON line_history (at) WHERE published_at IS NULL;

-- SIM moves with their severity, kept a year (purpose 6, security).
CREATE TABLE security_event (
    security_event_id uuid        PRIMARY KEY,
    iccid             text        NOT NULL,
    customer_id       text        NOT NULL,
    sim_type          text        NOT NULL,
    old_imei          text,
    new_imei          text,
    severity          text        NOT NULL CHECK (severity IN ('MEDIUM', 'HIGH')),
    auto_suspended    boolean     NOT NULL,
    event_id          uuid,
    at                timestamptz NOT NULL
);

CREATE INDEX security_event_line_idx ON security_event (iccid, at DESC);
CREATE INDEX security_event_customer_idx ON security_event (customer_id, at DESC);

-- Events that could not be processed, kept 30 days.
CREATE TABLE dead_letter (
    dead_letter_id   uuid        PRIMARY KEY,
    source_topic     text        NOT NULL,
    iccid            text,
    customer_id      text,
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
CREATE INDEX dead_letter_customer_idx ON dead_letter (customer_id, created_at DESC);
