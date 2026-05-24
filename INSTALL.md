# Конфигурация Claude Code для plants-care

Полный набор: 14 агентов, 7 команд, грунтовочные документы. Три слоя — backend,
продуктовый, mobile.

## Установка

Из корня репо `plants-care/`:

```bash
# Скопировать файлы в корень репо (из распакованного архива)
cp -r plants-care-config/CLAUDE.md  ./
cp -r plants-care-config/FLOW.md    ./
cp -r plants-care-config/FLUTTER.md ./
cp -r plants-care-config/.claude    ./

# settings.local.json — персональный, НЕ коммитить
echo ".claude/settings.local.json" >> .gitignore

git add CLAUDE.md FLOW.md FLUTTER.md .claude/ .gitignore
git commit -m "chore: add Claude Code agents, commands and conventions"
```

## Что подключить перед первым запуском

1. **GitHub MCP** (или `gh` CLI):
   ```bash
   claude mcp add --transport http github https://api.githubcopilot.com/mcp/
   gh auth status   # проверь, что gh авторизован
   ```
   При OAuth дай доступ только к репозиторию plants-care, не ко всем.

2. **Notion MCP** — уже подключён (агенты читают Roadmap/ADR/Parking lot).

3. **Testcontainers reuse** (для backend-тестов):
   ```bash
   echo 'testcontainers.reuse.enable=true' >> ~/.testcontainers.properties
   ```

4. **devcontainer** — если хочешь гонять агентов с `--dangerously-skip-permissions`,
   делай это только внутри контейнера, не на хосте.

## Состав

```
.claude/
├── settings.json              # права (коммитим)
├── settings.local.json        # локальные оверрайды (в .gitignore)
├── agents/
│   ├── spring-coder.md         ┐
│   ├── flyway-migrator.md      │ backend
│   ├── test-writer.md          │ (Spring Boot 3.3)
│   ├── reviewer.md             │
│   ├── docs-writer.md          ┘
│   ├── task-generator.md       ┐
│   ├── adr-designer.md         │ продуктовый
│   ├── backlog-analyst.md      ┘ (Notion → задачи/ADR)
│   ├── flutter-architect.md    ┐
│   ├── flutter-coder.md        │
│   ├── ui-builder.md           │ mobile
│   ├── flutter-test-writer.md  │ (Flutter)
│   ├── flutter-reviewer.md     │
│   └── flutter-docs-writer.md  ┘
└── commands/
    ├── plan-issue.md           ┐ backend
    ├── ship-issue.md           │
    ├── review-pr.md            ┘
    ├── sync-check.md           ┐ продуктовый
    ├── promote-idea.md         │
    ├── design-feature.md       ┘
    └── ship-mobile-feature.md    mobile

CLAUDE.md    # грунтовка backend-агентов
FLUTTER.md   # грунтовка mobile-агентов (скелет, заполняется после flutter-architect)
FLOW.md      # как продуктовый слой связан с исполнением
```

## Порядок работы по слоям

### Backend (готов к работе)
1. `/plan-issue N` — разобрать задачу, уточнить AC.
2. `/ship-issue N` — полный цикл до PR. Мержишь сам.
3. `/review-pr N` — ревью существующего PR.

Начни с одной P3-задачи без `/ship-issue` (делегируй агентов вручную), откалибруй
промпты — особенно `reviewer`, его почти всегда надо делать злее. Потом автономно.

### Продуктовый (готов к работе)
1. `/sync-check` — первым делом, увидишь дрейф Notion ↔ GitHub.
2. `/design-feature X` — решение + ADR для новой фичи.
3. `/promote-idea X` — идея из Parking lot → GitHub issue.

Цикл новой фичи: `/design-feature` → `/promote-idea` → `/ship-issue` → мерж → `/sync-check`.

### Mobile (фундамент есть, каркаса ещё нет)
**`/ship-mobile-feature` НЕ запустится, пока:**
1. Не прогнан `flutter-architect` (решения стека → MADR + заполненный FLUTTER.md).
2. Не собран каркас руками (flutter create, структура, dio+AuthInterceptor, go_router,
   тема, OpenAPI-клиент, CI) — human-driven, не автономно.
3. `flutter analyze` + `flutter test` зелёные на «hello world».

Только после этого — `/ship-mobile-feature N`. Первую фичу бери простую
(read-only «список растений»), не онбординг. Auth пока через dev-слот, экран
логина не делается.

## Безопасность (зашито в settings.json)

- `deny`: push в main, force-push, `pr merge`, правка Dockerfile/railway.toml/
  workflows/применённых миграций.
- `ask`: создание issue, запись в Notion, правка pom.xml, новые миграции.
- Мержит PR всегда человек. Агенты доводят до «готово к ревью».

## Оптимизация стоимости (опционально)

Можно задать разные тиры Claude по ролям через `model:` во frontmatter агента:
критичные (coder, reviewer, architect) — Sonnet/Opus, тривиальные (docs-writer) —
Haiku. Это срежет стоимость без потери качества.

## Что обновлять регулярно

- **CLAUDE.md / FLUTTER.md** — добавляй паттерны из успешных PR. Главный рычаг качества.
- **reviewer.md / flutter-reviewer.md** — добавляй классы багов, которые ревью пропустил.
- Subagent-промпты не трогай слишком часто — каждое изменение меняет поведение.
