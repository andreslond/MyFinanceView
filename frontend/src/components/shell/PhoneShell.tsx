import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Icon, type IconName } from '../ui/Icon';

type Tab = 'home' | 'movimientos';
interface Props { active: Tab; onRefresh?: () => void; refreshing?: boolean; children: ReactNode; }

const tabs: { key: Tab | 'metas' | 'yo'; label: string; icon: IconName; to?: string; disabled?: boolean }[] = [
  { key: 'home', label: 'Inicio', icon: 'home', to: '/home' },
  { key: 'movimientos', label: 'Movimientos', icon: 'list', to: '/movimientos' },
  { key: 'metas', label: 'Metas', icon: 'target', disabled: true },
  { key: 'yo', label: 'Yo', icon: 'user', disabled: true }
];

export function PhoneShell({ active, onRefresh, refreshing, children }: Props) {
  return (
    <div className="min-h-screen bg-surface-sunken flex justify-center">
      <div className="relative w-full max-w-[430px] bg-surface-base min-h-screen flex flex-col">
        {onRefresh && (
          <button type="button" onClick={onRefresh}
            className="absolute right-4 top-4 z-10 flex h-9 w-9 items-center justify-center rounded-btn border border-surface-border bg-surface-raised text-content-secondary hover:text-content-primary"
            aria-label="Refrescar">
            <Icon name="refresh" size={16} className={refreshing ? 'animate-spin' : ''} />
          </button>
        )}
        <div className="flex-1 overflow-y-auto pb-28">{children}</div>
        <nav className="fixed bottom-0 w-full max-w-[430px] border-t border-surface-border bg-surface-sunken/95 backdrop-blur px-2 py-2">
          <div className="flex items-center justify-around">
            {tabs.slice(0, 2).map((t) => <TabItem key={t.key} t={t} active={active} />)}
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-brand-purple text-white shadow-lg shadow-brand-purple/40"><Icon name="plus" size={22} /></div>
            {tabs.slice(2).map((t) => <TabItem key={t.key} t={t} active={active} />)}
          </div>
        </nav>
      </div>
    </div>
  );
}

function TabItem({ t, active }: { t: { key: string; label: string; icon: IconName; to?: string; disabled?: boolean }; active: string }) {
  const isActive = t.key === active;
  const cls = `flex flex-col items-center gap-1 px-2 text-[10px] ${isActive ? 'text-content-primary' : 'text-content-muted'} ${t.disabled ? 'opacity-40' : ''}`;
  const body = <><Icon name={t.icon} size={20} stroke={isActive ? 2.4 : 2} />{t.label}</>;
  return t.to && !t.disabled ? <Link to={t.to} className={cls}>{body}</Link> : <span className={cls}>{body}</span>;
}
