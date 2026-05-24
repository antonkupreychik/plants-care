# 🌿 Plants Care Bot

Telegram-бот для напоминаний об уходе за домашними растениями.
Java 21 + Spring Boot 3 + PostgreSQL.

## Стек

- **Java 21** + **Spring Boot 3.3**
- **PostgreSQL 16** (с JSONB и tsvector для поиска)
- **Flyway** для миграций
- **Hibernate / Spring Data JPA**
- **Testcontainers** для интеграционных тестов
- **Docker** для контейнеризации
- **Railway** для деплоя

## Быстрый старт

### Что нужно установить

- JDK 21 ([Temurin](https://adoptium.net/) или другой)
- Docker и Docker Compose
- Maven (или используй включённый wrapper `./mvnw`)

### Вариант 1: разработка локально (приложение через ./mvnw, БД в Docker)

Удобно, когда часто меняешь код — IDE подхватит изменения, не надо пересобирать образ.

```bash
cp .env.example .env
docker compose up -d
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

### Вариант 2: всё в Docker (включая приложение)

Удобно для проверки production-сборки.

```bash
docker compose --profile full up -d --build
docker compose --profile full logs -f app
docker compose --profile full down
```

После запуска любым способом:
- Healthcheck: http://localhost:8080/actuator/health
- Метрики (JSON): http://localhost:8080/actuator/metrics
- Метрики (Prometheus scrape): http://localhost:8080/actuator/prometheus (см. секцию Observability)

### Тесты

```bash
./mvnw test
```

Интеграционные тесты используют Testcontainers — Docker должен быть запущен.

Для ускорения локальных запусков можно включить переиспользование контейнера —
тесты будут стартовать в разы быстрее (Postgres не пересоздаётся между запусками):

```bash
echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
```

## Миграции БД

Миграции лежат в `src/main/resources/db/migration/` и применяются Flyway автоматически
при старте приложения и тестов.

- **V1** — основная схема (7 таблиц, индексы, триггеры)
- **V2** — сидинг 30 популярных видов растений в `species`
- **V15** — добавляет `client_id VARCHAR(64) NULL` в `care_history` с partial unique index `WHERE client_id IS NOT NULL`. Колонка nullable — записи из Telegram-бота остаются с NULL без конфликтов

## REST API

Идентификация пользователя во всех `/api/v1/**` эндпоинтах (кроме `/health`) — заголовок `X-Chat-Id: <telegramChatId>`.

### Инфраструктура

| Путь | Метод | Описание |
|---|---|---|
| `/api/v1/health` | `GET` | Liveness probe — возвращает `{"status":"UP"}` |
| `/swagger-ui.html` | — | Swagger UI (OpenAPI 3) |
| `/v3/api-docs` | — | OpenAPI JSON |

### События ухода (issue #86)

| Путь | Метод | Статус ответа | Описание |
|---|---|---|---|
| `/api/v1/care-events` | `POST` | 201 | Регистрация ухода (WATER/SPRAY/FERTILIZE). Поле `clientId` обеспечивает идемпотентность: повторный запрос с тем же `clientId` возвращает существующую запись без дублирования |
| `/api/v1/care-events/{id}` | `DELETE` | 204 | Отмена события через compensation pattern: запись не удаляется физически, создаётся компенсирующая запись |
| `/api/v1/plants/{id}/history` | `GET` | 200 | История ухода за растением с offset-пагинацией. Query params: `limit` [1–100], default 20; `offset`, default 0. Проверяет ownership растения |
| `/api/v1/today` | `GET` | 200 | Задачи ухода на сегодня в таймзоне пользователя |
| `/api/v1/calendar` | `GET` | 200 | Расписание за произвольный период. Query params: `from`, `to` (ISO date). Диапазон не более 60 дней. Дни без задач в ответ не включаются |
| `/api/v1/stats/streak` | `GET` | 200 | Текущий стрик растения (последовательные выполнения без пропусков). Query param: `plantId` |

### Формат ошибок

### Plants

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/api/v1/plants` | Список растений. Параметры: `locationId` (фильтр), `offset` (по умолч. 0), `limit` (по умолч. 20, макс. 100) |
| `GET` | `/api/v1/plants/{id}` | Одно растение |
| `POST` | `/api/v1/plants` | Создать растение (без расписания полива) |
| `PUT` | `/api/v1/plants/{id}` | Обновить растение. PATCH-семантика: обновляются только переданные поля (`name`, `notes`, `locationId`) |
| `DELETE` | `/api/v1/plants/{id}` | Soft-delete: выставляет `archivedAt`, из выборок не возвращается |

### Locations

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/api/v1/locations` | Список всех локаций пользователя |
| `GET` | `/api/v1/locations/{id}` | Одна локация |
| `POST` | `/api/v1/locations` | Создать локацию |
| `PUT` | `/api/v1/locations/{id}` | Обновить имя и/или emoji |
| `DELETE` | `/api/v1/locations/{id}` | Удалить локацию. Если в ней есть растения, обязателен параметр `targetLocationId` — растения переносятся в указанную локацию перед удалением |

### Формат ошибок

Все ошибки `/api/**` возвращаются в едином формате:

```json
{
  "error": {
    "code": "NOT_FOUND",
    "message": "Resource not found",
    "details": null
  }
}
```

Поле `details` заполняется при ошибках валидации (`VALIDATION_ERROR`) — содержит список `[{"field": "...", "message": "..."}]`.

## REST API

Публичные эндпоинты, аутентификация не требуется.

### Справочник видов растений

| Метод | Путь | Описание |
|-------|------|----------|
| GET | `/api/v1/species` | Список видов с пагинацией |
| GET | `/api/v1/species/{id}` | Детали вида по id |

Параметры `GET /api/v1/species`:

| Параметр | По умолчанию | Описание |
|----------|--------------|----------|
| `q` | `` (пусто) | Поиск по подстроке; если пустой — возвращаются все |
| `page` | `0` | Номер страницы (0-based) |
| `size` | `20` | Размер страницы; максимум 100 |

Ответ `GET /api/v1/species`:
```json
{
  "content": [
    {
      "id": 1,
      "name": "Монстера",
      "latinName": "Monstera deliciosa",
      "wateringDays": 7,
      "mistingDays": 3,
      "fertilizingDays": 14,
      "soilCheckDays": null,
      "careDifficulty": "EASY",
      "lightPreference": "INDIRECT"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 30,
  "totalPages": 2
}
```

Ответ `GET /api/v1/species/{id}` — те же поля плюс `description`. При отсутствии записи — `404`.

### Типы ухода

`GET /api/v1/care-types` — статический список типов без пагинации:

```json
[
  { "code": "WATERING",     "displayName": "Полив" },
  { "code": "MISTING",      "displayName": "Опрыскивание" },
  { "code": "FERTILIZING",  "displayName": "Подкормка" },
  { "code": "SOIL_CHECK",   "displayName": "Проверка грунта" }
]
```

Ответы кешируются на стороне сервера (TTL 1 час, Caffeine). Сброс кеша происходит автоматически при изменении данных через админку.

## Admin Panel

Админка живёт на `/admin/**`. Логин/пароль из env-переменных:

```bash
ADMIN_USERNAME=admin
ADMIN_PASSWORD_BCRYPT_HASH=<bcrypt hash>
```

Plain password в env запрещён — только bcrypt-хеш cost=12. Сгенерировать:

```bash
htpasswd -bnBC 12 "" 'mypass' | tr -d ':\n'
```

Результат (`$2y$12$...`) кладётся в `ADMIN_PASSWORD_BCRYPT_HASH` (без префикса `{bcrypt}` — он добавляется в коде).

Если переменные не заданы — приложение стартует, но `/admin/**` отдаёт `503 Admin panel is disabled`. Это сознательно: в локалке часто нет смысла настраивать админку.

На Railway добавь переменные через Variables. Для prod также установи `COOKIE_SECURE=true`, чтобы session cookie ставился только по HTTPS.

Rate limit: 5 неудачных попыток в минуту с IP → 429 на минуту. CSRF включён по умолчанию для всех POST/PUT/DELETE в `/admin/**`.

Откат миграций — пересоздание БД (`docker compose down -v && docker compose up -d`).
Для пет-проекта это нормально, для прода понадобятся undo-миграции.

## Observability

### Prometheus scrape endpoint (issue #115)

`GET /actuator/prometheus` — экспорт бизнес- и инфраструктурных метрик в формате Prometheus. Включён всегда (см. `management.endpoints.web.exposure.include` в `application.yml`).

Доступ:

- **Prod** (заданы `ADMIN_USERNAME` и `ADMIN_PASSWORD_BCRYPT_HASH`) — HTTP Basic с теми же credentials, что и админ-панель, роль `ADMIN`. Prometheus / Grafana Cloud конфигурируется с этими же значениями.
- **Dev** (admin-credentials не заданы) — без аутентификации, через default security chain. Достаточно `curl http://localhost:8080/actuator/prometheus`.

CSRF и session для этого chain выключены: scrape — machine-to-machine GET, STATELESS.

### Экспортируемые бизнес-метрики

Имена записаны в точечной нотации Micrometer. Prometheus-registry автоматически конвертирует `.` в `_` и добавляет суффикс `_total` для счётчиков.

| Метрика | Тип | Теги | Описание |
|---|---|---|---|
| `notifications.sent` | Counter | `channel`, `task_type` | Успешно отправленные одиночные уведомления |
| `notifications.failed` | Counter | `channel`, `reason` (`rate_limit` / `api_error` / `blocked` / `other`) | Неудачные отправки |
| `notifications.digest.sent` | Counter | — | Отправленные дайджесты (issue #50, сгруппированные пуши) |
| `telegram.api.errors` | Counter | `code` (`429` / `400` / `403` / `other`) | Ошибки Telegram Bot API |
| `callbacks.processed` | Counter | `action`, `outcome` (`ok` / `error` / `idempotent`) | Обработанные callback-кнопки. Неизвестные `action` подменяются на `unknown` (защита от cardinality explosion) |
| `users.registered.total` | Counter | — | Реальные новые регистрации (не каждый `/start`) |
| `users.active.dau` | Gauge | — | Уникальные пользователи с care-event за последние 24h. Обновляется раз в час (`DauMetricsUpdater`) |
| `scheduler.tick.duration` | Timer | — | Длительность тика шедулера уведомлений; публикует процентильную гистограмму |

Дополнительно Micrometer публикует стандартные JVM-метрики (память, GC, потоки) и HTTP-latency по REST-эндпоинтам — без отдельной конфигурации.

Полный контракт имён и тегов — в `MetricsService` (константы и enum'ы `FailureReason` / `TelegramErrorCode` / `CallbackOutcome`). Whitelist допустимых `action`-тегов для `callbacks.processed` лежит в `MetricsService.KNOWN_CALLBACK_ACTIONS`; при добавлении нового callback-action в хендлерах его надо туда же.

Настройка самого Prometheus-сервера, Grafana-дашбордов и алертов — вне scope этого PR.

## Деплой на Railway

### Первичная настройка

1. Зарегистрируйся на [railway.com](https://railway.com), привяжи GitHub
2. **New Project → Deploy from GitHub repo** → выбрать `antonkupreychik/plants-care`
3. **Add → Database → PostgreSQL** — добавить Postgres в этот же проект
4. Открыть настройки сервиса приложения → **Variables**:
   - Скопировать `Reference` для `${{Postgres.PGHOST}}`, `${{Postgres.PGPORT}}`, `${{Postgres.PGDATABASE}}`, `${{Postgres.PGUSER}}`, `${{Postgres.PGPASSWORD}}` — Railway сам подставит реальные значения
   - Добавить `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_BOT_ENABLED=true` — когда дойдём до интеграции с ботом
   - Добавить `CALENDAR_BASE_URL` — внешний https-URL приложения (например, `https://plants-care-production.up.railway.app`). Используется для построения ссылок `/calendar/{token}.ics`. Дефолт `https://example.com` для прода не годится.
5. Включить **Public Networking** в Settings → получишь URL вида `plants-care-production.up.railway.app`
6. Дождаться завершения первой сборки

Railway увидит `railway.toml` и `Dockerfile` — соберёт и задеплоит автоматически.

### Дальнейшие деплои

Каждый push в `main` запускает автоматический деплой. Railway:
1. Ждёт зелёного CI (если настроены branch protection)
2. Собирает Docker-образ
3. Делает rolling update: новая версия стартует параллельно со старой, healthcheck должен вернуть 200, потом старая останавливается

Логи доступны в UI Railway или через `railway logs` (CLI).

### Откат

В UI Railway → Deployments → выбрать предыдущий деплой → **Redeploy**.

## Структура проекта

```
src/
├── main/
│   ├── java/com/plantcare/bot/
│   └── resources/
│       ├── application.yml        # Базовая конфигурация
│       ├── application-dev.yml    # Профиль dev (verbose SQL)
│       ├── application-prod.yml   # Профиль prod
│       └── db/migration/          # Flyway миграции
└── test/
    └── java/com/plantcare/bot/

Dockerfile              # Multi-stage сборка production-образа
docker-compose.yml      # Postgres + опционально приложение (профиль "full")
.dockerignore
railway.toml            # Конфигурация деплоя на Railway
```

## Roadmap

См. документацию проекта в Notion. Текущий этап — MVP (этап 1).

## Разработка

- Создавай feature-ветки: `feature/<task-id>-<short-desc>`
- Каждая задача — отдельный PR
- CI должен быть зелёным до мёрджа
- Деплой на Railway — автоматический после мёрджа в `main`

## CI

GitHub Actions запускает на каждый push/PR:
1. **Test & Build** — `mvn clean verify` (компиляция + юнит и интеграционные тесты)
2. **Build Docker image** — проверка, что образ собирается (с кэшем GHA)

## Лицензия

Pet-проект, без лицензии.
