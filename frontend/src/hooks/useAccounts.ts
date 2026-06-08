import { useQuery } from '@tanstack/react-query';
import { accountsService } from '../services/accountsService';

export function useAccounts() {
  return useQuery({
    queryKey: ['accounts'],
    queryFn: () => accountsService.list(),
    staleTime: 5 * 60_000
  });
}
