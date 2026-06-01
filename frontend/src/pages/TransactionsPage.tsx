import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useAccounts } from '../hooks/useAccounts';
import { useCategories } from '../hooks/useCategories';
import { useTransactions } from '../hooks/useTransactions';
import { useAuth } from '../auth/AuthContext';
import { TxRow } from '../components/TxRow';
import { CategoryChangeModal } from '../components/CategoryChangeModal';
import type { CategoryDTO, TransactionDTO } from '../services/types';

const PAGE_SIZE = 25;

function parseCategoryIds(raw: string | null): readonly string[] {
  if (!raw) return [];
  return raw.split(',').filter(Boolean);
}

export default function TransactionsPage() {
  const [params, setParams] = useSearchParams();
  const { user, signOut } = useAuth();

  const accountId = params.get('accountId') ?? undefined;
  const categoryIds = parseCategoryIds(params.get('categoryIds'));
  const page = Math.max(1, Number.parseInt(params.get('page') ?? '1', 10) || 1);

  const filters = useMemo(
    () => ({
      accountId,
      categoryIds: categoryIds.length > 0 ? categoryIds : undefined,
      page,
      pageSize: PAGE_SIZE
    }),
    [accountId, categoryIds, page]
  );

  const transactionsQ = useTransactions(filters);
  const accountsQ = useAccounts();
  const categoriesQ = useCategories();

  const categoriesById = useMemo(() => {
    const map = new Map<string, CategoryDTO>();
    for (const c of categoriesQ.data ?? []) map.set(c.id, c);
    return map;
  }, [categoriesQ.data]);

  const [editingTx, setEditingTx] = useState<TransactionDTO | null>(null);

  const setFilter = (key: 'accountId' | 'categoryIds' | 'page', value: string | null) => {
    const next = new URLSearchParams(params);
    if (value === null || value === '') {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    // Reset to page 1 when filters change (except for page itself)
    if (key !== 'page') next.delete('page');
    setParams(next, { replace: false });
  };

  const toggleCategory = (id: string) => {
    const set = new Set(categoryIds);
    if (set.has(id)) set.delete(id);
    else set.add(id);
    setFilter('categoryIds', set.size > 0 ? Array.from(set).join(',') : null);
  };

  return (
    <div className="min-h-screen bg-surface-base">
      <header className="border-b border-surface-border bg-surface-sunken">
        <div className="mx-auto max-w-5xl px-4 py-4 flex items-center justify-between">
          <div>
            <p className="text-content-muted uppercase tracking-[0.6px] text-[11px] font-semibold">
              MyFinanceView
            </p>
            <h1 className="mt-1 text-content-primary text-[22px] font-bold tracking-[-0.6px]">
              Transacciones
            </h1>
          </div>
          <div className="flex items-center gap-3">
            <span className="text-content-secondary text-xs hidden sm:inline">
              {user?.email ?? ''}
            </span>
            <button
              type="button"
              onClick={() => signOut()}
              className="rounded-btn border border-surface-border bg-transparent px-3 py-1.5 text-content-secondary text-xs hover:text-content-primary"
            >
              Cerrar sesión
            </button>
          </div>
        </div>
      </header>

      <main className="mx-auto max-w-5xl px-4 py-6">
        <section className="mb-4 flex flex-wrap items-center gap-2">
          <label className="text-content-secondary text-xs">
            Cuenta:
            <select
              value={accountId ?? ''}
              onChange={(e) => setFilter('accountId', e.target.value || null)}
              className="ml-2 rounded-chip border border-surface-border bg-surface-raised px-2 py-1 text-content-primary text-xs focus:border-brand-purple focus:outline-none"
            >
              <option value="">Todas</option>
              {(accountsQ.data ?? []).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </label>

          <div className="flex items-center gap-2 flex-wrap">
            <span className="text-content-secondary text-xs">Categorías:</span>
            {(categoriesQ.data ?? []).slice(0, 8).map((c) => {
              const active = categoryIds.includes(c.id);
              return (
                <button
                  key={c.id}
                  type="button"
                  onClick={() => toggleCategory(c.id)}
                  className={`rounded-chip border px-2 py-1 text-xs transition-colors ${
                    active
                      ? 'border-brand-purple bg-brand-purple/20 text-content-primary'
                      : 'border-surface-border bg-surface-raised text-content-secondary hover:border-brand-purple/60'
                  }`}
                >
                  {c.displayName ?? c.name}
                </button>
              );
            })}
            {categoryIds.length > 0 && (
              <button
                type="button"
                onClick={() => setFilter('categoryIds', null)}
                className="text-content-muted text-xs hover:text-content-primary"
              >
                Limpiar
              </button>
            )}
          </div>
        </section>

        <section className="rounded-card border border-surface-border bg-surface-sunken overflow-hidden">
          {transactionsQ.isLoading && (
            <div className="p-6 text-content-secondary text-sm">Cargando transacciones…</div>
          )}
          {transactionsQ.isError && (
            <div className="p-6 text-brand-negative text-sm">
              Error al cargar: {(transactionsQ.error as Error).message}
            </div>
          )}
          {transactionsQ.data && transactionsQ.data.rows.length === 0 && (
            <div className="p-6 text-content-secondary text-sm">
              Sin transacciones para estos filtros.
            </div>
          )}
          {transactionsQ.data && transactionsQ.data.rows.length > 0 && (
            <table className="w-full text-left">
              <thead className="bg-surface-raised text-content-muted text-[11px] uppercase tracking-[0.6px]">
                <tr>
                  <th className="py-2 pl-4 pr-4 font-semibold">Fecha</th>
                  <th className="py-2 pr-4 font-semibold">Descripción</th>
                  <th className="py-2 pr-4 font-semibold">Categoría</th>
                  <th className="py-2 pr-4 font-semibold text-right">Monto</th>
                </tr>
              </thead>
              <tbody>
                {transactionsQ.data.rows.map((tx) => (
                  <TxRow
                    key={tx.id}
                    tx={tx}
                    category={tx.categoryId ? categoriesById.get(tx.categoryId) : undefined}
                    onClickCategory={(t) => setEditingTx(t)}
                  />
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="mt-4 flex items-center justify-between text-content-secondary text-xs">
          <button
            type="button"
            disabled={page <= 1}
            onClick={() => setFilter('page', String(page - 1))}
            className="rounded-btn border border-surface-border bg-surface-raised px-3 py-1.5 disabled:opacity-40 hover:text-content-primary"
          >
            ← Anterior
          </button>
          <span className="num-tabular">Página {page}</span>
          <button
            type="button"
            disabled={!transactionsQ.data?.hasMore}
            onClick={() => setFilter('page', String(page + 1))}
            className="rounded-btn border border-surface-border bg-surface-raised px-3 py-1.5 disabled:opacity-40 hover:text-content-primary"
          >
            Siguiente →
          </button>
        </section>
      </main>

      {editingTx && (
        <CategoryChangeModal
          tx={editingTx}
          categories={categoriesQ.data ?? []}
          onClose={() => setEditingTx(null)}
        />
      )}
    </div>
  );
}
