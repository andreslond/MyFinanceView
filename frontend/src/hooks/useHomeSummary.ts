// frontend/src/hooks/useHomeSummary.ts
import { useQuery } from '@tanstack/react-query';
import { fetchCategoryIndex, getMonthlySummary } from '../services/homeSummaryService';

export function useHomeSummary(startISO: string, endISO: string, monthLabel: string) {
  return useQuery({ queryKey: ['home-summary', startISO, endISO], queryFn: () => getMonthlySummary(startISO, endISO, monthLabel) });
}
export function useCategoryIndex() {
  return useQuery({ queryKey: ['category-index'], queryFn: fetchCategoryIndex, staleTime: 5 * 60_000 });
}
