current_task: "§12 Cierre — /commit + /opsx:archive + /openspec-sync-specs"

last_completed: "§11 GREEN — V004 + V005 aplicados a Supabase remoto (akkoqdjmmozyqdfjkabg, us-west-2, Postgres 17.6) vía MCP apply_migration. Verificación post-V004: display_name NOT NULL, 0 nulls, 19 system rows backfilled (Housing→Hogar, Dining Out→Restaurantes y Cafés). Verificación post-V005: merchants tabla creada vacía con RLS + 4 policies (select/insert/update/delete TO authenticated USING auth.uid=user_id), transactions.merchant_id uuid nullable agregada con FK + partial index, ambos índices presentes (idx_merchants_user_category + idx_transactions_merchant_id WHERE merchant_id IS NOT NULL), 512 transactions intactas con merchant_id=NULL (sin pérdida de datos). Security advisors: 5 warnings TOTAL — ninguno nuevo de V005 (todos pre-existentes: pg_trgm/vector en public, OTP expiry, leaked-password protection, trigger_set_updated_at search_path mutable). Tasks §11.1-§11.6 [x]."

next_step: "/commit del apply (chunk de schema apply, con SQL aplicado + verificación + advisors snapshot). Después /opsx:archive backend-mvp-readonly (mueve openspec/changes/backend-mvp-readonly/ → openspec/changes/archive/2026-06-08-backend-mvp-readonly/). Después /openspec-sync-specs (merge deltas del change a openspec/specs/backend-rest-api/). Después PR opcional via gh pr create (NO push automático sin operator ask)."

last_updated: "2026-06-08T12:30:00Z"

blockers: []

decisions_pending_design_update: []

decisions_absorbed_2026_06_02:
  - "B7: Auth uses ES256 via JWKS public endpoint."
  - "B1+B2: V004=display_name, V005=merchants (split)."
  - "B3: CategoryDTO drops parentId."
  - "B4: AccountDTO.name ← accounts.nickname."
  - "B5: Feedback loop +0.10 cap 1.00."
  - "B6: PATCH visibility guard del categoryId → 404 (D10 anti-IDOR)."
  - "M2: Drift detection reset merchant."
  - "M4: CORS reject preflight → 403."
  - "M7: ProblemDetail zero-echo."
  - "M8: Actuator hardened a /health only."
  - "M9: MerchantUpserter sin parámetro DSLContext."
  - "M12: TestJwtFactory + WireMock JWKS server."
  - "D12: MerchantNormalizer.normalize congelada."

key_findings_during_impl:
  - "V003 seeds categories.name with ENGLISH display labels (Housing, Dining Out)."
  - "V005 SQL uses bare uuid_generate_v4() (matches V001 style)."
  - "@Container static + per-test applyAllMigrations() causa CREATE TYPE duplicate. Patrón: @BeforeAll static + simple openConnection()."
  - "@Testcontainers extension llama container.stop() en @AfterAll → mata reused container en Windows. Patrón project-wide: manual `static { if (!postgres.isRunning()) postgres.start() }`, NO annotations. Ryuk maneja JVM-exit cleanup."
  - "Spring Security 6.x CorsConfigurer NO consulta @Bean CorsProcessor del contexto. 'Invalid CORS request' marker body es aceptable."
  - "ProblemDetailAdvice's @ExceptionHandler(Exception.class) atrapa NoResourceFoundException → 500 en /actuator/* deshabilitados. Necesita @ExceptionHandler(NoResourceFoundException.class) → 404 explícito."
  - "Java UUID.compareTo (signed-long) vs PostgreSQL UUID order (unsigned-byte) discrepan cuando MSB activo. Tests de tiebreaker ordering deben verificar conjunto, no orden absoluto Java."
  - "Supabase migration registry estaba incompleto pre-apply (solo v002_rls_policies + drop_chatwoot_artifacts), pero el schema state actual era V001+V002+V003-equivalent. V004+V005 se aplicaron sobre ese estado real sin issues; futuras migrations seguirán siendo trackeadas correctamente."

ops_decisions_2026_06_02:
  - "Adversarial review BEFORE /opsx:apply."
  - "V004+V005 a Supabase remoto: aplicado 2026-06-08 con literal `proceed` token operator-issued tras backup local verificado (backups/local/myfinance-pre-v004-v005-2026-06-08.sql, 545 INSERTs)."
  - "Push policy: local-only hasta que el operador pida PR."
  - "OQ1-OQ5 defaults: aceptadas."

state_snapshot_pre_v004_v005: backups/local/myfinance-pre-v004-v005-2026-06-08.sql

state_snapshot_post_v004_v005:
  applied_at: "2026-06-08T12:30:00Z"
  v004_post:
    display_name_nullable: "NO"
    null_count: 0
    system_count: 19
    dining_out_label: "Restaurantes y Cafés"
    housing_label: "Hogar"
  v005_post:
    merchants_rows: 0
    merchants_rls_enabled: true
    merchants_policy_count: 4
    merchant_id_type: "uuid"
    merchant_id_nullable: "YES"
    idx_merchants_user_category: 1
    idx_transactions_merchant_id: 1
    tx_count_unchanged: 512
    tx_with_null_merchant: 512
  security_advisors_new_from_v005: 0
  security_advisors_preexisting_warn: 5
