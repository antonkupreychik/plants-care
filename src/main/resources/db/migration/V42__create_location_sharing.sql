-- Issue #77: Шеринг доступа (Совместный уход).
-- Классическая SUBO-модель: Subject (пользователь) получает роль на Object (локацию).
--
-- location_access  — кто и с какой ролью имеет доступ к чужой локации.
-- location_invites — одноразовые deep-link приглашения (t.me/Bot?start=invite_<token>).

CREATE TABLE location_access (
    id                 BIGSERIAL   PRIMARY KEY,
    subject_user_id    BIGINT      NOT NULL REFERENCES users(id)      ON DELETE CASCADE,
    object_location_id BIGINT      NOT NULL REFERENCES locations(id)  ON DELETE CASCADE,
    role               VARCHAR(16) NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_location_access_subject_object UNIQUE (subject_user_id, object_location_id)
);

-- Воркер шедулера по object_location_id собирает всех subject_user_id для рассылки.
CREATE INDEX idx_location_access_object ON location_access (object_location_id);
CREATE INDEX idx_location_access_subject ON location_access (subject_user_id);

CREATE TABLE location_invites (
    id                   BIGSERIAL   PRIMARY KEY,
    token                VARCHAR(64) NOT NULL UNIQUE,
    location_id          BIGINT      NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    inviter_user_id      BIGINT      NOT NULL REFERENCES users(id)     ON DELETE CASCADE,
    role                 VARCHAR(16) NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,
    accepted_at          TIMESTAMPTZ,
    accepted_by_user_id  BIGINT      REFERENCES users(id) ON DELETE SET NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Поиск по токену при переходе по ссылке — token уже UNIQUE, отдельный индекс не нужен.
CREATE INDEX idx_location_invites_location ON location_invites (location_id);
