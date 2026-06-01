/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Tokens from docs/design/design-system.md §4
        // Dark theme primary surfaces
        surface: {
          base: '#0B0B0F',
          raised: '#15151C',
          sunken: '#08080C',
          border: 'rgba(255,255,255,0.07)'
        },
        // Accents — never swap (per design system §4)
        brand: {
          purple: '#7C5CFF',
          cyan: '#22D3EE',
          positive: '#34E0A1',
          negative: '#FF6B6B',
          amber: '#F4B86A',
          coral: '#FF9EA0',
          slate: '#8B95B2',
          green: '#6FE39A'
        },
        // Content colors
        content: {
          primary: '#F5F5F7',
          secondary: '#9CA3AF',
          muted: '#6B7280'
        }
      },
      fontFamily: {
        sans: ['Geist', 'system-ui', 'sans-serif'],
        mono: ['Geist Mono', 'ui-monospace', 'monospace']
      },
      borderRadius: {
        chip: '7px',
        glyph: '11px',
        btn: '12px',
        card: '22px',
        sheet: '26px'
      },
      fontVariantNumeric: {
        tabular: 'tabular-nums'
      }
    }
  },
  plugins: []
};
