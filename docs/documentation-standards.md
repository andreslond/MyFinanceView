# Documentation Standards — MyFinanceView

> How docs in this repo are organized, what each surface is for, and how to keep them in sync. Brief on purpose.

---

## 1. The Surfaces

| Surface | Path | Owns | When to edit |
|---|---|---|---|
| **North star** | `SPEC.md` | Vision, stack, key decisions | Architectural decision changes; stack changes |
| **Standards** | `docs/*.md` | Detailed *how* per concern | When a convention is added/refined |
| **Plans** | `plans/*.md` | Per-feature technical plan | When an in-flight feature reaches a milestone |
| **Backlog** | [Notion page](https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57) | Épicas, tareas, DoD | Continuously |
| **Per-change artifacts** | `openspec/changes/<id>/` | Proposal, design, specs, tasks | Per `/opsx:propose` invocation |
| **Capability specs** | `openspec/specs/<capability>/` | Canonical capability requirements | After `/opsx:archive` merges deltas |
| **Memory** | `~/.claude/projects/{slug}/memory/` | AI session-persistent facts | When decisions are made worth remembering |
| **Archive** | `archive/*` | Historical docs (OBSOLETE banner) | Only to update the banner |

## 2. Priority When Sources Conflict

```
SPEC.md > docs/*.md > plans/*.md > openspec/changes/ > Notion > archive/
```

If you can't reconcile a conflict locally, flag it to the human and pick the higher-priority source.

## 3. What Goes Where (decision matrix)

| Question | Goes in |
|---|---|
| What stack do we use? | `SPEC.md` |
| How exactly do I configure Spring Security? | `docs/backend-standards.md` |
| How do I run local Postgres? | `docs/development-guide.md` |
| What does the `savings_goals` table look like? | `docs/data-model.md` (canonical schema) |
| Why are we adding savings goals? | `plans/savings-goals-plan.md` (feature plan) |
| What's the DoD for TASK-SG-BE-04? | Notion (tarjeta) |
| Step-by-step changes for "Add GET /savings-goals"? | `openspec/changes/add-get-savings-goals/` |
| User's role preferences | Memory (`user_collaboration.md`) |

## 4. Writing Rules

1. **Edit before create.** A new file is a debt; add only if a fundamentally new content type emerges.
2. **One purpose per doc.** If `backend-standards.md` is growing security-flavored sections, consider splitting into `security-standards.md` only when ≥ 200 lines are clearly that topic.
3. **No prose duplication.** Cross-link, don't restate. A line in `data-model.md` should not also appear in `backend-standards.md`.
4. **Examples over abstraction.** If a rule needs a code snippet to be clear, include the snippet.
5. **Date or version stale claims.** "362 transactions at 2026-05-11" is more useful than "many transactions."
6. **Cite specifics.** "Use `BigDecimal` with `HALF_EVEN`" > "Use proper rounding."

## 5. Comments in Code

This is also documentation. Rules:

- **Comments for WHY, not WHAT.** Code names already say what.
- **No comments for known framework behavior.** "@Autowired injects the bean" is noise.
- **Stale comments are worse than no comments.** Delete or fix during the change that invalidated them.
- **TODOs include a date and reason.** `// TODO 2026-05-13 — confirm payment_day for Black Bancolombia` not `// TODO`.

## 6. Update Cadence

| Trigger | What to update |
|---|---|
| `/opsx:archive` runs | `/update-docs` skill walks SPEC.md, docs/, Notion |
| Schema migration applied | `docs/data-model.md` |
| New convention agreed in conversation | `docs/{relevant}.md` immediately, plus memory entry |
| New module added | `docs/backend-standards.md §2` |
| New skill or agent added | Reference from `CLAUDE.md` if project-scoped |

## 7. AI-Assisted Edits

When Claude (or another AI) edits a doc:
- Co-authorship line on the commit.
- Edit the canonical source; don't duplicate the change in adjacent files.
- After edits, `/update-docs` to surface any contradictions introduced.

## 8. Files NOT to Treat as Docs

- `target/`, `node_modules/`, generated sources — outputs, not inputs.
- `.env*` files — config, not documentation.
- `pom.xml`, `package.json` — build manifests; mention but don't duplicate their info in docs.
- Anything under `archive/` — historical; if a future reader cites them, point to current source.
