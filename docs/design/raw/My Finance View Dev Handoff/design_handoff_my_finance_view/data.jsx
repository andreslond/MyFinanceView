// data.jsx — shared dataset + icons + small UI primitives

// ─────────────────────────────────────────────────────────────
// Sample dataset
// ─────────────────────────────────────────────────────────────
const MFV_USER = { name: 'Arif', initial: 'A' };

const MFV_CATEGORIES = {
  subs:      { id: 'subs',      label: 'Subscriptions', color: 'var(--cat-subs)',      icon: 'repeat' },
  food:      { id: 'food',      label: 'Food & Drink',  color: 'var(--cat-food)',      icon: 'fork' },
  transport: { id: 'transport', label: 'Transport',     color: 'var(--cat-transport)', icon: 'car' },
  shopping:  { id: 'shopping',  label: 'Shopping',      color: 'var(--cat-shopping)',  icon: 'bag' },
  bills:     { id: 'bills',     label: 'Bills',         color: 'var(--cat-bills)',     icon: 'bolt' },
  fun:       { id: 'fun',       label: 'Entertainment', color: 'var(--cat-fun)',       icon: 'sparkle' },
  income:    { id: 'income',    label: 'Income',        color: 'var(--c-positive)',    icon: 'arrow-down' },
};

const MFV_BUDGETS = [
  { cat: 'subs',      spent: 80,    budget: 120,  trend: +12 },
  { cat: 'food',      spent: 412,   budget: 600,  trend: -4 },
  { cat: 'transport', spent: 98,    budget: 200,  trend: -8 },
  { cat: 'shopping',  spent: 215,   budget: 300,  trend: +22 },
  { cat: 'bills',     spent: 1720,  budget: 1800, trend: 0 },
  { cat: 'fun',       spent: 48,    budget: 150,  trend: -18 },
];

// Recent transactions (mixed sources, mostly synced from email)
const MFV_TX = [
  { id: 't1',  merchant: 'Spotify Premium',  cat: 'subs',      amount: -12.99,  date: 'Today',    time: '09:14', source: 'email', note: 'Auto-renewal' },
  { id: 't2',  merchant: 'Whole Foods',      cat: 'food',      amount: -84.32,  date: 'Today',    time: '11:02', source: 'email', note: 'Receipt' },
  { id: 't3',  merchant: 'Acme Inc · Salary',cat: 'income',    amount: 4200.00, date: 'Yesterday',time: '08:00', source: 'bank',  note: 'Direct deposit' },
  { id: 't4',  merchant: 'Uber',             cat: 'transport', amount: -18.40,  date: 'Yesterday',time: '22:18', source: 'email', note: 'Trip to Mission' },
  { id: 't5',  merchant: 'Amazon',           cat: 'shopping',  amount: -132.00, date: 'May 24',   time: '14:22', source: 'email', note: 'Mechanical keyboard', regret: true },
  { id: 't6',  merchant: 'Netflix',          cat: 'subs',      amount: -15.99,  date: 'May 23',   time: '00:00', source: 'email', note: 'Standard plan' },
  { id: 't7',  merchant: 'AirBnB',           cat: 'transport', amount: -548.99, date: 'May 21',   time: '11:00', source: 'email', note: 'Tokyo · 3 nights' },
  { id: 't8',  merchant: 'Figma',            cat: 'subs',      amount: -15.00,  date: 'May 20',   time: '08:01', source: 'email', note: 'Professional' },
  { id: 't9',  merchant: 'Apt 4B · Rent',    cat: 'bills',     amount: -1650.00,date: 'May 1',    time: '09:00', source: 'bank',  note: 'Wire transfer' },
  { id: 't10', merchant: 'Trader Joe\u2019s',cat: 'food',      amount: -42.18,  date: 'May 19',   time: '18:44', source: 'email', note: 'Groceries' },
  { id: 't11', merchant: 'Stripe · Refund',  cat: 'income',    amount: 24.99,   date: 'May 18',   time: '10:00', source: 'email', note: 'Refund' },
  { id: 't12', merchant: 'Starbucks',        cat: 'food',      amount: -6.75,   date: 'May 18',   time: '08:22', source: 'email', note: 'Latte + croissant' },
];

const MFV_GOALS = [
  { id: 'g1', label: 'Emergency fund',  saved: 6400, target: 10000, due: 'Dec 2026', color: 'var(--c-purple)', monthly: 400 },
  { id: 'g2', label: 'Japan, autumn',   saved: 1820, target: 3500,  due: 'Sep 2026', color: 'var(--c-cyan)',   monthly: 280 },
  { id: 'g3', label: 'New laptop',      saved: 1100, target: 2400,  due: 'Aug 2026', color: 'var(--c-amber)',  monthly: 200 },
  { id: 'g4', label: 'Pay off Visa',    saved: 1300, target: 2800,  due: 'Jun 2026', color: 'var(--c-coral)',  monthly: 250 },
];

// Aggregates
const MFV_BALANCE = 8322.89;
const MFV_INCOME_MTD = 4224.99;
const MFV_SPENT_MTD = 2526.62;
const MFV_SAVED_MTD = MFV_INCOME_MTD - MFV_SPENT_MTD;
const MFV_NET_PCT = +2.4; // vs last month

// 30-day spending sparkline (per-day spend)
const MFV_SPARKLINE = [42, 18, 86, 24, 0, 1650, 12, 64, 33, 92, 8, 0, 27, 156, 45, 22, 64, 13, 7, 99, 76, 84, 132, 12, 49, 33, 0, 41, 90, 23];

// ─────────────────────────────────────────────────────────────
// Icon library — minimal stroke set
// ─────────────────────────────────────────────────────────────
function MFVIcon({ name, size = 16, color = 'currentColor', stroke = 1.75 }) {
  const props = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: stroke, strokeLinecap: 'round', strokeLinejoin: 'round' };
  const paths = {
    repeat:    <><polyline points="17 1 21 5 17 9" /><path d="M3 11V9a4 4 0 0 1 4-4h14" /><polyline points="7 23 3 19 7 15" /><path d="M21 13v2a4 4 0 0 1-4 4H3" /></>,
    fork:      <><path d="M6 2v9a3 3 0 0 0 3 3v8" /><path d="M12 2v9a3 3 0 0 1-3 3" /><path d="M18 2c-1.7 0-3 1.3-3 3v6c0 1.1.9 2 2 2h1v9" /></>,
    car:       <><path d="M5 17h14M5 17v-4l2-5h10l2 5v4M5 17v2M19 17v2M8 13h.01M16 13h.01" /></>,
    bag:       <><path d="M6 7h12l-1 13H7L6 7z" /><path d="M9 7a3 3 0 1 1 6 0" /></>,
    bolt:      <><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" /></>,
    sparkle:   <><path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6" /></>,
    'arrow-down': <><line x1="12" y1="5" x2="12" y2="19" /><polyline points="19 12 12 19 5 12" /></>,
    'arrow-up': <><line x1="12" y1="19" x2="12" y2="5" /><polyline points="5 12 12 5 19 12" /></>,
    plus:      <><line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" /></>,
    bell:      <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" /><path d="M13.7 21a2 2 0 0 1-3.4 0" /></>,
    search:    <><circle cx="11" cy="11" r="7" /><line x1="21" y1="21" x2="16.65" y2="16.65" /></>,
    home:      <><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2h-4v-7H10v7H6a2 2 0 0 1-2-2V9z" /></>,
    chart:     <><line x1="18" y1="20" x2="18" y2="10" /><line x1="12" y1="20" x2="12" y2="4" /><line x1="6" y1="20" x2="6" y2="14" /></>,
    target:    <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="5" /><circle cx="12" cy="12" r="1" /></>,
    user:      <><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></>,
    mail:      <><path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" /><polyline points="22,6 12,13 2,6" /></>,
    check:     <><polyline points="20 6 9 17 4 12" /></>,
    x:         <><line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" /></>,
    chevron:   <><polyline points="9 18 15 12 9 6" /></>,
    back:      <><polyline points="15 18 9 12 15 6" /></>,
    dots:      <><circle cx="5" cy="12" r="1.5" /><circle cx="12" cy="12" r="1.5" /><circle cx="19" cy="12" r="1.5" /></>,
    thumbsUp:  <><path d="M14 9V5a3 3 0 0 0-6 0v4H4v11h12a2 2 0 0 0 2-1.7l1.5-7A2 2 0 0 0 17.5 9H14z" /></>,
    thumbsDown:<><path d="M10 15v4a3 3 0 0 0 6 0v-4h4V4H8a2 2 0 0 0-2 1.7L4.5 13A2 2 0 0 0 6.5 15H10z" /></>,
    sync:      <><polyline points="23 4 23 10 17 10" /><polyline points="1 20 1 14 7 14" /><path d="M3.5 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.65 4.36A9 9 0 0 0 20.5 15" /></>,
    flame:     <><path d="M12 22c-4 0-7-3-7-7 0-2 1-4 2.5-5 0 2 1 3 2.5 3 0-3 2-6 5-8 0 3 2 4 3 6 1 1.5 1.5 3 1.5 5 0 4-3 6-7.5 6z" /></>,
    eye:       <><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" /><circle cx="12" cy="12" r="3" /></>,
    eyeOff:    <><path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" /><line x1="1" y1="1" x2="23" y2="23" /></>,
    coffee:    <><path d="M18 8h1a4 4 0 0 1 0 8h-1" /><path d="M2 8h16v9a4 4 0 0 1-4 4H6a4 4 0 0 1-4-4V8z" /><line x1="6" y1="1" x2="6" y2="4" /><line x1="10" y1="1" x2="10" y2="4" /><line x1="14" y1="1" x2="14" y2="4" /></>,
    scan:      <><path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2" /><rect x="7" y="7" width="10" height="10" rx="1.5" /></>,
    forecast:  <><polyline points="3 16 9 10 13 14 21 6" /><polyline points="15 6 21 6 21 12" /></>,
    inbox:     <><polyline points="22 12 16 12 14 15 10 15 8 12 2 12" /><path d="M5.45 5.11L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z" /></>,
  };
  return <svg {...props}>{paths[name] || null}</svg>;
}

// Category color swatch with glyph
function MFVCatGlyph({ catId, size = 36, radius = 11 }) {
  const cat = MFV_CATEGORIES[catId];
  if (!cat) return null;
  return (
    <div style={{
      width: size, height: size, borderRadius: radius, flexShrink: 0,
      background: cat.color, opacity: 1,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: '#0B0B0F',
      boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.18)',
    }}>
      <MFVIcon name={cat.icon} size={size * 0.5} stroke={2} />
    </div>
  );
}

// ─────────────────────────────────────────────────────────────
// Charts — pure SVG
// ─────────────────────────────────────────────────────────────
function MFVDonut({ segments, size = 140, thickness = 22, centerLabel, centerValue, color, track }) {
  const r = (size - thickness) / 2;
  const cx = size / 2, cy = size / 2;
  const C = 2 * Math.PI * r;
  const total = segments.reduce((s, x) => s + x.value, 0) || 1;
  let acc = 0;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ overflow: 'visible' }}>
      <circle cx={cx} cy={cy} r={r} fill="none" stroke={track || 'var(--track)'} strokeWidth={thickness} />
      {segments.map((s, i) => {
        const frac = s.value / total;
        const len = frac * C;
        const dasharray = `${len} ${C - len}`;
        const dashoffset = -acc * C;
        acc += frac;
        return (
          <circle key={i} cx={cx} cy={cy} r={r} fill="none"
            stroke={s.color} strokeWidth={thickness}
            strokeDasharray={dasharray} strokeDashoffset={dashoffset}
            strokeLinecap="butt"
            transform={`rotate(-90 ${cx} ${cy})`} />
        );
      })}
      {centerValue && (
        <>
          <text x={cx} y={cy - 4} textAnchor="middle" fill="currentColor"
            style={{ font: '600 11px var(--font-sans)', opacity: 0.6, letterSpacing: 0.4, textTransform: 'uppercase' }}>{centerLabel}</text>
          <text x={cx} y={cy + 18} textAnchor="middle" fill="currentColor"
            style={{ font: '600 22px var(--font-sans)', letterSpacing: -0.5 }}>{centerValue}</text>
        </>
      )}
    </svg>
  );
}

function MFVSparkline({ values, width = 200, height = 36, color = 'var(--c-purple)', fill = true }) {
  if (!values.length) return null;
  const max = Math.max(...values, 1);
  const min = 0;
  const step = width / (values.length - 1);
  const points = values.map((v, i) => {
    const x = i * step;
    const y = height - ((v - min) / (max - min)) * (height - 4) - 2;
    return [x, y];
  });
  const pathD = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p[0].toFixed(2)} ${p[1].toFixed(2)}`).join(' ');
  const areaD = pathD + ` L${width} ${height} L0 ${height} Z`;
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
      {fill && <path d={areaD} fill={color} opacity="0.12" />}
      <path d={pathD} fill="none" stroke={color} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function MFVBars({ values, width = 200, height = 36, color = 'var(--c-purple)', gap = 3 }) {
  if (!values.length) return null;
  const max = Math.max(...values, 1);
  const bw = (width - gap * (values.length - 1)) / values.length;
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none">
      {values.map((v, i) => {
        const h = Math.max(2, (v / max) * (height - 2));
        const x = i * (bw + gap);
        const y = height - h;
        return <rect key={i} x={x} y={y} width={bw} height={h} rx={Math.min(2, bw / 3)} fill={color} opacity={v ? 1 : 0.25} />;
      })}
    </svg>
  );
}

function MFVBar({ pct, color = 'var(--c-purple)', height = 6 }) {
  return (
    <div style={{ height, background: 'var(--track)', borderRadius: 999, overflow: 'hidden', flex: 1 }}>
      <div style={{ width: `${Math.min(100, pct)}%`, height: '100%', background: color, borderRadius: 999, transition: 'width 0.4s cubic-bezier(.2,.7,.3,1)' }} />
    </div>
  );
}

function MFVRing({ pct, size = 64, thickness = 6, color = 'var(--c-purple)', label }) {
  const r = (size - thickness) / 2;
  const C = 2 * Math.PI * r;
  const dash = (Math.min(100, pct) / 100) * C;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
      <circle cx={size/2} cy={size/2} r={r} fill="none" stroke="var(--track)" strokeWidth={thickness} />
      <circle cx={size/2} cy={size/2} r={r} fill="none"
        stroke={color} strokeWidth={thickness}
        strokeDasharray={`${dash} ${C - dash}`}
        strokeLinecap="round"
        transform={`rotate(-90 ${size/2} ${size/2})`} />
      <text x={size/2} y={size/2 + 4} textAnchor="middle" fill="currentColor"
        style={{ font: '600 13px var(--font-sans)', letterSpacing: -0.3 }}>{label || `${Math.round(pct)}%`}</text>
    </svg>
  );
}

// Money formatter, splits cents for display flourish
function mfvMoney(amount, opts = {}) {
  const { sign = false, cents = true, abs = false } = opts;
  const v = abs ? Math.abs(amount) : amount;
  const dollars = Math.trunc(Math.abs(v));
  const c = Math.round((Math.abs(v) - dollars) * 100).toString().padStart(2, '0');
  const formatted = dollars.toLocaleString('en-US');
  const prefix = sign ? (v >= 0 ? '+' : '−') : (v < 0 ? '−' : '');
  return cents ? { whole: `${prefix}$${formatted}`, cents: c } : { whole: `${prefix}$${formatted}`, cents: '' };
}

Object.assign(window, {
  MFV_USER, MFV_CATEGORIES, MFV_BUDGETS, MFV_TX, MFV_GOALS,
  MFV_BALANCE, MFV_INCOME_MTD, MFV_SPENT_MTD, MFV_SAVED_MTD, MFV_NET_PCT, MFV_SPARKLINE,
  MFVIcon, MFVCatGlyph, MFVDonut, MFVSparkline, MFVBars, MFVBar, MFVRing, mfvMoney,
});
