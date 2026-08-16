# Plants Care Bot

Telegram-бот для напоминаний об уходе за домашними растениями.
**Java 21 + Spring Boot 3.5 + PostgreSQL 16 + Flyway + Testcontainers.**

Это pet-проект, но боевой — деплоится на Railway, ходит в реальный Telegram API.
Любая ошибка в шедулере/таймзонах приведёт к промахам напоминаний у живых пользователей.

---

## Стек и его границы (строго)

- **Java 21**. Используй язык по версии: `record`, sealed-классы, pattern matching, `var` в локальных, текстовые блоки. Виртуальные потоки — только осознанно, не на каждый сервис.
- **Spring Boot 3.5.x** (актуальная версия в `pom.xml` — 3.5.14). Namespace — `jakarta.*`, не `javax.*`. Не апать минор/мажор без отдельной задачи.
- **PostgreSQL 16**. Используются JSONB и `tsvector` (full-text). Не ломай это упрощением до `text`.
- **Flyway** — единственный способ менять схему. Никаких `ddl-auto: update`.
- **Hibernate / Spring Data JPA**. Entity — class, DTO — record.
- **Testcontainers** для интеграционных тестов. Reuse включён глобально, не отключай.
- **Maven** через wrapper (`./mvnw`), не системный `mvn`.
- **Docker + Railway** для деплоя. `Dockerfile` и `railway.toml` — не трогать без необходимости, поломка = красный прод.

Запрещено добавлять зависимости без обоснования в PR. Особенно: новые ORM, новые тест-фреймворки, Spring Cloud, Lombok-альтернативы.

---

## Команды разработчика

```bash
./mvnw test           # юниты, быстро
./mvnw verify         # юниты + интеграционные (Testcontainers). Это и есть «зелёное».
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
docker compose up -d                          # только Postgres
docker compose --profile full up -d --build   # всё в Docker
```

Перед заявкой «готово» всегда гоняй `./mvnw verify`. Не `test`, а именно `verify`.

---

## Структура и конвенции кода

### Пакеты
- `com.plantcare.bot.domain` — entity, value objects
- `com.plantcare.bot.repository` — Spring Data репозитории
- `com.plantcare.bot.service` — бизнес-логика
- `com.plantcare.bot.telegram` — хендлеры Telegram, апдейты, клавиатуры
- `com.plantcare.bot.scheduler` — крон-задачи, отправка напоминаний
- `com.plantcare.bot.config` — `@Configuration` и `@ConfigurationProperties`
- `com.plantcare.bot.web` — REST для мобильного клиента, actuator-расширения, healthcheck-эндпоинты, **SDUI-композиция** (`GET /api/v1/ui/*`)

**Telegram-слой не смешивать с бизнес-логикой.** Хендлер парсит апдейт → дёргает сервис → формирует ответ. Никаких репозиториев в хендлерах.

### Стиль
- **Конструкторная инъекция через `@RequiredArgsConstructor`**. Поле `@Autowired` — отказ ревью.
- **DTO — `record`**, entity — class с `@Entity`.
- **Lombok**: `@RequiredArgsConstructor`, `@Slf4j`, `@Getter` на entity. **Запрещены**: `@Data`, `@AllArgsConstructor`, `@Builder` на entity.
- **Configuration через `@ConfigurationProperties`**, не `@Value` россыпью.
- **Валидация — Bean Validation 3** (`jakarta.validation.constraints.*`) + `@Valid` на входе.
- Логирование через `@Slf4j`. SLF4J параметризованный (`log.info("x={}", x)`), не конкатенация.
- Исключения — свои бизнес-исключения, `@RestControllerAdvice` для маппинга. Голый `throw new RuntimeException` — отказ ревью.
- Контроллеры тонкие, тяжёлая логика в сервисах.

### Транзакции
- `@Transactional` ставится на сервисном слое, не на репозитории и не на контроллере.
- Read-only операции: `@Transactional(readOnly = true)`.
- Никаких вызовов внешних API (Telegram) внутри открытой транзакции к БД.

### Время и таймзоны (КРИТИЧНО для шедулера напоминаний)
- В БД храним только `TIMESTAMPTZ` (UTC).
- В коде — `Instant` и `OffsetDateTime`. `LocalDateTime` — только если явно «время в таймзоне пользователя» и эта таймзона рядом.
- Каждое напоминание считается в таймзоне пользователя (поле `users.timezone`).
- Тесты на расписания обязаны проверять минимум один кейс с не-UTC таймзоной (Asia/Almaty, Europe/Moscow и т.п.).

---

## Server-Driven UI для мобильного клиента (MADR-015 + MADR-016, Accepted)

Мобильный клиент (`plants-care-mobile`) переходит на **гибридный block-SDUI** с **опаковым
Map-контрактом** (MADR-016): read-heavy экраны собираются из блоков, которые описывает сервер.
Эндпоинт композиции — `GET /api/v1/ui/{screen}` (например, `GET /api/v1/ui/home`) в пакете
`web`/`api.v1`, возвращает **опаковый объект** `{ screenId, version, blocks[] }` (`Map<String,
Object>` / `ObjectNode`, НЕ типизированный DTO). Путь под `/api/v1/**` обязателен — там
действует JWT-цепочка `ApiSecurityConfig` (та же идентификация, что у `/today`, `/plants`).

**Композиция живёт в таблице `ui_views`** (`category, screen, layout_json JSONB,
min_catalog_version`): какие блоки, в каком порядке, статичные пропсы. Сменить состав/порядок/
видимость блоков или выкатить статичный контент = **UPDATE строки**, без релиза и правки кода.

Правила для backend-агента:
- **Контроллер `/ui/{screen}` тонкий:** читает строку `ui_views`, **гидрирует** динамические
  блоки данными из существующих сервисов (`weather`/`today`/`locations`/`plants`) на лету,
  отдаёт **опаковый** JSON. Статичные блоки (`banner`/`card`) — verbatim из `layout_json`.
  Не дублируй бизнес-логику; композицию НЕ хардкодь в коде — она в таблице.
- **Словарь блоков — ПРОЗА, не OpenAPI-схема.** Форма блоков описана в каталоге
  `plants-care-mobile/docs/adr/MADR-016-sdui-opaque-map.md`. Ответ опаковый — типизированных
  block-схем в спеке нет. Новый блок-тип: **сперва в прозовый словарь (MADR-016) → backend
  начинает слать → client учится парсить.** Не изобретай блок-типы в обход словаря.
- **Версионирование обязательно:** эндпоинт принимает `X-UI-Catalog-Version` и **не включает**
  блоки с `minCatalogVersion` выше присланной версии (forward-compat: старый клиент не получает
  незнакомых блоков).
- **Нормализация типов:** `type` задач/действий в блоках — care-словарь `WATER`/`SPRAY`/
  `FERTILIZE`/`SOIL_CHECK` (маппить из доменного `WATERING/…` на бэкенде).
- **Действия** в блоках — декларативный `ActionDescriptor` (`{ kind, method, path,
  payloadTemplate }`), ссылающийся на существующие эндпоинты (`POST /care-events`). Не плодить
  спец-эндпоинты под SDUI. Интерактив с локальным state у клиента нативный — блоку action не нужен.
- Если для блока нет данных ни в одном текущем сервисе — это отдельная фича, не тащить в SDUI.
- Детали — `plants-care-mobile/docs/adr/MADR-015-sdui.md` (база) + `MADR-016-sdui-opaque-map.md`
  (актуальная форма контракта).

---

## Flyway — правила без исключений

- Только **forward-миграции**. Никогда не редактируй применённый `V*.sql`.
- Файлы: `src/main/resources/db/migration/V{N+1}__{snake_case_description}.sql`.
- Каждая миграция — атомарная и обратимая логически (даже если undo не пишем).
- **Backward-совместимость для прода**:
  - Добавление NOT NULL колонки = 3 шага: добавь nullable → задеплой → бэкфил → отдельная миграция NOT NULL.
  - Переименование колонки = добавь новую → дублируй запись → миграция кода → дроп старой через релиз.
- Сидинг данных — отдельная миграция, не смешивать с DDL.
- Индексы под новые запросы добавляй в той же миграции, что новое поле, если запросы тоже в этом PR.
- После любой миграции — `./mvnw verify` обязан быть зелёным.

---

## Тесты

- **JUnit 5** + **AssertJ**. Hamcrest и `assertEquals` — не нужны, есть AssertJ.
- Репозитории — `@DataJpaTest` + Testcontainers Postgres, **не H2** (диалекты не совпадают, JSONB/tsvector сломаются).
- Сервисы — обычный `@SpringBootTest` или unit с Mockito, в зависимости от связности.
- Контроллеры — `@WebMvcTest` + `MockMvc`.
- Шедулер — отдельный тест с подменой `Clock` (бин `Clock` должен быть инжектируемым, не `Instant.now()` россыпью).
- Именование: `should_<behaviour>_when_<condition>`.
- AAA: arrange / act / assert разделены пустой строкой.
- Тест на happy + минимум один на edge (граница таймзоны, пустой ввод, отсутствие записи в БД).
- **Не мокать репозитории, если есть Testcontainers.** Моки только для внешних API (Telegram).

---

## Git / PR конвенции

- Ветка: `feature/<issue-number>-<short-kebab>`. Пример: `feature/29-rate-limiting`.
- 1 issue = 1 PR.
- В описании PR — обязательно `Closes #<N>`.
- Коммиты осмысленные. WIP-коммиты раздавить через `git rebase -i` перед PR.
- CI (`mvn clean verify` + docker build) обязан быть зелёным до запроса ревью.
- **Мержит только человек.** Агент создаёт PR и останавливается.
  - Исключение: для issue с лейблом `auto-impl` агент после `gh pr create` выполняет `gh pr merge --auto --squash --delete-branch` — это не немедленный мерж, а флаг для GitHub автоматически слить ветку когда CI позеленеет.

---

## Чего НЕ делать без явного указания

- Не апать мажорные/минорные версии (Spring Boot, Java, Postgres, Hibernate).
- Не редактировать `Dockerfile`, `docker-compose.yml`, `railway.toml`.
- Не отключать тесты, не помечать `@Disabled`, не добавлять `-DskipTests`.
- Не коммитить секреты, токены, `.env` (даже пример с реальным токеном).
- Не удалять и не редактировать применённые Flyway-миграции.
- Не использовать `ddl-auto` кроме `validate`.
- Не добавлять зависимости в `pom.xml` без явной просьбы и обоснования.
- Не делать `git push --force` в `main`. Вообще не пушить в `main` напрямую.
- Не закрывать issue без PR.

---

## Где смотреть контекст по задаче

- Issue (`gh issue view <N>`) — acceptance criteria.
- README — общая картина и команды.
- `pom.xml` — точные версии зависимостей перед использованием API.
- Существующий код — паттерны (как уже сделана аналогичная фича).
