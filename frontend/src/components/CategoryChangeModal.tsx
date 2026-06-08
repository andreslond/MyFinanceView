import { useEffect, useMemo, useState } from 'react';
import type { CategoryDTO, TransactionDTO } from '../services/types';
import { useUpdateTransactionCategory } from '../hooks/useUpdateTransactionCategory';

interface Props {
  tx: TransactionDTO;
  categories: readonly CategoryDTO[];
  onClose: () => void;
}

function label(c: CategoryDTO): string {
  return c.displayName ?? c.name;
}

export function CategoryChangeModal({ tx, categories, onClose }: Props) {
  const [selectedId, setSelectedId] = useState<string>(tx.categoryId ?? '');
  const mutation = useUpdateTransactionCategory();
  const isUnchanged = selectedId === (tx.categoryId ?? '');

  // Reset error state when the user re-picks
  useEffect(() => {
    mutation.reset();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId]);

  const sortedCategories = useMemo(() => {
    return [...categories].sort((a, b) =>
      label(a).toLocaleLowerCase('es').localeCompare(label(b).toLocaleLowerCase('es'), 'es')
    );
  }, [categories]);

  const onConfirm = () => {
    if (isUnchanged) {
      // D8: idempotent — no service call, just close.
      onClose();
      return;
    }
    mutation.mutate(
      { id: tx.id, categoryId: selectedId },
      {
        onSuccess: () => onClose()
      }
    );
  };

  const error = mutation.error as Error | null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="category-modal-title"
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/60 px-4 py-8 sm:items-center"
      onClick={(e) => {
        if (e.target === e.currentTarget && !mutation.isPending) onClose();
      }}
    >
      <div className="w-full max-w-md rounded-sheet border border-surface-border bg-surface-raised p-6 shadow-2xl">
        <header className="mb-4">
          <p className="text-content-muted uppercase tracking-[0.6px] text-[11px] font-semibold">
            Cambiar categoría
          </p>
          <h2 id="category-modal-title" className="mt-1 text-content-primary text-lg font-semibold">
            {tx.description}
          </h2>
          <p className="mt-1 text-content-secondary text-xs num-tabular">
            {new Date(tx.occurredAt).toLocaleDateString('es-CO', {
              day: '2-digit',
              month: 'short',
              year: 'numeric',
              timeZone: 'America/Bogota'
            })}{' '}
            · {tx.amount} {tx.currency}
          </p>
        </header>

        <label htmlFor="category-select" className="block text-content-secondary text-xs mb-2">
          Categoría
        </label>
        <select
          id="category-select"
          value={selectedId}
          onChange={(e) => setSelectedId(e.target.value)}
          disabled={mutation.isPending}
          className="w-full rounded-btn border border-surface-border bg-surface-base px-3 py-2 text-content-primary text-sm focus:border-brand-purple focus:outline-none"
        >
          <option value="">Sin categoría</option>
          {sortedCategories.map((c) => (
            <option key={c.id} value={c.id}>
              {label(c)}
            </option>
          ))}
        </select>

        {error && (
          <p role="alert" className="mt-3 text-brand-negative text-xs">
            No pudimos guardar el cambio: {error.message}. Vuelve a intentar.
          </p>
        )}

        <div className="mt-5 flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={mutation.isPending}
            className="rounded-btn border border-surface-border bg-transparent px-4 py-2 text-content-secondary text-sm hover:text-content-primary disabled:opacity-50"
          >
            Cancelar
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={mutation.isPending}
            className="rounded-btn bg-brand-purple px-4 py-2 text-surface-base text-sm font-semibold hover:opacity-90 disabled:opacity-50"
          >
            {mutation.isPending ? 'Guardando…' : isUnchanged ? 'Cerrar' : 'Confirmar'}
          </button>
        </div>
      </div>
    </div>
  );
}
