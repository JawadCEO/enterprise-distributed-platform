-- Webhooks (FR-22 to FR-25, ADR-008) and the regional outage guard (FR-21).

-- A customer's webhook endpoints. Each one has its own signing secret and the notification types
-- it wants; only those are sent to it.
CREATE TABLE webhook (
    webhook_id  uuid        PRIMARY KEY,
    customer_id text        NOT NULL REFERENCES customer (customer_id),
    url         text        NOT NULL CHECK (url ~ '^https?://' AND length(url) <= 2048),
    types       text[]      NOT NULL CHECK (cardinality(types) > 0),
    secret      text        NOT NULL,
    active      boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX webhook_customer_idx ON webhook (customer_id) WHERE active;

-- One row per notification per endpoint, with its delivery state. notification_id is derived
-- from the change and the endpoint, so a change read twice from Kafka is stored once (FR-23).
-- seq orders delivery per line (NFR-10a): a notification waits while an earlier one for the same
-- line and endpoint is still pending.
CREATE TABLE notification (
    notification_id uuid        PRIMARY KEY,
    seq             bigserial   NOT NULL,
    webhook_id      uuid        NOT NULL REFERENCES webhook (webhook_id) ON DELETE CASCADE,
    customer_id     text        NOT NULL,
    type            text        NOT NULL,
    -- the line it is about, or 'customer' for a notice about the whole account (outage, recovery)
    ordering_key    text        NOT NULL,
    source_id       uuid        NOT NULL,
    payload         text        NOT NULL,
    status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DELIVERED', 'PARKED')),
    attempts        integer     NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    -- retries run for a set time from here (FR-24); a redelivery asked for by the customer restarts it
    retry_since     timestamptz NOT NULL DEFAULT now(),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until     timestamptz,
    last_attempt_at timestamptz,
    last_error      text,
    delivered_at    timestamptz,
    parked_at       timestamptz
);

CREATE INDEX notification_due_idx ON notification (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX notification_line_order_idx ON notification (webhook_id, ordering_key, seq) WHERE status = 'PENDING';
CREATE INDEX notification_customer_idx ON notification (customer_id, created_at DESC, notification_id);
CREATE INDEX notification_created_idx ON notification (created_at);

-- A region the outage guard found silent. At most one open outage per region; ended_at is set
-- when its traffic comes back.
CREATE TABLE region_outage (
    outage_id          uuid        PRIMARY KEY,
    region             text        NOT NULL,
    home_region        text        NOT NULL,
    started_at         timestamptz NOT NULL,
    ended_at           timestamptz,
    baseline_per_sec   double precision NOT NULL,
    lines_in_region    integer     NOT NULL,
    lines_still_offline integer
);

CREATE UNIQUE INDEX region_outage_open_idx ON region_outage (region, home_region) WHERE ended_at IS NULL;

-- Notices about a whole account (outage and recovery), written with the outage row and published
-- on customer.notices; published_at stays NULL until the notice is on Kafka (outbox).
CREATE TABLE customer_notice (
    notice_id    uuid        PRIMARY KEY,
    customer_id  text        NOT NULL,
    kind         text        NOT NULL,
    at           timestamptz NOT NULL,
    details      jsonb       NOT NULL DEFAULT '{}',
    published_at timestamptz
);

CREATE INDEX customer_notice_unpublished_idx ON customer_notice (at) WHERE published_at IS NULL;

-- The outage guard asks how many active lines were last seen in a region.
CREATE INDEX device_state_region_idx ON device_state (region, connection);
