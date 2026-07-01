import { formatCOPCompact } from './money';

// ─────────────────────────────────────────────────────────────
// Pure monthly aggregation for the Home "Reflection-first" dashboard.
// No I/O here (the isolation test forbids supabase imports under lib/).
// income vs expense is decided by category TYPE, never by amount sign
// (all DB amounts are positive). Uncategorized counts as expense.
// ─────────────────────────────────────────────────────────────

export interface CategoryMeta {
  id: string;
  name: string;
  displayName: string | null;
  color: string;
  icon: string; // emoji
  type: 'income' | 'expense';
}

export interface RawTx {
  id: string;
  amount: number;
  occurredAt: string;
  description: string;
  categoryId: string | null;
}

export interface DonutSegment {
  label: string;
  value: number;
  color: string;
  pct: number;
}

export interface ReflectionItem {
  id: string;
  description: string;
  amount: number;
  occurredAt: string;
  category: CategoryMeta | null;
}

export interface HomeSummary {
  monthLabel: string;
  income: number;
  expense: number;
  saved: number; // income − expense (may be negative — shown honestly)
  txCount: number;
  segments: DonutSegment[]; // all expense categories, desc by value
  legend: DonutSegment[]; // top 4
  insight: string | null;
  reflections: ReflectionItem[]; // top 3 expenses by amount
}

const FALLBACK_COLOR = '#8B95B2';
const labelOf = (c: CategoryMeta): string => c.displayName ?? c.name;

export function computeMonthlySummary(
  txs: RawTx[],
  catIndex: Map<string, CategoryMeta>,
  monthLabel: string
): HomeSummary {
  let income = 0;
  let expense = 0;
  const byCat = new Map<string, { sum: number; cat: CategoryMeta }>();
  const expenseTx: ReflectionItem[] = [];

  for (const t of txs) {
    if (!Number.isFinite(t.amount)) continue;
    const cat = t.categoryId ? catIndex.get(t.categoryId) ?? null : null;

    if (cat?.type === 'income') {
      income += t.amount;
      continue;
    }

    expense += t.amount;
    const resolved: CategoryMeta =
      cat ?? {
        id: '__none__',
        name: 'Sin categoría',
        displayName: null,
        color: FALLBACK_COLOR,
        icon: '•',
        type: 'expense'
      };
    const prev = byCat.get(resolved.id);
    if (prev) prev.sum += t.amount;
    else byCat.set(resolved.id, { sum: t.amount, cat: resolved });

    expenseTx.push({
      id: t.id,
      description: t.description,
      amount: t.amount,
      occurredAt: t.occurredAt,
      category: cat
    });
  }

  const segments: DonutSegment[] = [...byCat.values()]
    .map((v) => ({
      label: labelOf(v.cat),
      value: v.sum,
      color: v.cat.color,
      pct: expense > 0 ? Math.round((v.sum / expense) * 100) : 0
    }))
    .sort((a, b) => b.value - a.value);

  const reflections = [...expenseTx].sort((a, b) => b.amount - a.amount).slice(0, 3);

  let insight: string | null = null;
  const topCat = segments[0];
  const biggest = reflections[0];
  if (topCat) {
    insight = `En ${monthLabel}, ${topCat.label} fue tu categoría más alta: ${formatCOPCompact(
      topCat.value
    )} (${topCat.pct}% del gasto).`;
    if (biggest) {
      insight += ` Tu movimiento más grande: ${biggest.description} por ${formatCOPCompact(
        biggest.amount
      )}.`;
    }
  }

  return {
    monthLabel,
    income,
    expense,
    saved: income - expense,
    txCount: txs.length,
    segments,
    legend: segments.slice(0, 4),
    insight,
    reflections
  };
}
