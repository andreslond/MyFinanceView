import type { ReflectionItem } from '../../lib/homeSummary';
import { formatCOP } from '../../lib/money';
import { Icon } from '../ui/Icon';
export function ReflectionRow({ item }: { item: ReflectionItem }) {
  const color = item.category?.color ?? '#8B95B2';
  return (
    <div className="rounded-card border border-surface-border bg-surface-raised p-3">
      <div className="flex items-center gap-3">
        <span className="flex h-9 w-9 items-center justify-center rounded-glyph text-base" style={{ background: color }}>{item.category?.icon ?? '•'}</span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-content-primary text-[13px] font-semibold">{item.description}</div>
          <div className="text-content-muted text-[11px]">{item.category?.displayName ?? item.category?.name ?? 'Sin categoría'}</div>
        </div>
        <div className="num-display text-content-primary text-sm font-semibold">{formatCOP(item.amount)}</div>
      </div>
      <div className="mt-2.5 flex gap-1.5">
        <button type="button" className="flex flex-1 items-center justify-center gap-1.5 rounded-[10px] bg-surface-base py-1.5 text-[11px] text-content-secondary"><Icon name="thumbs-up" size={12} /> Valió</button>
        <button type="button" className="flex flex-1 items-center justify-center gap-1.5 rounded-[10px] bg-surface-base py-1.5 text-[11px] text-content-secondary"><Icon name="thumbs-down" size={12} /> Me arrepiento</button>
      </div>
    </div>
  );
}
