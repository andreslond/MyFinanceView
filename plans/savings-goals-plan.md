# Plan — Metas de Ahorro (Savings Goals)

> **Estado:** Borrador inicial — pendiente de refinamiento con Notion (2026-05-13).
> **Documentos relacionados:** [SPEC.md](../SPEC.md), [database-model-plan.md](./database-model-plan.md), [página Notion del proyecto](https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57).
> **Fecha de creación:** 2026-05-12.

---

## 1. Contexto

MyFinanceView ya cubre el flujo de ingesta automática de transacciones (Gmail → n8n → Supabase), categorización con LLM y dashboards de gasto por corte. El **siguiente hito funcional** es permitir al usuario **planear y dar seguimiento a metas de ahorro personales**.

**Problema que resuelve:**
- Hoy no hay forma en la app de proyectar cuánto se debe ahorrar mes a mes para llegar a un objetivo (ej. "Viaje a Japón – $12M COP – diciembre 2026").
- El usuario quiere saber, mes a mes, cuánto le falta y cuánto debería aportar para cumplir.
- El sistema debe **reaccionar a la realidad** (si un mes se aporta menos, recalcular la cuota faltante; si se aporta de más, ajustar a la baja).

**Resultado esperado:**
- CRUD de metas de ahorro con avatar.
- Cálculo automático de **cuota mensual sugerida**, recalculada al final/inicio de cada periodo según el aporte real.
- Lista priorizada de metas en el dashboard, integrada visualmente con el resto del flujo financiero.

---

## 2. Requerimientos Funcionales

### 2.1 Configuración de una meta
Una meta puede tener una de estas tres formas:

| # | Modo | Campos requeridos | Comportamiento |
|---|---|---|---|
| A | Con monto y fecha | `target_amount`, `target_date` | Calcula `monthly_suggested = (target - saved) / meses_restantes` |
| B | Solo monto | `target_amount` | Track de progreso por % completado; sin cuota mensual sugerida |
| C | Solo fecha (futuro) | `target_date` | Fuera de alcance del MVP — descartar por ahora |

Otros campos:
- `name` — texto libre (ej. "Viaje a Japón", "Fondo de emergencia").
- `description` — opcional.
- `currency` — por defecto la `base_currency` del usuario (`COP`).
- `avatar_kind` — `'default'` o `'custom'`.
- `avatar_default_key` — slug del avatar por defecto seleccionado (ej. `'plane'`, `'house'`, `'piggy'`).
- `avatar_url` — URL en Supabase Storage cuando es custom.
- `status` — `'active' | 'paused' | 'achieved' | 'archived'`.
- `priority` — entero para ordenamiento manual (drag-and-drop futuro).

### 2.2 Aportes (contribuciones)
- Tabla aparte `savings_goal_contributions` con `amount`, `occurred_at`, `note`, `source` (`'manual' | 'auto'`).
- Permite aportes manuales y, a futuro, aportes derivados de transacciones marcadas como "ahorro".
- El **balance acumulado** de una meta = `SUM(contributions.amount)` (no se almacena, se calcula en query — fuente única de verdad).

### 2.3 Recálculo mensual de la cuota sugerida (modo A)
**Regla simple, determinista:**
```
monthly_suggested = max(
  0,
  (target_amount − saved_so_far) / months_remaining_inclusive
)
```
- `months_remaining_inclusive` = número de meses calendario entre la fecha de referencia (hoy) y `target_date`, incluyendo el mes actual si aún no ha cerrado.
- Se recalcula **on-read** (no se persiste): el backend devuelve el valor vigente al momento de la consulta.
- Si la meta se cierra antes de tiempo (aporte único que la completa) → `status = 'achieved'`.
- Si la fecha objetivo pasa sin alcanzar el monto → marcar `at_risk = true` en el DTO pero no cambiar `status` automáticamente (que el usuario decida si extender la fecha o archivar).

### 2.4 Avatar
- **Default:** set de íconos/emojis curados (avión, casa, alcancía, carro, regalo, corazón, libro, etc.) servidos como assets del frontend.
- **Custom:** el usuario sube una foto desde la UI → Supabase Storage (bucket privado `goal-avatars/{user_id}/{goal_id}.{ext}`).
- El backend solo guarda la **referencia** (`avatar_default_key` o `avatar_url`); el upload lo hace el frontend directo a Storage con el JWT del usuario.
- Validaciones del upload (en el frontend, reforzadas en RLS de Storage): JPG/PNG/WebP, máx 2 MB, dimensión recomendada 512×512.

### 2.5 Vistas / UX (a diseñar con Claude Design)
1. **Lista de metas** — tarjetas con avatar, nombre, barra de progreso, % completado, monto faltante, cuota sugerida del mes y días/meses restantes.
2. **Detalle de meta** — historial de aportes, gráfica de progreso, edición y registro rápido de aporte.
3. **Modal de creación/edición** — selector de modo (A o B), selector de avatar (grid de defaults + botón "subir foto").

---

## 3. Modelo de Datos

### 3.1 Nuevas tablas (esquema `myfinance`)

```sql
-- 3.1.1 savings_goals
CREATE TABLE myfinance.savings_goals (
    id                  UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id             UUID          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name                TEXT          NOT NULL,
    description         TEXT,
    target_amount       NUMERIC(18,2) CHECK (target_amount IS NULL OR target_amount > 0),
    target_date         DATE,
    currency            TEXT          NOT NULL DEFAULT 'COP',
    avatar_kind         TEXT          NOT NULL DEFAULT 'default'
                                       CHECK (avatar_kind IN ('default','custom')),
    avatar_default_key  TEXT,
    avatar_url          TEXT,
    status              TEXT          NOT NULL DEFAULT 'active'
                                       CHECK (status IN ('active','paused','achieved','archived')),
    priority            INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- al menos uno de target_amount o target_date debe estar presente
    CONSTRAINT chk_savings_goal_has_target
        CHECK (target_amount IS NOT NULL OR target_date IS NOT NULL),
    -- coherencia avatar
    CONSTRAINT chk_savings_goal_avatar
        CHECK (
            (avatar_kind = 'default' AND avatar_default_key IS NOT NULL AND avatar_url IS NULL)
         OR (avatar_kind = 'custom'  AND avatar_url IS NOT NULL)
        )
);

CREATE INDEX idx_savings_goals_user_status
    ON myfinance.savings_goals (user_id, status, priority);

-- trigger updated_at reusa myfinance.trigger_set_updated_at()
CREATE TRIGGER set_updated_at BEFORE UPDATE ON myfinance.savings_goals
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

-- 3.1.2 savings_goal_contributions
CREATE TABLE myfinance.savings_goal_contributions (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    goal_id         UUID          NOT NULL REFERENCES myfinance.savings_goals(id) ON DELETE CASCADE,
    amount          NUMERIC(18,2) NOT NULL CHECK (amount <> 0), -- permite ajustes negativos
    currency        TEXT          NOT NULL DEFAULT 'COP',
    occurred_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    note            TEXT,
    source          TEXT          NOT NULL DEFAULT 'manual'
                                   CHECK (source IN ('manual','auto')),
    transaction_id  UUID          REFERENCES myfinance.transactions(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_savings_contrib_goal_occurred
    ON myfinance.savings_goal_contributions (goal_id, occurred_at DESC);
CREATE INDEX idx_savings_contrib_user
    ON myfinance.savings_goal_contributions (user_id);
```

### 3.2 RLS
Política idéntica a la de `accounts` / `transactions`: el usuario solo lee/escribe filas con `user_id = auth.uid()`. Habilitar RLS en ambas tablas.

### 3.3 Storage
- Bucket `goal-avatars` privado.
- Path convention: `{user_id}/{goal_id}.{ext}`.
- Policy: el dueño puede leer/escribir su propio prefijo (`storage.foldername(name)[1] = auth.uid()::text`).

### 3.4 Migración
- Archivo: `backend/database/migrations/V004__savings_goals.sql` (continúa la numeración existente V001–V003).
- Incluir DDL + RLS + policies de Storage.

---

## 4. API REST (backend Java)

> Convenciones existentes: ver [SPEC.md §5](../SPEC.md) y §10 (Records, BigDecimal, UUID, ProblemDetail).

### 4.1 Endpoints

```
GET    /api/v1/savings-goals
       ?status={active|paused|achieved|archived|all}     (default: active+paused)
       Response: List<SavingsGoalDTO>

POST   /api/v1/savings-goals
       Body: CreateSavingsGoalRequest
       Response: SavingsGoalDTO  (201)

GET    /api/v1/savings-goals/{id}
       Response: SavingsGoalDetailDTO   (incluye lista paginada de aportes)

PATCH  /api/v1/savings-goals/{id}
       Body: UpdateSavingsGoalRequest   (campos opcionales)
       Response: SavingsGoalDTO

DELETE /api/v1/savings-goals/{id}
       Efecto: status = 'archived' (soft delete) — borrar físicamente solo si no tiene aportes

POST   /api/v1/savings-goals/{id}/contributions
       Body: { "amount": "...", "occurred_at": "...", "note": "..." }
       Response: ContributionDTO + SavingsGoalDTO actualizado

DELETE /api/v1/savings-goals/{id}/contributions/{contribId}
       Response: 204 + SavingsGoalDTO actualizado

POST   /api/v1/savings-goals/{id}/avatar
       Body: { "kind": "default", "default_key": "plane" }
              | { "kind": "custom", "storage_path": "..." }
       Response: SavingsGoalDTO
```

### 4.2 DTOs (Records Java)

```java
record SavingsGoalDTO(
    UUID id,
    String name,
    String description,
    BigDecimal targetAmount,        // nullable (modo B futuro)
    LocalDate targetDate,           // nullable
    String currency,
    AvatarDTO avatar,
    String status,
    int priority,
    BigDecimal savedAmount,         // SUM(contributions)
    BigDecimal remainingAmount,     // target - saved (null si target_amount null)
    BigDecimal progressPct,         // 0..1, null si target_amount null
    Integer monthsRemaining,        // null si target_date null o ya pasada
    BigDecimal monthlySuggested,    // recalculado on-read; null si modo B
    boolean atRisk,                 // true si target_date pasada y no achieved
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}

record AvatarDTO(String kind, String defaultKey, String url) {}

record ContributionDTO(
    UUID id,
    UUID goalId,
    BigDecimal amount,
    String currency,
    OffsetDateTime occurredAt,
    String note,
    String source,
    UUID transactionId
) {}
```

### 4.3 Lógica de `monthlySuggested`
Implementar en `domain/savings/SavingsGoalCalculator.java`:

```
monthsRemaining = ChronoUnit.MONTHS.between(
    YearMonth.from(today), YearMonth.from(targetDate)
) + 1   // inclusivo del mes actual
if (monthsRemaining <= 0) return null   // ya pasó
remaining = targetAmount.subtract(savedAmount).max(ZERO)
monthlySuggested = remaining.divide(BigDecimal.valueOf(monthsRemaining), 2, HALF_EVEN)
```

Tests unitarios obligatorios para casos límite:
- target_date este mes
- target_date el mes que viene
- target ya alcanzado (`remaining = 0`)
- target_date pasada y meta no completada

---

## 5. Integración con el Dashboard

- Añadir sección "Metas de Ahorro" al dashboard principal (TASK-UI-02), mostrando las 3 metas activas con mayor `priority` o las más próximas a vencer.
- Indicador de progreso global ("estás aportando X de Y sugerido este mes" — comparar `SUM(contributions del mes en curso)` vs `SUM(monthlySuggested de metas activas)`).
- Permitir registro rápido de aporte desde la tarjeta del dashboard.

---

## 6. Tareas (estilo TASK-* alineado con backlog existente)

> Estas tareas se moverán mañana a Notion como hijas de una nueva épica "Épica 5 — Metas de Ahorro" durante la sesión de refinamiento.

### Épica 5.A — Base de Datos
- **TASK-SG-DB-01** — Migración `V004__savings_goals.sql` con DDL de las dos tablas, constraints e índices.
- **TASK-SG-DB-02** — RLS y policies para `savings_goals` y `savings_goal_contributions`.
- **TASK-SG-DB-03** — Bucket `goal-avatars` en Supabase Storage + policies de owner-only.

### Épica 5.B — Backend Java
- **TASK-SG-BE-01** — jOOQ codegen (re-ejecutar tras migración) y repositorios.
- **TASK-SG-BE-02** — `domain/savings/SavingsGoalCalculator` con tests unitarios (HALF_EVEN, casos límite).
- **TASK-SG-BE-03** — `GET /savings-goals` con filtro por status y orden por prioridad.
- **TASK-SG-BE-04** — `POST /savings-goals` con validaciones (modo A vs B, avatar consistente).
- **TASK-SG-BE-05** — `GET /savings-goals/{id}` con detalle paginado de aportes.
- **TASK-SG-BE-06** — `PATCH /savings-goals/{id}` (cambio de status, edición de campos).
- **TASK-SG-BE-07** — `DELETE /savings-goals/{id}` (soft delete → archived).
- **TASK-SG-BE-08** — Endpoints de aportes: POST y DELETE.
- **TASK-SG-BE-09** — Endpoint de avatar: validación de path en Storage y kind.
- **TASK-SG-BE-10** — Tests de contrato REST-assured + integración Testcontainers.

### Épica 5.C — UI (diseño en Claude Design, implementación posterior)
- **TASK-SG-UI-01** — Mockups en Claude Design (lista, detalle, modal de creación).
- **TASK-SG-UI-02** — Vista de lista con barra de progreso y cuota sugerida del mes.
- **TASK-SG-UI-03** — Modal de creación/edición + selector de avatar default.
- **TASK-SG-UI-04** — Subida de avatar custom a Supabase Storage.
- **TASK-SG-UI-05** — Detalle con historial de aportes y registro rápido.
- **TASK-SG-UI-06** — Integración en dashboard (TASK-UI-02).

### Épica 5.D — Futuro (no MVP)
- Vincular aportes automáticos desde transacciones tipo `outgoing_transfer` con un tag/categoría especial.
- Modo C (solo fecha, sin monto) si surge la necesidad real.
- Notificaciones por Telegram cuando se acerca el cierre del mes y falta aportar.
- Compartir metas con otros usuarios (familiar/pareja).

---

## 7. Verificación End-to-End

1. **Migración:** correr `V004__savings_goals.sql` en Supabase local (Docker Compose §9 de SPEC) → verificar tablas, índices y RLS con `\d+ myfinance.savings_goals`.
2. **jOOQ:** re-ejecutar codegen y compilar → no warnings.
3. **Backend:** levantar Spring Boot, crear meta con `curl`/HTTPie, agregar aporte, verificar `monthlySuggested` para fechas distintas.
4. **Storage:** subir imagen al bucket con JWT del usuario, ver que aparece en la URL devuelta por el endpoint de avatar.
5. **Frontend (futuro):** crear meta → ver tarjeta en dashboard → registrar aporte → ver progreso actualizado.

---

## 8. Decisiones Abiertas (para mañana en Notion)

1. ¿Las metas se cuentan dentro del flujo de transacciones (cada aporte genera una transacción tipo `outgoing_transfer` ligada) o viven en su propio agregado contable separado? — **Recomendación inicial:** separadas; un `transaction_id` opcional permite enlazarlas a futuro sin acoplar.
2. ¿Cuál es el set inicial de avatares por defecto? — Definir lista de 8-12 keys junto a Claude Design.
3. ¿Permitimos aportes negativos (retiros)? — La columna soporta valores ≠ 0; queda como decisión de producto.
4. ¿Cómo se comparan los aportes vs el `monthly_suggested` del mes en curso? Ventana = mes calendario en `America/Bogota` → confirmar.
5. ¿Mostramos cuota sugerida solo para metas activas o también para `paused`? — Probable: solo activas.

---

## 9. Próximo Paso

**Mañana (2026-05-13):** sesión de refinamiento en Notion (página `MyFinanceView — App de Finanzas Personales`, agregar **Épica 5 — Metas de Ahorro** como bloque al final). Trasladar las TASK-SG-* de la §6 a Notion con su Definition of Done, y resolver las decisiones abiertas de la §8 antes de empezar implementación.
