# frontend-mvp Specification

## Purpose

Capability creada por el archivado del change `mvp-frontend-readonly` el 2026-06-01. Cubre el MVP frontend desplegado en Vercel (Vite + React 19 + TS strict + Tailwind + Supabase Auth) con 3 pantallas (login, listado de transacciones, modal de cambio de categoría). Fue puente para el lanzamiento de esa noche, hablando directo a Supabase PostgREST.

**Estado al archivado:** el frontend está desplegado y autentica, pero responde 403 en todas las queries a `myfinance.*` porque el operador rechazó exponer el schema en PostgREST (ver memoria `project_backend_only_path_to_myfinance_schema` y `openspec/changes/backend-mvp-readonly/`). El 403 es **gating intencional** que destrabará el change `backend-mvp-readonly` al aterrizar; no se "arregla" con un toggle del dashboard.

Cuando aterricen `backend-mvp-readonly` + `frontend-swap-to-backend`, los requirements "Forward-compatible data access layer" y "Category change modal" serán modificados para reflejar el swap del fetcher (de `supabase.from(...)` a `fetch(/api/v1/...)`) y la inclusión del feedback loop a `merchants`. Hasta entonces, esta canonical describe el estado entregado.

## Requirements


### Requirement: Frontend stack and runtime

El sistema SHALL desplegar una aplicación SPA construida con Vite, React 19, TypeScript en modo strict y Tailwind CSS, alojada en la carpeta `frontend/` del monorepo y publicada en Vercel como un único proyecto independiente del backend Java.

#### Scenario: Build reproducible
- **WHEN** un agente ejecuta `npm install && npm run build` desde `frontend/`
- **THEN** la build de Vite produce un bundle en `frontend/dist/` sin errores y sin warnings tipados como críticos por TypeScript

#### Scenario: TypeScript strict
- **WHEN** se compila el proyecto
- **THEN** `tsc --noEmit` pasa con `"strict": true`, `"noUncheckedIndexedAccess": true` y `"noImplicitOverride": true`

#### Scenario: Tailwind configurado
- **WHEN** una pantalla aplica una clase utilitaria de Tailwind (ej. `bg-neutral-900`)
- **THEN** el estilo se aplica en el bundle final sin necesidad de configuración adicional por archivo

### Requirement: Authentication via Supabase Auth

El sistema SHALL delegar autenticación completamente a Supabase Auth (email/password y magic link) y mantener la sesión activa en el cliente con refresh automático. El JWT emitido por Supabase SHALL acompañar cada query a PostgREST. El componente de auth SHALL configurarse para mostrar SÓLO el modo Sign In (no Sign Up) — single-user MVP.

#### Scenario: Login con email/password exitoso
- **WHEN** el usuario ingresa credenciales válidas en el formulario de login
- **THEN** Supabase emite un JWT, la sesión se persiste en almacenamiento local, y el usuario es redirigido a la ruta protegida por defecto (`/transactions`)

#### Scenario: Solicitar magic link (UI-verifiable)
- **WHEN** el usuario hace clic en "Enviar magic link" tras ingresar un email
- **THEN** la UI muestra un estado de confirmación ("Revisa tu correo para iniciar sesión"), el form queda inhabilitado para reenvío inmediato, y el componente NO falla aunque el correo no haya llegado todavía

#### Scenario: Callback de magic link con token válido
- **WHEN** el navegador aterriza en la URL de callback de Supabase con un token válido en el hash/query
- **THEN** el cliente intercambia el token por una sesión, persiste el JWT, y el usuario aterriza autenticado en la ruta protegida por defecto

#### Scenario: Acceso sin sesión
- **WHEN** un agente no autenticado intenta acceder a cualquier ruta distinta de `/login`
- **THEN** el sistema redirige a `/login` sin exponer datos protegidos

#### Scenario: Refresh automático
- **WHEN** el JWT está próximo a expirar
- **THEN** `supabase-js` renueva la sesión de forma transparente y las queries en curso no fallan por expiración

#### Scenario: Logout
- **WHEN** el usuario invoca logout
- **THEN** la sesión se limpia del almacenamiento, las queries activas se cancelan, y el usuario aterriza en `/login`

#### Scenario: Sign Up no se muestra
- **WHEN** el usuario abre `/login`
- **THEN** la UI NO ofrece tab/link de Sign Up — el único modo disponible es Sign In (forzado por configuración del componente, D9 del design.md)

### Requirement: Forward-compatible data access layer

El sistema SHALL aislar toda interacción con `@supabase/supabase-js` en un único cliente (`src/lib/supabaseClient.ts`) y en una capa de servicios (`src/services/<entity>Service.ts`). Los componentes, pantallas y hooks de React Query SHALL consumir exclusivamente los métodos públicos de los services. Esta capa garantiza que la migración futura al backend Java REST consista en reemplazar el cuerpo de los services sin tocar componentes ni hooks. El shape DTO usado en este MVP es **inventado** y SHALL realinearse contra `docs/api-spec.yml` cuando ese contrato exista (caveat reconocido en `design.md D4`).

#### Scenario: Componentes no importan `supabase-js` directo
- **WHEN** un test estático (Vitest + glob sobre `src/`) busca importaciones de `@supabase/supabase-js`
- **THEN** el test sólo encuentra coincidencias dentro de `src/lib/supabaseClient.ts` y archivos bajo `src/services/`; cualquier otra coincidencia falla la build

#### Scenario: ESLint refuerza el aislamiento
- **WHEN** un agente intenta importar `@supabase/supabase-js` desde un archivo bajo `src/pages/` o `src/components/`
- **THEN** `eslint` reporta un error vía la regla `no-restricted-imports` configurada en el proyecto

#### Scenario: Services exponen DTOs en formato camelCase
- **WHEN** un service retorna una transacción
- **THEN** el objeto tiene campos en camelCase (`occurredAt`, `categoryId`), fechas como ISO 8601 strings y montos como string (no `number`)

#### Scenario: Hooks de React Query sobre services
- **WHEN** una pantalla necesita listar transacciones
- **THEN** la pantalla invoca un hook `useTransactions(filters)` que internamente llama a `transactionsService.list(filters)` y usa React Query para caché/loading/error

### Requirement: Transactions listing screen

El sistema SHALL exponer una pantalla `/transactions` que liste las transacciones más recientes del usuario autenticado, ordenadas por `occurredAt` descendente, con paginación servidor-side y filtros por cuenta y categoría. El estado de los filtros SHALL reflejarse en query params de la URL para que los links sean compartibles. La pantalla NO muestra agregaciones monetarias (ver "Frontend never aggregates money") y NO conoce el concepto de "ciclo actual" (eso depende de `accounts.cut_day` que no es scope del MVP — M1 del adversarial review).

#### Scenario: Lista por defecto
- **WHEN** un usuario autenticado abre `/transactions`
- **THEN** la pantalla muestra sus transacciones más recientes (página 1, tamaño por defecto 25) ordenadas por `occurredAt DESC`

#### Scenario: Filtro por cuenta
- **WHEN** el usuario selecciona una cuenta del dropdown
- **THEN** la URL incluye `?accountId=<uuid>`, la lista se actualiza para mostrar sólo transacciones de esa cuenta, y la paginación se reinicia a página 1

#### Scenario: Filtro por categorías múltiples
- **WHEN** el usuario selecciona dos o más categorías
- **THEN** la URL incluye `?categoryIds=<uuid1>,<uuid2>` y la lista incluye sólo transacciones que matchean cualquiera de las categorías seleccionadas

#### Scenario: Link compartible
- **WHEN** el usuario copia la URL con filtros aplicados y la pega en otra pestaña autenticada
- **THEN** la lista se renderiza con los mismos filtros y los mismos resultados (a igualdad de datos)

#### Scenario: Paginación con siguiente/anterior
- **WHEN** el usuario navega a la página 2
- **THEN** la URL incluye `?page=2`, la lista muestra el siguiente bloque de 25 resultados, los filtros activos se preservan, y NO se muestra "página 2 de N" (el total agregado queda fuera de scope del MVP)

#### Scenario: Sin resultados
- **WHEN** los filtros activos no matchean ninguna transacción del usuario
- **THEN** la pantalla muestra un estado vacío explícito ("Sin transacciones para estos filtros") en lugar de una tabla vacía

### Requirement: Category change modal

El sistema SHALL permitir cambiar la categoría de una transacción mediante un modal lanzado desde una fila de la lista. La operación SHALL emitir exclusivamente `UPDATE myfinance.transactions SET category_id = $1 WHERE id = $2`. El sistema NO actualizará `myfinance.merchants` ni tocará la columna `category_confirmed` (esa columna no existe en el schema actual y el feedback loop a `merchants` está diferido al backend Java — TASK-BE-06; ver `design.md D2`).

#### Scenario: Apertura del modal
- **WHEN** el usuario hace clic en el control "cambiar categoría" de una fila
- **THEN** se abre un modal con la transacción contextual visible (descripción, monto, fecha) y un dropdown poblado con las categorías disponibles ordenadas por `displayName` ascendente (con fallback a `name` cuando `displayName` es null)

#### Scenario: Cambio de categoría exitoso
- **WHEN** el usuario selecciona una categoría distinta a la actual y pulsa "Confirmar"
- **THEN** la pantalla emite un UPDATE a `myfinance.transactions` que setea SÓLO `categoryId`, recibe la fila actualizada, cierra el modal con feedback visual de éxito, y la lista subyacente se refresca via React Query invalidation

#### Scenario: Modal idempotente — sin cambio de categoría
- **WHEN** el usuario abre el modal y pulsa "Confirmar" sin cambiar la categoría seleccionada
- **THEN** el service detecta que `selectedCategoryId === currentCategoryId` y NO emite query a Supabase; el modal se cierra silenciosamente (o muestra "Sin cambios") y la lista NO se refresca

#### Scenario: Error de red
- **WHEN** el UPDATE falla por red u otro error de Supabase
- **THEN** el modal permanece abierto, muestra un mensaje de error legible, conserva la selección del usuario y permite reintentar

#### Scenario: Cancelación
- **WHEN** el usuario cierra el modal sin pulsar "Confirmar"
- **THEN** la transacción permanece sin cambios y la lista no se refresca

### Requirement: Categories display in Spanish

El sistema SHALL mostrar las categorías por su `displayName` (español). Si una categoría no tiene `displayName` poblado, el sistema SHALL caer atrás al `name` técnico en inglés para no quedarse en blanco.

#### Scenario: Display normal
- **WHEN** una categoría tiene `displayName = "Restaurantes y Cafés"`
- **THEN** todas las superficies UI (badge en la lista, dropdown del modal, filtros) muestran "Restaurantes y Cafés"

#### Scenario: Fallback
- **WHEN** una categoría tiene `displayName` null o vacío
- **THEN** la UI muestra el `name` técnico (ej. "Dining Out") sin advertencias en consola — el cierre de TASK-DB-01 (`display_name` backfill) se hace en un change separado

### Requirement: Vercel deployment

El sistema SHALL estar desplegado en Vercel como un proyecto vinculado al monorepo con root directory `frontend/`. Las variables `VITE_SUPABASE_URL` y `VITE_SUPABASE_ANON_KEY` SHALL estar configuradas en el ambiente de producción de Vercel. El build command de Vercel SHALL encadenar `npm run typecheck && npm run lint && npm run test && npm run build` para que CI sea un único gate (D7 + m9 del adversarial review).

#### Scenario: Build en Vercel
- **WHEN** se hace push a `main` con cambios en `frontend/**`
- **THEN** Vercel detecta el cambio, ejecuta el build command encadenado con root `frontend/`, y publica el bundle en la URL productiva

#### Scenario: Variables de entorno
- **WHEN** el bundle de producción inicia
- **THEN** `import.meta.env.VITE_SUPABASE_URL` y `import.meta.env.VITE_SUPABASE_ANON_KEY` están definidas y apuntan al proyecto Supabase productivo (`akkoqdjmmozyqdfjkabg`)

#### Scenario: Smoke test post-deploy
- **WHEN** un curl a la URL productiva pide la raíz
- **THEN** el servidor responde HTTP 200 con el `index.html` de Vite

#### Scenario: SPA rewrites configurados
- **WHEN** un curl pide una ruta cliente como `/transactions`
- **THEN** Vercel responde con el `index.html` (no 404), permitiendo que React Router renderice la ruta correcta

### Requirement: Frontend never aggregates money

El frontend NEVER SHALL computar agregaciones monetarias (sumas, promedios, totales por período, proyecciones). Cualquier número agregado mostrado SHALL provenir de una vista o función SQL en Supabase, o (en el futuro) de un endpoint del backend Java.

#### Scenario: Lista sin total computado
- **WHEN** la pantalla de transacciones renderiza una página de resultados
- **THEN** la UI no muestra un "total de la página" ni un "total filtrado" computado en JavaScript — sólo las filas individuales con sus montos

#### Scenario: Total exigido por producto
- **WHEN** producto pide un total (no en este MVP, pero como guardrail)
- **THEN** el equipo crea una vista SQL en Supabase (`myfinance.v_*`) y el service la consume; el frontend nunca usa `.reduce()` sobre montos

### Requirement: Documented agent rules

El repositorio SHALL incluir `frontend/AGENTS.md` documentando: la regla de aislamiento de `supabase-js`, la prohibición de agregaciones monetarias en frontend, la estructura `lib/services/hooks/pages` esperada, el shape inventado de DTOs (para diff futuro contra `api-spec.yml`), el mapeo Tailwind ↔ tokens de `docs/design/design-system.md`, y la regla "NO actualizar `merchants` desde la UI" (responsabilidad de TASK-BE-06 del backend Java). Estas reglas SHALL ser legibles por cualquier agente que entre al folder.

#### Scenario: Agente lee `frontend/AGENTS.md`
- **WHEN** un agente nuevo abre `frontend/` por primera vez
- **THEN** encuentra `AGENTS.md` con las reglas operativas resumidas y links a los specs/standards relevantes
