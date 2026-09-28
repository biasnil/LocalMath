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
