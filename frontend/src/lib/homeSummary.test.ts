import { describe, expect, it } from 'vitest';
import { computeMonthlySummary, type CategoryMeta, type RawTx } from './homeSummary';

const cat = (id: string, type: 'income' | 'expense', color: string): CategoryMeta => ({
  id,
  name: id,
  displayName: id,
  color,
  icon: '•',
  type
});

const index = new Map<string, CategoryMeta>([
  ['food', cat('food', 'expense', '#FF9800')],
  ['shop', cat('shop', 'expense', '#795548')],
  ['salary', cat('salary', 'income', '#2E7D32')]
]);

const tx = (id: string, amount: number, categoryId: string | null): RawTx => ({
  id,
  amount,
  occurredAt: '2026-06-10T12:00:00Z',
  description: `d-${id}`,
  categoryId
});

describe('computeMonthlySummary', () => {
  it('splits income vs expense by category type, not by sign', () => {
    const s = computeMonthlySummary(
      [tx('a', 1000, 'salary'), tx('b', 300, 'food'), tx('c', 200, 'shop')],
      index,
      'Junio 2026'
    );
    expect(s.income).toBe(1000);
    expect(s.expense).toBe(500);
    expect(s.saved).toBe(500);
    expect(s.txCount).toBe(3);
  });

  it('treats uncategorized transactions as expense', () => {
    const s = computeMonthlySummary([tx('u', 400, null)], index, 'Junio 2026');
    expect(s.expense).toBe(400);
    expect(s.segments[0].label).toBe('Sin categoría');
  });

  it('builds donut segments sorted desc with integer percentages', () => {
    const s = computeMonthlySummary(
      [tx('b', 300, 'food'), tx('c', 100, 'shop')],
      index,
      'Junio 2026'
    );
    expect(s.segments.map((x) => x.label)).toEqual(['food', 'shop']);
    expect(s.segments[0].pct).toBe(75);
    expect(s.legend.length).toBeLessThanOrEqual(4);
  });

  it('picks the top 3 expenses by amount regardless of input order', () => {
    const s = computeMonthlySummary(
      [tx('lo', 10, 'food'), tx('hi', 900, 'shop'), tx('mid', 50, 'food'), tx('mid2', 40, 'food')],
      index,
      'Junio 2026'
    );
    expect(s.reflections.map((r) => r.id)).toEqual(['hi', 'mid', 'mid2']);
  });

  it('names the top category and biggest movement in the insight', () => {
    const s = computeMonthlySummary(
      [tx('big', 900, 'shop'), tx('small', 100, 'food')],
      index,
      'Junio 2026'
    );
    expect(s.insight).toContain('Junio 2026');
    expect(s.insight).toContain('shop');
    expect(s.insight).toContain('d-big');
  });

  it('is safe on an empty month', () => {
    const s = computeMonthlySummary([], index, 'Junio 2026');
    expect(s.expense).toBe(0);
    expect(s.segments).toEqual([]);
    expect(s.insight).toBeNull();
    expect(s.reflections).toEqual([]);
  });
});
