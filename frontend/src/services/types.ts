// DTOs exposed by services to the rest of the app.
// Camel-case, ISO 8601 strings for time, monetary amounts as strings
// (parsed at the render boundary with Decimal.js or similar — never
// added with `.reduce()`; see frontend/AGENTS.md and design-system.md §9).
//
// IMPORTANT: shape is INVENTED until docs/api-spec.yml ships schemas
// for these DTOs. When it does (post-TASK-BE-04), realign the mapper
// in each service and update this file. See design.md D4 + proposal
// "## Forward-compat note".

export interface TransactionDTO {
  id: string;
  userId: string;
  accountId: string;
  categoryId: string | null;
  occurredAt: string; // ISO 8601, UTC
  amount: string; // decimal string, never number
  currency: string;
  description: string;
  merchantId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AccountDTO {
  id: string;
  userId: string;
  name: string;
  bankId: string;
  accountType: string;
  currency: string;
  createdAt: string;
  updatedAt: string;
}

export interface CategoryDTO {
  id: string;
  userId: string | null; // null = system category
  name: string; // technical name (English)
  displayName: string | null; // Spanish; falls back to `name` when null
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  rows: T[];
  page: number;
  pageSize: number;
  hasMore: boolean; // we do NOT expose total — see spec "Paginación con siguiente/anterior"
}

export interface TransactionFilters {
  accountId?: string | undefined;
  categoryIds?: readonly string[] | undefined;
  page?: number | undefined;
  pageSize?: number | undefined;
}
