import { supabase } from '../lib/supabaseClient';
import type { CategoryDTO } from './types';

interface CategoryRow {
  id: string;
  user_id: string | null;
  name: string;
  display_name: string | null;
  parent_id: string | null;
  created_at: string;
  updated_at: string;
}

function rowToDTO(row: CategoryRow): CategoryDTO {
  return {
    id: row.id,
    userId: row.user_id,
    name: row.name,
    displayName: row.display_name,
    parentId: row.parent_id,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  };
}

export const categoriesService = {
  async list(): Promise<CategoryDTO[]> {
    // Order by display_name when populated, else fallback to name (handled client-side
    // because PostgREST cannot order by COALESCE without a SQL view; cheap enough on
    // <50 categories).
    const { data, error } = await supabase
      .schema('myfinance')
      .from('categories')
      .select('id, user_id, name, display_name, parent_id, created_at, updated_at');
    if (error) throw error;
    const rows = ((data ?? []) as CategoryRow[]).map(rowToDTO);
    rows.sort((a, b) => {
      const aLabel = (a.displayName ?? a.name).toLocaleLowerCase('es');
      const bLabel = (b.displayName ?? b.name).toLocaleLowerCase('es');
      return aLabel.localeCompare(bLabel, 'es');
    });
    return rows;
  }
};
