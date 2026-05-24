# Plant Care — Mobile (Flutter)

> **СТАТУС: СКЕЛЕТ.** Это заготовка грунтовочного документа для Flutter-агентов (аналог
> backend-овского CLAUDE.md). Значения помечены `[PROPOSED]` — это дефолты 2026,
> которые надо подтвердить через `flutter-architect` и зафиксировать как MADR.
> Пока документ не заполнен и каркас не собран — НЕ запускать фича-агентов.

Мобильный клиент к существующему Spring Boot бэкенду. Второй клиент рядом с Telegram-ботом.
Backend, доменная модель, шедулер — переиспользуются (см. backend CLAUDE.md и ADR-001…010).

---

## Стек `[PROPOSED — подтвердить версии на pub.dev]`

- **Flutter SDK:** <текущая стабильная, проверить> · **Dart:** соответствующая
- **State management:** Riverpod 3.x (+ riverpod_generator, hooks_riverpod при необходимости)
- **Архитектура:** Clean Architecture + MVVM (data → domain → presentation)
- **Навигация:** go_router
- **Сеть:** dio + клиент, сгенерированный из OpenAPI-спеки backend
- **Модели:** freezed + json_serializable
- **Локальное хранилище / offline:** drift (SQLite)
- **DI:** через Riverpod-провайдеры
- **Codegen:** build_runner (riverpod, drift, freezed, json_serializable)
- **Тема:** Material 3, единый ThemeData
- **Тесты:** flutter_test, mocktail, integration_test
- **Flavors:** dev / prod
- **CI/CD:** <открытый вопрос: Codemagic / GitHub Actions — решить>
- **Локализация:** <открытый вопрос: ru-only / ru+en с первого дня — решить>

Точные версии пакетов — в `pubspec.yaml`. Перед использованием API пакета проверяй его версию там, не полагайся на память.

---

## Структура проекта `[PROPOSED]`

Feature-first:

```
lib/
├── core/                 # общее: api client, router, theme, errors, utils
│   ├── network/
│   ├── router/
│   ├── theme/
│   ├── storage/          # drift database
│   └── errors/
├── features/
│   └── <feature>/
│       ├── data/         # репозитории (impl), DTO, маппинг, локальные источники
│       ├── domain/       # модели, use cases, интерфейсы репозиториев
│       └── presentation/ # Notifiers (state), экраны, виджеты
└── main_dev.dart / main_prod.dart   # entrypoints на flavor
```

Фича не импортит из presentation другой фичи. Общее — только через `core/`.

---

## Конвенции слоёв `[заполнить после MADR]`

### Data
- Репозиторий реализует интерфейс из domain.
- DTO (из OpenAPI) отдельно от domain-моделей. Маппинг DTO ↔ domain — явный.
- Offline: запись с client-generated UUID + updated_at, sync last-write-wins по updated_at (как backend).

### Domain
- Чистый Dart, без Flutter-импортов, без Riverpod.
- Модели — freezed, иммутабельные.
- Use case — один публичный метод, одна ответственность.

### Presentation
- State через Riverpod Notifier/AsyncNotifier.
- Виджет не лезет в репозиторий напрямую — только через провайдер.
- Никакой бизнес-логики в виджетах.

---

## Правила state (Riverpod) `[заполнить после MADR]`

- AsyncNotifier для асинхронных данных (API/БД).
- Никакого глобального мутабельного состояния вне провайдеров.
- ref.watch в build, ref.read в колбэках.
- Провайдеры с codegen (`@riverpod`), не ручные StateProvider где можно.

---

## Правила виджетов `[заполнить после MADR]`

- Декомпозиция: экран → секции → атомарные виджеты. Виджет > ~80 строк — дроби.
- const-конструкторы везде, где можно (Impeller + меньше rebuild'ов).
- Стили из темы, не хардкод цветов/размеров по месту.
- Списки — ленивые (ListView.builder), не Column в SingleChildScrollView для длинных списков.

---

## Время и таймзоны (КРИТИЧНО, как на backend)

- Сервер хранит UTC. Клиент показывает в таймзоне юзера.
- Не использовать `DateTime.now()` напрямую в логике — инжектить через провайдер Clock (тестируемость).
- Напоминания/расписания приходят с backend уже посчитанными — клиент не пересчитывает интервалы сам.

---

## Тестирование `[заполнить после MADR]`

- Domain (use cases) — unit, без Flutter.
- Data (репозитории, маппинг) — unit + drift in-memory.
- Presentation (Notifiers) — unit с overridden провайдерами.
- Экраны — widget-тесты.
- Ключевые флоу (онбординг, добавление растения) — integration_test.
- Перед «готово»: `flutter analyze` чисто + `flutter test` зелёное.

---

## Git / PR

- Ветка: `feature/<issue>-<kebab>` (как на backend).
- 1 issue = 1 PR, `Closes #N`.
- CI зелёный до ревью. Мержит человек.
- Mobile-issues лейблятся `mobile`.

---

## Чего НЕ делать

- Не запускать фича-агентов, пока этот документ не заполнен и каркас не собран.
- Не хардкодить API URL — через flavor + dart-define.
- Не коммитить ключи (APNs, RevenueCat, OAuth client secrets) — env/secure storage.
- Не тащить пакеты без обоснования; проверять, что пакет живой (последний релиз, support null safety, поддержка текущей SDK).
- Не смешивать слои (Flutter-импорт в domain = отказ ревью).
- Не апать мажорные версии пакетов без отдельной задачи.
- Не использовать deprecated-виджеты и Skia-специфичные хаки (Impeller дефолтный).
```
