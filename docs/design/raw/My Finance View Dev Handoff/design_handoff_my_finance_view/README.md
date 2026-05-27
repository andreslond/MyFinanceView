# Handoff: My Finance View

A high-fidelity mobile finance app prototype — real-time expense tracking with email-synced transactions, budgets, goals, and reflection prompts.

---

## ⚠️ About the design files

The files in this bundle are **design references created in HTML** — prototypes showing the intended look and behavior. They are **not production code to copy directly**.

Your task is to **recreate these designs in the target codebase's environment** (React Native, SwiftUI, Flutter, native iOS/Android, etc.) using its established patterns, design system, and component library. If no environment exists yet, pick the most appropriate stack for a mobile finance app — React Native or SwiftUI are strong fits — and implement there.

## Fidelity

**High-fidelity.** All colors, typography, spacing, radii, and interactions are intentional and final. Match them in implementation. The exception is iconography — the prototype uses inline SVGs from a custom stroke set; substitute with the codebase's existing icon library, matching weight/style.

## Product summary

The user wants clear answers to four questions:

1. **Where does my money go?** — budget breakdown, donut charts, category-tagged transactions.
2. **Am I on pace?** — goals tracked with progress rings + months-to-target math.
3. **What might I regret?** — large-purchase reflection prompts (Worth it? / Regret).
4. **What is the next action?** — Add / Scan / Unclassified queue / Forecast quick actions.

Transactions are sourced from the user's email inbox (receipts, subscription invoices, bank notifications) and parsed automatically. A **subtle "synced from email" badge** (small cyan mail glyph) marks each email-sourced row — the sync is treated as plumbing, not a hero feature.

---

## Screens & views

### 01 · Home Dashboard — three directions

The prototype presents **three home variants** side-by-side for the team to choose between. They share the same data layer and bottom-tab pattern; the difference is the framing.

#### A · Classic balance

For users who want a clean "what's my money doing right now" snapshot.

- **Top:** Avatar + "Good morning, {name}" + search/bell icons (36×36, radius 12, surface).
- **Balance hero:** "TOTAL BALANCE" caption (11px, uppercase, 0.6 tracking) → 44px display number with smaller cents (`.{cc}` at 22px text-2) → green delta chip "+2.4% vs last month".
- **Quick actions row** (4 equal-width buttons, surface bg, 14px radius, 11px above icon):
  - `Add` · `Scan` · `Unclassified` (purple badge with count) · `Forecast`
- **Budgets · May** — horizontally scrolling card track, snap-to-card, dot indicator below. First card is "primary" with full color fill; the rest are surface-colored. Card content: category icon + name → spent amount → "of $X · $Y left" → progress bar.
- **Recent activity** — vertical list of `TxRow`. Tap any row → bottom sheet.

#### B · Reflection-first

For users wanting introspection and judgment about purchases.

- **Greeting:** date + "Hi {name}." (22px / 700 / -0.6 tracking).
- **Donut hero card:** "Where did your money go?" caption → 120px donut + side legend showing top 4 categories with %.
- **Weekly insight card:** Purple-tinted gradient bg. Sparkle icon + "THIS WEEK" pill → 14px insight sentence mentioning a specific outlier transaction → two actions: `Review` (ghost) + `Flag purchase` (solid black).
- **Stat trio:** 3 mini-cards in a row — Income / Spent / Saved with delta chips. Saved card has cyan-tint accent.
- **"Worth a second look"** — reflection rows for large recent purchases with `Worth it` / `Regret` thumb buttons.

#### C · Power-user

For users who want density and analytics.

- **Header:** avatar + "Visa ····4082" subtitle + live "synced 2m ago" indicator.
- **Net balance card:** large number + Day/Week/Month/Year segmented toggle → 50px sparkline of the last 30 days with date axis.
- **4-stat grid** (2×2): Income / Spent / Saved / Net. Each card has a colored dot, value, and delta.
- **Budgets bar list:** compact rows — color dot, name, mini progress bar, percentage, trend delta (red/green). All 6 categories on screen.
- **Last 24h:** compact `TxRow` density variant (smaller icons, 8px row padding).

### 02 · Goals

- **Header:** "Saving toward / 4 goals" + `+ New` CTA (text-colored solid).
- **Aggregate card:** 110px multi-segment ring (one segment per goal) → "$X.Xk of $Y.Yk" center → side legend with streak chip + on-pace copy.
- **Per-goal cards:** Each card has a 56px ring (`MFVRing`), goal label, due-date chip in the goal's color, dollar progress, progress bar, monthly contribution + months-left, `+ Add` CTA.

### 03 · Email-sync flow

Three sequential steps + one interactive demo card. **Tab bar is hidden** during the flow (set `hideTabs` on `PhoneShell`).

1. **Detected** — "We found one in your inbox." → email source card (sender, subject, "Live" badge, time) → parsed amount + merchant. CTA: `Categorize`.
2. **Categorize** — compact tx summary → purple-tinted suggestion banner ("Suggested: Shopping") → 3×N grid of category cards (selected one has 1.5px outline in category color). CTA: `Confirm & save`.
3. **Saved** — large green check disc → "Saved to {Category}." → "Your X budget is now Y% used." → final tx card with email + account chips → secondary actions `Flag for review` / `Split`.

### 04 · Transaction sheet (bottom sheet)

Triggered from any `TxRow` tap. Slides up from bottom with `mfv-sheet-in` keyframe.

- **Handle bar** (36×4, border-2)
- **Header row:** category glyph + merchant + category label + close ×.
- **Amount display** (38px num-display, cents inset at 22px, green if income).
- **Meta row:** date · time · "Synced from email" (with small mail icon).
- **Meta list** (surface-2 grouped rows): From / Note / Account.
- **"Worth it?" toggle** (only for outflows): two large buttons — green thumbs-up / red thumbs-down. Selected state has tinted bg + colored border.
- **Footer actions:** `Recategorize` (ghost) + `View receipt` (text bg).

---

## Interactions & gestures

- **Tap transaction row** → open bottom sheet. Tap outside or × to dismiss. Background fades + sheet slides via `mfv-sheet-in` (.35s cubic-bezier(.2,.7,.3,1)).
- **Swipe budget cards** (Home A) — horizontal scroll-snap on the track, dot indicator follows. Update active index on scroll.
- **Pull to sync** — drag the home scroller down at `scrollTop === 0`. Beyond a 56px threshold, release triggers `onRefresh`. Indicator (34px disc, sync icon) rotates 0→180° during pull, then spins during refresh, then a "Synced just now" toast pings for ~1.8s.
- **Reflection vote** (Home B) — local state. Tapping `Worth it` or `Regret` highlights the selected button (no persistence in the prototype).
- **Theme toggle** — global. Driven by `t.theme` ∈ {`'dark'`, `'light'`} via `useTweaks`. Cascades through every `PhoneShell` instance.
- **Email-sync flow** — `interactive` prop on the "Try it ↻" artboard enables step progression; static step artboards have buttons disabled.

---

## State management

State is intentionally local — there's no global store in the prototype. In production, lift to your app's preferred pattern (Redux Toolkit, Zustand, Jotai, SwiftUI `@Observable`, etc).

**State surfaces in the prototype:**

| State | Owner | Notes |
|---|---|---|
| `theme` | `useTweaks` (canvas root) | dark/light, drives `.mfv.dark` / `.mfv.light` class |
| `openTx` | `PhoneShell` via `PhoneCtx` | currently-expanded transaction (sheet) |
| Budget swipe index | `HomeA` local | derived from scroll position |
| Pull-to-refresh phase | `PhoneShell` local | `idle / pulling / refreshing / done` |
| Step in add-tx flow | `AddTxFlow` local | 1 → 2 → 3, with `chosenCat` between steps |
| Reflection vote | `ReflectionRow` local | `null / 'up' / 'down'` |
| Tx regret flag | `TxSheet` local | initialized from `tx.regret` |
| Period toggle (Home C) | `HomeC` local | `'D' / 'W' / 'M' / 'Y'` |

**Production data needs:**

- Real-time email sync service (IMAP read, parse receipts/invoices, NLP for amount/merchant/category).
- Transactions, budgets, goals stored remotely + cached locally.
- Push notifications for "new transaction detected" → opens the categorize flow.
- Webhook for bank notifications (Plaid, etc.) for non-email sources.

---

## Design tokens

All tokens live in `tokens.css` (CSS custom properties scoped to `.mfv.dark` / `.mfv.light`).

### Colors — accents (theme-invariant)

| Token | Hex | Usage |
|---|---|---|
| `--c-purple` | `#7C5CFF` | primary accent, CTAs, primary budget card, brand |
| `--c-cyan` | `#22D3EE` | secondary accent, email badge, saved-stat highlight |
| `--c-positive` | `#34E0A1` | income, on-pace, positive deltas |
| `--c-negative` | `#FF6B6B` | overspend, regret, negative deltas |
| `--c-amber` | `#F4B86A` | transport category |
| `--c-coral` | `#FF9EA0` | shopping category |
| `--c-slate` | `#8B95B2` | bills category |
| `--c-green` | `#6FE39A` | entertainment category |

Soft variants are `rgba(R,G,B,0.16)` of the same.

### Category mapping

```
Subscriptions  → #7C5CFF  (purple)   icon: repeat
Food & Drink   → #22D3EE  (cyan)     icon: fork
Transport      → #F4B86A  (amber)    icon: car
Shopping       → #FF9EA0  (coral)    icon: bag
Bills          → #8B95B2  (slate)    icon: bolt
Entertainment  → #6FE39A  (green)    icon: sparkle
Income         → #34E0A1  (positive) icon: arrow-down
```

### Colors — dark theme

| Token | Hex | Notes |
|---|---|---|
| `--bg` | `#0B0B0F` | App background |
| `--surface` | `#16161F` | Cards, tab bar, buttons |
| `--surface-2` | `#1E1E2A` | Nested surfaces, grouped rows |
| `--surface-elev` | `#232336` | Elevated overlays |
| `--text` | `#FAFAFB` | Primary text |
| `--text-2` | `rgba(250,250,255,0.62)` | Secondary text |
| `--text-3` | `rgba(250,250,255,0.38)` | Tertiary / axis labels |
| `--border` | `rgba(255,255,255,0.07)` | Hairline dividers |
| `--border-2` | `rgba(255,255,255,0.12)` | Stronger borders, sheet handle |
| `--chip-bg` | `rgba(255,255,255,0.06)` | Chip / ghost button bg |
| `--track` | `rgba(255,255,255,0.08)` | Progress bar / donut track |

### Colors — light theme

| Token | Hex | Notes |
|---|---|---|
| `--bg` | `#F4F4F1` | Warm off-white |
| `--surface` | `#FFFFFF` | Cards |
| `--surface-2` | `#FAFAF7` | Nested surfaces |
| `--text` | `#0B0B0F` | Primary text |
| `--text-2` | `rgba(11,11,15,0.62)` | Secondary text |
| `--text-3` | `rgba(11,11,15,0.38)` | Tertiary |
| `--border` | `rgba(11,11,15,0.08)` | Hairline |
| `--border-2` | `rgba(11,11,15,0.14)` | Stronger borders |
| `--chip-bg` | `rgba(11,11,15,0.04)` | Chip bg |
| `--track` | `rgba(11,11,15,0.06)` | Progress track |
| `--shadow` | `0 4px 14px rgba(11,11,15,0.06)` | Elevation |

### Typography

- **Display + UI:** Geist (300, 400, 500, 600, 700) — via Google Fonts.
- **Numbers:** Geist Mono (400, 500, 600) — tabular-nums via `font-variant-numeric: tabular-nums`.
- Fallback stack: `-apple-system, BlinkMacSystemFont, system-ui, sans-serif` / `ui-monospace, 'SF Mono', Menlo, monospace`.
- The big balance numbers use Geist *Sans* (not Mono) with `font-feature-settings: 'tnum' 1, 'cv11' 1` and tight `-0.04em` tracking — see `.num-display` in `tokens.css`.

**Type scale used in screens:**

| Style | Size | Weight | Tracking | Usage |
|---|---|---|---|---|
| Balance hero | 44px | 600 | -1.6 | "Total balance" amount |
| Display tx amount | 38px | 600 | -1.2 | Sheet amount |
| H1 | 22px | 700 | -0.6 | Screen headers ("Hi Arif.") |
| H2 | 18px | 600 | -0.6 | Stat values |
| H3 | 14–15px | 600 | -0.2 | Card titles, tx merchant |
| Body | 13px | 400 | 0 | Sheet meta values |
| Small | 11–12px | 500 | 0 | Captions, helper text |
| Caption | 10–11px | 600 | 0.6 uppercase | "TOTAL BALANCE", section eyebrows |
| Mono | 10–12px | 600 | -0.01em | Numbers (Geist Mono) |

### Spacing scale

The prototype uses ad-hoc values informed by these multiples of 2:

`2 · 4 · 6 · 8 · 10 · 12 · 14 · 16 · 18 · 22 · 24`

Phone screen horizontal padding: **18px** (Home A/B/Goals) or **16px** (Home C, denser).

### Radii

| Value | Usage |
|---|---|
| `7px` | Step indicator chips, small toggles |
| `9–11px` | Category glyph squares, small chips |
| `12px` | Icon buttons, FAB, small surfaces |
| `14px` | Quick-action cards, mid surfaces |
| `16–20px` | Insight cards, budget cards |
| `22px` | Tab bar, primary cards |
| `26px` | Bottom sheet top, large card backgrounds |
| `999px` | Pills, chips |

### Shadow / elevation

- `--shadow` light: `0 4px 14px rgba(11,11,15,0.06)`
- `--shadow` dark: `0 10px 30px rgba(0,0,0,0.4)`
- FAB glow: `0 6px 18px rgba(124,92,255,0.45)`
- Primary budget card: `0 10px 24px color-mix(in oklch, <cat-color> 35%, transparent)`

### Animation primitives

| Keyframe | Duration | Usage |
|---|---|---|
| `mfv-rise` | 0.35s `cubic-bezier(.2,.7,.3,1)` | First-mount of hero numbers / step content |
| `mfv-fade` | 0.2s ease | Sheet backdrop |
| `mfv-sheet-in` | 0.35s `cubic-bezier(.2,.7,.3,1)` | Tx sheet slide-up |
| `mfv-spin` | 0.9s linear infinite | Refresh spinner |
| `mfv-toast` | 0.5s `cubic-bezier(.2,.7,.3,1)` | "Synced just now" toast |
| `mfv-pulse` | (defined, unused) | Reserved for live indicators |

### Tap feedback

`.tap` class applies `transform: scale(0.98)` on `:active` with a 0.12s transition.

---

## Component inventory

Components defined in the prototype, in rough dependency order. Recreate these in your stack:

| Component | File | Notes |
|---|---|---|
| `MFVIcon` | `data.jsx` | 24px stroke icon set: `repeat / fork / car / bag / bolt / sparkle / arrow-up / arrow-down / plus / bell / search / home / chart / target / user / mail / check / x / chevron / back / dots / thumbsUp / thumbsDown / sync / flame / eye / eyeOff / coffee / scan / forecast / inbox` |
| `MFVCatGlyph` | `data.jsx` | Colored rounded-square with category icon |
| `MFVDonut` | `data.jsx` | Multi-segment ring, optional centerLabel/centerValue |
| `MFVRing` | `data.jsx` | Single-arc progress ring with center % |
| `MFVSparkline` | `data.jsx` | Line + area fill, no axis |
| `MFVBars` | `data.jsx` | Mini bar chart |
| `MFVBar` | `data.jsx` | Horizontal progress bar |
| `Avatar` | `home.jsx` | 36×36 gradient initial avatar |
| `IconBtn` | `home.jsx` | 36×36 icon button with optional dot badge |
| `Token`, `Swatch` | `canvas.jsx` | Used only on the brief card |
| `TxRow` | `shell.jsx` | The recurring transaction list row (regular + `compact`) |
| `TxSheet` | `shell.jsx` | Bottom sheet for tx detail |
| `BudgetSwipeCard` | `home.jsx` | Horizontal-scroll budget card (primary + secondary) |
| `MiniStat` | `home.jsx` | 3-up Income/Spent/Saved card |
| `PowerStat` | `home.jsx` | 2×2 dense grid stat card |
| `ReflectionRow` | `home.jsx` | "Worth it / Regret" reflection card |
| `GoalCard` | `screens.jsx` | Ring + dollar progress + monthly pace card |
| `AddStep1/2/3` | `screens.jsx` | Email-sync flow steps |
| `PhoneShell` | `shell.jsx` | Frame: status bar + scroll + tabs + sheet + pull-to-refresh |
| `PhoneStatusBar` | `shell.jsx` | Custom-drawn (not the full iOS-frame starter) |
| `PhoneTabBar` | `shell.jsx` | 5-slot bar with floating FAB |
| `HomeA`, `HomeB`, `HomeC` | `home.jsx` | Three home variants |
| `GoalsScreen` | `screens.jsx` | Goals tab |
| `AddTxFlow` | `screens.jsx` | 3-step categorize flow |

### Money formatter

`mfvMoney(amount, { sign, cents, abs })` returns `{ whole: '−$84', cents: '32' }` so the cents can render at a smaller size, e.g.:

```jsx
const m = mfvMoney(-84.32);
<>{m.whole}<span style={{fontSize: '0.5em'}}>.{m.cents}</span></>
```

---

## Data shape

See `data.jsx` for full constants. Schemas:

```ts
type Category = {
  id: 'subs' | 'food' | 'transport' | 'shopping' | 'bills' | 'fun' | 'income';
  label: string;
  color: string;          // CSS color
  icon: string;           // MFVIcon name
};

type Budget = {
  cat: Category['id'];
  spent: number;
  budget: number;
  trend: number;          // percentage vs last month, signed
};

type Transaction = {
  id: string;
  merchant: string;
  cat: Category['id'];
  amount: number;         // signed; negative = expense
  date: string;           // 'Today' | 'Yesterday' | 'May 24'
  time: string;           // 'HH:MM'
  source: 'email' | 'bank';
  note: string;
  regret?: boolean;       // user-flagged
};

type Goal = {
  id: string;
  label: string;
  saved: number;
  target: number;
  due: string;            // 'Dec 2026'
  color: string;          // CSS color
  monthly: number;        // current contribution rate
};
```

---

## Assets

- **Fonts:** Geist + Geist Mono via Google Fonts CDN (`https://fonts.googleapis.com/css2?family=Geist:wght@300;400;500;600;700&family=Geist+Mono:wght@400;500;600`).
- **Icons:** Custom inline SVGs in `MFVIcon`. **Replace with your codebase's icon library** (Lucide, SF Symbols, Material Symbols — all have these glyphs). The stroke weight is 1.75–2.4 — match `currentColor` and pass the stroke prop through.
- **Images:** None. The prototype is deliberately type-and-data-driven.

---

## Files in this bundle

| File | Purpose |
|---|---|
| `My Finance View.html` | Entry HTML — wires React + Babel + scripts |
| `tokens.css` | All design tokens + animation keyframes |
| `data.jsx` | Sample dataset + icons + chart primitives + money formatter |
| `shell.jsx` | PhoneShell + StatusBar + TabBar + TxRow + TxSheet + pull-to-refresh |
| `home.jsx` | HomeA / HomeB / HomeC + Avatar + IconBtn + BudgetSwipeCard + MiniStat + PowerStat + ReflectionRow |
| `screens.jsx` | GoalsScreen + AddTxFlow (steps 1/2/3) + GoalCard |
| `canvas.jsx` | Composition: design canvas layout + Tweaks panel + brief card |
| `design-canvas.jsx` | Pan/zoom canvas starter (prototype-only; remove for production) |
| `tweaks-panel.jsx` | Tweaks panel starter (prototype-only; remove for production) |
| `ios-frame.jsx` | iOS frame starter (unused in final composition; safe to delete) |

---

## Implementation order suggested

1. Set up tokens (CSS variables or your stack's theme system). Verify dark/light swap end-to-end before any UI.
2. Build the icon component + import the icon set from your library.
3. Build the chart primitives — they're pure SVG, easy to port to React Native (`react-native-svg`) or SwiftUI (`Path`/`Shape`).
4. Build `TxRow`, `BudgetCard`, `GoalCard`, `MiniStat/PowerStat` — these compose everywhere.
5. Build the `PhoneShell` equivalent (your nav container) and the bottom-sheet primitive.
6. Build screens A/B/C — pick one to ship first, the other two are alternates the team can evaluate.
7. Wire data — connect the email-sync backend, transactions/budgets/goals models, push notifications for the categorize flow.

---

## Notes for the dev

- The prototype uses inline styles heavily (React `style={{}}`). Convert to your stack's idiom (StyleSheet, NativeWind, etc).
- `color-mix(in oklch, X 22%, transparent)` is used for tinted backgrounds — supported in modern browsers; in RN/native, pre-compute or use rgba.
- Pull-to-refresh: native platforms have first-party primitives (`RefreshControl` in RN, `refreshable` in SwiftUI) — use them instead of the manual pointer-event handler.
- The "Worth it?" reflection pattern is novel — surfacing it after the purchase rather than before is the point. Don't move it into a confirmation flow.
- The subtle email badge (small cyan mail glyph on synced rows) is intentionally low-contrast. Don't promote it into a full pill.
