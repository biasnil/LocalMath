# LocalMath

Offline step-by-step math solver for Android (Kotlin + Jetpack Compose). No internet permission.

Open this folder in Android Studio (File > Open), let Gradle sync, then press Run.
If Android Studio offers to upgrade the Android Gradle Plugin, it's safe to accept.

## What it solves
- Linear and quadratic equations in one variable (factoring, square roots, quadratic formula, complex roots)
- Polynomial equations of any degree: rational root test + synthetic division, then the quadratic
  formula; numeric roots when no exact method applies
- Equations with x in a denominator: restrictions, cross-multiplying, rejecting extraneous roots
- Fraction simplification: common denominators and cancelling common factors (polynomial GCD)
- Inequalities (<, >, ≤, ≥): linear ones by isolating x (flipping when dividing by a negative),
  polynomial and rational ones with a sign chart, answers in interval notation
- Graphs for almost every answer: pan, pinch-zoom, roots/intersections marked, shaded area for
  definite integrals, solution intervals for inequalities
- Simplifying and expanding expressions, exact arithmetic, exact values (sin(π/6), √8, ln(e³) …)
- Derivatives: sum, constant multiple, power, product, quotient and chain rules, e^x, a^x, ln, log,
  trig and inverse trig, logarithmic differentiation (x^x)
- Integrals: power rule, standard integrals, u = ax + b, u-substitution, integration by parts,
  definite integrals with exact values
- Systems of linear equations (up to 6 variables) by elimination, including no-solution and
  infinitely-many cases
- History of solved problems (Room database, stored only on the device)

### New in 0.6.0 (Normal / Notebook)

- **Normal / Notebook switch** at the top of the main screen.
  - *Normal*: one problem, full step-by-step working (as before).
  - *Notebook*: a running list of lines, each showing just `problem → answer`. Saved in the Room database
    (table `notebook`, added by a migration so existing history is kept), so it's still there next time.
- **Chaining**: later lines use values found earlier. `2x + 3 = 11` → x = 4, then `y = 3x + 2` → y = 14,
  `z = y − x` → z = 10, `x + y + z` → 28. Each line shows which values it used. A line whose letters are
  all known already (e.g. typing `2x + 3 = 11` again) is solved fresh; lines with two answers (x = ±√2) don't set a value.
- **Calculator-style lines**: a trailing `=` works (`5 * 6 =` → 30), in both modes.
- **Ans** puts the last answer into the input; tapping any answer does the same for that line.
  ✕ deletes a line; *Clear notebook* asks before deleting everything. Errors show in red on their own line.
- Engine: `Solver.solve(Input)` entry point and `engine/Notebook.kt` (value tracking and substitution).
  Symja is still not used: the pure-Kotlin engine checks its own answers numerically instead.

### New in 0.5.0 (engineering)

- **Tap-to-edit math input** (MathLive, bundled offline in `assets/editor`, MIT licence). Fractions, roots, powers,
  limits and sums appear as real math with boxes to fill in; tap anywhere in the problem to move the cursor.
  `÷` makes a fraction; hold `∫` for a definite integral. The old plain-text input is still there (⋮ menu).
  The editor's LaTeX is turned back into LocalMath's text syntax by `engine/LatexInput.kt`.
- **Eng keyboard page** — SI prefixes k, M, G, m, µ, n, p; `∥` for parallel (`10k ∥ 4.7k` = R₁R₂/(R₁+R₂));
  `×10ⁿ`, `10ˣ`, and `dB` (20·log₁₀).
- **Engineering answers** (⋮ menu) — results also shown with prefixes, e.g. 0.002553 → 2.553 m.
- **Formula library** (Formulas button) — 70 common formulas for DC circuits, AC and electronics, digital/IoT,
  mechanics, materials, thermal/fluids and maths. Search, ★ favourites, pick the unknown, fill in the rest
  (prefixes work: 4.7k, 100n). Steps show the formula rearranged, the numbers substituted and the result
  with units in engineering notation. Formulas where the unknown appears twice are solved as equations; every
  answer is checked by putting it back into the formula.
- Letters now keep their case, so `R` and `r` are different variables (function names still work in any case).
- Exact `log` of powers of ten (log 1000 = 3).

### New in 0.4.0 (Calculus 2)

- **Limits** — `lim(sin x / x, 0)`, `lim(f, x → 2)`, one-sided `lim(1/x, 0+)` / `lim(1/x, 0−)`, and `lim(f, ∞)` / `lim(f, −∞)`.
  Uses substitution, factor-and-cancel, highest powers at ∞, L'Hôpital, conjugates, logarithms for 1^∞ forms and the squeeze theorem.
  Two-sided limits check both sides.
- **Hyperbolic functions** — sinh, cosh, tanh, arsinh, arcosh, artanh everywhere (derivatives, integrals, limits, graphs).
- **Higher and partial derivatives** — `d/dx(f, 2)` (orders 1–10); `d/dy(x²y³)` treats other letters as constants.
- **Sums and series** — `Σ(k², 1, 10)`, closed forms `Σ(k³, 1, n)`, geometric sums, and infinite series `Σ(f, 1, ∞)`
  with the divergence test, p-series comparison, telescoping, alternating series test and ratio test.
  Exact values when known (e.g. π²/6); otherwise a labelled numerical estimate (≈).
- **Sequences** — `a_n = (1 + 1/n)^n` (terms, arithmetic/geometric, limit) and recurrences
  `a_n = 2a_(n−1) + 1; a_1 = 1` or `a_n = a_(n−1) + a_(n−2); a_1 = 1; a_2 = 1` (closed forms where possible).
- **Differential equations** — `y′ = f(x)`, separable, linear first-order (integrating factor),
  and `a·y″ + b·y′ + c·y = k`. Starting values after `;`: `y″ + y = 0; y(0) = 1; y′(0) = 0`. `dy/dx` also works.
- **Partial fractions** in integrals, e.g. `∫(1/(x² − 1))`.

Not supported yet: second-order equations with a non-constant right side, general nonlinear ODEs,
closed forms for recurrences with complex roots.

## Input syntax
| Want | Type |
|---|---|
| Derivative | `d/dx(x² sin x)` |
| Indefinite integral | `∫(x e^x)` (add `dt` after it for another variable: `∫(t²)dt`) |
| Definite integral | `∫(x², 0, 3)` |
| System | `2x + y = 5; x − y = 1` |
| Absolute value | `abs(x)` |
| Inequality | `x² − 4 ≥ 0` (one sign at a time; `1 < x < 3` isn't supported yet) |

Missing closing brackets are added automatically. Hold ⌫ to clear. `e` is always Euler's number.

## How answers are checked
Every derivative and integral is verified numerically before it's shown (and definite integrals
are cross-checked with Simpson's rule). If a check fails, LocalMath shows a message instead of
a possibly wrong answer.

## Layout
- `engine/` — pure Kotlin, no Android dependencies (Parser, Sym, Differentiator, Integrator,
  Calculus, SystemSolver, Equations, RatFunc, Inequalities, Graph, Solver)
- `ui/` — Compose screens and the keyboard; `MathView` shows the page built by `SolutionHtml`
  (KaTeX from `assets/katex`, graphs drawn by `assets/plot.js`)
- `data/` — Room history

KaTeX (MIT licence) is bundled in `app/src/main/assets/katex`.
