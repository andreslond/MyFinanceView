## ADDED Requirements

### Requirement: Row Level Security on user-owned tables

El esquema `myfinance` SHALL tener Row Level Security habilitada y políticas estrictas por `auth.uid() = user_id` en todas las tablas que contienen datos por usuario: `transactions`, `accounts`, `merchants`, `user_settings`, `budgets`, `budget_categories`. Estas políticas SHALL aplicar a las operaciones `SELECT`, `INSERT`, `UPDATE` y `DELETE`.

#### Scenario: Sin sesión, sin datos
- **WHEN** un cliente PostgREST consulta `myfinance.transactions` sin JWT (anon)
- **THEN** la respuesta es HTTP 401 o un array vacío (según configuración de PostgREST), y nunca expone filas

#### Scenario: Usuario A no ve filas de Usuario B
- **WHEN** Usuario A (con su JWT) consulta `myfinance.transactions`
- **THEN** la respuesta incluye solo filas con `user_id = auth.uid()` de Usuario A, sin importar filtros adicionales

#### Scenario: INSERT con user_id ajeno rechazado
- **WHEN** Usuario A intenta hacer INSERT en `myfinance.transactions` con `user_id` distinto a su `auth.uid()`
- **THEN** la operación falla con error de política RLS y no se inserta nada

#### Scenario: UPDATE de fila ajena rechazado
- **WHEN** Usuario A intenta UPDATE de una fila cuyo `user_id` no es el suyo
- **THEN** la operación afecta 0 filas y no modifica datos de Usuario B

#### Scenario: DELETE de fila ajena rechazado
- **WHEN** Usuario A intenta DELETE de una fila cuyo `user_id` no es el suyo
- **THEN** la operación afecta 0 filas

### Requirement: Read-only RLS on shared catalogs

Las tablas catálogo compartidas en `myfinance` (`categories`, `banks`, `exchange_rates`) SHALL tener Row Level Security habilitada con política `SELECT` para el rol `authenticated`. INSERT/UPDATE/DELETE SHALL estar prohibidos para roles cliente (`anon`, `authenticated`), permitidos solo para el `service_role` usado por migraciones y administración.

#### Scenario: Lectura autenticada permitida
- **WHEN** Usuario A consulta `myfinance.categories` con su JWT
- **THEN** recibe todas las categorías del catálogo

#### Scenario: Lectura anónima rechazada
- **WHEN** un cliente sin JWT consulta `myfinance.categories`
- **THEN** la respuesta es HTTP 401 o vacía

#### Scenario: Escritura desde cliente rechazada
- **WHEN** Usuario A intenta INSERT/UPDATE/DELETE en `myfinance.categories` con su JWT
- **THEN** la operación falla por política RLS

#### Scenario: Escritura desde service_role permitida
- **WHEN** una migración o script administrativo usa `service_role` para INSERT en `myfinance.categories`
- **THEN** la operación procede normalmente

### Requirement: Feedback loop trigger on transaction category confirmation

El esquema `myfinance` SHALL incluir un trigger `tg_transaction_category_feedback` sobre `myfinance.transactions` que dispara la función `myfinance.fn_update_merchant_from_transaction()` `AFTER UPDATE` cuando `category_confirmed` transiciona de no-verdadero a verdadero, o cuando el usuario confirma con un `category_id` distinto al previo. La función SHALL actualizar o crear la fila correspondiente en `myfinance.merchants` para mantener el invariante de aprendizaje descrito en `SPEC.md §7`.

#### Scenario: Confirmación crea merchant si no existe
- **WHEN** Usuario A confirma la categoría de una transacción cuyo `raw_pattern` (derivado de `description`) no existe en `myfinance.merchants` para `user_id = A`
- **THEN** el trigger inserta una fila en `myfinance.merchants` con `user_id = A`, `raw_pattern` derivado, `normalized_name` derivado, `category_id` igual al confirmado, `confidence = 0.6` (valor inicial post-primera confirmación: 0.5 base + 0.1 ajuste), `match_count = 1`, `last_confirmed_at = now()`

#### Scenario: Confirmación incrementa confidence en merchant existente
- **WHEN** Usuario A confirma una transacción cuyo `raw_pattern` ya existe en `myfinance.merchants` para `user_id = A`
- **THEN** el trigger hace UPDATE de esa fila: `confidence = LEAST(0.95, confidence + 0.1)`, `match_count = match_count + 1`, `last_confirmed_at = now()`, `category_id` actualizado al nuevo si difiere

#### Scenario: Confirmación con misma categoría sigue acumulando
- **WHEN** Usuario A confirma una transacción que ya tenía `category_confirmed = true` (re-confirmación)
- **THEN** el trigger NO se dispara (la transición `category_confirmed false → true` no ocurrió y `category_id` no cambió) y `merchants` no se modifica — esto evita inflar confidence con interacciones idempotentes

#### Scenario: Cambio de categoría confirmada también actualiza merchant
- **WHEN** Usuario A cambia el `category_id` de una transacción que ya estaba confirmada, manteniendo `category_confirmed = true`
- **THEN** el trigger se dispara (porque `category_id` cambió) y actualiza el `category_id` del merchant correspondiente; `confidence` y `match_count` se incrementan igual

#### Scenario: Atomicidad bajo retry
- **WHEN** una transacción del cliente que confirma categoría se aborta por error de red después de COMMIT pero antes de respuesta visible
- **THEN** el UPDATE ya quedó persistido junto con el efecto del trigger; un reintento del cliente con el mismo `category_confirmed = true` no incrementa de nuevo (porque no hay transición); `merchants` queda en estado consistente

#### Scenario: RLS respetada por el trigger
- **WHEN** el trigger escribe en `myfinance.merchants`
- **THEN** la fila resultante tiene el `user_id` del dueño de la transacción y respeta la política `WITH CHECK (auth.uid() = user_id)` (el trigger corre con `SECURITY INVOKER` para preservar el contexto del usuario)

### Requirement: Documented raw_pattern normalization

La función `myfinance.fn_update_merchant_from_transaction()` SHALL derivar `raw_pattern` y `normalized_name` desde la `description` de la transacción usando el mismo algoritmo de normalización que el workflow n8n. La migración que crea la función SHALL documentar el algoritmo en comentarios SQL.

#### Scenario: Normalización consistente con n8n
- **WHEN** dado un `description` de banco (ej. `"BOLD*Rancho Grande B"`)
- **THEN** la función produce el mismo `raw_pattern` (ej. `"BOLD*"`) y `normalized_name` (ej. `"BOLD"`) que el workflow n8n produce para esa misma descripción

#### Scenario: Algoritmo documentado en la migración
- **WHEN** un agente lee la migración SQL del trigger
- **THEN** encuentra un bloque de comentario que describe el algoritmo de normalización en castellano y referencia el nodo de n8n equivalente

### Requirement: Versioned migrations

Las migraciones SQL que aplican RLS y el trigger SHALL vivir en `backend/database/migrations/` siguiendo el naming `V{n}__<descripcion>.sql` (estilo Flyway). Cada migración SHALL ser idempotente o estar protegida por `IF NOT EXISTS`/`CREATE OR REPLACE` para soportar re-ejecución en ambientes locales con Testcontainers.

#### Scenario: Aplicación en Supabase productivo
- **WHEN** un agente aplica la migración vía MCP de Supabase (`apply_migration`)
- **THEN** el cambio se persiste en `myfinance.*`, las políticas/triggers quedan activos, y un re-run de la misma migración no falla

#### Scenario: Aplicación en ambiente local
- **WHEN** Testcontainers inicializa una instancia de Postgres con los scripts de `backend/database/migrations/`
- **THEN** las políticas RLS y el trigger se aplican en el mismo orden y producen el mismo schema lógico que producción
