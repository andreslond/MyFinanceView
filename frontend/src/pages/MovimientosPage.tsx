import { useEffect, useMemo, useRef, useState } from 'react';
import { PhoneShell } from '../components/shell/PhoneShell';
import { LedgerRow } from '../components/ledger/LedgerRow';
import { useAccountsIndex, useBankSummaries, useInfiniteTransactions } from '../hooks/useLedger';
import { useCategoryIndex } from '../hooks/useHomeSummary';

export default function MovimientosPage() {
  const accountsQ = useAccountsIndex();
  const banksQ = useBankSummaries(accountsQ.data);
  const catsQ = useCategoryIndex();
  const [bankId, setBankId] = useState<string | null>(null);

  const accountIds = useMemo(() => {
    if (!bankId) return undefined;
    const s = banksQ.data?.find((b) => b.bankId === bankId);
    return s?.accountIds;
  }, [bankId, banksQ.data]);

  const txQ = useInfiniteTransactions(accountIds);
  const rows = txQ.data?.pages.flatMap((p) => p.rows) ?? [];
  const totalCount = (banksQ.data ?? []).reduce((s, b) => s + b.count, 0);
  const nBanks = banksQ.data?.length ?? 0;

  const sentinel = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        const first = entries[0];
        if (first?.isIntersecting && txQ.hasNextPage && !txQ.isFetchingNextPage) {
          txQ.fetchNextPage();
        }
      },
      { rootMargin: '200px' }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [txQ.hasNextPage, txQ.isFetchingNextPage, txQ.fetchNextPage]);

  const refresh = () => {
    accountsQ.refetch();
    banksQ.refetch();
    catsQ.refetch();
    txQ.refetch();
  };

  return (
    <PhoneShell active="movimientos" onRefresh={refresh} refreshing={txQ.isFetching}>
      <div className="px-[18px] pt-6">
        <div className="text-content-primary text-[22px] font-bold tracking-[-0.6px]">Movimientos</div>
        <div className="num-tabular mt-0.5 text-content-secondary text-xs">
          {totalCount > 0
            ? `${totalCount} movimientos · ${nBanks} ${nBanks === 1 ? 'banco' : 'bancos'}`
            : 'Cargando…'}
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => setBankId(null)}
            className={`rounded-chip border px-2.5 py-1 text-xs ${
              !bankId
                ? 'border-brand-purple bg-brand-purple/20 text-content-primary'
                : 'border-surface-border bg-surface-raised text-content-secondary'
            }`}
          >
            Todos
          </button>
          {(banksQ.data ?? []).map((b) => (
            <button
              key={b.bankId}
              type="button"
              onClick={() => setBankId(b.bankId)}
              className={`rounded-chip border px-2.5 py-1 text-xs ${
                bankId === b.bankId
                  ? 'border-brand-purple bg-brand-purple/20 text-content-primary'
                  : 'border-surface-border bg-surface-raised text-content-secondary'
              }`}
            >
              {b.bankName} <span className="num-tabular text-content-muted">{b.count}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="mt-3">
        {txQ.isLoading && (
          <div className="px-[18px] py-6 text-content-secondary text-sm">Cargando movimientos…</div>
        )}
        {txQ.isError && (
          <div className="px-[18px] py-6 text-brand-negative text-sm">
            Error: {(txQ.error as Error).message}
          </div>
        )}
        {rows.map((tx) => (
          <LedgerRow
            key={tx.id}
            tx={tx}
            account={accountsQ.data?.get(tx.accountId)}
            category={tx.categoryId ? catsQ.data?.get(tx.categoryId) : undefined}
          />
        ))}
        <div ref={sentinel} />
        {txQ.isFetchingNextPage && (
          <div className="px-[18px] py-4 text-center text-content-muted text-xs">Cargando más…</div>
        )}
        {!txQ.hasNextPage && rows.length > 0 && (
          <div className="px-[18px] py-6 text-center text-content-muted text-xs">
            Fin — {rows.length} movimientos cargados
          </div>
        )}
      </div>
    </PhoneShell>
  );
}
