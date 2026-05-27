# UI Handoff — MyFinanceView

> Screens, flows, and interactions for the MyFinanceView mobile UI. Curated from `raw/My Finance View Dev Handoff/design_handoff_my_finance_view/README.md`. **The bundle's `.jsx` files are design references, NOT production code** — recreate in the chosen stack.

---

## 1. Status & fidelity

- **High-fidelity.** Colors, type, spacing, radii, interactions are final. Match in implementation.
- **Mobile-first.** Phone canvas in prototype is 320×660; production target is 390×844.
- **Stack TBD.** Per [frontend-standards.md](../frontend-standards.md): React 19 + Vite + TS is the current pick (decision still pending Épica 3 grooming). The bundle's React/JSX is reference only.
- **Open questions** that should resolve before implementation: see [design-system.md §11](design-system.md).

## 2. Tabs & shell

5-slot tab bar with floating FAB. `PhoneShell` owns:

- Status bar (custom-drawn, not full iOS chrome).
- Scrollable content.
- Bottom sheet (tx detail).
- Pull-to-refresh state machine (drag down at `scrollTop === 0`, ≥56px to trigger).
- Theme toggle (dark/light) cascading via `.mfv.dark` / `.mfv.light`.

The tab bar **hides** during the email-sync flow (`hideTabs` prop).

## 3. Screens

### 3.1 Home — three variants for grooming

The bundle ships **three** home dashboards side-by-side; the team picks one (or merges) during Épica 3 grooming. All share the same data layer.

#### A · Classic balance
"What's my money doing right now."
- Greeting (avatar + "Good morning, {name}" + search/bell).
- **Balance hero** (44px display, green delta chip).
- Quick actions row: `Add · Scan · Unclassified · Forecast` (Unclassified shows count badge).
- **Budgets · May** — horizontally-scrolling snap card track (first card primary-filled, rest surface).
- Recent activity vertical list (`TxRow`).

#### B · Reflection-first
For introspection.
- Date + "Hi {name}." (22px / 700).
- **Donut hero card**: "Where did your money go?" + 120px donut + side legend (top 4 cats).
- **Weekly insight card**: purple-tinted gradient, sparkle pill, sentence naming the outlier, two actions (`Review` ghost + `Flag purchase` solid).
- **Stat trio**: Income / Spent / Saved (Saved card has cyan accent).
- **"Worth a second look"** reflection rows for large recent purchases with `Worth it` / `Regret` thumbs.

#### C · Power-user
Dense + analytics.
- Header: avatar + "Visa ····4082" + "synced 2m ago" indicator.
- **Net balance card**: large number + D/W/M/Y segmented toggle + 50px sparkline (last 30 days).
- **4-stat grid (2×2)**: Income / Spent / Saved / Net.
- **Budgets bar list** (compact, all 6 categories on-screen).
- **Last 24h** compact `TxRow` density variant.

### 3.2 Goals

- Header: "Saving toward / 4 goals" + `+ New` CTA.
- **Aggregate card**: 110px multi-segment ring (one segment per goal) + center "$X.Xk of $Y.Yk" + streak chip.
- **Per-goal cards**: 56px `MFVRing`, label, due-date chip in goal's color, $ progress, progress bar, monthly contribution, `+ Add` CTA.

Backed by the [Savings Goals épica](../../plans/savings-goals-plan.md).

### 3.3 Email-sync flow (3 steps + interactive demo)

Triggered when the backend reports a new email-detected transaction. **Tab bar hidden** during the flow.

1. **Detected** — "We found one in your inbox." → email source card (sender, subject, "Live" badge, time) → parsed amount + merchant. CTA: `Categorize`.
2. **Categorize** — compact tx summary → purple-tinted suggestion banner ("Suggested: Shopping") → 3×N category card grid (selected has 1.5px outline in category color). CTA: `Confirm & save`.
3. **Saved** — large green check disc → "Saved to {Category}." → "Your X budget is now Y% used." → final tx card + secondary actions `Flag for review` / `Split`.

The "Suggested" comes from the LLM categorizer (see [SPEC.md](../../SPEC.md) §LLM categorization).

### 3.4 Transaction sheet (bottom sheet)

Triggered by tapping any `TxRow`. Slide-up via `mfv-sheet-in`.

- Handle bar (36×4).
- Header: category glyph + merchant + category label + `×`.
- **Amount** (38px num-display; cents inset at 22px; green if income).
- Meta row: date · time · "Synced from email" (small mail icon for email-sourced).
- Meta list (grouped surface-2 rows): From / Note / Account.
- **"Worth it?" toggle** (outflows only): green thumbs-up / red thumbs-down. Selected = tinted bg + colored border.
- Footer: `Recategorize` (ghost) + `View receipt` (text bg).

## 4. Interactions / gestures

| Gesture | Behavior |
|---|---|
| Tap `TxRow` | Open bottom sheet. Tap outside or × to dismiss. |
| Swipe budget cards (Home A) | Horizontal scroll-snap; dot indicator follows. |
| Pull to sync | Drag down at `scrollTop === 0`. >56px = trigger. Indicator rotates 0→180° during pull, spins during refresh, "Synced just now" toast 1.8s. |
| Reflection vote (Home B) | Local state. Tap `Worth it` or `Regret`. (No persistence in prototype.) |
| Theme toggle | Global. `t.theme` ∈ {`'dark'`, `'light'`}. |

## 5. State surfaces (prototype-local; lift to app state in production)

| State | Owner | Notes |
|---|---|---|
| `theme` | canvas root | drives `.mfv.dark` / `.mfv.light` |
| `openTx` | `PhoneShell` via `PhoneCtx` | currently-expanded tx |
| Budget swipe index | `HomeA` local | derived from scroll |
| Pull-to-refresh phase | `PhoneShell` local | `idle / pulling / refreshing / done` |
| Step in add-tx flow | `AddTxFlow` local | 1 → 2 → 3, with `chosenCat` |
| Reflection vote | `ReflectionRow` local | `null / 'up' / 'down'` |
| Tx regret flag | `TxSheet` local | initialized from `tx.regret` |
| Period toggle (Home C) | `HomeC` local | `'D' / 'W' / 'M' / 'Y'` |

## 6. Data shape expected by the UI

From `raw/.../data.jsx`. The API contract (see [api-spec.yml](../api-spec.yml)) must return shapes compatible with:

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
  trend: number;          // % vs last month, signed
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
  regret?: boolean;
};

type Goal = {
  id: string;
  label: string;
  saved: number;
  target: number;
  due: string;            // 'Dec 2026'
  color: string;          // CSS color
  monthly: number;
};
```

⚠️ **Money values from the API:** per [base-standards.md](../base-standards.md), the backend sends money as a string (`BigDecimal` serialized) or as integer minor units, not as JS `number`. The bundle's `amount: number` is illustrative — the real type in [api-spec.yml](../api-spec.yml) wins.

## 7. Backend touchpoints (concrete asks from the UI)

This is what the API needs to expose / produce for the screens above to work:

1. **Aggregates for Home A/B/C** (balance, deltas vs last month, donut breakdowns by category, sparkline data points).
2. **Budgets per category, per month** (with `spent`, `budget`, `trend`).
3. **Goals** + multi-segment progress (already covered in [plans/savings-goals-plan.md](../../plans/savings-goals-plan.md)).
4. **Transactions list + filters** (paginated, server-driven sorting/filtering).
5. **Tx detail** with `note`, `source`, `regret` flag (writable from "Worth it?" toggle).
6. **Categorization suggestion endpoint** for the email-sync flow (LLM-backed, returns `{ category, confidence }`).
7. **Email-sync feed** for the "Detected" step.
8. **Reflection flag persistence** (PATCH on tx).

Not all of these exist today — they map to épicas yet to be groomed in Notion.

## 8. Implementation order (designer-suggested)

1. Tokens — verify dark/light end-to-end before any UI.
2. Icon component + import set from chosen library.
3. Chart primitives (donut / ring / sparkline / bars / bar).
4. `TxRow`, `BudgetCard`, `GoalCard`, `MiniStat/PowerStat` — they compose everywhere.
5. `PhoneShell` (nav container) + bottom-sheet primitive.
6. Pick one Home variant (A/B/C) to ship first.
7. Wire data — email-sync backend, transactions / budgets / goals models, push notifications.

## 9. Things to NOT do (from the designer's notes)

- Don't promote the "Synced from email" badge into a full pill — it's plumbing, not a feature.
- Don't move the "Worth it?" reflection into a pre-purchase confirm flow. The point is post-purchase reflection.
- Don't invent new categories or palette colors — the six are canonical.
- Don't pile on glass / blur effects on chrome.

---

## See also

- [design-system.md](design-system.md) — tokens, type, colors, components.
- [raw/My Finance View Dev Handoff/design_handoff_my_finance_view/README.md](raw/My%20Finance%20View%20Dev%20Handoff/design_handoff_my_finance_view/README.md) — full handoff (19KB).
- [plans/savings-goals-plan.md](../../plans/savings-goals-plan.md) — Goals épica.
- [frontend-standards.md](../frontend-standards.md) — code conventions (still placeholder).
