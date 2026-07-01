// COP money formatters — PRESENTATION ONLY (demo).
// The project rule is "the frontend never computes monetary aggregations";
// for this client demo the Home aggregation runs client-side and these helpers
// only format for display, using `Number` (no Decimal.js). Post-demo: backend.

const nf0 = new Intl.NumberFormat('es-CO', { maximumFractionDigits: 0 });
const nf1 = new Intl.NumberFormat('es-CO', {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1
});

/** Full Colombian grouping, no decimals: `$20.083.078` (negatives with `−`). */
export function formatCOP(value: number): string {
  const sign = value < 0 ? '−' : '';
  return `${sign}$${nf0.format(Math.abs(Math.round(value)))}`;
}

/** Compact hero form: `$20,1 M` · `$757 k` · `$540`. */
export function formatCOPCompact(value: number): string {
  const sign = value < 0 ? '−' : '';
  const abs = Math.abs(value);
  if (abs >= 1_000_000_000) return `${sign}$${nf1.format(abs / 1_000_000_000)} MM`;
  if (abs >= 1_000_000) return `${sign}$${nf1.format(abs / 1_000_000)} M`;
  if (abs >= 1_000) return `${sign}$${nf0.format(Math.round(abs / 1_000))} k`;
  return `${sign}$${nf0.format(Math.round(abs))}`;
}

/** Movement amount: income prefixed `+`, expense shown as plain magnitude. */
export function formatSignedCOP(value: number, isIncome: boolean): string {
  return isIncome ? `+${formatCOP(value)}` : formatCOP(value);
}
