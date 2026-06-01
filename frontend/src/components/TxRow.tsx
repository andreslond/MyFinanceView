import type { CategoryDTO, TransactionDTO } from '../services/types';

interface Props {
  tx: TransactionDTO;
  category: CategoryDTO | undefined;
  onClickCategory: (tx: TransactionDTO) => void;
}

function formatCurrency(amount: string, currency: string): string {
  // Presentation only — backend supplies the canonical numeric string.
  // We render with two decimals and locale grouping; no math.
  const n = Number.parseFloat(amount);
  if (!Number.isFinite(n)) return `${amount} ${currency}`;
  const formatted = n.toLocaleString('es-CO', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
  const symbol = currency === 'COP' ? '$' : currency;
  return `${symbol} ${formatted}`;
}

function formatDate(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString('es-CO', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    timeZone: 'America/Bogota'
  });
}

function categoryLabel(c: CategoryDTO | undefined): string {
  if (!c) return 'Sin categoría';
  return c.displayName ?? c.name;
}

export function TxRow({ tx, category, onClickCategory }: Props) {
  const isNegative = tx.amount.startsWith('-');
  return (
    <tr className="border-b border-surface-border hover:bg-surface-raised transition-colors">
      <td className="py-3 pr-4 align-top whitespace-nowrap text-content-secondary text-xs num-tabular">
        {formatDate(tx.occurredAt)}
      </td>
      <td className="py-3 pr-4 align-top">
        <div className="text-content-primary text-sm font-medium leading-tight">
          {tx.description}
        </div>
        <div className="mt-1 text-content-muted text-xs num-tabular">{tx.id.slice(0, 8)}</div>
      </td>
      <td className="py-3 pr-4 align-top">
        <button
          type="button"
          onClick={() => onClickCategory(tx)}
          className="inline-flex items-center gap-2 rounded-chip border border-surface-border bg-surface-base px-2.5 py-1 text-xs text-content-secondary hover:border-brand-purple hover:text-content-primary transition-colors"
          aria-label="Cambiar categoría"
        >
          {categoryLabel(category)}
        </button>
      </td>
      <td
        className={`py-3 align-top text-right num-tabular text-sm font-medium ${
          isNegative ? 'text-brand-negative' : 'text-brand-positive'
        }`}
      >
        {formatCurrency(tx.amount, tx.currency)}
      </td>
    </tr>
  );
}
