-- Issue #92: Rate limit events log table.
-- Stores 429 events for admin monitoring and outlier analysis.
CREATE TABLE rate_limit_events (
    id          BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ     NOT NULL DEFAULT now(),
    ip          VARCHAR(45)     NOT NULL,
    user_id     BIGINT,
    endpoint    VARCHAR(255)    NOT NULL,
    limit_type  VARCHAR(64)     NOT NULL
);

-- Index for time-based queries (top violators for a day)
CREATE INDEX idx_rate_limit_events_occurred_at ON rate_limit_events (occurred_at DESC);
-- Index for IP-based queries
CREATE INDEX idx_rate_limit_events_ip ON rate_limit_events (ip);
