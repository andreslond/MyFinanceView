/**
 * Trim a slice fetched with ONE extra row down to `pageSize` and report
 * whether more rows exist. Keeps range-pagination logic pure and testable
 * (the I/O lives in services/).
 */
export function paginate<T>(raw: T[], pageSize: number): { rows: T[]; hasMore: boolean } {
  const hasMore = raw.length > pageSize;
  return { rows: hasMore ? raw.slice(0, pageSize) : raw, hasMore };
}
