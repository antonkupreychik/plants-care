---
argument-hint: <issue-number>
description: Полный цикл Flutter-фичи: ветка → data/domain/state → UI → тесты → ревью → PR. Требует собранного каркаса и заполненного FLUTTER.md. Auth — через dev-слот, реализация логина не делается.
---

Реализуй мобильную фичу из issue #$1 от начала до PR. Я мержу сам.

## Предусловия (проверь, прежде чем начать)

1. **Каркас существует** и `flutter analyze` + `flutter test` зелёные на чистом main. Если каркаса нет — СТОП, его надо собрать сначала (human-driven, не через эту команду).
2. **FLUTTER.md заполнен** (не скелет с [PROPOSED]). Если ещё скелет — СТОП, прогони `flutter-architect` и зафиксируй решения.
3. `gh issue view $1` — issue есть, открыт, с AC. Размыты — СТОП, эскалируй / `/plan-issue`.
4. OpenAPI-клиент сгенерирован и покрывает нужные фиче эндпоинты. Если эндпоинта нет — СТОП, это backend-задача (Java-агенты), не делай клиентский воркэраунд.

## Пайплайн

### 1. Подготовка
- `git worktree add ../plants-care-mobile-$1 -b feature/$1-<slug>` от свежего main.

### 2. Data + Domain + State
- Делегируй **flutter-coder**: domain (модели, use cases) → data (репозитории, маппинг DTO, drift) → presentation-state (Riverpod Notifiers).
- Auth — через `authTokenProvider` (dev-токен), identity не хардкодить.
- Жди отчёт: какие провайдеры экспонированы для UI (сигнатуры), `flutter analyze` + build_runner чисто.

### 3. UI
- Делегируй **ui-builder**: экраны и виджеты, потребляющие провайдеры от flutter-coder.
- Обязательно все состояния: loading / error / empty / data.
- Жди отчёт: какие экраны, как обработаны состояния.

### 4. Цикл fix (coder ↔ ui-builder)
- Если ui-builder сообщил «не хватает состояния/метода» → возврат к flutter-coder.
- **Максимум 3 итерации.** Не сошлось — СТОП, эскалируй.

### 5. Тесты
- Делегируй **flutter-test-writer**: unit (domain/data) + widget (все состояния экрана) + integration (флоу фичи).
- Обязательно: тест с не-UTC таймзоной (если про время), тест auth-слота, offline (если применимо).
- Жди отчёт: `flutter test` зелёное.

### 6. Ревью
- Делегируй **flutter-reviewer**. Он сам прогонит `flutter analyze` + `flutter test` и пройдёт чеклист.
- 🔴 BLOCKING → возврат соответствующему агенту (coder / ui-builder / test-writer).
- **Максимум 2 итерации после ревью.** Не сошлось — СТОП, эскалируй.

### 7. Документация
- Если есть мобильный docs-writer — делегируй обновить README мобилки / комментарии. Если нет — пропусти, отметь что docs не обновлены.

### 8. Финал
- `flutter analyze` чисто + `flutter test` зелёное.
- `git add -A && git commit` осмысленно. Раздави WIP через rebase до 1-3 коммитов.
  Шаблон: `<type>: <short> (#$1)`, type ∈ feat/fix/refactor/test/chore.
- `git push -u origin feature/$1-<slug>`.
- `gh pr create --base main` с телом:
  ```
  Closes #$1
  ## Что сделано
  - <фича, экраны, провайдеры>
  ## Слои
  - domain / data / presentation — что добавлено
  ## Auth
  - через dev-слот (authTokenProvider), реализация логина НЕ входит
  ## Тесты
  - <сценарии: состояния экрана, таймзоны, offline, auth-слот>
  ## Чек
  - [x] flutter analyze чисто
  - [x] flutter test зелёное
  - [x] flutter-reviewer прошёл
  ```

### 9. Отчёт
В чат: ссылка на PR, сколько итераций, что нашёл reviewer, что отложено.

## Стоп-условия (СТОП и эскалация)

- Нет каркаса / FLUTTER.md ещё скелет.
- Нужного эндпоинта нет в OpenAPI (backend-задача).
- 3 итерации coder ↔ ui-builder не помогли.
- 2 итерации после reviewer не помогли.
- Нужен новый пакет в pubspec.
- Возникает архитектурное решение не из issue (→ `flutter-architect` / MADR).
- Фича тянет за собой реализацию реального auth (не входит в текущую фазу).

## Чего не делаешь

- Не мержишь PR. Не пушишь в main.
- Не реализуешь экран логина / Apple-Google-email auth.
- Не хардкодишь identity.
- Не добавляешь пакеты без подтверждения.
- Не удаляешь worktree до мержа.