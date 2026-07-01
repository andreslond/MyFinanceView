import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Icon } from './Icon';

describe('Icon', () => {
  it('renders an svg for a known name', () => {
    const { container } = render(<Icon name="home" />);
    expect(container.querySelector('svg')).toBeInTheDocument();
  });
});
