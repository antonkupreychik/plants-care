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
- Метрики: http://localhost:8080/actuator/metrics

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

Откат миграций — пересоздание БД (`docker compose down -v && docker compose up -d`).
Для пет-проекта это нормально, для прода понадобятся undo-миграции.

## Деплой на Railway

### Первичная настройка

1. Зарегистрируйся на [railway.com](https://railway.com), привяжи GitHub
2. **New Project → Deploy from GitHub repo** → выбрать `antonkupreychik/plants-care`
3. **Add → Database → PostgreSQL** — добавить Postgres в этот же проект
4. Открыть настройки сервиса приложения → **Variables**:
   - Скопировать `Reference` для `${{Postgres.PGHOST}}`, `${{Postgres.PGPORT}}`, `${{Postgres.PGDATABASE}}`, `${{Postgres.PGUSER}}`, `${{Postgres.PGPASSWORD}}` — Railway сам подставит реальные значения
   - Добавить `TELEGRAM_BOT_TOKEN`, `TELEGRAM_BOT_USERNAME`, `TELEGRAM_BOT_ENABLED=true` — когда дойдём до интеграции с ботом
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
