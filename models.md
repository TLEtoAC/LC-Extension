# models.md — Agent Council model mapping

Factual record of which LLM ran which council role in this repo, versus the pairing recommended in the master plan (Section 31). Update this file when a later phase uses a different model.

**This repo so far:** every Agent Council role that actually executed used **Cursor Grok 4.6** (`model: inherit` from the user-facing parent). The Section 31 Claude / Gemini / GPT-OSS pairing was never applied.

---

## How to read this file

| Column | Meaning |
|---|---|
| Role | Section 31.1 Agent Council persona |
| Typical tasks | What that role owns |
| Plan recommendation | Primary (backup) from `leetcode-contest-monitor-plan.md` §31.1 |
| Actual model this repo has used | What ran. Blank / same as parent = inherit |
| Notes | Why it differs from the plan, or when to change |

---

## Roster

| Role | Typical tasks | Plan recommendation | Actual model this repo has used | Notes |
|---|---|---|---|---|
| Architect | Task breakdown, integration review, phase sign-off, PR slice plan | Claude Opus 4.6 Thinking (backup: Claude Sonnet 4.6 Thinking) | **Cursor Grok 4.6** | User-facing parent in Cursor. Phase 6 plan + Phase 7 coordination. |
| Extension/Background | `tabLifecycleManager.js`, `alarmScheduler.js`, `backendClient.js`, §7A-1 | Claude Sonnet 4.6 Thinking (backup: Gemini 3.1 Pro) | **Cursor Grok 4.6** | Phase 6 scrape loop + Phase 7 interval/ENDED hook. Inherit. |
| Content Script/DOM | `contestPageScript.js`, `problemPageScript.js`, selectors, wait | Gemini 3.1 Pro (backup: GPT-OSS 120B) | **Cursor Grok 4.6** | Phases 3–4 landed before this mapping file. No Gemini run recorded. |
| Backend/Core | `ContestStateService` critical section, ranking, lifecycle | Claude Sonnet 4.6 Thinking (backup: Gemini 3.1 Pro) | **Cursor Grok 4.6** | Phase 6 `applyIngest` + calculation + concurrency tests. Inherit. |
| Parser/Data | `AcceptanceStatsParser`, BigDecimal suffix math | GPT-OSS 120B (backup: Gemini 3.7 Flash) | **Cursor Grok 4.6** | Phase 5 parser. No GPT-OSS run recorded. |
| REST API | Controllers (`config`, `ingest`, `status`, `health`), CORS | GPT-OSS 120B (backup: Gemini 3.5 Flash) | **Cursor Grok 4.6** | Phases 1 / 3b / 4. No GPT-OSS run recorded. |
| UI | side panel, popup, options, backend-unreachable states | Gemini 3.7 Flash (backup: Gemini 3.6 Flash) | **Cursor Grok 4.6** | Options interval UI in Phase 7. Side panel is Phase 10. |
| Testing/QA | JUnit (incl. concurrency) + Jest; optional Playwright E2E | Claude Sonnet 4.6 Thinking (backup: Gemini 3.1 Pro) | **Cursor Grok 4.6** | Phase 6 concurrency + calculation tests. Inherit. |
| Edge-Case Red Team | SW restarts, tab recovery, ingest races, ENDED, login-wall | Claude Opus 4.6 Thinking (backup: Claude Sonnet 4.6 Thinking) | **Cursor Grok 4.6** | Simulated inside the Phase 6 implementer (inherit), not a separate Opus pass. |
| Logging/Docs | Five knowledge artifacts + `models.md` + comment audit | Gemini 3.5 Flash (backup: Gemini 3.6 Flash) | **Cursor Grok 4.6** | Phase 6 docs branch + Phase 7 docs in this session. Inherit. |
| Explore / architecture review | Current-state research before a phase | *(not in §31 roster)* | **Cursor Grok 4.6** | Phase 6 research subagent, `model: inherit`. |

---

## Phase → who ran (this repo)

| Phase | Lead(s) per §31.2 | Reviewer per §31.2 | What actually ran |
|---|---|---|---|
| 1. Backend Skeleton | REST API | Architect | Cursor Grok 4.6 (historical; not this session) |
| 2. Extension Scaffold | Extension/Background | Architect | Cursor Grok 4.6 (historical) |
| 3. Discovery + Config | Content Script/DOM + REST API | Architect | Cursor Grok 4.6 (historical) |
| 4. Single-Q scrape + ingest | Content Script/DOM + Backend/Core | Edge-Case Red Team | Cursor Grok 4.6 (historical) |
| 5. Parser | Parser/Data | Testing/QA | Cursor Grok 4.6 (historical) |
| 6. Four questions + concurrency | Extension/Background + Backend/Core | Edge-Case Red Team | **Cursor Grok 4.6** parent Architect + inherit subagents (explore, implementer covering Backend/Core, Extension/Background, Testing/QA, Logging/Docs, Edge-Case Red Team) |
| 7. Alarm Scheduler | Extension/Background | Architect | **Cursor Grok 4.6** parent Architect + inherit Phase 7 implementer (Extension/Background lead; Architect review) |
| 8. State + Ranking | Backend/Core | Edge-Case Red Team | **Cursor Grok 4.6** parent Architect + inherit implementer (Backend/Core + Testing/QA + Docs + Red Team) |
| 9–11 | See §31.2 | See §31.2 | In progress this session. Default remains Cursor Grok 4.6. |

---

## Phase 6 session (this conversation's parent)

Parent identity: **Cursor Grok 4.6** (user-facing Architect).

All Phase 6 subagents used `model: inherit` → also **Cursor Grok 4.6**:

- Architect — task breakdown, Phase 6 execution plan, PR slice proposal
- Explore / architecture review — Phase 6 current-state research
- Backend/Core + Extension/Background + Testing/QA + Logging/Docs + Edge-Case Red Team — simulated in one implementer

Phase 6 code is on `feat/phase6-backend-ingest`, `feat/phase6-extension-scrape`, `docs/phase6-knowledge-artifacts`. This file does not rewrite those commits.

---

## Phase 7+ (this session)

Also **Cursor Grok 4.6** (`inherit`):

- Architect coordination
- Phase 7 implementer (Extension/Background lead; Architect review)

Branch: `feat/phase7-alarm-polish` (from `feat/phase6-extension-scrape`).

---

## Phase 8–11 (this session)

Also **Cursor Grok 4.6** (`inherit`):

- Architect + Backend/Core + REST/UI + Testing + Docs + Edge-Case Red Team — simulated in one implementer
- Branch: `feat/phases-8-11` (from `phase1` @ `8dbe9f8`)

---

## Going forward

Record a new row (or update "Actual") when a phase uses something other than Cursor Grok 4.6. Do not list a plan model as "Actual" unless that model ran.

Known plan gap (unchanged from §31.1): Architect and Edge-Case Red Team share Opus → Sonnet; Gemini 3.1 Pro is the emergency third option. Unused here.
