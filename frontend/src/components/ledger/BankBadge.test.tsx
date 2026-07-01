import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { BankBadge } from './BankBadge';

describe('BankBadge', () => {
  it('shows bank name with masked last4', () => {
    const { getByText } = render(<BankBadge bankName="Bancolombia" last4="1234" />);
    expect(getByText(/Bancolombia/)).toBeInTheDocument();
    expect(getByText(/1234/)).toBeInTheDocument();
  });
});
