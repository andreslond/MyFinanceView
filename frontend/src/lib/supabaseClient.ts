// Single source of truth for the supabase-js client.
// All other imports of @supabase/supabase-js are blocked by ESLint
// (`no-restricted-imports`) and the isolation test (src/__tests__/isolation.spec.ts).
// See frontend/AGENTS.md for the rationale.
import { createClient, type Session, type SupabaseClient, type User } from '@supabase/supabase-js';

// Re-export type symbols so consumers (e.g. AuthContext) don't trip the
// no-restricted-imports ESLint rule. Types carry no runtime dependency.
export type { Session, User };

const url = import.meta.env.VITE_SUPABASE_URL;
const anonKey = import.meta.env.VITE_SUPABASE_ANON_KEY;

if (!url || !anonKey) {
  throw new Error(
    'Missing VITE_SUPABASE_URL or VITE_SUPABASE_ANON_KEY. Copy frontend/.env.example to .env.local and fill it in.'
  );
}

export const supabase: SupabaseClient = createClient(url, anonKey, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    detectSessionInUrl: true
  }
});
