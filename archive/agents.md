# ⚠️ OBSOLETO — archivado 2026-05-13

> **Fuente de verdad actual:** [`SPEC.md`](../SPEC.md) (raíz del repo) y la [página Notion del proyecto](https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57).
>
> **Razón del archivo:** este documento propone Clean Architecture **por capas** (`domain/model`, `application/usecase`, `infrastructure/`, `interfaces/rest`), pero la decisión vigente es **monolito modular por dominio** (paquetes `api/`, `domain/{transaction,category,merchant,billing,savings,...}`, `db/`, `config/`) según [SPEC.md §3](../SPEC.md). Se conserva únicamente como referencia histórica.

---

# AGENTS_CONTEXT.md

# Project: My Finance View
# Type: Personal Finance Real-Time Budget Dashboard
# Architecture Level: A (Professional Clean Monolith)
# Cloud: AWS (ECS Fargate)
# Database: Supabase (PostgreSQL)
# Backend: Java 21 - Spring Boot 3
# Frontend: React 18 + Vite
# Auth: Supabase Auth (JWT validated by backend)

---

# 1. PROJECT MISSION

My Finance View is a real-time personal finance dashboard system that aggregates bank email notifications, normalizes financial transactions, and provides live budget tracking, projections, and intelligent alerts.

The system must:

- Ingest transactions from email (via n8n)
- Normalize and validate financial data
- Store structured transactions
- Track multi-currency operations
- Compute real-time financial summaries
- Provide monthly budget projections
- Trigger alerts for overspending
- Provide a responsive, real-time financial dashboard UI

The system is designed as a professional-grade backend using Clean Architecture principles and is intended to evolve into an event-driven system (Architecture B).

---

# 2. HIGH-LEVEL ARCHITECTURE

Gmail → n8n → Spring Boot Backend → Supabase Postgres  
                                   ↓  
                              React + Vite Frontend  

AWS Infrastructure:

Route53 (optional)
        ↓
ALB (HTTPS)
        ↓
ECS Fargate (Dockerized Spring Boot)
        ↓
Supabase (External PostgreSQL)

Frontend Deployment (recommended):
- Vercel OR S3 + CloudFront

---

# 3. ARCHITECTURE STYLE

Architecture Level A: Clean Professional Monolith

Backend:
- Clean Architecture
- DDD-lite (Domain Driven Design principles)
- Layered separation
- Domain isolation
- Idempotent ingestion
- Strong financial precision
Frontend:
- Modular React architecture
- API-driven UI
- No business logic duplication
- Pure presentation + state orchestration

System MUST evolve toward Architecture B without breaking frontend contracts.

---

# 4. BACKEND PACKAGE STRUCTURE

com.myfinanceview

domain/
  model/
  valueobject/
  repository/
  service/
  event/

application/
  usecase/
  dto/
  mapper/

infrastructure/
  persistence/
  config/
  security/

interfaces/
  rest/

---

# 5. AGENT RESPONSIBILITIES

---

## 5.1 Domain Agent

Owns:

- Financial business rules
- Money precision logic
- Transaction invariants
- Budget calculations
- Domain events

Rules:

- NEVER use double or float for money
- ALWAYS use BigDecimal
- ALWAYS use RoundingMode.HALF_EVEN
- Money must be immutable
- Currency must be explicit
- No framework dependencies allowed

Domain must remain pure Java.

---

## 5.2 Application Agent

Owns:

- Use cases
- Orchestration logic
- Transaction workflows
- Projection calculations
- Alert evaluation

Must NOT:

- Contain infrastructure code
- Contain persistence logic

---

## 5.3 Persistence Agent

Owns:

- JPA Entities
- Spring Data repositories
- Database configuration
- Query optimization

Constraints:

- PostgreSQL compatible
- external_id unique
- Indexes on user_id, occurred_at, type
- Connection pool max 5
- SSL required

---

## 5.4 API Agent

Owns:

- REST controllers
- Input validation
- JWT validation
- HTTP response mapping

Rules:

- Controllers must be thin
- No business logic in controllers
- Extract user_id from validated JWT
- Never trust client-provided user_id

Endpoints:

POST /transactions
GET /dashboard/today
GET /dashboard/month
GET /projection
POST /budget

---

## 5.5 Security Agent

Owns:

- JWT validation (Supabase JWT)
- Spring Security configuration
- HTTPS enforcement

Constraints:

- Never expose Supabase service role
- Backend validates JWT
- Frontend stores access token securely

---

## 5.6 DevOps Agent

Owns:

- Dockerfile
- GitHub Actions
- AWS ECS deployment
- ECR push
- ALB health checks
- Secrets management

Infrastructure Rules:

- Multi-stage Docker build
- Java 21
- JVM memory limits set
- Health endpoint: /actuator/health
- No credentials in code
- Use environment variables
- Use AWS Secrets Manager if possible

CI/CD Pipeline:

1. Checkout
2. Setup JDK
3. Maven build
4. Run tests
5. Build Docker image
6. Push to ECR
7. Deploy to ECS
8. Force new deployment

---

## 5.7 Financial Engine Agent

Owns:

- Monthly summary calculation
- Daily aggregation
- Budget comparison
- Projection model

Projection Formula:

(current_spend / days_elapsed) * total_days_in_month

Future evolution:

- Separate fixed vs variable expenses
- Predictive projection
- Anomaly detection

Must ensure:

- Deterministic results
- BigDecimal precision
- No floating point arithmetic
- Deterministic outputs

---

## 5.8 Currency Agent

Owns:

- Exchange rate management
- Base currency normalization
- Conversion logic

Rules:

- Store original amount
- Store converted base amount
- Store exchange rate used
- Never convert dynamically on dashboard queries

Conversion must happen at ingestion or application layer.
---

## 5.9 Event Evolution Agent (Future B Architecture)

Prepared but not active.

Responsibilities:

- Domain events
- Outbox pattern
- CQRS separation
- Read model optimization

---

# 6. FRONTEND ARCHITECTURE (React + Vite)

Frontend is a pure presentation and orchestration layer.

It must:

- Never contain financial calculation logic
- Never compute projections
- Never trust local calculations
- Render backend-calculated summaries
- Manage authentication state
- Provide responsive UI

---

# 6.1 Frontend Folder Structure

src/

api/
  client.ts
  dashboardApi.ts
  transactionApi.ts
  budgetApi.ts

components/
  ui/
  dashboard/
  transactions/
  charts/
  alerts/

pages/
  DashboardPage.tsx
  TransactionsPage.tsx
  BudgetPage.tsx
  LoginPage.tsx

hooks/
  useAuth.ts
  useDashboard.ts
  useTransactions.ts

store/
  authStore.ts
  uiStore.ts

types/
  api.ts
  models.ts

utils/
  formatters.ts

---

# 6.2 Frontend Agent Responsibilities

---

## Frontend Architecture Agent

Owns:

- Modular component structure
- Separation of API layer
- State management
- Routing

Must enforce:

- No business logic in components
- Use hooks for data fetching
- Strict typing (TypeScript recommended)

---

## Frontend API Agent

Owns:

- Axios or fetch wrapper
- JWT injection in headers
- Error normalization
- Retry handling

Must:

- Attach Authorization header
- Handle 401 by redirecting to login
- Never store service credentials

---

## Frontend Auth Agent

Owns:

- Supabase client configuration
- Login/logout
- Session refresh
- Token storage

Flow:

1. User logs in via Supabase
2. Supabase returns JWT
3. JWT stored securely (memory or secure storage)
4. JWT sent to backend on every request
5. Backend validates JWT

Frontend must NOT validate token logic itself.

---

## Frontend Dashboard Agent

Owns:

- Render monthly summary
- Render daily summary
- Render progress bar
- Render projections
- Render alert indicators
- Render transaction list
- Render charts

Must:

- Use backend aggregated endpoints
- Avoid local aggregation of transactions
- Display formatted monetary values only

---

## Frontend State Management Rules

Recommended:

- Zustand OR React Context (lightweight)
- React Query (recommended) for server state

Guidelines:

- Server state → React Query
- UI state → Zustand or local state
- No duplicated financial logic

---

# 7. FRONTEND UI REQUIREMENTS

Dashboard must display:

- Total spent today
- Total spent this month
- Total income this month
- Net balance
- Budget progress bar
- Projected end-of-month spend
- Alert indicators
- Transaction table
- Monthly cumulative chart

---

# 8. FRONTEND SECURITY RULES

- Never expose Supabase service role
- Never embed secrets
- Use HTTPS only
- Sanitize dynamic content
- Handle 401 globally
- Implement logout on invalid token

---

# 9. DATA MODEL PRINCIPLES

Transactions must include:

- id
- user_id
- account_id
- type
- amount
- currency
- amount_base_currency
- occurred_at
- external_id
- raw_payload
- created_at

---

# 10. PERFORMANCE RULES

Backend:
- All money operations use BigDecimal
- Rounding mode: HALF_EVEN
- Scale: 2 (configurable future)
- Timezone: UTC
- Use OffsetDateTime
- Avoid N+1
- Use aggregation queries
- Limit connection pool

Frontend:

- Paginate transactions
- Avoid loading full history
- Cache dashboard queries
- Lazy load heavy components

---

# 11. NON-FUNCTIONAL REQUIREMENTS

- Idempotent ingestion
- Deterministic calculations
- Auditability
- Containerized backend
- Cloud-ready
- Cost optimized
- Responsive frontend
- Clean UI architecture

---

# 12. DEPLOYMENT TARGET

Backend:
- AWS ECS Fargate
- ALB
- CloudWatch logs

Frontend:
- Vercel

---

# 13. PROJECT PHILOSOPHY

This is not a hobby project.

This system must reflect:

- Professional backend engineering
- Financial correctness
- Clean frontend architecture
- Cloud-native deployment
- CI/CD maturity
- Evolution-ready design

It should be suitable as:

- Production-grade personal system
- Technical portfolio highlight
- Foundation for fintech-level architecture

---

## 14. DATA MODEL

### 14.1 banks

- id (uuid)
- name (text)
- created_at (timestamp)

---

### 14.2 accounts

- id (uuid)
- user_id (uuid)
- bank_id (uuid)
- type (checking | savings | credit_card)
- currency (ISO 4217 string)
- last4 (text)
- active (boolean)
- created_at (timestamp)

---

### 14.3 transactions

- id (uuid)
- user_id (uuid)
- account_id (uuid)
- type (
    credit_card_purchase |
    debit_purchase |
    credit_card_payment |
    incoming_transfer |
    outgoing_transfer |
    incoming_payment
  )
- amount (numeric)
- currency (ISO 4217)
- amount_base_currency (numeric)
- description (text)
- occurred_at (timestamp)
- source (gmail | manual | api)
- external_id (email message_id)
- raw_payload (jsonb)
- created_at (timestamp)

Constraints:
- Unique index on external_id
- Index on occurred_at
- Index on type

---

### 14.4 budgets

- id (uuid)
- user_id (uuid)
- month (int)
- year (int)
- total_budget (numeric)
- currency (ISO 4217)
- created_at (timestamp)

---

### 14.5 exchange_rates

- id (uuid)
- base_currency
- target_currency
- rate
- date
- created_at

---

## 15. TRANSACTION LOGIC

### Transaction Types Mapping

Email parsing must classify into:

- credit_card_purchase
- debit_purchase
- credit_card_payment
- incoming_transfer
- outgoing_transfer
- incoming_payment

---

### Base Currency

System must define one base currency (e.g., COP).

Every transaction must store:

- Original amount
- Original currency
- Converted amount to base currency

Conversion occurs during ingestion or backend normalization.

---

END OF FILE