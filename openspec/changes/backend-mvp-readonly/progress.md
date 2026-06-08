current_task: "§10 STOP — operator gate: pre-V004 backup + state snapshot. AWAITING operator backup + literal `proceed` token before §11 (apply V004+V005 to Supabase remote via MCP)."

last_completed: "§7 + §8 GREEN — suite 125/0/0/0. §7: 3 controllers (Transaction/Account/Category), ProblemDetailAdvice (incl. NoResourceFoundException → 404 for disabled actuator paths), CorsConfig (note: Spring Security 6 ignores @Bean CorsProcessor; 'Invalid CORS request' marker body is acceptable — 21 bytes, no PII; security met by 403 + no ACAO headers). §8 leftovers: .env.example refreshed (added SUPABASE_JWT_JWKS_URI/ISSUER, SUPABASE_DB_URL, APP_CORS_ALLOWED_ORIGINS; dropped legacy SUPABASE_JWT_SECRET); docs/development-guide.md appended `Running the backend MVP locally` section. tasks.md §7.1-7.12 and §8.1-8.6 all [x]. Also fixed pre-existing flaky test shouldOrderByOccurredAtDescThenIdDesc (Java UUID.compareTo signed-long vs Postgres unsigned-byte semantic mismatch on MSB)."

next_step: "Operator action: (1) backup Supabase project state (Studio → Database → Backups → on-demand snapshot OR equivalent), (2) confirm with literal token `proceed`. Then agent applies V004 + V005 to Supabase remote via Supabase MCP (apply_migration), runs state verification (categories.display_name NOT NULL, merchants table exists, transactions.merchant_id FK), then /commit + PR + /opsx:archive + /openspec-sync-specs."

last_updated: "2026-06-08T05:00:00Z"

blockers: []

decisions_pending_design_update: []

decisions_absorbed_2026_06_02:
  - "B7: Auth uses ES256 via JWKS public endpoint (verified by curl probe); not HS256/symmetric."
  - "B1+B2: V004=display_name, V005=merchants (split). Cola posterior corre +1."
  - "B3: CategoryDTO drops parentId (no existe en schema)."
  - "B4: AccountDTO.name ← accounts.nickname (rename en mapper, no en schema)."
  - "B5: Feedback loop +0.10 cap 1.00 (SPEC.md gana sobre +0.05/0.95)."
  - "B6: PATCH visibility guard del categoryId → 404 sin diferenciar (D10 anti-IDOR)."
  - "M2: Drift detection en feedback loop — reset merchant a confidence=0.50, match_count=1 cuando user discrepa."
  - "M4: CORS reject preflight → 403 explícito."
  - "M7: ProblemDetail zero-echo (token, claims, UUID rechazado, descripción)."
  - "M8: Actuator hardened a /health only; resto deshabilitado → 404."
  - "M9: MerchantUpserter sin parámetro DSLContext (service inyecta repo)."
  - "M12: TestJwtFactory (EC P-256 efímero) + WireMock JWKS server."
  - "D12 nuevo: MerchantNormalizer.normalize congelada con regla exacta."

key_findings_during_impl:
  - "V003 seeds categories.name with ENGLISH display labels ('Housing', 'Dining Out')."
  - "V005 SQL uses bare uuid_generate_v4() (matches V001 style)."
  - "@Container static + per-test applyAllMigrations() causa CREATE TYPE duplicate. Patrón: @BeforeAll static + simple openConnection()."
  - "@SpringBootTest reusa ApplicationContext → JwtDecoder JWKS cache permanece caliente. Rotation tests deben resetRequestCount entre fases."
  - "spring-boot-starter-oauth2-resource-server requiere JwtDecoder bean; blank jwks-uri → fallback lambda decoder que tira BadJwtException."
  - "@Testcontainers extension llama container.stop() en @AfterAll → mata reused container en Windows. Patrón project-wide: manual `static { if (!postgres.isRunning()) postgres.start() }`, NO annotations. Ryuk maneja JVM-exit cleanup."
  - "Spring Security 6.x CorsConfigurer NO consulta @Bean CorsProcessor del contexto. 'Invalid CORS request' marker body es aceptable."
  - "ProblemDetailAdvice's @ExceptionHandler(Exception.class) atrapa NoResourceFoundException → 500 en /actuator/* deshabilitados. Necesita @ExceptionHandler(NoResourceFoundException.class) → 404 explícito."
  - "Java UUID.compareTo (signed-long sobre mostSigBits) vs PostgreSQL UUID order (unsigned-byte sobre 16 bytes) discrepan cuando MSB activo. Tests de tiebreaker ordering deben usar verificación de conjunto, no orden absoluto Java."

ops_decisions_2026_06_02:
  - "Adversarial review BEFORE /opsx:apply."
  - "V004+V005 a Supabase remoto: agente autorizado vía MCP, condicional a Docker local test verde + state snapshot + operator gate manual (literal `proceed`)."
  - "Push policy: local-only hasta que el operador pida PR."
  - "OQ1-OQ5 defaults: aceptadas."

state_snapshot_pre_v004_v005: pending_at_task_10
