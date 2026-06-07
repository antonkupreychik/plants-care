export const meta = {
  name: 'auto-implement',
  description: 'Батч issue с лейблом auto-impl для backend (plants-care): пул воркеров с ограничением параллелизма и дозагрузкой очереди. Заменяет неограниченный fan-out из ship-issue.',
  whenToUse: 'Когда нужно разгрести очередь auto-impl в plants-care с кэпом и backfill. Запуск: «начни работу», «auto-implement», или из /ship-issue без аргумента при нескольких issue.',
  phases: [
    { title: 'Queue' },
    { title: 'Dispatch' },
  ],
}

// ── Конфиг ───────────────────────────────────────────────────────────────────
const REPO = 'antonkupreychik/plants-care'
const DIR = '/Users/astonuser/plant/plants-care'   // корень проекта (на случай запуска из другой cwd)
const BASE = 'develop'
const DEFAULT_BRANCH = 'main'                       // PR в develop ≠ default → Closes #N сам issue НЕ закроет
const LABEL = 'auto-impl'
const GUIDE = '.claude/commands/ship-issue.md'   // источник правды по пайплайну
const CONVENTIONS = 'CLAUDE.md'
const GREEN = './mvnw verify'

// args: { cap?: 3, maxIssues?: number, emptyPollsBeforeStop?: 1, implementModel?: string }
const cfg = {
  cap: (args && args.cap) || 3,                                  // сколько issue в работе одновременно
  maxIssues: (args && args.maxIssues) || Infinity,
  emptyPollsBeforeStop: (args && args.emptyPollsBeforeStop) || 1,
  // Модель агентов-реализаторов. По умолчанию наследует сессию (Opus 4.8 — важная работа).
  // 'claude-sonnet-4-6' — прогнать очередь дешевле.
  implementModel: (args && args.implementModel) || undefined,
}

const FETCH_SCHEMA = {
  type: 'object',
  required: ['issues'],
  properties: {
    issues: {
      type: 'array',
      items: {
        type: 'object',
        required: ['number', 'title'],
        properties: {
          number: { type: 'integer' },
          title: { type: 'string' },
          body: { type: 'string' },
        },
      },
    },
  },
}

const RESULT_SCHEMA = {
  type: 'object',
  required: ['status', 'summary'],
  properties: {
    status: { type: 'string', enum: ['pr', 'skipped', 'failed'] },
    prUrl: { type: 'string' },
    reason: { type: 'string' },
    summary: { type: 'string' },
  },
}

async function fetchIssues() {
  const res = await agent(
    `Выполни ровно эту команду и верни результат как JSON:
gh issue list --repo ${REPO} --label ${LABEL} --state open --json number,title,body --limit 50

Верни ТОЛЬКО объект {"issues":[{"number":N,"title":"...","body":"..."}]}. Пусто => {"issues":[]}.`,
    { label: 'fetch:auto-impl', phase: 'Queue', schema: FETCH_SCHEMA, model: 'claude-haiku-4-5-20251001' }
  )
  return (res && res.issues) || []
}

function implementPrompt(issue) {
  return `Ты автономный backend-агент проекта plants-care (Java 21 / Spring Boot). Доводишь одну issue до PR.

GitHub: ${REPO} | базовая ветка: ${BASE}

## Issue к реализации
#${issue.number}: ${issue.title}
${issue.body || '(описание не заполнено)'}

## Как работать
1. Работай в каталоге проекта ${DIR} (\`cd ${DIR}\`). Все git/gh/сборочные команды — оттуда или из созданного worktree.
2. **Идемпотентность (проверь ПЕРВЫМ делом).** PR в ${BASE} НЕ закрывает issue, т.к. default-ветка репо — ${DEFAULT_BRANCH}; эта issue могла уже обрабатываться:
   - открытый PR: \`gh pr list --repo ${REPO} --state open --search "${issue.number} in:title,body"\`
   - ветка/worktree: \`git branch --list "feature/${issue.number}-*"\` и каталог \`../plants-care-${issue.number}\`
   - Если открытый PR на #${issue.number} уже есть → status "skipped", reason "PR уже открыт", второй НЕ создавай.
   - Если осталась ветка/worktree от прошлого прогона без PR → подчисти (\`git worktree remove --force ../plants-care-${issue.number}\`, при необходимости \`git branch -D feature/${issue.number}-*\`) перед стартом.
3. Прочитай гайд пайплайна: ${GUIDE} — следуй ему ТОЧНО от «Предусловия» до «Финал». Worktree/ветку создаёт **шаг 1 гайда** (\`git worktree add ../plants-care-${issue.number} ...\`) — не дублируй \`git worktree add\` сам.
4. Прочитай конвенции: ${CONVENTIONS} — жёсткие правила стека (Flyway forward-only, таймзоны/Clock, инъекция через конструктор, без апгрейдов версий и правок Dockerfile/railway).
5. Доведи сборку до зелёного — ${GREEN} (именно verify, не test) — открой PR в ${BASE} с «Closes #${issue.number}» и включи auto-merge: \`gh pr merge <PR> --auto --squash --delete-branch\`.
   Если команда упала (в репо не включён «Allow auto-merge») — НЕ считай это успехом мержа: оставь PR открытым и укажи это в reason. PR руками НЕ мержить.

## Стоп-условия — ПРОПУСКАЙ, не блокируйся
Любое стоп-условие из гайда (размытые AC; нужна новая зависимость в pom.xml; правка Dockerfile/railway/compose; апгрейд Spring Boot/Java/Postgres; архитектурный выбор не из issue; 3 итерации coder↔test или 2 после ревью не помогли):
- НЕ спрашивай пользователя, НЕ зависай.
- Прокомментируй issue блокером: gh issue comment ${issue.number} --repo ${REPO} --body "<что заблокировало>"
- Оставь issue открытой (разберёт человек), НЕ создавай мусорный PR.
- Верни status "skipped" с reason и иди дальше.

## Что вернуть (структурно)
status: "pr" если PR создан, "skipped" если заблокирован стоп-условием ИЛИ PR на issue уже был, "failed" если неожиданно сломалось.
prUrl (если есть), reason (для skipped/failed; для pr — если auto-merge не включился), summary (1-3 строки).`
}

// ── Phase 1: очередь ─────────────────────────────────────────────────────────
phase('Queue')
log(`Репозиторий ${REPO} | лейбл ${LABEL} | кэп ${cfg.cap} одновременно`)

const queue = []
const seen = new Set()
for (const it of await fetchIssues()) {
  if (!seen.has(it.number)) { seen.add(it.number); queue.push(it) }
}

if (!queue.length) {
  log(`Очередь ${LABEL} пуста. Нечего запускать.`)
  return { dispatched: [], note: 'no auto-impl issues' }
}
log(`В очереди ${queue.length}: ${queue.map(i => `#${i.number}`).join(', ')}`)

// ── Phase 2: пул воркеров с дозагрузкой ──────────────────────────────────────
phase('Dispatch')

const done = []
let started = 0
let emptyPolls = 0
let pollPromise = null

function refill() {
  if (!pollPromise) {
    pollPromise = (async () => {
      const fresh = await fetchIssues()
      let added = 0
      for (const it of fresh) {
        if (!seen.has(it.number)) { seen.add(it.number); queue.push(it); added++ }
      }
      if (added) { emptyPolls = 0; log(`Дозагрузка очереди: +${added} новых issue`) }
      else { emptyPolls++ }
      pollPromise = null
      return added
    })()
  }
  return pollPromise
}

async function worker(wid) {
  const mine = []
  while (started < cfg.maxIssues) {
    let item = queue.shift()
    if (!item) {
      await refill()                       // освободился — пробуем подхватить новые
      item = queue.shift()
      if (!item) {
        if (emptyPolls >= cfg.emptyPollsBeforeStop) break
        continue
      }
    }
    started++
    log(`воркер ${wid} → #${item.number} «${item.title}»`)
    let r
    try {
      r = await agent(implementPrompt(item), {
        label: `#${item.number}`,
        phase: 'Dispatch',
        schema: RESULT_SCHEMA,
        ...(cfg.implementModel ? { model: cfg.implementModel } : {}),
      })
    } catch (e) {
      r = { status: 'failed', reason: String(e).slice(0, 300), summary: 'агент упал' }
    }
    done.push({ number: item.number, title: item.title, ...r })
    mine.push(item.number)
    log(`готово #${item.number}: ${r.status}${r.prUrl ? ' ' + r.prUrl : ''}`)
  }
  return mine
}

await parallel(Array.from({ length: cfg.cap }, (_, i) => () => worker(i + 1)))

// ── Сводка ───────────────────────────────────────────────────────────────────
const byStatus = { pr: [], skipped: [], failed: [] }
for (const d of done) (byStatus[d.status] || (byStatus[d.status] = [])).push(d)

log(`Итог: PR ${byStatus.pr.length} | пропущено ${byStatus.skipped.length} | сбой ${byStatus.failed.length} (всего ${done.length})`)
for (const d of done) log(`  #${d.number} [${d.status}] ${d.prUrl || d.reason || ''} — ${d.summary}`)

return {
  total: done.length,
  prs: byStatus.pr.map(d => ({ number: d.number, url: d.prUrl })),
  skipped: byStatus.skipped.map(d => ({ number: d.number, reason: d.reason })),
  failed: byStatus.failed.map(d => ({ number: d.number, reason: d.reason })),
  done,
}
