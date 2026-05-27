# My Finance View · Design System

A mobile finance app design system: typography, color, surfaces, components, and brand voice. Built from the My Finance View prototype and ready to apply to production code, marketing surfaces, or follow-on mocks.

> The companion prototype lives in `../My Finance View.html` — open it to see every component composed into screens.

---

## What this is for

The product helps people get clear on their money in real time, with most transactions sourced automatically from their email inbox. The system is built to answer four questions on every screen:

1. **Where does my money go?**
2. **Am I on pace?**
3. **What might I regret?**
4. **What's next?**

That framing leans the visual language toward clarity, type-first hierarchy, and intentional restraint with color. We use color to encode meaning (categories, gain/loss, urgency), not to decorate.

---

## Content fundamentals

- **Voice is warm and second-person.** "Your shopping budget is now 71% used." Never the bank-formal "Account balance reflects pending transactions."
- **Direct questions are encouraged** as section headers — *Where did your money go? · Worth a second look · How should we classify this?*
- **Numbers do the heavy lifting.** Copy supports the numbers, never the reverse. If the number is "+$132 over average", the copy should namedrop the outlier transaction, not generalize.
- **Confirmation copy is matter-of-fact** ("Saved to Shopping."). One short sentence, then move on.
- **No emoji in product copy.** Sparkle and flame iconography are used sparingly for insights / streaks.
- **Casing:** Title Case for screen titles and card eyebrows. Sentence case for body. Uppercase + 0.6 tracking for small labels ("TOTAL BALANCE").

---

## Visual foundations

- **Type:** Geist (sans) for UI + display, Geist Mono for all numerals. Tabular numerals everywhere money appears.
- **Color:** Purple `#7C5CFF` and Cyan `#22D3EE` as the brand accents; positive `#34E0A1` and negative `#FF6B6B` for movement. Six category hues (subscriptions/food/transport/shopping/bills/fun) carry consistent meaning everywhere.
- **Themes:** Dark `#0B0B0F` and Light `#F4F4F1`. The dark mode is the primary; light mode preserves the same hierarchy with warmer off-white surfaces.
- **Surfaces:** 22–26px rounded cards on near-black or warm-white backgrounds. Hairline borders (`7%` in dark, `8%` in light), no heavy strokes.
- **Shadow:** Soft and diffuse in dark (`0 10px 30px rgba(0,0,0,0.4)`), tighter in light (`0 4px 14px rgba(11,11,15,0.06)`). Brand-tinted glow on the FAB.
- **Imagery:** None. The system is intentionally type-and-data-first. No stock photography, no decorative illustrations.
- **Iconography:** Custom 24×24 stroke set, 1.75–2.4 weight, `currentColor`. Replace with the consuming codebase's icon library (Lucide is the closest off-the-shelf match).
- **Animation:** Cubic-bezier `(.2, .7, .3, 1)` for material slides (sheets, hero numbers). Tight 0.12s scale-down (0.98) on `:active` for tap feedback. No bouncy springs, no parallax.
- **Press states:** Apply `.tap` for scale(0.98) on `:active`.
- **Hover states:** Web-only afterthought (this is mobile-first). When present, lighten chip-bg by ~2%.
- **Layout rules:** Phone canvas 320×660 in the prototype (production 390×844). Side padding 18px (or 16px for dense screens). Tab bar floats with 14px side margin.
- **Transparency / blur:** Restrained. Only the tx sheet uses a dimmed backdrop (rgba(0,0,0,0.5)). No glass / blur effects on chrome.

---

## Files

### Tokens & code

| File | Purpose |
|---|---|
| `../tokens.css` | All design tokens — CSS custom properties under `.mfv.dark` / `.mfv.light` |
| `../data.jsx` | Icon library, chart primitives, sample dataset, money formatter |
| `../shell.jsx` | PhoneShell + TxRow + TxSheet + pull-to-refresh |
| `../home.jsx` | Three home screen variants + supporting widgets |
| `../screens.jsx` | Goals, Add/Categorize flow |

### Preview cards (this folder)

| Card | What it shows |
|---|---|
| `brand-overview.html` | Brand summary + accent strip |
| `type-display.html` | Display + heading scale |
| `type-body.html` | Body + supporting text |
| `type-mono.html` | Geist Mono + tabular numeral specimens |
| `color-accents.html` | Purple / cyan / positive / negative + soft variants |
| `color-categories.html` | The six spending category hues |
| `color-themes.html` | Dark vs light surface scales side-by-side |
| `spacing-radii.html` | Radius set + spacing values in use |
| `shadows.html` | Elevation specimens (light / dark / FAB glow) |
| `iconography.html` | The 24-glyph stroke icon set |
| `comp-buttons.html` | Quick actions, icon buttons, CTAs, FAB, chips |
| `comp-tx-row.html` | The TxRow component (regular + compact) |
| `comp-budget-card.html` | BudgetSwipeCard (primary + secondary) |
| `comp-charts.html` | Donut · ring · sparkline · mini bar list |
| `comp-stats-insight.html` | MiniStat trio · PowerStat grid · insight card |
| `comp-phone-shell.html` | Phone frame + status bar + tab bar |
| `comp-tx-sheet.html` | Bottom-sheet transaction detail |

---

## Iconography rules

- Stroke-only, 24×24 viewBox, `stroke-width` between 1.75 (decorative) and 2.5 (interactive).
- All icons use `currentColor` so a single token controls color across the app.
- The category set (`repeat`, `fork`, `car`, `bag`, `bolt`, `sparkle`, `arrow-down`) is canonical — never swap a category's icon.
- For new icons, match weight and rounded line caps. Lucide is the closest off-the-shelf set if you need to extend; SF Symbols 'rounded' variant also fits.
- Never embed emoji. The system uses inline SVG only.

---

## Component implementation notes

- **`TxRow`** is the most-reused component. It accepts `tx` plus an optional `compact` boolean (drops icon to 34×34, padding to 8). Tap calls `setOpenTx(tx)` on the `PhoneCtx` context.
- **`BudgetSwipeCard`** styles its "primary" variant differently — full-bleed gradient in the category color with a decorative concentric-circles overlay at 15% opacity. All other cards in the track use `secondary` (surface + 1px border).
- **`MFVRing` vs `MFVDonut`** — Ring is one arc (single goal). Donut takes a `segments` array (multi-category). Both can show a centered value.
- **`PhoneShell`** owns the pull-to-refresh state machine. It only fires `onRefresh` when scrollTop is 0 at drag start and the user pulls past 56px. The "Synced just now" toast pings for 1.8s on success.
- **`AddTxFlow`** is the categorize flow. Pass `interactive` to enable step progression; static artboards render frozen steps.

---

## Caveats

- **Iconography is custom.** If you adopt this system, the first move is swapping these inline SVGs for your icon library — match weight and metaphor.
- **`color-mix(in oklch, ...)`** is used in a few places for tinted backgrounds. Modern browsers handle it; if you target older runtimes, precompute the tints.
- **`text-wrap: pretty`** is used on longer body copy. Falls back gracefully but won't be optimized in older browsers.
- **Geist is loaded from Google Fonts at runtime.** For native apps, bundle the .ttf locally — see `https://vercel.com/font` for licensing.
- **No accessibility audit yet.** Sizes meet 11px minimum but the color tokens haven't been contrast-tested formally. Do that before shipping.

---

## Bold ask

I'd love feedback on:

1. **Are the three home variants distinct enough?** They share the data model; the difference is framing. If two feel redundant, tell me which one to merge.
2. **The "Worth it?" reflection pattern** — does it feel useful or judgmental? It's the most opinionated part of the system.
3. **Category palette** — is it doing too much, or do you want more separation between food/cyan and the rest?

If you find anything that doesn't earn its place, flag it and I'll cut it.
