# Design System — MyFinanceView

> Canonical visual language for the MyFinanceView mobile UI. Curated from the design bundle at `raw/My Finance View SysteMDesign/design-system/` and `raw/My Finance View Dev Handoff/`. **For the full spec read those READMEs**; this doc is the quick reference.

---

## 1. Product framing

The UI exists to answer four questions on every screen:

1. **Where does my money go?**
2. **Am I on pace?**
3. **What might I regret?**
4. **What's next?**

Design choices serve those questions. Color encodes meaning, not decoration. Type leads, copy supports.

## 2. Voice & content rules

- Warm, second-person ("Your shopping budget is now 71% used.").
- Direct questions are encouraged as section headers ("Where did your money go?", "Worth a second look").
- Numbers do the heavy lifting; copy namedrops outliers instead of generalizing.
- Confirmation copy is matter-of-fact ("Saved to Shopping.") — one short sentence.
- **No emoji in product copy.** Sparkle / flame iconography only for insights / streaks.
- Casing: Title Case for screen titles + card eyebrows; sentence case for body; UPPERCASE with `0.6` tracking for small labels ("TOTAL BALANCE").

## 3. Type

- **Sans / display:** Geist (300, 400, 500, 600, 700) — Google Fonts.
- **Numbers:** Geist Mono (400, 500, 600) with `font-variant-numeric: tabular-nums`.
- Big balance numbers use Geist Sans (not Mono) with `font-feature-settings: 'tnum' 1, 'cv11' 1` and `-0.04em` tracking — class `.num-display` in `tokens.css`.

### Scale

| Style | Size | Weight | Tracking | Usage |
|---|---|---|---|---|
| Balance hero | 44px | 600 | -1.6 | "Total balance" amount |
| Display tx | 38px | 600 | -1.2 | Sheet amount |
| H1 | 22px | 700 | -0.6 | Screen headers ("Hi Arif.") |
| H2 | 18px | 600 | -0.6 | Stat values |
| H3 | 14–15px | 600 | -0.2 | Card titles, tx merchant |
| Body | 13px | 400 | 0 | Sheet meta values |
| Small | 11–12px | 500 | 0 | Captions, helper text |
| Caption | 10–11px | 600 | `0.6` UPPERCASE | "TOTAL BALANCE" eyebrows |
| Mono | 10–12px | 600 | -0.01em | Numbers |

## 4. Color

**Accents (theme-invariant):**

| Token | Hex | Usage |
|---|---|---|
| `--c-purple` | `#7C5CFF` | primary accent, CTAs, brand |
| `--c-cyan` | `#22D3EE` | secondary accent, email-synced badge |
| `--c-positive` | `#34E0A1` | income, on-pace, positive delta |
| `--c-negative` | `#FF6B6B` | overspend, regret, negative delta |
| `--c-amber` | `#F4B86A` | transport |
| `--c-coral` | `#FF9EA0` | shopping |
| `--c-slate` | `#8B95B2` | bills |
| `--c-green` | `#6FE39A` | entertainment |

Soft variants = `rgba(R,G,B,0.16)` of the accent.

**Category mapping (canonical — never swap):**

```
Subscriptions  → purple    icon: repeat
Food & Drink   → cyan      icon: fork
Transport      → amber     icon: car
Shopping       → coral     icon: bag
Bills          → slate     icon: bolt
Entertainment  → green     icon: sparkle
Income         → positive  icon: arrow-down
```

**Themes:** Dark is primary (`--bg: #0B0B0F`). Light is warm off-white (`--bg: #F4F4F1`). Full surface scale in `raw/.../tokens.css`.

## 5. Surfaces, spacing, radii

- Cards: 22–26px rounded, hairline border (`7%` dark / `8%` light).
- Phone padding: 18px (Home A/B, Goals) or 16px (Home C — denser).
- Spacing multiples of 2: `2 · 4 · 6 · 8 · 10 · 12 · 14 · 16 · 18 · 22 · 24`.
- Radii: 7 (chips/toggles) · 9–11 (cat glyphs) · 12 (icon btns, FAB) · 14 (quick-actions) · 16–20 (insight/budget) · 22 (tab bar, primary cards) · 26 (bottom sheet top) · 999 (pills).
- Shadow: light `0 4px 14px rgba(11,11,15,0.06)`, dark `0 10px 30px rgba(0,0,0,0.4)`, FAB glow `0 6px 18px rgba(124,92,255,0.45)`.

## 6. Iconography

- Custom **24×24 stroke** set, weight 1.75–2.4, `currentColor`.
- Closest off-the-shelf swap: **Lucide** (web) / **SF Symbols rounded** (iOS).
- The canonical glyphs in `raw/.../data.jsx`: `repeat, fork, car, bag, bolt, sparkle, arrow-up, arrow-down, plus, bell, search, home, chart, target, user, mail, check, x, chevron, back, dots, thumbsUp, thumbsDown, sync, flame, eye, eyeOff, coffee, scan, forecast, inbox`.
- **No emoji.** Inline SVG only.

## 7. Animation

| Keyframe | Duration / curve | Usage |
|---|---|---|
| `mfv-rise` | `0.35s cubic-bezier(.2,.7,.3,1)` | First-mount hero numbers |
| `mfv-sheet-in` | `0.35s cubic-bezier(.2,.7,.3,1)` | Tx sheet slide-up |
| `mfv-fade` | `0.2s ease` | Sheet backdrop |
| `mfv-spin` | `0.9s linear infinite` | Refresh spinner |
| `mfv-toast` | `0.5s cubic-bezier(.2,.7,.3,1)` | "Synced just now" |
| `.tap :active` | `transform: scale(0.98)` 0.12s | Tap feedback |

No bouncy springs, no parallax, no blur on chrome.

## 8. Component inventory

Defined in `raw/.../*.jsx`. Recreate these in the target stack (React Native / SwiftUI / etc):

`MFVIcon, MFVCatGlyph, MFVDonut, MFVRing, MFVSparkline, MFVBars, MFVBar, Avatar, IconBtn, TxRow, TxSheet, BudgetSwipeCard, MiniStat, PowerStat, ReflectionRow, GoalCard, AddStep1/2/3, PhoneShell, PhoneStatusBar, PhoneTabBar`

See [ui-handoff.md](ui-handoff.md) for which component is used in which screen.

## 9. Money formatter

`mfvMoney(amount, { sign, cents, abs })` → `{ whole: '−$84', cents: '32' }` so cents render at smaller size:

```jsx
const m = mfvMoney(-84.32);
<>{m.whole}<span style={{fontSize: '0.5em'}}>.{m.cents}</span></>
```

> Note: this is presentation only. Per [base-standards.md](../base-standards.md), the **backend is the source of truth for monetary values**. The frontend renders what the API computed.

## 10. Caveats (pre-implementation)

- The bundle uses inline React `style={{}}`. Convert to the chosen stack's idiom (StyleSheet / NativeWind / SwiftUI modifiers).
- `color-mix(in oklch, X N%, transparent)` for tinted backgrounds is used in a few places — pre-compute for older runtimes.
- Geist is loaded via Google Fonts CDN; bundle locally for native apps.
- **No accessibility audit yet.** Min sizes are 11px but contrast hasn't been formally tested. Audit before shipping.

## 11. Open questions raised by the designer

These come from the bundle's README — flag them in the Épica 3 grooming:

1. Are the three home variants (A/B/C) distinct enough, or do two feel redundant?
2. Does the **"Worth it?" reflection pattern** feel useful or judgmental?
3. Category palette: too many hues, or want more separation between food (cyan) and the rest?

---

## See also

- [ui-handoff.md](ui-handoff.md) — screens, flows, interactions.
- [frontend-standards.md](../frontend-standards.md) — code-side standards (still placeholder until Épica 3 starts).
- [raw/](raw/) — original bundle, immutable. Treat as source of truth when this curated doc is ambiguous.
- [raw/My Finance View Dev Handoff/design_handoff_my_finance_view/README.md](raw/My%20Finance%20View%20Dev%20Handoff/design_handoff_my_finance_view/README.md) — full handoff (19KB).
- [raw/My Finance View SysteMDesign/design-system/README.md](raw/My%20Finance%20View%20SysteMDesign/design-system/README.md) — full design system reference (7.8KB).
