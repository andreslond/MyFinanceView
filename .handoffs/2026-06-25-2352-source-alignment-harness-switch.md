---
created: 2026-06-25T23:52:28Z
branch: main
worktree: main checkout
mode: paused
---

# Alineación de fuentes + preparación para cambio de harness

## Next step
Esperar el nuevo plan del operador; si se retoma el desarrollo del backend en este PC, correr jOOQ codegen (Docker/DB) antes de `mvn compile` y/o decidir el target de deploy del backend (TASK-BT-01).

## Goal
Dejar Engram, MEMORY.md, docs y Notion alineados con la realidad verificada del repo, y `main` estable, antes de un cambio de harness.

## Done this session
- Detectado y corregido un error grave: la primera reconciliación se hizo sobre un `main` local **36 commits desactualizado**; afirmaba lo contrario a la realidad.
- Re-sincronizado `main` local a `origin/main` (estado real) + cherry-pick de la skill `my-finance-view-design` (commit `df33af0`). Ahead 1 / behind 0, sin push.
- Estado **verificado** contra `origin/main` (2026-06-08) y Supabase en vivo (`akkoqdjmmozyqdfjkabg`): backend REST read-only IMPLEMENTADO (controllers Account/Category/Transaction + health, ~125 tests, archivado); V001–V005 aplicadas (V004 display_name 19 cats, V005 tabla merchants con 0 filas); backups nightly+pre-op LIVE; frontend MVP LIVE en Vercel.
- Corregidos: Engram #43/#15/#14, `MEMORY.md` + memoria de categorías, nueva memoria backend+jOOQ, bloque de sync en Notion.

## Working tree state
- **Committed (this session, on main, NOT pushed):** `df33af0 feat(skills): add my-finance-view-design skill`; (+ este handoff cuando se commitee).
- **Staged / Unstaged:** ninguno.
- **Untracked:** este handoff hasta commitearlo.
- **Red build:** `mvn compile` FALLA en este PC con `package com.myfinanceview.jooq.generated does not exist` — jOOQ codegen no ha corrido aquí (entorno, no regresión).

## Pending
- Migraciones: TASK-DB-02 installments · DB-03 cut/payment_day + get_billing_period · DB-04 **seed** de merchants · DB-05 smart-cat fields · savings_goals.
- Deploy backend: TASK-BT-01 target sin decidir · BT-03 deploy auto · BT-06 monitor · BT-07 smoke · BT-08 `docs/deployment.md`.
- Decidir si pushear `main` (1 commit local adelante de origin).
- `docs/data-model.md` tiene doc-lag (dice V004 "pending" aunque está aplicada) — corregir en el repo cuando toque.

## Blockers
- jOOQ codegen pendiente en este PC para compilar el backend localmente (requiere Docker/DB).
- cut_day/payment_day Black Bancolombia sin confirmar (TASK-DB-03).

## Non-obvious context
- **Verificar siempre `git fetch` antes de evaluar estado**: el checkout local estaba 36 commits atrás y llevó a conclusiones invertidas. `origin/main` es la verdad; complementar con consulta directa a Supabase para el estado de migraciones.
- El backend NO compila en frío sin jOOQ codegen — un `mvn compile` rojo aquí NO significa backend roto.
- La nota adversarial-review NO se versionó aparte: ya vive en `openspec/changes/archive/2026-06-08-backend-mvp-readonly/notes/` (+ una `-v2`).
- `main` debe quedar limpio (proyecto multi-agente); estos commits están en local sin pushear, a la espera del nuevo plan.
