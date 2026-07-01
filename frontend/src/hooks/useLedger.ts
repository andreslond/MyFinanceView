// frontend/src/hooks/useLedger.ts
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { fetchAccountsIndex, fetchBankSummaries, listLedgerPage, type AccountMeta } from '../services/ledgerService';

const PAGE_SIZE = 30;

export function useAccountsIndex() {
  return useQuery({ queryKey: ['accounts-index'], queryFn: fetchAccountsIndex, staleTime: 5 * 60_000 });
}
export function useBankSummaries(index: Map<string, AccountMeta> | undefined) {
  return useQuery({ queryKey: ['bank-summaries'], queryFn: () => fetchBankSummaries(index as Map<string, AccountMeta>), enabled: !!index, staleTime: 5 * 60_000 });
}
export function useInfiniteTransactions(accountIds?: string[]) {
  return useInfiniteQuery({
    queryKey: ['ledger', accountIds ?? 'all'],
    queryFn: ({ pageParam }) => listLedgerPage(pageParam as number, PAGE_SIZE, accountIds),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.hasMore ? last.page + 1 : undefined)
  });
}
