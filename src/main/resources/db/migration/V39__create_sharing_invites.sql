-- V39__create_sharing_invites.sql
-- Issue #191 (mobile G22, экран 26 «Совместный уход»): пользователь приглашает
-- соухаживающего по контакту (@username / телефон), выбирает набор растений и
-- решает, может ли приглашённый отмечать уход (canLogCare).
--
-- Модель доступа:
--   * sharing_invites — приглашение, выпущенное владельцем (inviter_user_id).
--     contact приглашённого хранится свободной строкой (@username или телефон),
--     потому что у приглашённого может ещё не быть аккаунта на момент инвайта.
--   * status: PENDING (выпущено, ещё не принято) / ACCEPTED (принято).
--     ACCEPTED-состояние и связывание с реальным user приглашённого — отдельная
--     задача (приём инвайта); здесь создаём приглашение и читаем список.
--   * can_log_care — право приглашённого отмечать уход за выбранными растениями.
--   * sharing_invite_plants — набор растений, на которые распространяется инвайт.
--
-- Backward-compat note:
--   * Две новые таблицы — для старого кода невидимы (аддитивно, один релиз).
--   * id маппится JPA как GenerationType.IDENTITY → BIGSERIAL.
--   * created_at маппится @CreationTimestamp → TIMESTAMPTZ (UTC), как везде.
--   * ON DELETE CASCADE на inviter_user_id и plant_id: удаление пользователя или
--     растения убирает связанные инвайты/связи. id не переиспользуется.
--   * Только DDL, без сидинга. Безопасно для rolling deploy.

BEGIN;

CREATE TABLE sharing_invites (
    id              BIGSERIAL    PRIMARY KEY,
    inviter_user_id BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    invitee_contact VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    can_log_care    BOOLEAN      NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_sharing_invites_status CHECK (status IN ('PENDING', 'ACCEPTED'))
);

COMMENT ON TABLE sharing_invites IS
    'Приглашение соухаживающего (issue #191): по строке на приглашение от владельца.';

COMMENT ON COLUMN sharing_invites.inviter_user_id IS
    'Владелец растений, выпустивший приглашение.';

COMMENT ON COLUMN sharing_invites.invitee_contact IS
    'Контакт приглашённого: @username или телефон. Свободная строка — аккаунта может ещё не быть.';

COMMENT ON COLUMN sharing_invites.status IS
    'Статус приглашения: PENDING (ещё не принято) или ACCEPTED (принято).';

COMMENT ON COLUMN sharing_invites.can_log_care IS
    'Право приглашённого отмечать уход за растениями из набора.';

COMMENT ON COLUMN sharing_invites.created_at IS
    'Момент создания приглашения в UTC (TIMESTAMPTZ).';

-- Основной запрос: список приглашений, выпущенных владельцем. Покрывает FK.
CREATE INDEX idx_sharing_invites_inviter
    ON sharing_invites (inviter_user_id, created_at);

CREATE TABLE sharing_invite_plants (
    invite_id BIGINT NOT NULL REFERENCES sharing_invites(id) ON DELETE CASCADE,
    plant_id  BIGINT NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    PRIMARY KEY (invite_id, plant_id)
);

COMMENT ON TABLE sharing_invite_plants IS
    'Набор растений, на которые распространяется приглашение (issue #191).';

-- Обратный поиск инвайтов по растению + покрытие FK plant_id.
CREATE INDEX idx_sharing_invite_plants_plant
    ON sharing_invite_plants (plant_id);

COMMIT;
