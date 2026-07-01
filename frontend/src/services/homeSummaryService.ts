// frontend/src/services/homeSummaryService.ts
import { supabase } from '../lib/supabaseClient';
import { computeMonthlySummary, type CategoryMeta, type HomeSummary, type RawTx } from '../lib/homeSummary';

interface CategoryRow { id: string; name: string; display_name: string | null; color: string | null; icon: string | null; type: 'income' | 'expense'; }
interface TxRow { id: string; amount: string; occurred_at: string; description: string; category_id: string | null; }

export async function fetchCategoryIndex(): Promise<Map<string, CategoryMeta>> {
  const { data, error } = await supabase
    .schema('myfinance').from('categories')
    .select('id, name, display_name, color, icon, type');
  if (error) throw error;
  const map = new Map<string, CategoryMeta>();
  for (const r of (data ?? []) as CategoryRow[]) {
    map.set(r.id, { id: r.id, name: r.name, displayName: r.display_name, color: r.color ?? '#8B95B2', icon: r.icon ?? '•', type: r.type });
  }
  return map;
}

export async function getMonthlySummary(startISO: string, endISO: string, monthLabel: string): Promise<HomeSummary> {
  const catIndex = await fetchCategoryIndex();
  const { data, error } = await supabase
    .schema('myfinance').from('transactions')
    .select('id, amount, occurred_at, description, category_id')
    .gte('occurred_at', startISO).lt('occurred_at', endISO)
    .limit(3000);
  if (error) throw error;
  const txs: RawTx[] = ((data ?? []) as TxRow[]).map((r) => ({
    id: r.id, amount: Number.parseFloat(r.amount), occurredAt: r.occurred_at, description: r.description, categoryId: r.category_id
  }));
  return computeMonthlySummary(txs, catIndex, monthLabel);
}
