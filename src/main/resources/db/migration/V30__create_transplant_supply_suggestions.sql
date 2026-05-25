-- V30__create_transplant_supply_suggestions.sql
-- Issue #141: подсказка «добавить расходники перед пересадкой».
--
-- Раз в день шедулер вычисляет предстоящую пересадку как
--   (дата последнего события TRANSPLANT из plant_events) + интервал из конфига
-- и шлёт пользователю мягкую нотификацию с кнопками
--   «➕ В список покупок» / «Не нужно».
--
-- Эта таблица — трекер идемпотентности подсказок:
--   * не дать слать повторно по одному и тому же предстоящему событию;
--   * не дать дублировать товары в shopping_items при повторном нажатии кнопки.
--
-- Ключ идемпотентности — source_event_id: это id записи TRANSPLANT в
-- plant_events, от которой посчитана предстоящая пересадка. Появилась новая
-- пересадка → новый source_event_id → можно подсказать снова. UNIQUE на
-- (user_id, plant_id, source_event_id) фиксирует эту гарантию на уровне БД:
-- шедулер перед отправкой пытается INSERT, конфликт UNIQUE = «уже подсказывали».
--
-- status:
--   SUGGESTED  — нотификация отправлена, ждём реакции юзера;
--   ADDED      — юзер нажал «В список покупок», позиции добавлены в shopping_items;
--   DISMISSED  — юзер нажал «Не нужно».
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима (аддитивно, один релиз).
--   * id маппится JPA как GenerationType.IDENTITY → BIGSERIAL.
--   * created_at маппится @CreationTimestamp → TIMESTAMPTZ (UTC), как в остальных таблицах.
--   * ON DELETE CASCADE на user_id / plant_id / source_event_id: при удалении
--     пользователя, растения или самого события пересадки трекер уезжает вместе
--     с ними — это ок, id не переиспользуются, висячих подсказок не остаётся.
--   * Безопасно для rolling deploy. Только DDL, без сидинга.

BEGIN;

CREATE TABLE transplant_supply_suggestions (
    id                      BIGSERIAL    PRIMARY KEY,
    user_id                 BIGINT       NOT NULL REFERENCES users(id)        ON DELETE CASCADE,
    plant_id                BIGINT       NOT NULL REFERENCES plants(id)       ON DELETE CASCADE,
    source_event_id         BIGINT       NOT NULL REFERENCES plant_events(id) ON DELETE CASCADE,
    status                  VARCHAR(16)  NOT NULL,
    predicted_transplant_at TIMESTAMPTZ  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    responded_at            TIMESTAMPTZ,

    CONSTRAINT chk_transplant_supply_suggestion_status
        CHECK (status IN ('SUGGESTED', 'ADDED', 'DISMISSED')),

    CONSTRAINT uq_transplant_supply_suggestion_event
        UNIQUE (user_id, plant_id, source_event_id)
);

COMMENT ON TABLE transplant_supply_suggestions IS
    'Трекер идемпотентности подсказок расходников перед пересадкой (issue #141): '
        'по строке на (user_id, plant_id, source_event_id). Шедулер перед '
        'отправкой пытается INSERT — конфликт UNIQUE означает «уже подсказывали '
        'по этой предстоящей пересадке».';

COMMENT ON COLUMN transplant_supply_suggestions.source_event_id IS
    'id записи TRANSPLANT в plant_events, от которой посчитана предстоящая '
        'пересадка. Ключ идемпотентности: новая пересадка → новый source_event_id '
        '→ можно подсказать снова.';

COMMENT ON COLUMN transplant_supply_suggestions.status IS
    'SUGGESTED — нотификация отправлена, ждём реакции; ADDED — юзер добавил в '
        'список покупок; DISMISSED — юзер нажал «не нужно».';

COMMENT ON COLUMN transplant_supply_suggestions.predicted_transplant_at IS
    'Вычисленная дата предстоящей пересадки (last TRANSPLANT + интервал из конфига) '
        'в UTC (TIMESTAMPTZ). Для аудита/отладки расчёта шедулера.';

COMMENT ON COLUMN transplant_supply_suggestions.created_at IS
    'Момент создания строки (= момент отправки нотификации) в UTC (TIMESTAMPTZ).';

COMMENT ON COLUMN transplant_supply_suggestions.responded_at IS
    'Момент нажатия кнопки юзером в UTC (TIMESTAMPTZ). NULL, пока статус SUGGESTED.';

-- Основной запрос: «активные подсказки юзера, ждущие реакции».
--   SELECT ... WHERE user_id = :uid AND status = 'SUGGESTED'
-- Покрывает и FK user_id.
CREATE INDEX idx_transplant_supply_suggestions_user_status
    ON transplant_supply_suggestions (user_id, status);

-- FK plant_id и source_event_id под ON DELETE CASCADE и под обратный поиск
-- «подсказывали ли уже по этому событию». Postgres не индексирует FK сам.
CREATE INDEX idx_transplant_supply_suggestions_plant
    ON transplant_supply_suggestions (plant_id);

CREATE INDEX idx_transplant_supply_suggestions_source_event
    ON transplant_supply_suggestions (source_event_id);

COMMIT;
