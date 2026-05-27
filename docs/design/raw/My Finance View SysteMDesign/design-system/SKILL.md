---
name: my-finance-view-design
description: Use this skill to generate well-branded interfaces and assets for My Finance View, either for production or throwaway prototypes/mocks/etc. Contains essential design guidelines, colors, type, fonts, assets, and UI kit components for prototyping.
user-invocable: true
---

Read the README.md file within this skill, and explore the other available files.

If creating visual artifacts (slides, mocks, throwaway prototypes, etc), copy assets out and create static HTML files for the user to view. If working on production code, you can copy assets and read the rules here to become an expert in designing with this brand.

The system is centered on four product questions:
1. Where does my money go?
2. Am I on pace?
3. What might I regret?
4. What's next?

Lean into clarity, type-first hierarchy, and intentional restraint with color. Use color to encode meaning — not as decoration.

If the user invokes this skill without any other guidance, ask them what they want to build or design, ask some questions, and act as an expert designer who outputs HTML artifacts or production code, depending on the need.

Key files to read first:
- `README.md` — voice, visual foundations, component rules
- `../tokens.css` — every design token in one CSS file (dark + light)
- `../data.jsx` — icon set + category palette + chart primitives + money formatter
- The preview cards (`type-*.html`, `color-*.html`, `comp-*.html`) — visual specimens

When extending the system, never invent new categories or palette colors without strong reason — the six existing category hues are canonical.
