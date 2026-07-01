# Plan — Home "Reflection-first" para demo a cliente

> **Fecha:** 2026-07-01 · **Objetivo:** desplegar a una URL de Vercel la pantalla **Home "Reflection-first" (HomeB)** con **data real** de Supabase, para mostrar a un cliente potencial **mañana (2026-07-02)**.
> **Rama:** `worktree-home-dashboard-demo`. **Aprobación requerida antes de codificar.**

---

## 1. Contexto y decisiones tomadas

- La pantalla **no existe como código**; sí existe como diseño en `docs/design/raw/.../home.jsx` → función **`HomeB`** (donut SVG, trío de métricas, tarjeta de insight, filas de reflexión). **Se porta 1:1 a React + Tailwind** con los tokens del proyecto. Fuente de diseño confirmada: **HomeB del repo**.
- El frontend (`frontend/`) ya está montado (Vite + React 19 + TS + Tailwind + supabase-js) y **desplegado** en Vercel (`frontend-delta-murex-29.vercel.app`), hablando **directo a Supabase**. Construimos sobre esa base — **sin backend ni infra nueva** para el demo.
- **Data real** (`akkoqdjmmozyqdfjkabg`, schema `myfinance`): 618 tx, moneda **COP**, 19 categorías con `color` + `icon` (emoji) + `display_name` (ES). Todos los montos son **positivos**; el sentido (ingreso/gasto) lo da `categories.type`.
- **Mes del demo: junio 2026** (129 tx, el último mes completo con data rica). Se rotula explícitamente "Junio 2026", no "este mes" (julio está casi vacío).
- **Trío Income/Spent/Saved: honesto tal cual.** Con junio, Ingresos capturados ($1,1M) ≪ Gastos ($20M) ⇒ "Saved" sale negativo. Se muestra real; el barrido de data (ingresos mal categorizados) queda **post-demo**.
- **Lectura en vivo, sin realtime.** La pantalla **recalcula todo (donut, trío, tipos) leyendo la DB en cada carga/refresh manual**. NO hay suscripciones realtime. Cuando se mejore la categorización o se corrijan tipos de transacción, el **siguiente refresh manual** ya refleja los nuevos valores. Se incluye un control de **refresh manual** (recargar / botón) además del reload del navegador. "Otros Gastos" y el barrido de datos son **post-demo** (trabajo de data, no de código).

### Números reales de junio 2026 (referencia)
| Métrica | Valor |
|---|---|
| Gastos (expense) | $20.083.078 (126 tx) |
| Ingresos (income) | $1.100.000 (3 tx) |
| Saved (income − spent) | **−$18.983.078** (se muestra honesto) |
| Donut top 4 | Otros Gastos 41% · Compras 27% · Pago de Deudas 15% · Salud 6% |

---

## 2. Alcance

### Dentro
1. Pantalla **Home "Reflection-first"** en marco tipo teléfono (mobile-first, canvas ~390px), con **barra de tabs inferior visual** (Home activo; Goals/Stats/Me presentes, no funcionales; Stats puede enlazar a `/transactions`).
2. Secciones (todas con **data real de junio 2026**):
   - Header: fecha + "Hola, {nombre}." + avatar.
   - **Donut** "¿A dónde se fue tu dinero?" — gasto por categoría (color real) + leyenda top 4 con %.
   - **Tarjeta de insight** "Esta semana/mes": frase generada de un outlier **real** (categoría de mayor gasto o mayor transacción del mes). Botones Revisar / Marcar (visuales).
   - **Trío** Ingresos / Gastos / Ahorro — reales y honestos (Ahorro puede ser negativo).
   - **"Vale una segunda mirada"**: top 3 transacciones grandes reales del mes; botones Worth it / Regret **visuales** (no persisten — es preview).
3. **Formateo COP** (`Intl.NumberFormat('es-CO')`): completo `$20.083.078` y compacto `$20,1 M` para héroes.
4. Ruta `/home` detrás del login existente (`RequireAuth`); `/` redirige a `/home`. Se conserva `/transactions`.
5. **Deploy a Vercel** (misma config existente).

6. **Segunda vista — "Movimientos" (scroll infinito, multi-banco):** lista de **todas** las transacciones en scroll infinito (React Query `useInfiniteQuery` sobre el paginado por rango ya existente), para demostrar al cliente que **captura todo** y de **múltiples bancos**. Cada fila muestra **badge de banco + ····last4** (data real: **Bancolombia** 317 tx + **Davivienda** 301 tx = 618). Encabezado con conteo total y bancos activos ("618 movimientos · 2 bancos"). Filtro rápido por banco (chips). Lectura en vivo; refresh manual.

### Fuera (post-demo — ver §7)
- Endpoints de agregación en el backend Java, deploy del backend, categorización LLM/n8n, Goals/Me funcionales, persistencia de Worth it/Regret, Decimal.js, dominio propio.
- **No** se arregla el `accountsService` legacy (pide columnas `name`/`account_type` inexistentes; reales: `nickname`/`type`/`last4`). La vista nueva usa su propio servicio con columnas correctas. Fix del legacy = post-demo.

---

## 3. Diseño de la pantalla (mapeo HomeB → data real)

| Sección HomeB | Fuente de datos real | Nota |
|---|---|---|
| Header "Hi {name}." | email del usuario (`useAuth`) → primer nombre/inicial | fecha = "Junio 2026" (mes del demo) |
| Donut + centro "Spent" | Σ gasto junio por `categories.type='expense'`, agrupado por categoría | centro = `$20,1 M`; segmentos con `categories.color` |
| Leyenda (4 filas %) | top 4 categorías de gasto | % sobre total gasto |
| Insight card | outlier real (categoría top = "Otros Gastos" $8,2M, o mayor tx del mes) | frase en ES; botones visuales |
| Trío Ingresos/Gastos/Ahorro | Σ income, Σ expense, income−expense (junio) | **honesto**; Ahorro negativo permitido, color rojo si <0 |
| "Vale una segunda mirada" | top 3 tx de gasto del mes por monto | botones Worth it/Regret sin persistencia |

Tokens/estilo: Tailwind (`surface-*`, `brand-*`, `content-*`, `rounded-card`, `num-display`) ya definidos; fuente Geist ya cargada. Se invoca la skill **`my-finance-view-design`** al construir para clavar la marca.

---

## 4. Capa de datos (demo-only, en el frontend)

Nuevo servicio **`src/services/homeSummaryService.ts`**:
- `getMonthlySummary(monthStartISO, monthEndISO)`:
  1. Fetch transacciones del mes: `supabase.schema('myfinance').from('transactions').select('id, amount, occurred_at, description, category_id').gte('occurred_at', start).lt('occurred_at', end)`.
  2. Fetch categorías con `color, icon, type, display_name, name` (extender el select existente).
  3. **Agrega en JS** (join por `category_id`): totales income/expense, gasto por categoría (ordenado desc), top tx del mes, outlier.
- Devuelve un DTO `HomeSummary` inmutable (record-like) ya "listo para render" (montos formateados + segmentos del donut).
- Hook `useHomeSummary(month)` con React Query (patrón existente).

> **Deuda técnica consciente:** esto **viola** la regla "el frontend nunca agrega dinero" y usa `Number` en vez de `Decimal.js`. Es **aceptable solo para el demo** (agregación de ~126 filas, valores de display). Post-demo se mueve a un endpoint del backend o una vista SQL. Se documenta en el código y en §7.

Formateo: **`src/lib/money.ts`** con `formatCOP(value)` → `$20.083.078` y `formatCOPCompact(value)` → `$20,1 M`.

---

## 5. Archivos a crear / modificar

**Crear:**
- `frontend/src/lib/money.ts` — formateadores COP.
- `frontend/src/services/homeSummaryService.ts` — agregación mensual.
- `frontend/src/hooks/useHomeSummary.ts` — React Query.
- `frontend/src/components/home/Donut.tsx` — donut SVG (port de `MFVDonut`).
- `frontend/src/components/home/MiniStat.tsx` — tarjeta del trío.
- `frontend/src/components/home/InsightCard.tsx` — tarjeta de insight.
- `frontend/src/components/home/ReflectionRow.tsx` — fila "vale una segunda mirada".
- `frontend/src/components/home/PhoneShell.tsx` — marco de teléfono + tab bar (Home + Movimientos activos; Goals/Me visuales).
- `frontend/src/pages/HomePage.tsx` — ensambla el Home.
- `frontend/src/services/ledgerService.ts` — transacciones + join a `accounts`/`banks` (columnas reales `nickname`/`type`/`last4`), paginado por rango para scroll infinito.
- `frontend/src/hooks/useInfiniteTransactions.ts` — `useInfiniteQuery`.
- `frontend/src/components/ledger/BankBadge.tsx` — badge de banco + ····last4.
- `frontend/src/components/ledger/LedgerRow.tsx` — fila de movimiento (con banco).
- `frontend/src/pages/MovimientosPage.tsx` — vista de scroll infinito multi-banco.

**Modificar:**
- `frontend/src/App.tsx` — rutas `/home` (RequireAuth) y `/` → `/home`.
- `frontend/src/services/categoriesService.ts` + `types.ts` — incluir `color`, `icon`, `type` en `CategoryDTO` (o tipo dedicado en el servicio de Home para no romper contratos existentes).

---

## 6. Verificación y deploy

1. **Local:** `npm run typecheck && npm run lint && npm run test` verdes; `npm run dev` y verificar visualmente el Home con data real (login con la cuenta dueña de la data).
2. **Fidelidad:** comparar contra `docs/design/raw/png/B _ Reflection-first.png` (ajustado a COP/junio).
3. **Build:** `npm run build` (Vercel corre typecheck+lint+test+build por `vercel.json`).
4. **Deploy:** push de la rama → deploy en Vercel (preview URL para compartir). Confirmar env vars `VITE_SUPABASE_URL` / `VITE_SUPABASE_ANON_KEY` en el proyecto Vercel.

---

## 7. Post-demo — brechas para "completar el MVP" (del análisis de engram)

- **Data:** barrido de ingresos mal categorizados (arregla el trío). Seed de `merchants`.
- **Backend:** decidir target de deploy (`TASK-BT-01`); endpoints de agregación (mover el cálculo del Home fuera del front); categorización LLM + feedback (Épica 2).
- **Migraciones pendientes:** cut/payment day + billing period, installments, smart-cat, `savings_goals`.
- **Frontend:** Decimal.js para dinero; pantallas Goals/Stats/Me; persistencia de feedback.
- **Infra:** RLS en `banks`/`exchange_rates` (`TASK-DT-01`); auto-deploy backend, smoke tests, monitoreo, dominio propio.

---

## 8. Riesgos

- **Ingresos sub-registrados** → "Ahorro" negativo. Mitigado: mostrado honesto + narrativa ("aún falta categorizar ingresos"); es un buen gancho de venta ("por esto necesitas la app").
- **"Otros Gastos" domina el donut (41%)** — es data real; puede ser talking point ("mejor categorización = valor de la app").
- **Agregación en el front** — demo-only, documentada; no apta para producción.
