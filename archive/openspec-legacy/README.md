# OpenSpec legacy (archivado — NO autoritativo)

Este directorio es el **archivo histórico** del harness OpenSpec que el
proyecto usó hasta 2026-06-25, cuando se reemplazó por el **harness Uncle
Bob** (ver `AGENTS.md`, `docs/uncle-bob/`).

Contenido preservado tal cual estaba bajo `openspec/`:

- `changes/archive/` — los 5 cambios completados (proposals, designs, tasks,
  progress, specs delta y notas de adversarial-review). Valor: trazabilidad
  de decisiones de diseño reales.
- `specs/` — las 5 specs canónicas por capacidad (`backend-rest-api`,
  `backend-runtime`, `database-backups`, `frontend-mvp`,
  `harness-progress-tracking`).
- `templates/` — plantillas del flujo OpenSpec (`progress-template.md`,
  `supabase-write-checklist.md`).

## Reglas de uso

- **NO es autoritativo.** La autoridad de diseño vigente vive en `SPEC.md`,
  `docs/` y `plans/`. Si algo aquí contradice esas fuentes, ganan ellas.
- Se conserva como **referencia histórica** (por qué se tomaron ciertas
  decisiones, qué se revisó). No lo cites como guía de cómo trabajar hoy.
- El flujo de trabajo actual es el harness Uncle Bob: conversación → Gherkin
  → TDD → review → mutación. Ver `AGENTS.md` y `docs/uncle-bob/workflow.md`.

Los comandos `/opsx:*`, los skills `openspec-*` y `scripts/preflight.ps1`
fueron eliminados en la misma migración: ya no existen en el repo.
