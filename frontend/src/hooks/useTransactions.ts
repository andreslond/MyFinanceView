import { useQuery } from '@tanstack/react-query';
import { transactionsService } from '../services/transactionsService';
import type { TransactionFilters } from '../services/types';

export function useTransactions(filters: TransactionFilters) {
  return useQuery({
    queryKey: ['transactions', filters],
    queryFn: () => transactionsService.list(filters)
  });
}
