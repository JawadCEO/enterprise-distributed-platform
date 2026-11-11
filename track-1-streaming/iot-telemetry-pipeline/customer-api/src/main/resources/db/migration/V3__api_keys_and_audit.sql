-- API keys for customers, operators and network senders, and an audit log of registry changes and
-- replays (NFR-19).

CREATE TABLE api_key (
    key_id      text        PRIMARY KEY,                -- public prefix of the key, safe to log
    key_hash    text        NOT NULL,                   -- SHA-256 hex of the full key
    role        text        NOT NULL CHECK (role IN ('CUSTOMER', 'OPERATOR', 'NETWORK')),
    customer_id text        REFERENCES customer (customer_id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    CHECK ((role = 'CUSTOMER') = (customer_id IS NOT NULL))
);

CREATE TABLE audit_log (
    audit_id    bigserial   PRIMARY KEY,
    at          timestamptz NOT NULL DEFAULT now(),
    actor       text        NOT NULL,                   -- key_id of the caller
    role        text        NOT NULL,
    customer_id text,
    action      text        NOT NULL,
    target      text,
    details     jsonb       NOT NULL DEFAULT '{}'
);

CREATE INDEX audit_log_at_idx ON audit_log (at DESC);
CREATE INDEX audit_log_customer_idx ON audit_log (customer_id, at DESC);
