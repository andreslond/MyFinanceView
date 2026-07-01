// frontend/src/services/ledgerService.ts
import { supabase } from '../lib/supabaseClient';
import { paginate } from '../lib/paging';

export interface AccountMeta { id: string; bankId: string; bankName: string; last4: string | null; nickname: string | null; type: string; }
export interface BankSummary { bankId: string; bankName: string; accountIds: string[]; count: number; }
export interface LedgerTx { id: string; amount: number; occurredAt: string; description: string; categoryId: string | null; accountId: string; }
export interface LedgerPage { rows: LedgerTx[]; page: number; hasMore: boolean; }

interface AccountRow { id: string; bank_id: string; last4: string | null; nickname: string | null; type: string; }
interface BankRow { id: string; name: string; }
interface TxRow { id: string; amount: string; occurred_at: string; description: string; category_id: string | null; account_id: string; }

export async function fetchAccountsIndex(): Promise<Map<string, AccountMeta>> {
  const [accountsRes, banksRes] = await Promise.all([
    supabase.schema('myfinance').from('accounts').select('id, bank_id, last4, nickname, type'),
    supabase.schema('myfinance').from('banks').select('id, name')
  ]);
  if (accountsRes.error) throw accountsRes.error;
  if (banksRes.error) throw banksRes.error;
  const bankName = new Map<string, string>();
  for (const b of (banksRes.data ?? []) as BankRow[]) bankName.set(b.id, b.name);
  const map = new Map<string, AccountMeta>();
  for (const a of (accountsRes.data ?? []) as AccountRow[]) {
    map.set(a.id, { id: a.id, bankId: a.bank_id, bankName: bankName.get(a.bank_id) ?? 'Banco', last4: a.last4, nickname: a.nickname, type: a.type });
  }
  return map;
}

export async function fetchBankSummaries(index: Map<string, AccountMeta>): Promise<BankSummary[]> {
  const byBank = new Map<string, BankSummary>();
  for (const a of index.values()) {
    const s = byBank.get(a.bankId);
    if (s) s.accountIds.push(a.id);
    else byBank.set(a.bankId, { bankId: a.bankId, bankName: a.bankName, accountIds: [a.id], count: 0 });
  }
  const summaries = [...byBank.values()];
  await Promise.all(summaries.map(async (s) => {
    const { count, error } = await supabase.schema('myfinance').from('transactions')
      .select('id', { count: 'exact', head: true }).in('account_id', s.accountIds);
    if (error) throw error;
    s.count = count ?? 0;
  }));
  return summaries.filter((s) => s.count > 0).sort((a, b) => b.count - a.count);
}

export async function listLedgerPage(page: number, pageSize: number, accountIds?: string[]): Promise<LedgerPage> {
  const from = page * pageSize;
  const to = from + pageSize; // one extra to detect hasMore
  let query = supabase.schema('myfinance').from('transactions')
    .select('id, amount, occurred_at, description, category_id, account_id')
    .order('occurred_at', { ascending: false }).range(from, to);
  if (accountIds && accountIds.length > 0) query = query.in('account_id', accountIds);
  const { data, error } = await query;
  if (error) throw error;
  const mapped: LedgerTx[] = ((data ?? []) as TxRow[]).map((r) => ({
    id: r.id, amount: Number.parseFloat(r.amount), occurredAt: r.occurred_at, description: r.description, categoryId: r.category_id, accountId: r.account_id
  }));
  const { rows, hasMore } = paginate(mapped, pageSize);
  return { rows, page, hasMore };
}
