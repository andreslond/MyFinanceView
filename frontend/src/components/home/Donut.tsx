import type { DonutSegment } from '../../lib/homeSummary';

interface Props { segments: DonutSegment[]; size?: number; thickness?: number; centerLabel?: string; centerValue?: string; }

export function Donut({ segments, size = 120, thickness = 20, centerLabel, centerValue }: Props) {
  const r = (size - thickness) / 2;
  const cx = size / 2, cy = size / 2;
  const C = 2 * Math.PI * r;
  const total = segments.reduce((s, x) => s + x.value, 0) || 1;
  let acc = 0;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="shrink-0">
      <circle cx={cx} cy={cy} r={r} fill="none" stroke="rgba(255,255,255,0.08)" strokeWidth={thickness} />
      {segments.map((s, i) => {
        const frac = s.value / total;
        const len = frac * C;
        const dashoffset = -acc * C;
        acc += frac;
        return (
          <circle key={i} cx={cx} cy={cy} r={r} fill="none" stroke={s.color} strokeWidth={thickness}
            strokeDasharray={`${len} ${C - len}`} strokeDashoffset={dashoffset} strokeLinecap="butt"
            transform={`rotate(-90 ${cx} ${cy})`} />
        );
      })}
      {centerValue && (
        <>
          <text x={cx} y={cy - 4} textAnchor="middle" className="fill-content-muted"
            style={{ font: '600 11px Geist, sans-serif', letterSpacing: 0.4, textTransform: 'uppercase' }}>{centerLabel}</text>
          <text x={cx} y={cy + 18} textAnchor="middle" className="fill-content-primary num-display"
            style={{ font: '600 22px Geist, sans-serif' }}>{centerValue}</text>
        </>
      )}
    </svg>
  );
}
