import type { CSSProperties, ReactNode } from 'react';

// Stroke icon set — geometry ported from the design bundle's `MFVIcon`
// (docs/design/raw/.../data.jsx). `list` and `bank` are not present in that
// source; they use the equivalent standard Feather/Lucide glyphs, matching
// the same viewBox/stroke conventions as everything else here.
export type IconName =
  | 'home'
  | 'list'
  | 'target'
  | 'user'
  | 'plus'
  | 'refresh'
  | 'sparkle'
  | 'arrow-up'
  | 'arrow-down'
  | 'chevron'
  | 'thumbs-up'
  | 'thumbs-down'
  | 'bank';

interface Props {
  name: IconName;
  size?: number;
  stroke?: number;
  className?: string;
  style?: CSSProperties;
}

const PATHS: Record<IconName, ReactNode> = {
  home: <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2h-4v-7H10v7H6a2 2 0 0 1-2-2V9z" />,
  list: (
    <>
      <line x1="8" y1="6" x2="21" y2="6" />
      <line x1="8" y1="12" x2="21" y2="12" />
      <line x1="8" y1="18" x2="21" y2="18" />
      <line x1="3" y1="6" x2="3.01" y2="6" />
      <line x1="3" y1="12" x2="3.01" y2="12" />
      <line x1="3" y1="18" x2="3.01" y2="18" />
    </>
  ),
  target: (
    <>
      <circle cx="12" cy="12" r="9" />
      <circle cx="12" cy="12" r="5" />
      <circle cx="12" cy="12" r="1" />
    </>
  ),
  user: (
    <>
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
      <circle cx="12" cy="7" r="4" />
    </>
  ),
  plus: (
    <>
      <line x1="12" y1="5" x2="12" y2="19" />
      <line x1="5" y1="12" x2="19" y2="12" />
    </>
  ),
  refresh: (
    <>
      <polyline points="23 4 23 10 17 10" />
      <polyline points="1 20 1 14 7 14" />
      <path d="M3.5 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.65 4.36A9 9 0 0 0 20.5 15" />
    </>
  ),
  sparkle: <path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6" />,
  'arrow-up': (
    <>
      <line x1="12" y1="19" x2="12" y2="5" />
      <polyline points="5 12 12 5 19 12" />
    </>
  ),
  'arrow-down': (
    <>
      <line x1="12" y1="5" x2="12" y2="19" />
      <polyline points="19 12 12 19 5 12" />
    </>
  ),
  chevron: <polyline points="9 18 15 12 9 6" />,
  'thumbs-up': <path d="M14 9V5a3 3 0 0 0-6 0v4H4v11h12a2 2 0 0 0 2-1.7l1.5-7A2 2 0 0 0 17.5 9H14z" />,
  'thumbs-down': <path d="M10 15v4a3 3 0 0 0 6 0v-4h4V4H8a2 2 0 0 0-2 1.7L4.5 13A2 2 0 0 0 6.5 15H10z" />,
  bank: (
    <>
      <polygon points="12 2 20 7 4 7" />
      <line x1="6" y1="18" x2="6" y2="11" />
      <line x1="10" y1="18" x2="10" y2="11" />
      <line x1="14" y1="18" x2="14" y2="11" />
      <line x1="18" y1="18" x2="18" y2="11" />
      <line x1="3" y1="22" x2="21" y2="22" />
    </>
  )
};

export function Icon({ name, size = 16, stroke = 1.75, className, style }: Props) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={stroke}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      style={style}
    >
      {PATHS[name]}
    </svg>
  );
}
