-- Issue #97: Errors viewer — таблица последних exception'ов из логов.
--
-- Пишется асинхронно кастомным Logback-аппендером (ErrorLogDbAppender) при level >= WARN,
-- читается страницей /admin/errors. Append-only: UPDATE по строкам не бывает, удаление —
-- только пакетное по retention (ErrorLogRetentionScheduler, 30 дней).
--
-- FK на users НЕТ осознанно:
--   * инсерт идёт из фонового потока и не должен падать из-за исчезнувшего/чужого user_id
--     (например, sub из JWT удалённого юзера);
--   * запись об ошибке должна пережить удаление пользователя — это диагностика, не бизнес-данные.
--
-- Партиционирование по месяцам (см. issue, раздел Performance) НЕ делается: при retention
-- 30 дней и объёмах пет-проекта DELETE по индексу created_at дешевле, чем сопровождение
-- партиций (требует составного PK (id, created_at) и отдельной джобы создания партиций).
CREATE TABLE error_logs (
    id              BIGSERIAL PRIMARY KEY,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    level           VARCHAR(10)  NOT NULL,
    logger_name     VARCHAR(255) NOT NULL,
    message         TEXT         NOT NULL,
    exception_class VARCHAR(255),
    -- Ключ группировки «одинаковых» ошибок: класс исключения + первый кадр стека
    -- (см. ErrorFingerprint). Не хеш — читается глазами прямо в таблице.
    fingerprint     VARCHAR(512) NOT NULL,
    stack_trace     TEXT,
    user_id         BIGINT,
    request_path    VARCHAR(512),
    correlation_id  VARCHAR(64),
    thread_name     VARCHAR(128)
);

COMMENT ON TABLE error_logs IS
    'Issue #97: WARN/ERROR из логов для админской страницы /admin/errors. Retention 30 дней.';
COMMENT ON COLUMN error_logs.fingerprint IS
    'Ключ схлопывания одинаковых ошибок: <exceptionClass> at <первый кадр стека>.';
COMMENT ON COLUMN error_logs.correlation_id IS
    'MDC correlationId запроса (заголовок X-Correlation-Id либо сгенерированный UUID).';

-- Основной порядок вывода и окно retention.
CREATE INDEX idx_error_logs_created_at ON error_logs (created_at DESC);
-- «Все ошибки юзера за час до» + фильтр по юзеру.
CREATE INDEX idx_error_logs_user_created ON error_logs (user_id, created_at DESC)
    WHERE user_id IS NOT NULL;
-- Топ-10 уникальных ошибок за 24ч (GROUP BY fingerprint с окном по created_at).
CREATE INDEX idx_error_logs_fingerprint_created ON error_logs (fingerprint, created_at DESC);
-- Фильтр по логгеру (префикс com.plantcare.*) и по уровню.
CREATE INDEX idx_error_logs_logger_created ON error_logs (logger_name, created_at DESC);
CREATE INDEX idx_error_logs_level_created ON error_logs (level, created_at DESC);
-- Поиск по correlation_id со страницы деталки.
CREATE INDEX idx_error_logs_correlation_id ON error_logs (correlation_id)
    WHERE correlation_id IS NOT NULL;
