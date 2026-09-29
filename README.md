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

### New in 0.8.2 (numbers, statistics, triangles)

- **Numbers**: `gcd(12, 18)`, `lcm(4, 6, 10)` (by prime factors), `factor(360)` (division ladder → 2³ × 3² × 5)
- **Percentages**: `15% of 80`, `80 + 15%`, `80 − 15%`, `20 as % of 80`, `80 to 92 as %` (percentage change)
- **Ratios**: `12:18` (simplify, also with decimals), `share 60 in 2:3`, `3:5 = x:20` (proportion)
- **Counting**: `5!`, `nCr(10, 3)` or `10C3`, `nPr(10, 3)`, and the binomial theorem for `(2x + 3)^5` (powers 3 to 12,
  with Pascal's triangle); `(x + 1)^2` still just expands
- **Statistics**: `stats(2, 4, 4, 5, 7, 9)` gives mean, median, mode, range, quartiles and IQR (medians of the halves),
  variance, and both σ (population) and s (sample); or ask for one: `mean( )`, `median( )`, `mode( )`, `sd( )`,
  `var( )`, `quartiles( )`
- **Triangles**: `triangle(a = 5, b = 7, C = 60°)` — any three of the sides a, b, c and the opposite angles A, B, C
  (at least one side). Cosine rule for SSS and SAS, sine rule for ASA/AAS, and the ambiguous SSA case gives both
  triangles. Area ½ab sin C and perimeter.
- **Completing the square**: `complete(2x² − 8x + 3)` → 2(x − 2)² − 5 with the vertex and a graph;
  `complete(x² − 4x + 1 = 0)` solves the equation that way
- New **n!** keyboard page for all of these (hold "of" for "as % of")
- Normal: the input box grows for matrices so a 3 × 3 isn't cut off. Matrix tab: fits narrow phones and moves up
  above the phone's keyboard.

### New in 0.8.0 (vectors and matrices)

- **Vectors (2D)** — type components `(3, 4)` or length∠angle `5∠30°` (a plain angle is in degrees; with π or °
  it is used as written). Name them with `;`: `a = (3, 5); b = (4, 2); a + b`.
  - Sums and differences step by step, like the NASA "Vector Addition" diagram: add the x-components, add the
    y-components, then the magnitude by Pythagoras and the direction (0°–360° from the positive x-axis, with the
    reference angle and quadrant)
  - A head-to-tail diagram with every sum: given vectors from the origin in blue, the moved vector in the text colour,
    the resultant in red, and dashed components labelled a_x, b_x, a_y, b_y, c_x = a_x + b_x, c_y = a_y + b_y
  - Scalar multiples (`2a`, `a/2`, `−a`), magnitude `|a|`, polar form `polar(a)`, dot product `a · b` (says when the
    vectors are perpendicular), 2D cross product `a × b` (the signed parallelogram area; 3D vectors also work),
    `angle(a, b)`, `unit(a)`, `proj(a, b)` (scalar and vector projection)
- **Matrices** — `[[1, 2], [3, 4]]` in text, or the [2×2] / [3×3] keys (+row / +col to grow them). Exact fractions,
  up to 6 × 6.
  - `A + B`, `A − B`, `3A`, `A B` (each entry written out as row · column), `A (5, 6)`, `A^3`, `A^-1`, `A^T`
  - `det(A)` or `|A|`: ad − bc for 2 × 2, cofactor expansion along the row or column with the most zeros for 3 × 3,
    row reduction for bigger ones
  - `inv(A)`: the 2 × 2 formula, or Gauss–Jordan on [A | I] with every row operation shown, then a check that
    A·A⁻¹ = I
  - `ref(A)`, `rref(A)`, `rank(A)`, `trace(A)`
  - `eig(A)`: the characteristic equation det(A − λI) = 0, the eigenvalues, and an eigenvector for each
    (irrational and complex ones exactly for 2 × 2)
  - `A x = b` (x not defined yet) or `solve(A, b)`: augmented-matrix row reduction; one solution, no solution, or
    infinitely many written with parameters t, s, …
- New **[ ]** keyboard page for all of the above. The basic × key now types `·`, so between two vectors it means the
  dot product; × (the cross product) is on the [ ] page. For numbers both still just multiply.
- **Matrix tab** (next to Normal and Notebook): fill in Matrix A and Matrix B as boxes (blank = 0, fractions like
  1/2 work), ⇄ to swap, − / + to resize (1 × 1 to 6 × 6), then tap Determinant, Inverse, Transpose, Rank,
  Multiply by, Row echelon form, Diagonal matrix, To the power of, LU decomposition, Cholesky decomposition,
  A × B, A + B, A − B, or type an expression like `2A + 3B`. The answer shows with full steps; ▴ Edit matrices
  goes back to the boxes.
  - `lu(A)`: A = LU (or PA = LU when a row swap is needed), `chol(A)`: A = LLᵀ (exact square roots),
    `diag(A)`: A = PDP⁻¹ when every eigenvalue is a whole number or fraction
- **▾ Hide keys / ▴ Keys**: hide the keyboard to see more of the answer; Solve stays available.
- **Notebook**: vectors and matrices carry over to later lines (`a = (3, 5)`, then `2a + b`, then `|c|`). A later
  number with the same letter replaces a vector, and the other way round.

### New in 0.7.0 (complex numbers, compound inequalities, more ODEs, finance formulas)

- **Complex numbers** — `i` is √−1 (new `i` key on the f(x) page). Arithmetic with steps: multiplying out with
  i² = −1, dividing by the conjugate, powers of i, De Moivre for big powers, `√(−16)`, `√(3 + 4i)`, Euler's formula
  (`e^(iπ)` = −1), `ln(−1)`, `i^i`. Also `abs(z)` (modulus), `arg(z)`, `conj(z)`, `real(z)`, `imag(z)`.
  Every non-real answer shows its modulus, argument and polar form, with the point plotted on the complex plane.
- **Complex equations** — `(1 + i)z = 3 − i`, `z² + (1 − i)z − i = 0` (complex quadratic formula), `z³ = 8i`
  (all n roots by De Moivre), other polynomials numerically. `(x + i)(x − i)` multiplies out to `x² + 1`.
  Real equations with no real roots (like `x² + 2x + 5 = 0`) now give the complex roots as the answer.
- **Compound inequalities** — `1 < x < 3`, `−3 < 1 − 2x ≤ 5` (done to all three parts at once, flipping both signs
  when dividing by a negative), and `0 ≤ x² − 4 < 5` or `1 < 1/x < 2` (both parts solved, then the overlap).
- **Second-order ODEs with a non-constant right side** — `y'' + 4y = 3cos(2x) + x`, `y'' − 2y' + y = x·e^x`:
  undetermined coefficients (including the "multiply by x" case when the guess repeats a root), and variation of
  parameters for other right sides (`y'' + y = 1/cos(x)`). Also Cauchy–Euler equations `x²y'' + bxy' + cy = 0`.
- **More first-order ODEs** — Bernoulli (`y' + y = xy²`), homogeneous (`y' = (x² + y²)/(xy)`) and exact
  (`(2xy + 1) + (x² + 2y)y' = 0`).
- **Any other ODE, numerically** — when no exact method fits, give starting values and LocalMath solves it with
  Runge–Kutta (RK4): `y' = x² + y²; y(0) = 0`, `y'' = −sin(y); y(0) = 1; y'(0) = 0`. Shows a table and graph,
  and says where the solution blows up.
- **Recurrences with complex roots** — `a_n = a_(n−1) − a_(n−2)` gives `cos(π(n−1)/3) + …` using polar form.
- **Formulas now has two tabs: Engineering and Finance & economics.** 53 new formulas: interest and growth
  (simple, compound, continuous, EAR, present value, CAGR, doubling time), loans and annuities (monthly payment,
  balance, total interest, future/present value of payments, perpetuity), investing (ROI, dividend yield, EPS, P/E,
  Gordon growth, CAPM), accounting (margins, depreciation, ratios, ROA/ROE, turnover, DSO), business (break-even,
  markup, contribution margin, tax, discount) and economics (elasticity, GDP, inflation, unemployment, multipliers).
  Rates are typed in percent; money is shown with two decimals (`1,580.17`) and can be typed as `250,000` or `250k`.
  As before, pick any unknown — the rate of a loan is found numerically.
- Exact roots of perfect powers: `27^(2/3)` = 9, `(−8)^(1/3)` = −2.

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

Also in 0.7.0: non-constant right sides, Cauchy–Euler, Bernoulli, homogeneous and exact equations, and numerical
solutions for everything else (see above).

## Input syntax
| Want | Type |
|---|---|
| Derivative | `d/dx(x² sin x)` |
| Indefinite integral | `∫(x e^x)` (add `dt` after it for another variable: `∫(t²)dt`) |
| Definite integral | `∫(x², 0, 3)` |
| System | `2x + y = 5; x − y = 1` |
| Absolute value | `abs(x)` |
| Inequality | `x² − 4 ≥ 0`, or two signs: `1 < x ≤ 3` |
| Complex number | `(3 + 2i)(1 − 4i)`, `abs(3 + 4i)`, `arg(z)`, `conj(z)`, `real(z)`, `imag(z)` |
| Differential equation | `y'' + y = sin(x); y(0) = 1; y'(0) = 0` |
| Vector | `(3, 4)`, `5∠30°` (degrees), `5∠π/6` (radians) |
| Named vectors | `a = (3, 5); b = 5∠30°; a + b` |
| Dot / cross product | `a · b`, `a × b` (or `dot(a, b)`, `cross(a, b)`) |
| Length, angle, unit, projection | `|a|`, `angle(a, b)`, `unit(a)`, `proj(a, b)`, `polar(a)` |
| Matrix | `[[1, 2], [3, 4]]` |
| Matrix operations | `A B`, `A^2`, `A^-1`, `A^T`, `det(A)`, `inv(A)`, `rref(A)`, `ref(A)`, `rank(A)`, `trace(A)`, `eig(A)`, `lu(A)`, `chol(A)`, `diag(A)` |
| Matrix equation | `A = [[2, 1], [1, -1]]; A x = (5, 1)` or `solve(A, (5, 1))` |

Missing closing brackets are added automatically. Hold ⌫ to clear. `e` is always Euler's number and `i` is always √−1
(except as the counter in `Σ(i², i, 1, 10)`, and inside the formula library where letters are just labels).

## How answers are checked
Every derivative and integral is verified numerically before it's shown (and definite integrals
are cross-checked with Simpson's rule). If a check fails, LocalMath shows a message instead of
a possibly wrong answer.

## Layout
- `engine/` — pure Kotlin, no Android dependencies (Parser, Sym, Differentiator, Integrator,
  Calculus, SystemSolver, Equations, RatFunc, Inequalities, Complex, Ode, Graph, Solver, and LinAlg / Vectors /
  Matrices for vectors and matrices)
- `ui/` — Compose screens and the keyboard; `MathView` shows the page built by `SolutionHtml`
  (KaTeX from `assets/katex`, graphs drawn by `assets/plot.js`, vector diagrams as inline SVG)
- `data/` — Room history

KaTeX (MIT licence) is bundled in `app/src/main/assets/katex`.
