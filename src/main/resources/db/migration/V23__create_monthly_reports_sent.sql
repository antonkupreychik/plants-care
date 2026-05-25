-- V23__create_monthly_reports_sent.sql
-- Issue #137: месячный отчёт по уходу.
--
-- Идемпотентность шедулера месячных отчётов: не отправлять отчёт одному и тому
-- же юзеру дважды за один и тот же отчётный месяц. Шедулер перед отправкой
-- делает INSERT в эту таблицу — если PK конфликт, значит за этот период уже
-- слали (повторный тик / рестарт пода / двойной запуск при rolling deploy).
--
-- Отчётный период year_month берётся по календарю в TZ юзера (users.timezone):
-- «отчёт за апрель» считается по месяцу пользователя, а не по UTC, что даёт
-- устойчивость к сдвигу времени запуска шедулера на стыке месяцев.
--
-- Backward-compat note:
--   * Новая таблица — для старого кода невидима (аддитивно, один релиз).
--   * ON DELETE CASCADE на user_id: при удалении пользователя история
--     отправленных отчётов уезжает вместе с ним — это ок, id не переиспользуется.
--   * Безопасно для rolling deploy.

BEGIN;

CREATE TABLE monthly_report_sent (
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    year_month  INT         NOT NULL,
    sent_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, year_month)
);

COMMENT ON TABLE monthly_report_sent IS
    'Идемпотентность месячных отчётов (issue #137): по одной строке на '
        '(user_id, отчётный месяц). Шедулер перед отправкой пытается INSERT — '
        'конфликт PK означает «отчёт за этот месяц уже отправлен».';

COMMENT ON COLUMN monthly_report_sent.year_month IS
    'Отчётный месяц в формате YYYYMM (в TZ юзера, users.timezone). '
        'Например, 202604 для отчёта за апрель 2026.';

COMMENT ON COLUMN monthly_report_sent.sent_at IS
    'Момент отправки отчёта в UTC (TIMESTAMPTZ). Для дебага и метрик.';

COMMIT;
