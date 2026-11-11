-- Observability (part 4): customer-api reads requests finished in the last 5 minutes every 15 s
-- for its request-outcome metrics.
CREATE INDEX line_request_completed_idx ON line_request (completed_at) WHERE completed_at IS NOT NULL;
