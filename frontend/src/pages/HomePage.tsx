import { PhoneShell } from '../components/shell/PhoneShell';
import { Donut } from '../components/home/Donut';
import { MiniStat } from '../components/home/MiniStat';
import { InsightCard } from '../components/home/InsightCard';
import { ReflectionRow } from '../components/home/ReflectionRow';
import { useHomeSummary } from '../hooks/useHomeSummary';
import { useAuth } from '../auth/AuthContext';
import { formatCOPCompact } from '../lib/money';

const START = '2026-06-01T00:00:00Z';
const END = '2026-07-01T00:00:00Z';
const MONTH = 'Junio 2026';

function firstName(email: string | undefined): string {
  if (!email) return 'ahí';
  const local = email.split('@')[0] ?? '';
  const raw = local.split(/[.\-_]/)[0] ?? '';
  if (!raw) return 'ahí';
  return raw.charAt(0).toUpperCase() + raw.slice(1);
}

export default function HomePage() {
  const { user } = useAuth();
  const q = useHomeSummary(START, END, MONTH);
  const name = firstName(user?.email ?? undefined);

  return (
    <PhoneShell active="home" onRefresh={() => q.refetch()} refreshing={q.isFetching}>
      <div className="px-[18px] pt-6">
        <div className="mb-5 flex items-center justify-between">
          <div>
            <div className="text-content-muted text-xs">{MONTH}</div>
            <div className="mt-0.5 text-content-primary text-[22px] font-bold tracking-[-0.6px]">
              Hola, {name}.
            </div>
          </div>
          <div className="flex h-9 w-9 items-center justify-center rounded-glyph bg-gradient-to-br from-brand-purple to-brand-cyan text-sm font-semibold text-white">
            {name.charAt(0)}
          </div>
        </div>

        {q.isLoading && <div className="text-content-secondary text-sm">Cargando…</div>}
        {q.isError && (
          <div className="text-brand-negative text-sm">Error: {(q.error as Error).message}</div>
        )}

        {q.data && (
          <>
            <div className="mb-3.5 rounded-card border border-surface-border bg-surface-sunken p-[18px]">
              <div className="text-content-muted text-[11px] font-semibold uppercase tracking-[0.6px]">
                ¿A dónde se fue tu dinero?
              </div>
              <div className="mt-3 flex items-center gap-4">
                <Donut
                  segments={q.data.segments}
                  centerLabel="Gasto"
                  centerValue={formatCOPCompact(q.data.expense)}
                />
                <div className="flex flex-1 flex-col gap-1.5">
                  {q.data.legend.map((s) => (
                    <div key={s.label} className="flex items-center gap-2">
                      <span
                        className="h-2 w-2 shrink-0 rounded-[2px]"
                        style={{ background: s.color }}
                      />
                      <span className="flex-1 truncate text-content-secondary text-[11px]">
                        {s.label}
                      </span>
                      <span className="num-tabular text-content-primary text-[11px] font-semibold">
                        {s.pct}%
                      </span>
                    </div>
                  ))}
                </div>
              </div>
            </div>

            {q.data.insight && (
              <div className="mb-3.5">
                <InsightCard text={q.data.insight} />
              </div>
            )}

            <div className="mb-3.5 flex gap-2">
              <MiniStat label="Ingresos" value={formatCOPCompact(q.data.income)} tone="positive" />
              <MiniStat label="Gastos" value={formatCOPCompact(q.data.expense)} tone="neutral" />
              <MiniStat
                label="Ahorro"
                value={formatCOPCompact(q.data.saved)}
                tone={q.data.saved < 0 ? 'negative' : 'accent'}
              />
            </div>

            {q.data.reflections.length > 0 && (
              <>
                <div className="mb-2 flex items-center justify-between">
                  <span className="text-content-primary text-sm font-semibold">
                    Vale una segunda mirada
                  </span>
                  <span className="text-content-muted text-[11px]">
                    {q.data.reflections.length} este mes
                  </span>
                </div>
                <div className="flex flex-col gap-2">
                  {q.data.reflections.map((item) => (
                    <ReflectionRow key={item.id} item={item} />
                  ))}
                </div>
              </>
            )}
          </>
        )}
      </div>
    </PhoneShell>
  );
}
