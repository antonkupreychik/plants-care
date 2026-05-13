CREATE TABLE notification_digests (
                                      id              BIGSERIAL PRIMARY KEY,
                                      user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                      plant_task_ids  JSONB NOT NULL,
                                      created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notif_digests_user_created ON notification_digests(user_id, created_at DESC);