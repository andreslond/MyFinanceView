import { Icon } from '../ui/Icon';

export function BankBadge({ bankName, last4 }: { bankName: string; last4: string | null }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-chip bg-surface-base px-1.5 py-0.5 text-[10px] text-content-muted">
      <Icon name="bank" size={11} stroke={2} />
      {bankName}
      {last4 ? ` ····${last4}` : ''}
    </span>
  );
}
