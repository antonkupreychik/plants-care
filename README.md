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
- **V20** — добавляет `plants.acquired_at DATE NULL` — дата, когда юзер завёл растение. Используется шедулером годовщин и строкой «С тобой с …» в карточке. NULL = в годовщинах не участвует
- **V21** — таблица `plant_anniversaries_sent` (PK `(plant_id, anniversary_year)`) для идемпотентности годовщин: один пуш на растение в год. FK `ON DELETE CASCADE`. Здесь же — частичный индекс `idx_plants_acquired_active` на `plants(acquired_at) WHERE acquired_at IS NOT NULL AND archived_at IS NULL` под запрос шедулера
- **V22** — таблица `species_facts` (энциклопедия видов, ADR-011): кураторские факты по видам с категориями `ORIGIN`/`CARE`/`TOXICITY`/`CURIOSITY` (CHECK), `title`/`body`/`source`/`display_order`. FK на `species` `ON DELETE CASCADE`, btree-индекс `(species_id, category, display_order)`. GIN-индекс намеренно не создаётся (обоснование в комментарии миграции). Сидинг — отдельной задачей
- **V23** — таблица `monthly_report_sent` (PK `(user_id, year_month)`) для идемпотентности месячных отчётов: одна отправка на юзера за отчётный месяц. `year_month` хранится как `YYYYMM` по календарю в TZ юзера. FK `ON DELETE CASCADE` на `users`
- **V24** — таблица `diseases` (справочник типичных болезней/вредителей, ADR-013): `name` (уникальное), `latin_name` (nullable), `symptoms`/`treatment`/`prevention`, `symptom_codes` (CSV кодов enum `DiagnosisSymptom` для матчинга с диагностикой #73) и `search_tags`. Expression-GIN индекс `idx_diseases_search` по `to_tsvector('simple', name || search_tags || symptoms)` для полнотекстового поиска (по образцу `species`). Сидинг — отдельной миграцией V25
- **V25** — сидинг 20 типичных проблем в `diseases`: вредители, грибковые/бактериальные болезни, неинфекционные нарушения
- **V26** — три nullable `BOOLEAN`-колонки `toxic_to_cats` / `toxic_to_dogs` / `toxic_to_humans` на `species`. Тройное состояние значимо: `true` — токсично, `false` — безопасно, `NULL` — нет данных. Детальная карточка растения показывает бейдж «⚠️ токсично для …» только для флагов `true`; при `false`/`NULL` блок отсутствует
- **V27** — таблица `shopping_items` (issue #136): персональный список покупок пользователя, по строке на позицию. `title` (до 160 символов), `checked` (DEFAULT false). FK `ON DELETE CASCADE` на `users`, btree-индекс `(user_id, checked)` под выборку списка с разбивкой по статусу
- **V28** — добавляет `plants.parent_id BIGINT NULL` — self-FK на `plants(id)` для родословной растений (ADR-012, issue #139). Растение-черенок ссылается на материнское; NULL = корень родословной. FK `ON DELETE SET NULL` (удаление родителя обрывает связь, потомок остаётся). btree-индекс `idx_plants_parent_id` под выборку потомков по родителю. Карточка показывает «🌱 Потомки: N» у родителя и кликабельную ссылку «⬅️ Родитель: …» у потомка
- **V29** — сидинг энциклопедических фактов в `species_facts` (`ORIGIN`/`CARE`/`TOXICITY`/`CURIOSITY`) и простановка флагов токсичности `species.toxic_to_*` для топ-видов по популярности (issue #132). Это сидинг, обещанный в V22 (#129), и данные под флаги из V26 (#130). Чистый сид без DDL. Привязка к виду по `latin_name`, не по id. Идемпотентен: факты — `INSERT ... WHERE NOT EXISTS` по `(species_id, category, body)`, токсичность — `UPDATE`. Виды без достоверных данных ASPCA остаются с `NULL` («нет данных»)
- **V30** — таблица `transplant_supply_suggestions` (issue #141): трекер идемпотентности подсказок расходников перед пересадкой. По строке на `(user_id, plant_id, source_event_id)`, где `source_event_id` — id записи TRANSPLANT в `plant_events`, от которой посчитана предстоящая пересадка. UNIQUE `(user_id, plant_id, source_event_id)` гарантирует один пуш на одно предстоящее событие (новая пересадка → новый `source_event_id` → можно подсказать снова). `status` (`SUGGESTED`/`ADDED`/`DISMISSED`, CHECK), `predicted_transplant_at`. FK на `users`/`plants`/`plant_events` `ON DELETE CASCADE`. Индексы `(user_id, status)` и по обоим FK
- **V34** — добавляет `care_schedules.amount_ml INT NULL` (issue #185): объём полива в миллилитрах для расписаний типа `WATERING`. NULL = объём не задан; для остальных типов колонка не используется

## REST API

Идентификация пользователя во всех `/api/v1/**` эндпоинтах (кроме `/health`) — заголовок `X-Chat-Id: <telegramChatId>`.

### Инфраструктура

| Путь | Метод | Описание |
|---|---|---|
| `/api/v1/health` | `GET` | Liveness probe — возвращает `{"status":"UP"}` |
| `/swagger-ui.html` | — | Swagger UI (OpenAPI 3) |
| `/v3/api-docs` | — | OpenAPI JSON |

### Auth (issue #178)

Управление сессией. `logout`/`logout-all` требуют bearer-токен (остальные `/api/v1/auth/**` — публичные).

| Метод | Путь | Статус ответа | Описание |
|---|---|---|---|
| `POST` | `/api/v1/auth/logout` | 204 | Отзывает предъявленный refresh-токен (тело: `{ "refreshToken" }`). Идемпотентен и толерантен: повторный вызов, невалидный/просроченный/чужой токен — всё равно 204 |
| `POST` | `/api/v1/auth/logout-all` | 204 | Инвалидирует **все** refresh-токены пользователя через эпоху `users.tokens_valid_from = now` |

После `logout-all` refresh-токен, чья секунда выпуска (`iat`) предшествует `tokens_valid_from`, отклоняется при ротации с `TOKEN_REVOKED`. Токены, выданные до появления фичи (эпоха `null`, без claim `tvf`), остаются валидными.

### События ухода (issue #86)

| Путь | Метод | Статус ответа | Описание |
|---|---|---|---|
| `/api/v1/care-events` | `POST` | 201 | Регистрация ухода (WATER/SPRAY/FERTILIZE). Поле `clientId` обеспечивает идемпотентность: повторный запрос с тем же `clientId` возвращает существующую запись без дублирования |
| `/api/v1/care-events/{id}` | `DELETE` | 204 | Отмена события через compensation pattern: запись не удаляется физически, создаётся компенсирующая запись |
| `/api/v1/plants/{id}/history` | `GET` | 200 | История ухода за растением с offset-пагинацией. Query params: `limit` [1–100], default 20; `offset`, default 0. Проверяет ownership растения |
| `/api/v1/today` | `GET` | 200 | Задачи ухода на сегодня в таймзоне пользователя |
| `/api/v1/calendar` | `GET` | 200 | Расписание за произвольный период. Query params: `from`, `to` (ISO date). Диапазон не более 60 дней. Дни без задач в ответ не включаются |
| `/api/v1/calendar/progress` | `GET` | 200 | Прогресс выполнения по дням за период: карта `{ "YYYY-MM-DD": { "planned", "done" } }`, где `planned` — запланированные occurrence'ы расписаний, `done` — выполненные (не отменённые) записи ухода за этот локальный день в таймзоне пользователя. Query params: `from`, `to` (ISO date), диапазон не более 60 дней. Дни без планов и без выполнений не включаются |
| `/api/v1/stats/streak` | `GET` | 200 | Текущий стрик растения (последовательные выполнения без пропусков). Query param: `plantId` |
| `/api/v1/reports/monthly` | `GET` | 200 | Сводный отчёт по уходу за месяц текущего пользователя: `done`, `overdue` (выполнено с опозданием), `byType` (по всем типам ухода), `streak`, `healthTrend` (понедельные ISO-бакеты качества). Query param `month` обязателен, формат `YYYY-MM`. Границы месяца считаются в таймзоне пользователя. Невалидный `month` → 400 |

### Формат ошибок

### Plants

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/api/v1/plants` | Список растений. Параметры: `locationId` (фильтр), `offset` (по умолч. 0), `limit` (по умолч. 20, макс. 100) |
| `GET` | `/api/v1/plants/{id}` | Одно растение |
| `POST` | `/api/v1/plants` | Создать растение (без расписания полива) |
| `PUT` | `/api/v1/plants/{id}` | Обновить растение. PATCH-семантика: обновляются только переданные поля (`name`, `notes`, `locationId`) |
| `DELETE` | `/api/v1/plants/{id}` | Soft-delete: выставляет `archivedAt`, из выборок не возвращается |

Оба `GET`-эндпоинта включают в каждый объект `PlantDto` поля здоровья (issue #207):

| Поле | Тип | Описание |
|---|---|---|
| `healthScore` | `integer\|null` | Балл здоровья 0–100. `null` при недостаточных данных |
| `healthZone` | `GREEN\|YELLOW\|RED\|null` | Цветовая зона. `null` при недостаточных данных |
| `healthInsufficientData` | `boolean\|null` | `true` — менее 3 активных записей ухода, `score`/`zone` равны `null` |

При создании растения через REST засеваются все четыре расписания ухода (`WATERING`/`MISTING`/`FERTILIZING`/`SOIL_CHECK`) из дефолтов вида; включён по умолчанию только `WATERING`.

### Schedules (issue #185)

Расписания ухода конкретного растения. У каждого растения ровно четыре расписания по числу типов ухода. Все эндпоинты user-scoped (JWT).

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/api/v1/plants/{id}/schedules` | Все четыре расписания в фиксированном порядке. Если расписание ещё не настроено — отдаётся дефолтный интервал вида с `enabled=false` |
| `PUT` | `/api/v1/plants/{id}/schedules/{type}` | Создать/обновить расписание типа `{type}`; пересчитывает `nextDueAt` |

`type` ∈ `WATERING` / `MISTING` / `FERTILIZING` / `SOIL_CHECK`. Тело `PUT`: `{ "every", "unit", "amountMl?", "enabled" }`. Элемент ответа:

```json
{ "type": "WATERING", "every": 7, "unit": "DAY", "amountMl": 250, "enabled": true, "nextDueAt": "2026-06-05T08:00:00Z" }
```

`unit` сейчас всегда `DAY`. `amountMl` осмыслен только для `WATERING` (для остальных типов игнорируется). `nextDueAt` (UTC) заполнен только при `enabled=true`, иначе `null`.

### Locations

| Метод | Путь | Описание |
|---|---|---|
| `GET` | `/api/v1/locations` | Список всех локаций пользователя |
| `GET` | `/api/v1/locations/{id}` | Одна локация |
| `POST` | `/api/v1/locations` | Создать локацию |
| `PUT` | `/api/v1/locations/{id}` | Обновить имя и/или emoji |
| `DELETE` | `/api/v1/locations/{id}` | Удалить локацию. Если в ней есть растения, обязателен параметр `targetLocationId` — растения переносятся в указанную локацию перед удалением |

### Shopping (issue #196)

Персональный список покупок пользователя. Все эндпоинты user-scoped.

| Метод | Путь | Статус ответа | Описание |
|---|---|---|---|
| `GET` | `/api/v1/shopping` | 200 | Список позиций: `{ "items": [{ "id", "title", "checked", "createdAt" }] }` |
| `POST` | `/api/v1/shopping` | 201 | Добавить позицию. Тело: `{ "title" }` |
| `PATCH` | `/api/v1/shopping/{id}` | 200 | Частичное обновление: передаются только изменяемые поля (`checked`, `title`) |
| `DELETE` | `/api/v1/shopping/{id}` | 204 | Удалить позицию |

### Me (issue #182, расширено #180)

Профиль и настройки текущего пользователя. Скоуп — аутентифицированный пользователь.

| Метод | Путь | Статус ответа | Описание |
|---|---|---|---|
| `GET` | `/api/v1/me` | 200 | Профиль: `id`, `email`, `emailVerified`, `createdAt` (UTC), имя, аватар, счётчики для хедера (`plantsTotal`, `tasksToday`, `notificationsUnread`), настройки (`quietHoursStart`, `quietHoursEnd`, `timezone`, `locale`, `seasonalEnabled`, `seasonalMode`, `weatherEnabled`), `featureFlags` и привязки входа (`appleLinked`, `googleLinked`, `emailLinked`, `telegramLinked`). `tasksToday` — число невыполненных задач на сегодня в таймзоне пользователя. `avatar` — плейсхолдер (всегда `null`, колонки в схеме нет); `notificationsUnread` — плейсхолдер (всегда `0` до фида уведомлений, issue #183) |
| `PATCH` | `/api/v1/me` | 200 | Частичное обновление настроек (`quietHoursStart`, `quietHoursEnd`, `timezone`, `locale`, `seasonalEnabled`, `seasonalMode`, `weatherEnabled`): меняются только переданные поля. Смена `timezone` пересчитывает активные расписания с сохранением локального времени дня. Невалидный IANA-`timezone` → 400. Совпадение тихих часов (`quietHoursStart == quietHoursEnd`, с учётом текущего значения, если передано только одно поле) → 400 |
### Notifications (issue #183)

Персистентный inbox уведомлений пользователя для мобильного клиента. Все эндпоинты user-scoped. Наполнение ленты добавят отдельные issue — текущая реализация даёт только чтение и отметку прочтения.

| Метод | Путь | Статус ответа | Описание |
|---|---|---|---|
| `GET` | `/api/v1/notifications` | 200 | Лента уведомлений (новые сверху, `createdAt` DESC) и счётчик непрочитанных `unreadCount`. Offset-пагинация: `limit` [1–100], default 20; `offset` ≥ 0, default 0. Значение вне диапазона → 400. Тип уведомления (`care`/`alert`/`award`/`report`/`system`) и опциональный `plantId` для deep-link |
| `POST` | `/api/v1/notifications/{id}/read` | 204 | Идемпотентная отметка прочтения: повторный вызов — no-op, момент первого прочтения не перезаписывается. Чужое или несуществующее уведомление → 404 |

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
      "lightPreference": "INDIRECT",
      "toxicToCats": true,
      "toxicToDogs": true,
      "toxicToHumans": null
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 30,
  "totalPages": 2
}
```

Флаги `toxicToCats` / `toxicToDogs` / `toxicToHumans` — тройное состояние: `true` токсично, `false` безопасно, `null` данных нет.

Ответ `GET /api/v1/species/{id}` — те же поля плюс `description` и `facts[]` — энциклопедические факты вида (ADR-011), отсортированные по категории, затем по `display_order`; пустой массив, если фактов нет. Каждый факт: `category` (`ORIGIN`/`CARE`/`TOXICITY`/`CURIOSITY`), `body`, опциональные `title` и `source`. При отсутствии записи — `404`.

Поиск `q` в `GET /api/v1/species` дополнительно находит вид по совпадению в тексте его фактов (title/body), не только по имени и тегам.

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

### Grafana dashboard

Преднастроенный дашборд для импорта: [`grafana/plants-care-dashboard.json`](grafana/plants-care-dashboard.json).

**Содержимое:** 16 панелей в 5 секциях (Users, Notifications, Callbacks, Scheduler, опционально JVM/HTTP).

**Локальный стек одной командой (рекомендуется для разработки):**
```bash
docker compose --profile monitoring up -d
# затем (если app не в Docker, а через mvn spring-boot:run):
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
Откроет:
- Prometheus — http://localhost:9090 (таргеты: Status → Targets)
- Grafana — http://localhost:3000 (без логина, дашборд сразу в «Plants Care → Plants Care Bot — Business Metrics»)

Если app тоже хочется в Docker — добавь профиль `full`:
```bash
docker compose --profile full --profile monitoring up -d
```

**Ручной импорт (если Grafana уже где-то развёрнута):**
1. В Grafana: **Dashboards → New → Import → Upload JSON file**, выбрать `grafana/plants-care-dashboard.json`.
2. В выпадающем списке `Prometheus data source` (переменная вверху дашборда) выбрать твой Prometheus.
3. Save.

**Тонкости:**
- Дашборд работает с Prometheus-style именами метрик (`notifications_sent_total` и т.п.) — стандартное поведение `micrometer-registry-prometheus`.
- Процентили `scheduler.tick.duration` (p50/p95/p99) считаются через `histogram_quantile` поверх buckets — публикация histogram включена программно (`Timer.builder(...).publishPercentileHistogram()` в `MetricsService`), доп. конфиг в `application.yml` не нужен.
- Секция JVM & HTTP свёрнута по умолчанию — это default Micrometer-метрики, всегда доступны.
- Алерты в дашборде не зашиты. Прометей-side rule'ы (например, на `failure_rate > 5%` или `telegram_api_errors{code="429"} > N`) лучше держать отдельно.

Настройка самого Prometheus-сервера и алертов — вне scope этого PR.

### Sentry — агрегация ошибок (issue #114)

Необработанные исключения и проглоченные `catch` в шедулерах и Telegram-диспетчере
отправляются в Sentry через `sentry-spring-boot-starter-jakarta`.

Включён **только на prod** (`application-prod.yml`); на dev/test выключен (no-op), чтобы
не тратить квоту и не слать события из тестов. Даже на prod, если `SENTRY_DSN` не задан,
стартер инициализируется в no-op и ничего не отправляет.

PII не уходит в Sentry: `SentryPiiFilter` (`BeforeSendCallback`) хеширует `chat_id` и
`telegram_user_id`, маскирует имена растений, заметки и параметры/breadcrumbs, чистит
данные пользователя. `send-default-pii: false` на всех профилях.

События размечаются тегами `layer` (`telegram` / `scheduler` / `admin` / `weather`) и
`feature` (имя класса) для фильтрации в Sentry UI.

| Переменная | Дефолт | Где | Обязательная |
|---|---|---|---|
| `SENTRY_DSN` | пусто | prod | нет — пустой DSN = Sentry no-op |

### Очередь исходящих сообщений (issue #29)

Массовые отправки шедулеров (напоминания, дайджесты, годовщины, месячные отчёты,
акклиматизация, отпуск, фото-прогресс) идут не напрямую в Telegram API, а через
`RateLimitedTelegramSender`: in-memory bounded-очередь, которую дренирует один
daemon-поток со скоростью ≤ `permits-per-second` (token bucket, запас под лимит
Telegram ~30/sec). На 429 поток ждёт `retry_after` и повторяет до `max-retries`
раз; при переполнении очереди сообщение дропается с warn (метрика
`notifications.failed{reason=other}`), а не блокирует шедулер.

Интерактивные хендлеры команд/колбэков и `AdminBroadcastService` (со своим
троттлингом) через эту очередь намеренно НЕ ходят.

Конфигурация — `telegram.rate-limit` в `application.yml`:

| Переменная | Дефолт | Описание |
|---|---|---|
| `TELEGRAM_RATE_LIMIT_PERMITS_PER_SECOND` | `25` | Сколько отправок в секунду разрешает token bucket |
| `TELEGRAM_RATE_LIMIT_QUEUE_CAPACITY` | `10000` | Глубина очереди; при переполнении сообщение дропается |
| `TELEGRAM_RATE_LIMIT_MAX_RETRIES` | `3` | Сколько раз повторять отправку при 429 перед признанием неудачи |
| `TELEGRAM_RATE_LIMIT_DEFAULT_RETRY_AFTER_SECONDS` | `1` | Fallback retry-after, если 429 не содержит распарсиваемого значения |
| `TELEGRAM_RATE_LIMIT_MAX_RETRY_AFTER_SECONDS` | `60` | Потолок для `retry_after` из 429 — чтобы одно сообщение не заблокировало очередь на часы |

## Подсказка расходников перед пересадкой (issue #141)

Раз в день шедулер (cron `0 0 9 * * *` UTC) вычисляет «предстоящую пересадку» как
дату последней записи `TRANSPLANT` в журнале растения (issue #76) плюс `interval-months`.
Если эта дата попадает в окно ближайших `horizon-days` в таймзоне пользователя (и не в
quiet hours), бот шлёт мягкую нотификацию с кнопками «➕ В список покупок» / «Не нужно».
Подсказка появляется только для растений, у которых уже есть `TRANSPLANT` в истории.

Нажатие «➕ В список покупок» добавляет позиции из `supplies` в список покупок (issue #136)
и идемпотентно: повтор по тому же событию не дублирует ни подсказку (UNIQUE
`(user_id, plant_id, source_event_id)`), ни товары.

Конфигурация — `plants.transplant-suggestion` в `application.yml`:

| Переменная | Дефолт | Описание |
|---|---|---|
| `PLANTS_TRANSPLANT_SUGGESTION_ENABLED` | `true` | Мастер-переключатель фичи |
| `PLANTS_TRANSPLANT_SUGGESTION_HORIZON_DAYS` | `14` | За сколько дней до предстоящей пересадки подсказывать |
| `PLANTS_TRANSPLANT_SUGGESTION_INTERVAL_MONTHS` | `12` | Типовой интервал между пересадками |

`supplies` (по умолчанию «Грунт», «Дренаж») и `message-template` задаются списком/строкой
в `application.yml`, env-override для них не предусмотрен.

## Деплой на Railway

### Первичная настройка

1. Зарегистрируйся на [railway.com](https://railway.com), привяжи GitHub
2. **New Project → Deploy from GitHub repo** → выбрать `antonkupreychik/plants-care`
3. **Add → Database → PostgreSQL** — добавить Postgres в этот же проект
4. Открыть настройки сервиса приложения → **Variables**:
   - Скопировать `Reference` для `${{Postgres.PGHOST}}`, `${{Postgres.PGPORT}}`, `${{Postgres.PGDATABASE}}`, `${{Postgres.PGUSER}}`, `${{Postgres.PGPASSWORD}}` — Railway сам подставит реальные значения
   - Добавить `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_BOT_ENABLED=true` — когда дойдём до интеграции с ботом
   - Добавить `CALENDAR_BASE_URL` — внешний https-URL приложения (например, `https://plants-care-production.up.railway.app`). Используется для построения ссылок `/calendar/{token}.ics`. Дефолт `https://example.com` для прода не годится.
   - (опционально) Добавить `SENTRY_DSN` — DSN проекта Sentry для агрегации ошибок (см. секцию Observability). Если не задан, Sentry на prod работает в no-op.
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

Это **модульный монолит** (issue #127, ADR-001): одно приложение, но с явными
границами между ядром и слоями доставки. Зависимости текут **только внутрь, к
`core`** — ядро не знает про Telegram-бот, REST API или веб-админку.

```
com.plantcare
├── PlantsCareApplication        # @SpringBootApplication (корень компонент-скана)
├── core      # Ядро: бизнес-логика без деталей доставки
│   ├── domain / repository      # JPA-сущности и Spring Data репозитории
│   ├── service                  # Бизнес-сервисы (delivery-agnostic)
│   ├── diagnosis / weather / seasonal   # Доменные подсистемы
│   ├── metrics / observability  # Метрики, Sentry
│   └── config / beans / util    # Общие @ConfigurationProperties, Clock, TimeZoneEngine
├── bot       # Доставка через Telegram (long polling)
│   ├── telegram / command / state / client
│   ├── beans (PlantsCareBot) / config (TelegramBotConfig, …)
│   └── service                  # Telegram-сервисы: меню, callback, клавиатуры, шедулеры-отправители
├── admin     # Веб-админка на /admin/** (Thymeleaf, form-login)
│   └── config (AdminSecurityConfig), controller, broadcast, dashboard, …
└── api       # REST API на /api/** (stateless, для мобильного клиента)
    ├── v1 / web                 # Контроллеры (рукописные + spec-first)
    ├── generated                # OpenAPI-generator: интерфейсы + модели (build-time)
    └── config (ApiSecurityConfig, OpenApiConfig)
```

**Правило зависимостей** проверяется ArchUnit (`LayeredArchitectureTest`), падает при нарушении:
- `core` не зависит от `bot` / `admin` / `api` и не использует Telegram API (`org.telegram.*`);
- `api` не зависит от `bot` / `admin` (изолированный канал доставки).

`admin` сознательно переиспользует Telegram-клиент `bot` для рассылок — это
общий канал доставки, не нарушение границы ядра.

**Безопасность** — три раздельных `SecurityFilterChain` (а не один с `if`-ами):
`/api/**` stateless (`ApiSecurityConfig`), `/admin/**` session + form-login
(`AdminSecurityConfig`), бот работает вне HTTP.

```
src/
├── main/
│   ├── java/com/plantcare/        # core / bot / admin / api (см. выше)
│   └── resources/
│       ├── application.yml        # Базовая конфигурация
│       ├── application-dev.yml    # Профиль dev (verbose SQL)
│       ├── application-prod.yml   # Профиль prod
│       ├── openapi/openapi.yaml   # Спецификация REST API (spec-first)
│       └── db/migration/          # Flyway миграции
└── test/
    └── java/com/plantcare/        # зеркалит структуру main + architecture/

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
