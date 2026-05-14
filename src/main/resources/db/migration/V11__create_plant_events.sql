-- =====================================================
-- V11: Журнал событий растения (issue #76)
--
-- Редкие, разовые события: пересадка, замена грунта, обрезка,
-- обработка от вредителей. Не регулярный уход — это контекст для
-- понимания истории растения (вянет → смотрим, не была ли недавно
-- жёсткая обрезка корней и т.п.).
--
-- Не изменяет таблицу plants — отдельная сущность, как и положено
-- по тех. заметкам в issue.
-- =====================================================

CREATE TABLE IF NOT EXISTS plant_events (
    id          BIGSERIAL PRIMARY KEY,
    plant_id    BIGINT       NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    event_type  VARCHAR(32)  NOT NULL,
    event_date  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    comment     VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_plant_event_type
        CHECK (event_type IN ('TRANSPLANT', 'SOIL_CHANGE', 'PRUNING', 'PEST_TREATMENT'))
);

-- Индекс для пагинации последних событий растения (DESC по event_date).
CREATE INDEX IF NOT EXISTS idx_plant_events_plant_date
    ON plant_events(plant_id, event_date DESC);

COMMENT ON TABLE plant_events IS
    'Журнал редких событий растения (issue #76): пересадка, обрезка и пр. '
        'Не путать с care_history — там регулярные задачи.';

COMMENT ON COLUMN plant_events.event_type IS
    'TRANSPLANT / SOIL_CHANGE / PRUNING / PEST_TREATMENT';

COMMENT ON COLUMN plant_events.event_date IS
    'Когда событие произошло (для v1 = created_at; в будущем юзер сможет '
        'указать прошлую дату).';

COMMENT ON COLUMN plant_events.comment IS
    'Опциональный комментарий до 500 символов. В v1 не заполняется при создании, '
        'колонка зарезервирована под будущую фичу «добавить комментарий».';
