# MyFinanceView — Especificación Completa del Proyecto

> **Documento de contexto para Claude Code / IntelliJ**
> Última actualización: 13 de mayo de 2026
> Este archivo vive en la raíz del repositorio como `SPEC.md`.
>
> ### 🎯 Jerarquía de fuentes de verdad
>
> Desde el 2026-05-13 los documentos vivos del proyecto, en orden de prioridad cuando entran en conflicto:
>
> 1. **`SPEC.md`** (este archivo) — north star: visión, stack y decisiones clave.
> 2. **[`docs/`](docs/)** — estándares detallados:
>    - [`base-standards.md`](docs/base-standards.md) — principios cross-cutting, workflow, DoD.
>    - [`backend-standards.md`](docs/backend-standards.md) — Java / Spring / jOOQ.
>    - [`data-model.md`](docs/data-model.md) — schema `myfinance` + migraciones pendientes.
>    - [`development-guide.md`](docs/development-guide.md) — setup local, comandos.
>    - [`documentation-standards.md`](docs/documentation-standards.md) — qué doc va dónde.
>    - [`frontend-standards.md`](docs/frontend-standards.md) — placeholder.
>    - [`api-spec.yml`](docs/api-spec.yml) — contrato OpenAPI 3.1 (canónico).
> 3. **[`plans/`](plans/)** — planes técnicos por feature (ej. [`savings-goals-plan.md`](plans/savings-goals-plan.md)).
> 4. **[`openspec/changes/<id>/`](openspec/)** — artefactos por cambio (`proposal.md`, `design.md`, `specs/`, `tasks.md`).
> 5. **[`openspec/specs/`](openspec/specs/)** — specs canónicos por capability (después de `/opsx:archive`).
> 6. **[Página Notion del proyecto](https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57)** — backlog dinámico (épicas, tareas, DoD).
> 7. **[`CLAUDE.md`](CLAUDE.md)** — entrypoint para Claude Code con punteros a todo lo de arriba.
>
> Documentos archivados en [`archive/`](archive/) son **históricos** — nunca autoritativos.
>
> ### 🏛️ Estilo arquitectónico
>
> **Monolito modular por dominio.** Un solo deployable de Spring Boot. Paquetes por bounded context (`domain/transaction`, `domain/category`, `domain/merchant`, `domain/billing`, `domain/savings`, …), no por capas técnicas. Sin hexagonal puro, sin CQRS, sin microservicios, sin JPA, sin reactivo. Ver [`docs/base-standards.md §2`](docs/base-standards.md) y [`docs/backend-standards.md §2`](docs/backend-standards.md).
>
> **Excepción explícita — servicios de infraestructura (enmienda 2026-05-13):** la prohibición de microservicios aplica al *dominio de aplicación* (transactions, savings, billing, …). Procesos auxiliares de infraestructura — n8n (ingesta de correos), el sidecar `myfinance-backup-runner` (encripta y sube snapshots de Postgres), Traefik, Uptime Kuma — pueden vivir como servicios separados en su propia subcarpeta (`scripts/<dominio-infra>/`) porque no implementan reglas de negocio y su sustitución no afecta al Spring Boot app. Cada nuevo servicio de infraestructura requiere justificación explícita en el `design.md` del cambio que lo introduce. Esta enmienda formaliza la carve-out usada por [`openspec/changes/supabase-backup-policy`](openspec/changes/supabase-backup-policy/) (resuelve adversarial-review finding #2 del 2026-05-13, que correctamente señaló que el sidecar Node+Express necesita amendment, no auto-exención dentro del propio `design.md`).
>
> ### 🔁 Flujo de trabajo (por cambio)
>
> ```
> /enrich-us  →  /opsx:propose  →  /opsx:apply  →  adversarial-review
>             →  /commit  →  /opsx:archive  →  /openspec-sync-specs  →  /update-docs
> ```
>
> Ver [`docs/base-standards.md §3`](docs/base-standards.md) y [`CLAUDE.md`](CLAUDE.md) para detalle.
>
> ### 🤖 Skills y agents
>
> User skills (`~/.claude/skills/`): `enrich-us` (project-overridden for Notion), `adversarial-review`, `code-auditing`, `commit`, `update-docs`, `writing-skills`, `openspec-sync-specs`, `show-spec-working`, `explain`, `meta-prompt`, `using-git-worktrees`, `sync-agent-symlinks` (upstream lidr-specboot) + OpenSpec built-ins.
>
> Agents proyecto ([`.claude/agents/`](.claude/agents/)): `backend-developer`, `frontend-developer` (placeholder), `product-strategy-analyst`, `adversarial-reviewer`, `code-auditor`.

---

## 1. Visión General

MyFinanceView es una aplicación personal de finanzas personales para un usuario en Bogotá, Colombia. El sistema captura automáticamente las transacciones bancarias desde correos electrónicos del banco (Davivienda, Bancolombia), las categoriza con IA, y expone un API REST consumido por una futura UI en React.

**Problema central que resuelve:** Las tarjetas de crédito colombianas envían correos por cada transacción. Actualmente esos correos se procesan con un workflow en n8n que los parsea y los guarda en Supabase. El sistema necesita un backend Java que exponga esos datos de forma estructurada, con soporte nativo para cuotas (installments), ciclos de facturación y un sistema de categorización que aprende del feedback del usuario.

---

## 2. Stack Tecnológico

| Capa | Tecnología | Versión | Razón |
|---|---|---|---|
| Lenguaje | Java | 25 | Virtual Threads (Loom), Records, Pattern Matching |
| Framework | Spring Boot | 3.4+ | Soporte nativo Loom, GraalVM-ready |
| Acceso a datos | jOOQ | 3.19+ | Type-safe SQL, schema ya diseñado y estable |
| Base de datos | PostgreSQL (Supabase) | 17.6 | Ya existente, esquema `myfinance` |
| Auth | Supabase JWT + Spring Security | — | Auth ya implementada en Supabase |
| Tests unitarios | JUnit 5 + AssertJ | — | Estándar Spring |
| Tests de contrato | REST-assured | — | Tests HTTP legibles y expresivos |
| Tests de integración | Testcontainers (PostgreSQL) | — | Postgres real en tests, mismo schema |
| Build | Maven | 3.9+ | Predecible para proyecto individual |
| API Spec | OpenAPI 3.1 (YAML manual) | — | Sin code generation, solo documentación |
| Containerización | Docker Compose | — | Para desarrollo local con Postgres |

**Decisión clave — Sin code generation de OpenAPI:**
El spec YAML vive en `/spec/openapi.yaml` como fuente de verdad del contrato, pero NO se usa el `openapi-generator-maven-plugin`. Las interfaces se escriben a mano. Razón: para un proyecto unipersonal, el generador agrega burocracia sin beneficio real y produce código genérico que interfiere con el estilo propio.

**Decisión clave — jOOQ sobre JPA/Hibernate:**
El schema de Postgres está diseñado, es estable y es complejo (cuotas, ciclos de facturación, merchants). jOOQ genera clases type-safe desde el schema existente. Las queries complejas son más expresivas y seguras que JPQL. Sin magia de lazy loading ni problemas N+1.

---

## 3. Metodología de Desarrollo

### Spec-Driven Development + TDD

```
Spec (qué hace el endpoint)
  → Test de contrato (el endpoint debe retornar esto) — ROJO
    → Implementación mínima que pasa el test — VERDE
      → Refactor
```

**Reglas:**
- Nada se implementa sin un test que falle primero
- El spec YAML se actualiza antes de implementar un endpoint nuevo
- Los tests de integración usan Testcontainers con el mismo schema `myfinance` de Supabase
- Los tests de contrato validan el contrato HTTP (status codes, estructura del JSON) con REST-assured
- No se mergea código con tests en rojo

### Estructura de paquetes

```
com.myfinanceview/
├── api/
│   ├── controller/          ← Spring @RestController
│   ├── dto/                 ← Records Java (request/response)
│   └── exception/           ← @ControllerAdvice, manejo de errores
├── domain/
│   ├── transaction/         ← Lógica de negocio de transacciones
│   ├── category/            ← Lógica de categorización
│   ├── merchant/            ← Lógica de merchants y feedback loop
│   ├── billing/             ← Lógica de ciclos de facturación
│   └── savings/             ← Metas de ahorro (Épica 5)
├── db/
│   ├── repository/          ← Interfaces de acceso a datos
│   └── jooq/                ← Implementaciones con jOOQ
└── config/
    ├── SecurityConfig.java  ← Spring Security + JWT Supabase
    ├── JooqConfig.java      ← Configuración de jOOQ
    └── OpenApiConfig.java   ← Springdoc config
```

### Estructura del repositorio

```
myfinance-view/
├── spec/
│   └── openapi.yaml              ← Contrato del API (fuente de verdad)
├── src/
│   ├── main/java/com/myfinanceview/
│   └── test/java/com/myfinanceview/
│       ├── contract/             ← Tests REST-assured
│       └── integration/          ← Tests Testcontainers
├── docker/
│   └── docker-compose.yml        ← Postgres local para desarrollo
├── db/
│   └── migrations/               ← Scripts SQL pendientes (ver Sección 6)
├── SPEC.md                       ← Este archivo
└── pom.xml
```

---

## 4. Base de Datos — Supabase

**Proyecto Supabase:** `MyFinanceView & Chatwoot`
**Project ID:** `akkoqdjmmozyqdfjkabg`
**Host:** `db.akkoqdjmmozyqdfjkabg.supabase.co`
**Postgres versión:** 17.6
**Schema principal:** `myfinance`

> ⚠️ El esquema `public` contiene 87 tablas de Chatwoot que serán eliminadas en TASK-DT-02. No referenciar nada del esquema `public` en el backend.

### 4.1 Tablas existentes en `myfinance`

#### `accounts` — Cuentas bancarias y tarjetas
```sql
id uuid PK
user_id uuid FK auth.users
bank_id uuid FK banks
nickname text                    -- "Visa Signature Davivienda"
type text                        -- 'credit_card' | 'debit' | 'savings'
currency text DEFAULT 'COP'
last4 text                       -- últimos 4 dígitos
active boolean DEFAULT true
cut_day integer                  -- PENDIENTE TASK-DB-03 (día de corte, ej. 15)
payment_day integer              -- PENDIENTE TASK-DB-03 (día límite de pago)
```

**Cuentas activas (3):**
- Visa Signature Davivienda — corte día 15 — `id: 0a664ee9-624c-490e-9a8a-7db6c14592dc`
- Black Bancolombia — corte a confirmar
- Bancolombia débito

#### `transactions` — Transacciones financieras
```sql
id uuid PK
account_id uuid FK accounts
user_id uuid FK auth.users
type text                        -- 'credit_card_purchase' | 'debit' | 'income' | etc.
amount numeric NOT NULL          -- siempre positivo
currency text DEFAULT 'COP'
amount_base_currency numeric     -- convertido a COP si es moneda extranjera
description text                 -- descripción cruda del banco (ej. "BOLD*Rancho Grande B")
occurred_at timestamptz
category_id uuid FK categories   -- categoría actual (confirmada o sugerida)
source text                      -- 'gmail' | 'manual' | 'api'
external_id text UNIQUE          -- para idempotencia en ingesta desde Gmail
raw_payload jsonb                -- correo original completo para auditoría
-- PENDIENTES (TASK-DB-02):
installments_total integer DEFAULT 1
installment_number integer DEFAULT 1
parent_transaction_id uuid FK transactions (self)
-- PENDIENTES (TASK-DB-05):
merchant_id uuid FK merchants
ai_suggested_category_id uuid FK categories
categorization_confidence numeric   -- 0.0 a 1.0
category_confirmed boolean DEFAULT false
```

**Estado actual:** 362 transacciones. Corte Davivienda 15 abr → 7 may 2026: $2,450,097 COP en 42 transacciones.

#### `categories` — Categorías de gasto/ingreso
```sql
id uuid PK
name text UNIQUE                 -- clave interna en inglés ("Dining Out")
display_name text                -- PENDIENTE TASK-DB-01 — nombre en español ("Restaurantes y Cafés")
type text                        -- 'expense' | 'income'
icon text                        -- emoji o nombre de ícono
color text                       -- hex color
```

**19 categorías actuales:** Shopping, Dining Out, Food & Groceries, Transportation, Other Expense, Income, y más.
Todas necesitan `display_name` en español (TASK-DB-01).

#### `merchants` — Comercios normalizados (TABLA PENDIENTE — TASK-DB-04)
```sql
id uuid PK DEFAULT uuid_generate_v4()
user_id uuid FK auth.users
raw_pattern text NOT NULL        -- patrón crudo del banco ("DIDI*", "BOLD*", "RAPPI")
normalized_name text NOT NULL    -- nombre limpio ("Didi", "BOLD", "Rappi")
category_id uuid FK categories   -- categoría aprendida
confidence numeric DEFAULT 0.5   -- 0.0 a 1.0 (sube con cada confirmación del usuario)
match_count integer DEFAULT 0    -- veces que se ha usado este patrón
last_confirmed_at timestamptz    -- última vez que el usuario confirmó
created_at timestamptz DEFAULT now()
updated_at timestamptz DEFAULT now()
UNIQUE (user_id, raw_pattern)
```

**Lógica de confianza:**
- `confidence >= 0.85` → categorizar directo, sin llamar al LLM
- `0.5 <= confidence < 0.85` → categorizar con LLM pero incluir como contexto
- `confidence < 0.5` → categorizar con LLM, mostrar como dudosa

#### `banks` — Catálogo de bancos
```sql
id uuid PK
name text                        -- "Davivienda", "Bancolombia"
country text DEFAULT 'CO'
```

#### `budgets` y `budget_categories` — Presupuestos (vacíos, pendiente de diseño)

#### `exchange_rates` — Tasas de cambio (vacío)

#### `user_settings` — Configuración del usuario (1 fila)

### 4.2 Migraciones pendientes

Ver Sección 6 para el detalle completo. Orden de ejecución:

1. TASK-DB-01: `ALTER TABLE categories ADD COLUMN display_name text`
2. TASK-DB-03: `ALTER TABLE accounts ADD COLUMN cut_day integer, ADD COLUMN payment_day integer`
3. TASK-DB-02: `ALTER TABLE transactions ADD COLUMN installments_total, installment_number, parent_transaction_id`
4. TASK-DB-04: `CREATE TABLE merchants`
5. TASK-DB-05: `ALTER TABLE transactions ADD COLUMN merchant_id, ai_suggested_category_id, categorization_confidence, category_confirmed`

**IMPORTANTE:** Las migraciones deben ejecutarse en ese orden por dependencias de FK. Los scripts SQL completos deben vivir en `/db/migrations/` con naming `V{n}__{descripcion}.sql` (estilo Flyway, aunque no se use Flyway inicialmente).

---

## 5. API REST — Endpoints Requeridos

> El contrato completo estará en `/spec/openapi.yaml`. Este es el inventario funcional.

### Autenticación
Todos los endpoints requieren JWT de Supabase en el header `Authorization: Bearer {token}`.
Spring Security valida el JWT contra la clave pública de Supabase.

### 5.1 Transacciones

```
GET    /api/v1/transactions
       ?account_id={uuid}
       &from={date}           ← ISO 8601
       &to={date}
       &category_id={uuid}
       &confirmed={boolean}
       &page={int}&size={int}
       Response: Page<TransactionDTO>

GET    /api/v1/transactions/{id}
       Response: TransactionDTO

PATCH  /api/v1/transactions/{id}/category
       Body: { "category_id": "uuid", "confirmed": true }
       Response: TransactionDTO
       Efecto secundario: actualiza myfinance.merchants (feedback loop)

GET    /api/v1/transactions/pending-review
       ?account_id={uuid}
       Response: List<TransactionDTO> ordenada por categorization_confidence ASC
       — Devuelve solo transactions donde category_confirmed = false
```

### 5.2 Cuentas y Ciclo de Facturación

```
GET    /api/v1/accounts
       Response: List<AccountDTO>

GET    /api/v1/accounts/{id}/billing-summary
       ?reference_date={date}  ← default: hoy
       Response: BillingSummaryDTO {
         period_start, period_end, days_remaining,
         total_amount, transaction_count,
         breakdown_by_category: List<CategoryBreakdownDTO>,
         estimated_total: numeric,        ← proyección al cierre
         previous_period_total: numeric   ← para comparar
       }
```

### 5.3 Categorías

```
GET    /api/v1/categories
       Response: List<CategoryDTO>         ← con display_name en español
```

### 5.4 Merchants (sistema de memoria)

```
GET    /api/v1/merchants
       ?search={string}
       Response: List<MerchantDTO> ordenada por match_count DESC

PUT    /api/v1/merchants/{id}
       Body: { "category_id": "uuid", "normalized_name": "string" }
       Response: MerchantDTO

DELETE /api/v1/merchants/{id}
       Efecto: merchant_id = NULL en transacciones asociadas (no CASCADE)

POST   /api/v1/merchants/merge
       Body: { "source_ids": ["uuid", "uuid"], "target_id": "uuid" }
       Response: MerchantDTO del target actualizado
```

### 5.5 Feedback (webhook interno para n8n)

```
POST   /api/v1/feedback/transaction
       Header: X-Webhook-Secret: {secret}
       Body: { "transaction_id": "uuid", "correct_category_id": "uuid" }
       Response: { "success": true, "new_confidence": 0.85 }
       — Este endpoint lo llama n8n cuando el usuario confirma desde Telegram
```

---

## 6. Épicas y Backlog Detallado

> Versión completa con Definition of Done en Notion:
> https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57

### Épica 1 — Migraciones de Base de Datos

| Task | Descripción | Prioridad |
|---|---|---|
| TASK-DB-01 | `display_name` en español en `categories` | Alta |
| TASK-DB-02 | Soporte de cuotas (installments) en `transactions` | Alta — crítico para Colombia |
| TASK-DB-03 | `cut_day` y `payment_day` en `accounts` | Alta |
| TASK-DB-04 | Crear tabla `myfinance.merchants` | Alta |
| TASK-DB-05 | Campos de categorización inteligente en `transactions` | Alta |

### Épica 2 — Sistema de Categorización Inteligente (n8n)

| Task | Descripción | Prioridad |
|---|---|---|
| TASK-N8N-01 | Lookup en `merchants` antes del LLM | Alta |
| TASK-N8N-02 | Prompt enriquecido con contexto (monto, hora, historial) | Alta |
| TASK-N8N-03 | Webhook de retroalimentación (feedback loop) | Alta |
| TASK-N8N-04 | Notificación Telegram enriquecida con botones inline | Media |

### Épica 3 — Backend Java (NUEVO — este repositorio)

| Task | Descripción | Prioridad |
|---|---|---|
| ~~TASK-BE-01~~ ✓ | ~~Setup inicial: Spring Boot 25, jOOQ, Maven, Docker Compose~~ — Done 2026-05-13 (`openspec/changes/archive/2026-05-13-backend-scaffolding/`) | Alta |
| TASK-BE-02 | Configuración jOOQ: codegen desde schema `myfinance` de Supabase | Alta |
| TASK-BE-03 | Spring Security: validación JWT de Supabase | Alta |
| TASK-BE-04 | `GET /transactions` con filtros y paginación | Alta |
| TASK-BE-05 | `GET /accounts/{id}/billing-summary` | Alta |
| TASK-BE-06 | `PATCH /transactions/{id}/category` + feedback loop a merchants | Alta |
| TASK-BE-07 | `GET /transactions/pending-review` | Media |
| TASK-BE-08 | CRUD de `merchants` + merge | Media |
| TASK-BE-09 | `POST /feedback/transaction` (webhook para n8n) | Media |
| TASK-BE-10 | Swagger UI con Springdoc | Baja |

### Épica 4 — UI React (futuro)

| Task | Descripción | Prioridad |
|---|---|---|
| TASK-UI-01 | Vista de transacciones pendientes de revisión | Media |
| TASK-UI-02 | Dashboard de gastos por corte | Media |
| TASK-UI-03 | Gestión de merchants conocidos | Baja |

### Épica 5 — Deuda Técnica

| Task | Descripción | Prioridad |
|---|---|---|
| TASK-DT-01 | Habilitar RLS en `banks` y `exchange_rates` | Alta |
| TASK-DT-02 | Eliminar tablas Chatwoot del esquema `public` | Media — confirmar antes |
| TASK-DT-03 | Recategorizar transacción "Compra Virtual 8132" ($614,074) | Media |
| TASK-DT-04 | Clarificar y documentar esquema `ar_costing` | Baja |

---

## 7. Sistema de Categorización Inteligente

### Flujo de decisión (implementado en n8n, consumido por el backend)

```
Email banco entra a n8n
  └─→ Parser extrae: descripción, monto, fecha, hora
        └─→ Buscar en myfinance.merchants
              ├─→ [confidence >= 0.85] → Asignar categoría directo (sin LLM)
              │                          Incrementar match_count
              │
              └─→ [sin match o confidence < 0.85] → Llamar al LLM
                        Prompt incluye:
                        - Descripción cruda del banco
                        - Monto en COP
                        - Hora local (America/Bogota)
                        - Día de la semana en español
                        - Últimas 5 transacciones similares confirmadas
                        - Lista de categorías con display_name en español
                        LLM responde: { category_name, confidence, merchant_normalized }
                          └─→ Guardar ai_suggested_category_id + categorization_confidence
                          └─→ Upsert en myfinance.merchants
```

### Loop de retroalimentación

```
Usuario corrige categoría (desde Telegram o UI)
  └─→ POST /api/v1/feedback/transaction
        └─→ UPDATE transactions SET category_id, category_confirmed = true
        └─→ UPDATE merchants SET confidence += 0.1, match_count++, last_confirmed_at = now()
              └─→ Si confidence >= 0.85 → el patrón ya nunca va al LLM
```

### Señales de contexto para el LLM

El monto y la hora son señales complementarias a la descripción:
- Monto pequeño (< $20,000) + noche (> 9 PM) → probablemente transporte (Didi, Uber)
- Monto mediano ($30,000–$80,000) + mediodía → probablemente restaurante
- Monto grande (> $200,000) + cualquier hora → compra online, mercado, o cuota

Estas señales NO son reglas hardcodeadas — se pasan como contexto al LLM para que las pondere.

---

## 8. Spec del API — Spec Antigua (pendiente de refinar)

> ⚠️ Existe una spec antigua creada hace tiempo en el computador personal del desarrollador.
> Antes de usarla hay que traerla, revisarla y alinearla con:
> 1. El modelo de datos actual de Supabase (secciones 4.1 y 4.2)
> 2. Los endpoints definidos en la Sección 5
> 3. Las épicas del backlog (Sección 6)
>
> Una vez refinada, se sube a `/spec/openapi.yaml` y se usa como punto de partida para TASK-BE-04 en adelante.

**Checklist de refinamiento de la spec antigua:**
- [ ] ¿Los endpoints coinciden con los definidos en Sección 5?
- [ ] ¿Los DTOs usan los nombres de campo correctos del schema actual?
- [ ] ¿Incluye los campos de cuotas (`installments_total`, `installment_number`)?
- [ ] ¿Incluye `display_name` en `CategoryDTO`?
- [ ] ¿El endpoint de billing-summary existe y tiene la estructura correcta?
- [ ] ¿Están los endpoints de merchants y feedback?
- [ ] ¿La autenticación está documentada (Bearer JWT)?

---

## 9. Configuración de Desarrollo Local

### Variables de entorno requeridas

```bash
# Supabase
SUPABASE_URL=https://akkoqdjmmozyqdfjkabg.supabase.co
SUPABASE_ANON_KEY=...
SUPABASE_SERVICE_ROLE_KEY=...    # solo para migraciones y tests
SUPABASE_JWT_SECRET=...          # para validar tokens en Spring Security

# Base de datos directa (para jOOQ codegen y Testcontainers)
DB_HOST=db.akkoqdjmmozyqdfjkabg.supabase.co
DB_PORT=5432
DB_NAME=postgres
DB_SCHEMA=myfinance
DB_USER=...
DB_PASSWORD=...

# Webhook
WEBHOOK_SECRET=...               # para POST /feedback/transaction

# Local (Docker Compose)
LOCAL_DB_PORT=5433               # para no pisar el 5432 local si existe
```

### Docker Compose (desarrollo local)

```yaml
# docker/docker-compose.yml
services:
  postgres:
    image: postgres:17
    environment:
      POSTGRES_DB: myfinance_local
      POSTGRES_USER: myfinance
      POSTGRES_PASSWORD: localpassword
    ports:
      - "5433:5432"
    volumes:
      - ./db/migrations:/docker-entrypoint-initdb.d
```

---

## 10. Convenciones de Código

- **DTOs:** Records Java (`record TransactionDTO(UUID id, BigDecimal amount, ...)`)
- **Fechas:** `OffsetDateTime` para timestamps, `LocalDate` para fechas de corte. Siempre almacenar en UTC, convertir a `America/Bogota` solo en la capa de presentación
- **Montos:** `BigDecimal` — nunca `double` ni `float` para dinero
- **Ids:** `UUID` — nunca `Long` ni `String`
- **Errores:** `ProblemDetail` (RFC 7807, nativo en Spring 6+)
- **Logs:** SLF4J + Logback. Nunca loguear tokens, passwords ni datos personales
- **Naming de tests:** `should{Resultado}When{Condicion}` — ej. `shouldReturnTransactionsSortedByConfidenceWhenFetchingPendingReview`

---

## 11. Contexto del Negocio

- **Usuario:** desarrollador de software en Bogotá, Colombia. Trabaja en Globant (empresa principal). También es dueño del restaurante Arepa Roja.
- **Uso de la app:** control personal de gastos, principalmente tarjeta Visa Signature Davivienda con corte el día 15 de cada mes
- **Corte actual (al 11 mayo 2026):** $2,450,097 COP acumulados en 42 transacciones, faltan 4 días para cerrar el ciclo
- **Fuente de datos actual:** workflow n8n que lee correos de Gmail → parsea → guarda en Supabase. El backend Java convive con este workflow, no lo reemplaza
- **Moneda principal:** COP (pesos colombianos). Formato: `$32.230,00` = 32,230 pesos (los decimales son centavos, no miles)

---

## 12. Próximos Pasos Inmediatos

En este orden:

0. **~~supabase-backup-policy~~** ✓ (2026-05-13) — pipeline de backup cifrado con `age` + `pg_dump` + Cloudflare R2 + restore-verify automático. Ver [`docs/development-guide.md §11–12`](docs/development-guide.md) y [`scripts/backup/README.md`](scripts/backup/README.md). **Prerequisito operador** antes de activar: generar claves age, configurar R2, importar workflows n8n, desplegar sidecar en VPS. Ver [`openspec/changes/supabase-backup-policy/tasks.md §1`](openspec/changes/supabase-backup-policy/tasks.md).
1. **Ejecutar migraciones DB-01 a DB-05** — el schema debe estar completo antes de que jOOQ genere las clases. **Precondición:** backup pre-op verificado en los últimos 60 min (ver checklist `openspec/templates/supabase-write-checklist.md`).
2. **Traer y refinar la spec antigua** — alinearla con la Sección 5 de este documento
3. ~~**TASK-BE-01**~~ ✓ — setup del proyecto Spring Boot con Java 25, jOOQ, Maven (done 2026-05-13, archivo `openspec/changes/archive/2026-05-13-backend-scaffolding/`)
4. **TASK-DB-06 / flyway-migrations** (NUEVO — surfaced 2026-05-13) — adoptar Flyway desde el principio para gestión de migraciones, eliminando el orquestador manual `database/init-db.sh` y separando definitivamente `database/local/` (stubs) de `database/migrations/` (baseline Flyway)
5. **monorepo-restructure** — mover scaffold a `backend/` para dejar la raíz limpia para `frontend/` cuando arranque
6. **TASK-BE-02** — configurar jOOQ codegen contra el schema `myfinance` ya migrado
7. **TASK-BE-03** — Spring Security con JWT de Supabase
8. Desde ahí, TDD endpoint por endpoint siguiendo el orden de la Sección 6

---

*Este documento es la fuente de verdad del proyecto. Ante cualquier ambigüedad entre el código y este spec, el spec gana.*
