interface Props { label: string; value: string; delta?: string; tone: 'positive' | 'negative' | 'accent' | 'neutral'; }
export function MiniStat({ label, value, delta, tone }: Props) {
  const deltaColor = tone === 'negative' ? 'text-brand-negative' : 'text-brand-positive';
  const accent = tone === 'accent' ? 'border-brand-cyan/30 bg-brand-cyan/5' : 'border-surface-border bg-surface-raised';
  return (
    <div className={`flex-1 rounded-card border ${accent} p-3`}>
      <div className="text-content-muted text-[10px] font-semibold uppercase tracking-[0.6px]">{label}</div>
      <div className="num-display mt-1 text-content-primary text-[18px] font-semibold">{value}</div>
      {delta && <div className={`num-tabular mt-0.5 text-[10px] font-semibold ${deltaColor}`}>{delta}</div>}
    </div>
  );
}
