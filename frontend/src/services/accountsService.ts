import { supabase } from '../lib/supabaseClient';
import type { AccountDTO } from './types';

interface AccountRow {
  id: string;
  user_id: string;
  name: string;
  bank_id: string;
  account_type: string;
  currency: string;
  created_at: string;
  updated_at: string;
}

function rowToDTO(row: AccountRow): AccountDTO {
  return {
    id: row.id,
    userId: row.user_id,
    name: row.name,
    bankId: row.bank_id,
    accountType: row.account_type,
    currency: row.currency,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

export const accountsService = {
  async list(): Promise<AccountDTO[]> {
    const { data, error } = await supabase
      .schema('myfinance')
      .from('accounts')
      .select('id, user_id, name, bank_id, account_type, currency, created_at, updated_at')
      .order('name', { ascending: true });
    if (error) throw error;
    return ((data ?? []) as AccountRow[]).map(rowToDTO);
  }
};
