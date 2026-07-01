import { Icon } from '../ui/Icon';
export function InsightCard({ text }: { text: string }) {
  return (
    <div className="rounded-card border border-brand-purple/30 bg-gradient-to-br from-brand-purple/15 to-transparent p-4">
      <div className="mb-2 flex items-center gap-2">
        <span className="flex h-6 w-6 items-center justify-center rounded-[8px] bg-brand-purple text-white"><Icon name="sparkle" size={13} stroke={2.4} /></span>
        <span className="text-brand-purple text-[11px] font-bold uppercase tracking-[0.6px]">Este mes</span>
      </div>
      <p className="text-content-primary text-sm leading-relaxed">{text}</p>
      <div className="mt-3 flex gap-2">
        <button type="button" className="rounded-full border border-surface-border px-3.5 py-1.5 text-xs font-medium text-content-primary">Revisar</button>
        <button type="button" className="rounded-full bg-content-primary px-3.5 py-1.5 text-xs font-semibold text-surface-base">Marcar</button>
      </div>
    </div>
  );
}
