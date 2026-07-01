import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MiniStat } from './MiniStat';

describe('MiniStat', () => {
  it('shows label, value and delta', () => {
    const { getByText } = render(<MiniStat label="Ahorro" value="−$19,0 M" delta="−1726%" tone="negative" />);
    expect(getByText('Ahorro')).toBeInTheDocument();
    expect(getByText('−$19,0 M')).toBeInTheDocument();
    expect(getByText('−1726%')).toBeInTheDocument();
  });
});
