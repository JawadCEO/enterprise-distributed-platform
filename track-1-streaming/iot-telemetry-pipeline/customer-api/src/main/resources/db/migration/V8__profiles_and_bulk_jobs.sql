-- Remote profile switch (FR-10, Flow E) and bulk jobs (FR-9).

-- The carrier profile an embedded or integrated SIM is running; NULL means the one it shipped with.
ALTER TABLE line ADD COLUMN profile text;

ALTER TABLE line_request DROP CONSTRAINT line_request_action_check;
ALTER TABLE line_request ADD CONSTRAINT line_request_action_check
    CHECK (action IN ('ACTIVATE', 'SUSPEND', 'RESTORE', 'DEACTIVATE', 'SWITCH_PROFILE'));
ALTER TABLE line_request ADD COLUMN profile text;
ALTER TABLE line_request ADD COLUMN job_id uuid;

-- A bulk job: one action on up to 50,000 of a customer's lines. Its lines are fed to the ordinary
-- request path a few hundred at a time, so the job never holds more than its share of the network.
CREATE TABLE job (
    job_id          uuid        PRIMARY KEY,
    customer_id     text        NOT NULL REFERENCES customer (customer_id),
    action          text        NOT NULL,
    profile         text,
    status          text        NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED')),
    total           integer     NOT NULL CHECK (total BETWEEN 1 AND 50000),
    succeeded       integer     NOT NULL DEFAULT 0,
    failed          integer     NOT NULL DEFAULT 0,
    idempotency_key text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 128),
    created_at      timestamptz NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    -- the customer-api instance feeding the job holds it until then; another takes over if it dies
    runner_until    timestamptz,
    UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX job_running_idx ON job (created_at) WHERE status = 'RUNNING';

-- One row per line of a job. QUEUED until it is submitted; then it carries its request, or the
-- reason it was refused (REJECTED) without reaching the network.
CREATE TABLE job_item (
    job_id      uuid    NOT NULL REFERENCES job (job_id) ON DELETE CASCADE,
    iccid       text    NOT NULL,
    status      text    NOT NULL CHECK (status IN ('QUEUED', 'SUBMITTED', 'REJECTED')),
    request_id  uuid,
    reason      text,
    PRIMARY KEY (job_id, iccid)
);

CREATE INDEX job_item_queued_idx ON job_item (job_id) WHERE status = 'QUEUED';
CREATE INDEX line_request_job_idx ON line_request (job_id, status) WHERE job_id IS NOT NULL;
