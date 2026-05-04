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

## Быстрый старт

### Что нужно установить

- JDK 21 ([Temurin](https://adoptium.net/) или другой)
- Docker и Docker Compose
- Maven (или используй включённый wrapper `./mvnw`)

### Вариант 1: разработка локально (приложение через ./mvnw, БД в Docker)

Удобно, когда часто меняешь код — IDE подхватит изменения, не надо пересобирать образ.

```bash
# 1. Скопировать пример переменных окружения
cp .env.example .env

# 2. Поднять только PostgreSQL
docker compose up -d

# 3. Запустить приложение в dev-профиле
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

### Вариант 2: всё в Docker (включая приложение)

Удобно для проверки production-сборки или для деплоя.

```bash
# Запустить приложение и БД вместе
docker compose --profile full up -d --build

# Посмотреть логи
docker compose --profile full logs -f app

# Остановить
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

### Только Docker-образ

Если нужно собрать образ без запуска:

```bash
docker build -t plants-care:local .
docker run --rm -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/plants_care \
  -e DB_USERNAME=plants_care \
  -e DB_PASSWORD=plants_care \
  plants-care:local
```

## Структура проекта

```
src/
├── main/
│   ├── java/com/plantcare/bot/   # Java-исходники
│   └── resources/
│       ├── application.yml        # Базовая конфигурация
│       ├── application-dev.yml    # Профиль dev (verbose SQL)
│       ├── application-prod.yml   # Профиль prod
│       └── db/migration/          # Flyway миграции
└── test/
    └── java/com/plantcare/bot/   # Тесты

Dockerfile              # Multi-stage сборка production-образа
docker-compose.yml      # Postgres + опционально приложение (профиль "full")
.dockerignore           # Что НЕ тащить в Docker build context
```

## Roadmap

См. документацию проекта в Notion. Текущий этап — MVP (этап 1).

## Разработка

- Создавай feature-ветки: `feature/<task-id>-<short-desc>`
- Каждая задача — отдельный PR
- CI должен быть зелёным до мёрджа

## CI

GitHub Actions запускает на каждый push/PR:
1. **Test & Build** — `mvn clean verify` (компиляция + юнит и интеграционные тесты)
2. **Build Docker image** — проверка, что образ собирается (с кэшем GHA)

## Лицензия

Pet-проект, без лицензии.
