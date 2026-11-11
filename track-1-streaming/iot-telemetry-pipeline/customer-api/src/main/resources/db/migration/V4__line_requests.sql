-- Customer line requests (FR-4 to FR-8, ADR-007). A request, the line's PENDING state and the
-- command for provisioning-worker are written in one transaction; line_command is the outbox for
-- line.commands.

CREATE TABLE line_request (
    request_id          uuid        PRIMARY KEY,
    customer_id         text        NOT NULL REFERENCES customer (customer_id),
    iccid               text        NOT NULL REFERENCES line (iccid),
    action              text        NOT NULL CHECK (action IN ('ACTIVATE', 'SUSPEND', 'RESTORE', 'DEACTIVATE')),
    status              text        NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    reason              text,
    previous_state      text        NOT NULL CHECK (previous_state IN ('INVENTORY', 'ACTIVE', 'SUSPENDED')),
    idempotency_key     text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    created_at          timestamptz NOT NULL DEFAULT now(),
    deadline_at         timestamptz NOT NULL,
    network_accepted_at timestamptz,
    completed_at        timestamptz,
    -- the same key always returns the original request (FR-6); keys live as long as the request
    UNIQUE (customer_id, idempotency_key)
);

-- at most one request in progress per line (FR-5)
CREATE UNIQUE INDEX line_request_one_pending_idx ON line_request (iccid) WHERE status = 'PENDING';
CREATE INDEX line_request_deadline_idx ON line_request (deadline_at) WHERE status = 'PENDING';
CREATE INDEX line_request_line_idx ON line_request (iccid, created_at DESC);
CREATE INDEX line_request_created_idx ON line_request (created_at);

CREATE TABLE line_command (
    request_id   uuid        PRIMARY KEY REFERENCES line_request (request_id) ON DELETE CASCADE,
    iccid        text        NOT NULL,
    payload      text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE INDEX line_command_unpublished_idx ON line_command (created_at) WHERE published_at IS NULL;
