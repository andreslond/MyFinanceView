import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { PhoneShell } from './PhoneShell';

describe('PhoneShell', () => {
  it('renders the tab bar and children', () => {
    const { getByText } = render(
      <MemoryRouter><PhoneShell active="home"><div>contenido</div></PhoneShell></MemoryRouter>
    );
    expect(getByText('contenido')).toBeInTheDocument();
    expect(getByText('Inicio')).toBeInTheDocument();
    expect(getByText('Movimientos')).toBeInTheDocument();
  });
});
