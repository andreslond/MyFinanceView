import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

// Per design.md D3 + frontend/AGENTS.md:
// @supabase/supabase-js may ONLY be imported from src/lib/supabaseClient.ts
// and any file under src/services/. Anywhere else fails the build.

const SRC = join(__dirname, '..');
const ALLOWLIST = new Set<string>([
  'lib/supabaseClient.ts'
]);

function isAllowed(relPath: string): boolean {
  const normalized = relPath.split(sep).join('/');
  if (ALLOWLIST.has(normalized)) return true;
  if (normalized.startsWith('services/')) return true;
  return false;
}

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === 'dist') continue;
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      walk(full, out);
    } else if (/\.(ts|tsx)$/.test(entry)) {
      out.push(full);
    }
  }
  return out;
}

const SUPABASE_IMPORT_RE = /from\s+['"]@supabase\/supabase-js['"]/;

describe('supabase-js isolation', () => {
  it('only lib/supabaseClient.ts and services/* may import @supabase/supabase-js', () => {
    const offenders: string[] = [];
    for (const file of walk(SRC)) {
      const rel = relative(SRC, file);
      const content = readFileSync(file, 'utf8');
      if (SUPABASE_IMPORT_RE.test(content) && !isAllowed(rel)) {
        offenders.push(rel);
      }
    }
    expect(
      offenders,
      `Files outside the allowlist must NOT import @supabase/supabase-js:\n${offenders.join('\n')}`
    ).toEqual([]);
  });
});
