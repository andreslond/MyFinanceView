import { supabase } from '../lib/supabaseClient';
import type { Page, TransactionDTO, TransactionFilters } from './types';

interface TransactionRow {
  id: string;
  user_id: string;
  account_id: string;
  category_id: string | null;
  occurred_at: string;
  amount: string;
  currency: string;
  description: string;
  merchant_id: string | null;
  created_at: string;
  updated_at: string;
}

function rowToDTO(row: TransactionRow): TransactionDTO {
  return {
    id: row.id,
    userId: row.user_id,
    accountId: row.account_id,
    categoryId: row.category_id,
    occurredAt: row.occurred_at,
    amount: row.amount,
    currency: row.currency,
    description: row.description,
    merchantId: row.merchant_id,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

const DEFAULT_PAGE_SIZE = 25;

export const transactionsService = {
  async list(filters: TransactionFilters = {}): Promise<Page<TransactionDTO>> {
    const page = Math.max(1, filters.page ?? 1);
    const pageSize = Math.max(1, filters.pageSize ?? DEFAULT_PAGE_SIZE);
    const from = (page - 1) * pageSize;
    const to = from + pageSize; // request one extra row to detect hasMore

    let query = supabase
      .schema('myfinance')
      .from('transactions')
      .select(
        'id, user_id, account_id, category_id, occurred_at, amount, currency, description, merchant_id, created_at, updated_at'
      )
      .order('occurred_at', { ascending: false })
      .range(from, to);

    if (filters.accountId) {
      query = query.eq('account_id', filters.accountId);
    }
    if (filters.categoryIds && filters.categoryIds.length > 0) {
      query = query.in('category_id', [...filters.categoryIds]);
    }

    const { data, error } = await query;
    if (error) throw error;

    const rows = (data ?? []) as TransactionRow[];
    const hasMore = rows.length > pageSize;
    const trimmed = hasMore ? rows.slice(0, pageSize) : rows;

    return {
      rows: trimmed.map(rowToDTO),
      page,
      pageSize,
      hasMore
    };
  },

  /**
   * Update ONLY category_id. Per design.md D2 + D8, the MVP does NOT touch
   * `category_confirmed` (column does not exist yet) and does NOT update
   * `myfinance.merchants` (that's the backend Java's job — TASK-BE-06).
   *
   * Idempotent: callers SHOULD compare `selectedCategoryId === currentCategoryId`
   * before invoking; this method assumes the caller has a real change to apply.
   */
  async updateCategory(id: string, categoryId: string): Promise<TransactionDTO> {
    const { data, error } = await supabase
      .schema('myfinance')
      .from('transactions')
      .update({ category_id: categoryId })
      .eq('id', id)
      .select(
        'id, user_id, account_id, category_id, occurred_at, amount, currency, description, merchant_id, created_at, updated_at'
      )
      .single();
    if (error) throw error;
    if (!data) throw new Error(`Transaction ${id} not found or RLS blocked the update.`);
    return rowToDTO(data as TransactionRow);
  }
};
