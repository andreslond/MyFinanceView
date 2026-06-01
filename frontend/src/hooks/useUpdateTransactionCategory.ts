import { useMutation, useQueryClient } from '@tanstack/react-query';
import { transactionsService } from '../services/transactionsService';

interface Vars {
  id: string;
  categoryId: string;
}

export function useUpdateTransactionCategory() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, categoryId }: Vars) => transactionsService.updateCategory(id, categoryId),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['transactions'] });
    }
  });
}
