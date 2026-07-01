import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Donut } from './Donut';

describe('Donut', () => {
  it('draws a circle per segment plus the track and shows the center value', () => {
    const segments = [
      { label: 'A', value: 60, color: '#f00', pct: 60 },
      { label: 'B', value: 40, color: '#0f0', pct: 40 }
    ];
    const { container, getByText } = render(<Donut segments={segments} centerLabel="Gasto" centerValue="$100" />);
    // 1 track + 2 segments
    expect(container.querySelectorAll('circle').length).toBe(3);
    expect(getByText('$100')).toBeInTheDocument();
  });
});
