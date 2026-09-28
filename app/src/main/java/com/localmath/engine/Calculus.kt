package com.localmath.engine

/**
 * Derivatives, integrals and general simplification.
 * Every calculus answer is double-checked numerically before it's shown, so a gap in the rules
 * produces an honest "not supported" message instead of a wrong answer.
 */
object Calculus {

    private val SAMPLE_POINTS = listOf(0.37, 0.83, 1.29, 1.71, 2.53, -0.61, -1.37, 3.1, 0.12)

    fun simplify(e: Expr): Solution {
        val s = Sym.from(e)
        val vars = Sym.variables(s)
        val main = mainVar(vars)
        val start = Tex.expr(e)
        val result = Sym.latex(s, main)
        val steps = mutableListOf(Step("Start with", start))
        steps += if (!differs(result, start)) Step("Already simplified", result)
        else Step("Simplify", result, "Combine like terms, cancel common factors and evaluate exact values")
        val approx = if (vars.isEmpty() && s !is S.Num) {
            val d = Sym.eval(s, emptyMap())
            if (d.isFinite()) "\\approx ${Tex.decimal(d)}" else null
        } else null
        val graph = if (vars.size == 1) Graphs.build(listOf(Graphs.curve(s, main, "y = $result"))) else null
        return Solution(if (vars.isEmpty()) "Exact value" else "Simplify", steps, result, approx, graph)
    }

    // ---------------- derivatives ----------------

    fun derivative(e: Expr.Derivative): Solution {
        val v = e.variable
        val f = Sym.from(e.body)
        val start = Tex.expr(e)
        val others = Sym.variables(f) - v
        val steps = mutableListOf(Step("Start with", start,
            if (others.isEmpty()) null
            else "Other letters (\\(${others.sorted().joinToString(", ")}\\)) are treated as constants. " +
                "This is the partial derivative \\(\\frac{\\partial}{\\partial $v}\\)."))
        val fTex = Sym.latex(f, v)
        if (differs(fTex, Tex.expr(e.body))) {
            steps += Step("Simplify the function first", "f($v) = $fTex")
        }

        var current = f
        for (k in 1..e.order) {
            // Full detail for the first derivative; just the main rule for later ones.
            val diff = Differentiator(v, if (k == 1) 3 else 1)
            val next = diff.d(current)
            verifyDerivative(current, next, v)
            if (e.order > 1) {
                steps += Step("${ordinal(k)} derivative: differentiate ${if (k == 1) "\\(f\\)" else "again"}",
                    "${primeName(k - 1, v)} = ${Sym.latex(current, v)}")
            }
            steps += diff.steps
            val nextTex = Sym.latex(next, v)
            if (current is S.Sum || diff.steps.isEmpty() || e.order > 1) {
                steps += Step(
                    if (e.order > 1) "So the ${ordinal(k)} derivative is" else if (current is S.Sum) "Add the results" else "Result",
                    if (e.order > 1) "${primeName(k, v)} = $nextTex" else "\\frac{d}{d$v}\\left[$fTex\\right] = $nextTex"
                )
            }
            current = next
        }

        val resultTex = Sym.latex(current, v)
        val graph = Graphs.build(listOf(
            Graphs.curve(f, v, "f($v) = $fTex"),
            Graphs.curve(current, v, "${primeName(e.order, v)} = $resultTex")
        ))
        val kind = (if (e.order == 1) "Derivative" else "${ordinal(e.order)} derivative") +
            (if (others.isEmpty()) "" else " (partial)") + " with respect to \\($v\\)"
        return Solution(kind, steps, resultTex, graph = graph)
    }

    /** f(x), f'(x), f''(x), f'''(x), then f^{(4)}(x) and up. */
    private fun primeName(k: Int, v: Char) = when (k) {
        0 -> "f($v)"
        1, 2, 3 -> "f${"'".repeat(k)}($v)"
        else -> "f^{($k)}($v)"
    }

    fun ordinal(k: Int) = when {
        k % 100 in 11..13 -> "${k}th"
        k % 10 == 1 -> "${k}st"
        k % 10 == 2 -> "${k}nd"
        k % 10 == 3 -> "${k}rd"
        else -> "${k}th"
    }

    // ---------------- integrals ----------------

    fun integral(e: Expr.Integral): Solution {
        val f = Sym.from(e.body)
        val v = e.variable ?: inferVariable(Sym.variables(f))
        val start = Tex.expr(Expr.Integral(e.body, v, e.lower, e.upper))
        val steps = mutableListOf(Step("Start with", start))
        val fTex = Sym.latex(f, v)
        if (differs(fTex, Tex.expr(e.body))) {
            steps += Step("Simplify the integrand first", fTex)
        }

        val integrator = Integrator(v)
        val antiderivative = integrator.integrate(f) ?: throw MathError(
            "This integral isn't supported yet. LocalMath can do powers and roots, e^x, sin, cos, tan, ln, " +
                "u-substitution and simple integration by parts."
        )
        verifyDerivative(antiderivative, f, v, what = "integral")
        steps += integrator.steps
        val fTexAnti = Sym.latex(antiderivative, v)

        if (e.lower == null || e.upper == null) {
            steps += Step(
                if (f is S.Sum) "Add the results and the constant of integration" else "Add the constant of integration",
                "\\int ${bracket(f, v)}\\,d$v = $fTexAnti + C"
            )
            val graph = Graphs.build(listOf(
                Graphs.curve(f, v, "f($v) = $fTex"),
                Graphs.curve(antiderivative, v, "F($v) = $fTexAnti \\;(C = 0)")
            ))
            return Solution("Indefinite integral", steps, "$fTexAnti + C", graph = graph)
        }

        val a = Sym.from(e.lower)
        val b = Sym.from(e.upper)
        if (Sym.variables(a).isNotEmpty() || Sym.variables(b).isNotEmpty()) {
            throw MathError("The limits of a definite integral must be numbers")
        }
        val ad = Sym.eval(a, emptyMap())
        val bd = Sym.eval(b, emptyMap())
        if (!ad.isFinite() || !bd.isFinite()) throw MathError("The limits must be real numbers")
        checkNoSingularity(f, v, ad, bd)

        val fb = Sym.substitute(antiderivative, v, b)
        val fa = Sym.substitute(antiderivative, v, a)
        val value = Sym.sub(fb, fa)
        val valueD = Sym.eval(value, emptyMap())
        val numeric = simpson(f, v, ad, bd)
        if (!valueD.isFinite() || Math.abs(valueD - numeric) > 1e-6 * Math.max(1.0, Math.abs(numeric))) {
            throw MathError("Couldn't evaluate this definite integral reliably (it may be improper)")
        }

        steps += Step("Evaluate between the limits (Fundamental Theorem of Calculus)",
            "\\Big[$fTexAnti\\Big]_{${Sym.latex(a)}}^{${Sym.latex(b)}} = \\left(${Sym.latex(fb)}\\right) - \\left(${Sym.latex(fa)}\\right) = ${Sym.latex(value)}")
        val approx = if (value is S.Num && value.v.isInteger) null else "\\approx ${Tex.decimal(valueD)}"
        val graph = Graphs.build(listOf(Graphs.curve(f, v, "y = $fTex")), shade = ad to bd)
        return Solution("Definite integral", steps, Sym.latex(value), approx, graph)
    }

    /** False when the two only differ by spacing or the order of factors (nothing worth a step). */
    private fun differs(a: String, b: String): Boolean {
        fun norm(s: String) = s.replace(" ", "").replace("\\left", "").replace("\\right", "").toList().sorted()
        return norm(a) != norm(b)
    }

    private fun bracket(s: S, v: Char) = if (s is S.Sum) "\\left(${Sym.latex(s, v)}\\right)" else Sym.latex(s, v)

    private fun inferVariable(vars: Set<Char>): Char = when {
        vars.isEmpty() -> 'x'
        vars.size == 1 -> vars.first()
        'x' in vars -> 'x'
        else -> throw MathError("Add dx (or dt, du …) after the integral to say which variable to integrate")
    }

    private fun mainVar(vars: Set<Char>) = if ('x' in vars || vars.isEmpty()) 'x' else vars.min()

    // ---------------- numeric self-checks ----------------

    private fun env(vars: Set<Char>, v: Char, x: Double): Map<Char, Double> =
        vars.sorted().mapIndexed { i, c -> c to (if (c == v) x else 0.7 + 0.4 * i) }.toMap() + (v to x)

    /** Checks d/dv [f] ≈ g at several points using a central difference. */
    private fun verifyDerivative(f: S, g: S, v: Char, what: String = "derivative") {
        val vars = Sym.variables(f) + Sym.variables(g) + v
        var checked = 0
        for (x in SAMPLE_POINTS) {
            val h = 1e-5 * Math.max(1.0, Math.abs(x))
            val fp = Sym.eval(f, env(vars, v, x + h))
            val fm = Sym.eval(f, env(vars, v, x - h))
            val gx = Sym.eval(g, env(vars, v, x))
            if (!fp.isFinite() || !fm.isFinite() || !gx.isFinite() || Math.abs(gx) > 1e6) continue
            val numeric = (fp - fm) / (2 * h)
            if (Math.abs(numeric - gx) > 1e-4 * Math.max(1.0, Math.abs(gx))) {
                throw MathError("Sorry, LocalMath couldn't verify this $what, so it won't show a possibly wrong answer.")
            }
            checked++
        }
    }

    private fun checkNoSingularity(f: S, v: Char, a: Double, b: Double) {
        val n = 400
        for (i in 0..n) {
            val x = a + (b - a) * i / n
            if (!Sym.eval(f, env(Sym.variables(f), v, x)).isFinite()) {
                throw MathError("The function isn't defined everywhere between the limits (improper integrals aren't supported yet)")
            }
        }
    }

    private fun simpson(f: S, v: Char, a: Double, b: Double, n: Int = 2000): Double {
        val h = (b - a) / n
        val vars = Sym.variables(f)
        fun y(x: Double) = Sym.eval(f, env(vars, v, x))
        var sum = y(a) + y(b)
        for (i in 1 until n) sum += y(a + i * h) * (if (i % 2 == 1) 4 else 2)
        return sum * h / 3
    }
}
