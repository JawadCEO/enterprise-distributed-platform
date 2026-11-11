-- Failure drills (part 3): the outage guard's fleet-wide silence latch moves from Redis to here.
-- While every region is quiet, offline marking is paused; if the latch lived only in Redis, a Redis
-- restart during that silence would forget it, and the deadline repair would then mark the whole
-- quiet fleet offline with an alert per line.
CREATE TABLE presence_latch (
    name       text        PRIMARY KEY,
    details    jsonb       NOT NULL DEFAULT '{}',
    set_at     timestamptz NOT NULL DEFAULT now()
);
