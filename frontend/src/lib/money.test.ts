import { describe, expect, it } from 'vitest';
import { formatCOP, formatCOPCompact, formatSignedCOP } from './money';

describe('formatCOP', () => {
  it('groups with Colombian thousands separators and no decimals', () => {
    expect(formatCOP(20083078)).toBe('$20.083.078');
  });
  it('renders negatives with a minus sign', () => {
    expect(formatCOP(-18983078)).toBe('−$18.983.078');
  });
  it('rounds to the nearest peso', () => {
    expect(formatCOP(1234.6)).toBe('$1.235');
  });
});

describe('formatCOPCompact', () => {
  it('abbreviates millions with a comma decimal and " M"', () => {
    expect(formatCOPCompact(20083078)).toBe('$20,1 M');
  });
  it('abbreviates thousands with " k"', () => {
    expect(formatCOPCompact(756658)).toBe('$757 k');
  });
  it('leaves small amounts whole', () => {
    expect(formatCOPCompact(540)).toBe('$540');
  });
});

describe('formatSignedCOP', () => {
  it('prefixes income with +', () => {
    expect(formatSignedCOP(1100000, true)).toBe('+$1.100.000');
  });
  it('shows expense as plain magnitude', () => {
    expect(formatSignedCOP(84320, false)).toBe('$84.320');
  });
});
