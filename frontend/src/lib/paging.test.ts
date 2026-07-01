import { describe, expect, it } from 'vitest';
import { paginate } from './paging';

describe('paginate', () => {
  it('flags hasMore and trims when raw exceeds pageSize', () => {
    const { rows, hasMore } = paginate([1, 2, 3, 4], 3);
    expect(rows).toEqual([1, 2, 3]);
    expect(hasMore).toBe(true);
  });
  it('reports no more when raw fits exactly', () => {
    const { rows, hasMore } = paginate([1, 2, 3], 3);
    expect(rows).toEqual([1, 2, 3]);
    expect(hasMore).toBe(false);
  });
  it('reports no more on a short final page', () => {
    const { rows, hasMore } = paginate([1], 3);
    expect(rows).toEqual([1]);
    expect(hasMore).toBe(false);
  });
});
