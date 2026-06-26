# Bitácora del harness

> Append-only. Una entrada por sesión cerrada. Formato libre pero conciso:
> fecha, feature, qué se hizo, resultado de mutación.

---

## 2026-06-25 — Harness Uncle Bob instalado

**Qué se hizo:**
- Se migró el harness de signSystem (`experiment/uncle-bob-harness`) al
  proyecto, adaptándolo de TypeScript/Jest/Stryker a Java/Maven/JUnit/PIT.
- Se retiró el harness anterior de OpenSpec (skills, comandos `/opsx:*`,
  `scripts/preflight.ps1`); el legado se archivó en `archive/openspec-legacy/`.
- Aún no se ha implementado ninguna feature de dominio por este pipeline.

**Resultado mutation_tester:** N/A (sin feature todavía).
