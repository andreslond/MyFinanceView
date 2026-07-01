import type { LedgerTx, AccountMeta } from '../../services/ledgerService';
import type { CategoryMeta } from '../../lib/homeSummary';
import { formatSignedCOP } from '../../lib/money';
import { BankBadge } from './BankBadge';

function fmtDate(iso: string): string {
  return new Date(iso).toLocaleDateString('es-CO', {
    day: '2-digit',
    month: 'short',
    timeZone: 'America/Bogota'
  });
}

export function LedgerRow({
  tx,
  account,
  category
}: {
  tx: LedgerTx;
  account: AccountMeta | undefined;
  category: CategoryMeta | undefined;
}) {
  const isIncome = category?.type === 'income';
  const color = category?.color ?? '#8B95B2';
  return (
    <div className="flex items-center gap-3 border-b border-surface-border px-[18px] py-3">
      <span
        className="flex h-9 w-9 shrink-0 items-center justify-center rounded-glyph text-base"
        style={{ background: color }}
      >
        {category?.icon ?? '•'}
      </span>
      <div className="min-w-0 flex-1">
        <div className="truncate text-content-primary text-[13px] font-medium">{tx.description}</div>
        <div className="mt-1 flex items-center gap-2">
          <BankBadge bankName={account?.bankName ?? 'Banco'} last4={account?.last4 ?? null} />
          <span className="text-content-muted text-[11px]">{fmtDate(tx.occurredAt)}</span>
        </div>
      </div>
      <div
        className={`num-display shrink-0 text-sm font-semibold ${
          isIncome ? 'text-brand-positive' : 'text-content-primary'
        }`}
      >
        {formatSignedCOP(tx.amount, isIncome)}
      </div>
    </div>
  );
}
