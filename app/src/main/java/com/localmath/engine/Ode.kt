package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.depends
import com.localmath.engine.Sym.mul
import com.localmath.engine.Sym.neg
import com.localmath.engine.Sym.pow

/** Ordinary differential equations in y(x) with y', y''. */
object Ode {

    private const val Y = 'y'
    private val C = S.Var('C')
    private val C1 = S.Var(Sym.C1)
    private val C2 = S.Var(Sym.C2)

    private class Condition(val order: Int, val at: S, val value: S)

    fun solve(equations: List<Input.Equation>): Solution {
        val main = equations.filter { e -> e.left.has { it is Expr.Prime } || e.right.has { it is Expr.Prime } }
        if (main.size != 1) throw MathError("Enter one differential equation (with y'), plus starting values like y(0) = 1")
        val eq = main[0]
        val conditions = (equations - eq).map { c ->
            val call = c.left as? Expr.Call ?: throw MathError("Starting values look like y(0) = 1 or y'(0) = 2")
            if (call.name != Y) throw MathError("Use y for the unknown function")
            val at = Sym.from(call.arg)
            val value = Sym.from(c.right)
            if (Sym.variables(at).isNotEmpty() || Sym.variables(value).isNotEmpty()) throw MathError("Starting values must be numbers")
            Condition(call.order, at, value)
        }

        // y' -> placeholder variables so the symbolic engine can work with them.
        var order = 0
        fun convert(e: Expr): S = Sym.from(e.mapNodes { node ->
            when (node) {
                is Expr.Prime -> {
                    if (node.name != Y) throw MathError("Use y for the unknown function (y', y'')")
                    if (node.order > 2) throw MathError("Only first- and second-order equations are supported")
                    order = Math.max(order, node.order)
                    Expr.Var(if (node.order == 1) Sym.Y1 else Sym.Y2)
                }
                is Expr.Call -> throw MathError("Write starting values separately, after a ;")
                else -> null
            }
        })
        val lhs = convert(eq.left)
        val rhs = convert(eq.right)
        val d = Sym.sub(lhs, rhs)                     // d(x, y, y', y'') = 0
        val others = Sym.variables(d) - setOf(Y, Sym.Y1, Sym.Y2)
        if (others.size > 1) throw MathError("Use one independent variable, like x")
        val x = others.firstOrNull() ?: 'x'

        val steps = mutableListOf(Step("Start with", equations.joinToString(",\\quad ") { Tex.equation(it.left, it.right) }))
        val general = if (order == 2) secondOrder(d, x, steps) else firstOrder(d, x, steps)
        verify(d, general, x)
        return finish(general, x, conditions, order, steps)
    }

    // ---------------- first order ----------------

    private sealed class Sol {
        /** y = expression (with C, or C₁ and C₂). */
        data class Explicit(val y: S) : Sol()
        /** G(y) = H(x) + C when y can't be isolated. */
        data class Implicit(val g: S, val h: S) : Sol()
    }

    private fun firstOrder(d: S, x: Char, steps: MutableList<Step>): Sol {
        val a = Differentiator(Sym.Y1, -1).d(d)                // coefficient of y'
        if (depends(a, Sym.Y1) || a == Sym.ZERO) throw MathError("LocalMath solves equations where y' appears to the first power")
        val rest = Sym.substitute(d, Sym.Y1, Sym.ZERO)
        val f = Sym.div(neg(rest), a)                          // y' = f(x, y)
        val yp = "y'"
        if (d != Sym.sub(S.Var(Sym.Y1), f)) steps += Step("Solve for \\($yp\\)", "$yp = ${Sym.latex(f, x)}")

        // y' = f(x): just integrate.
        if (!depends(f, Y)) {
            val integ = Integrator(x, 1)
            val F = integ.integrate(f) ?: throw MathError("Couldn't integrate the right side")
            steps += Step("Integrate both sides", "y = \\int ${bracket(f, x)}\\,d$x = ${Sym.latex(F, x)} + C")
            steps += integ.steps.map { Step("  " + it.title, it.math, it.note) }
            return Sol.Explicit(add(F, C))
        }

        // Linear: y' = P(x)·y + Q(x).
        val P = Differentiator(Y, -1).d(f)
        val Q = Sym.substitute(f, Y, Sym.ZERO)
        if (!depends(P, Y) && !depends(Q, Y) && Q != Sym.ZERO) return linear(neg(P), Q, x, steps)

        // Separable: y' = g(x)·h(y).  e^{x − y} counts too: it's e^{x}·e^{−y}.
        val factors = (if (f is S.Prod) f.factors else listOf(f)).flatMap { t ->
            if (t is S.Pow && !depends(t.base, x) && !depends(t.base, Y) && t.exp is S.Sum) (t.exp as S.Sum).terms.map { pow(t.base, it) }
            else listOf(t)
        }
        if (factors.all { !depends(it, x) || !depends(it, Y) }) {
            val gx = mul(factors.filter { !depends(it, Y) })
            val hy = mul(factors.filter { depends(it, Y) })
            return separable(gx, hy, x, steps)
        }
        throw MathError("LocalMath can solve y' = f(x), separable equations and linear first-order equations. This one isn't in those forms.")
    }

    private fun bracket(s: S, x: Char) = if (s is S.Sum) "\\left(${Sym.latex(s, x)}\\right)" else Sym.latex(s, x)

    /** ln|u| -> ln u (taking the positive branch, as textbooks do for integrating factors). */
    private fun dropAbs(s: S): S = when (s) {
        is S.Func -> if (s.name == "ln" && s.arg is S.Func && (s.arg as S.Func).name == "abs") Sym.func("ln", (s.arg as S.Func).arg)
            else Sym.func(s.name, dropAbs(s.arg))
        is S.Sum -> add(s.terms.map { dropAbs(it) })
        is S.Prod -> mul(s.factors.map { dropAbs(it) })
        is S.Pow -> pow(dropAbs(s.base), dropAbs(s.exp))
        else -> s
    }

    /** y' + p(x)·y = q(x) with an integrating factor. */
    private fun linear(p: S, q: S, x: Char, steps: MutableList<Step>): Sol {
        steps += Step("It's linear: write it as \\(y' + p($x)\\,y = q($x)\\)",
            "y' " + (if (p is S.Num) (term((p as S.Num).v, "y") ?: "").let { if (it.startsWith("-")) "- " + it.drop(1) else "+ $it" }
                else "+ ${bracket(p, x)}\\,y") + " = ${Sym.latex(q, x)}",
            "\\(p($x) = ${Sym.latex(p, x)}\\), \\(q($x) = ${Sym.latex(q, x)}\\)")
        val ip = Integrator(x, -1).integrate(p) ?: throw MathError("Couldn't integrate p(x)")
        val mu = pow(Sym.E, dropAbs(ip))
        val muRaw = "e^{${Sym.latex(dropAbs(ip), x)}}"
        val muTex = Sym.latex(mu, x)
        steps += Step("Integrating factor \\(\\mu = e^{\\int p\\,d$x}\\)", "\\mu = $muRaw" + if (muRaw != muTex) " = $muTex" else "",
            if (ip != dropAbs(ip)) "Taking \\($x > 0\\) so \\(\\ln|$x| = \\ln $x\\)." else null)
        val muQ = mul(mu, q)
        steps += Step("Multiply through by \\(\\mu\\): the left side becomes \\((\\mu y)'\\)",
            "\\left(${Sym.latex(mu, x)}\\,y\\right)' = ${Sym.latex(muQ, x)}")
        val integral = Integrator(x, -1).integrate(muQ) ?: throw MathError("LocalMath couldn't do the integral this equation needs (μ·q). Try a simpler right side.")
        steps += Step("Integrate both sides", "${Sym.latex(mu, x)}\\,y = ${Sym.latex(integral, x)} + C")
        val y = Sym.div(add(integral, C), mu)
        steps += Step("Divide by \\(\\mu\\)", "y = ${Sym.latex(y, x)}")
        return Sol.Explicit(y)
    }

    /** dy/dx = g(x)·h(y)  ->  ∫ dy/h(y) = ∫ g(x) dx. */
    private fun separable(g: S, h: S, x: Char, steps: MutableList<Step>): Sol {
        val invH = pow(h, Sym.MINUS_ONE)
        steps += Step("Separate the variables", "\\frac{dy}{${Sym.latex(h, x)}} = ${bracket(g, x)}\\,d$x",
            "Everything with \\(y\\) on one side, everything with \\($x\\) on the other.")
        val G = Integrator(Y, -1).integrate(invH) ?: throw MathError("Couldn't integrate the y side")
        val H = Integrator(x, -1).integrate(g) ?: throw MathError("Couldn't integrate the $x side")
        steps += Step("Integrate both sides", "${Sym.latex(G, Y)} = ${Sym.latex(H, x)} + C")

        // Try to get y on its own.
        val (c, core) = Sym.splitCoef(G)
        val cS = S.Num(c)
        if (core is S.Func && core.name == "ln" && core.arg is S.Func && (core.arg as S.Func).name == "abs") {
            val inner = (core.arg as S.Func).arg
            val slope = Differentiator(Y, -1).d(inner)
            if (!depends(slope, Y) && slope != Sym.ZERO) {
                val shift = Sym.substitute(inner, Y, Sym.ZERO)
                val y = Sym.div(add(mul(C, pow(Sym.E, dropAbs(Sym.div(H, cS)))), neg(shift)), slope)
                steps += Step("Undo the logarithm", "y = ${Sym.latex(y, x)}",
                    "\\(C\\) now stands for \\(\\pm e^{C}\\), which is still just some constant.")
                return Sol.Explicit(y)
            }
        }
        val dG = Differentiator(Y, -1).d(G)
        if (!depends(dG, Y)) {
            val y = Sym.div(add(H, C, neg(Sym.substitute(G, Y, Sym.ZERO))), dG)
            steps += Step("Solve for \\(y\\)", "y = ${Sym.latex(y, x)}")
            return Sol.Explicit(y)
        }
        if (core is S.Pow && core.base == Sym.E && core.exp == S.Var(Y)) {
            val y = Sym.func("ln", Sym.div(add(H, C), cS))
            steps += Step("Take logarithms", "y = ${Sym.latex(y, x)}")
            return Sol.Explicit(y)
        }
        if (core is S.Pow && core.base == S.Var(Y) && core.exp is S.Num) {
            val y = pow(Sym.div(add(H, C), cS), pow(core.exp, Sym.MINUS_ONE))
            steps += Step("Solve for \\(y\\)", "y = ${Sym.latex(y, x)}",
                if ((core.exp as S.Num).v.den.toInt() == 1 && (core.exp as S.Num).v.num.toInt() % 2 == 0) "Taking the positive root; the negative root also works." else null)
            return Sol.Explicit(y)
        }
        steps += Step("Leave the answer in implicit form", "${Sym.latex(G, Y)} = ${Sym.latex(H, x)} + C",
            "It isn't possible to write y on its own neatly here.")
        return Sol.Implicit(G, H)
    }

    // ---------------- second order ----------------

    private fun secondOrder(d: S, x: Char, steps: MutableList<Step>): Sol {
        val a = Differentiator(Sym.Y2, -1).d(d)
        val b = Differentiator(Sym.Y1, -1).d(d)
        val c = Differentiator(Y, -1).d(d)
        val rest = Sym.substitute(Sym.substitute(Sym.substitute(d, Sym.Y2, Sym.ZERO), Sym.Y1, Sym.ZERO), Y, Sym.ZERO)
        if (listOf(a, b, c).any { it !is S.Num }) {
            throw MathError("LocalMath solves second-order equations of the form a·y'' + b·y' + c·y = k with constant a, b, c")
        }
        if (Sym.variables(rest).isNotEmpty()) {
            throw MathError("For second-order equations the right side must be a constant for now (like y'' + y = 3)")
        }
        val av = (a as S.Num).v
        val bv = (b as S.Num).v
        val cv = (c as S.Num).v
        val k = neg(rest)                                  // a y'' + b y' + c y = k
        val homogeneous = k == Sym.ZERO
        val lhsTex = listOfNotNull(term(av, "y''"), term(bv, "y'"), term(cv, "y")).joinToString(" + ").replace("+ -", "- ")
        steps += Step("It's linear with constant coefficients", "$lhsTex = ${Sym.latex(k)}",
            if (homogeneous) null else "Solve the version with 0 on the right first, then add one particular solution.")

        // Characteristic equation.
        val charPoly = Polynomial(mapOf(2 to av, 1 to bv, 0 to cv))
        steps += Step("Try \\(y = e^{r$x}\\): the characteristic equation", "${charPoly.format('r')} = 0")
        val disc = bv * bv - Rational.of(4) * av * cv
        val center = S.Num(-bv / (Rational.of(2) * av))
        val spread = mul(pow(S.Num(disc.abs()), Sym.HALF), S.Num(Rational.ONE / (Rational.of(2) * av)))
        val X = S.Var(x)
        val yh: S = when {
            disc.sign > 0 -> {
                val r1 = add(center, spread)
                val r2 = add(center, neg(spread))
                steps += Step("Two real roots", "r_1 = ${Sym.latex(r1)},\\quad r_2 = ${Sym.latex(r2)}",
                    "So \\(y = C_1 e^{r_1 $x} + C_2 e^{r_2 $x}\\).")
                add(mul(C1, pow(Sym.E, mul(r1, X))), mul(C2, pow(Sym.E, mul(r2, X))))
            }
            disc.isZero -> {
                steps += Step("One repeated root", "r = ${Sym.latex(center)}", "So \\(y = (C_1 + C_2 $x)\\,e^{r$x}\\).")
                mul(add(C1, mul(C2, X)), pow(Sym.E, mul(center, X)))
            }
            else -> {
                steps += Step("Complex roots \\(r = \\alpha \\pm \\beta i\\)", "\\alpha = ${Sym.latex(center)},\\quad \\beta = ${Sym.latex(spread)}",
                    "So \\(y = e^{\\alpha $x}\\left(C_1\\cos \\beta $x + C_2 \\sin \\beta $x\\right)\\).")
                mul(pow(Sym.E, mul(center, X)),
                    add(mul(C1, Sym.func("cos", mul(spread, X))), mul(C2, Sym.func("sin", mul(spread, X)))))
            }
        }
        steps += Step(if (homogeneous) "General solution" else "Solution of the version with 0 on the right", "y_h = ${Sym.latex(yh, x)}")
        if (homogeneous) return Sol.Explicit(yh)
        if (cv.isZero) throw MathError("For y'' + b·y' = k LocalMath needs a y term too (for now)")
        val yp = Sym.div(k, c)
        steps += Step("A constant particular solution", "y_p = \\frac{${Sym.latex(k)}}{${Tex.rational(cv)}} = ${Sym.latex(yp)}",
            "A constant has \\(y' = y'' = 0\\), so only \\(${Tex.rational(cv)}\\,y = ${Sym.latex(k)}\\) is left.")
        val y = add(yh, yp)
        steps += Step("General solution \\(y = y_h + y_p\\)", "y = ${Sym.latex(y, x)}")
        return Sol.Explicit(y)
    }

    private fun term(c: Rational, what: String) = when {
        c.isZero -> null
        c.isOne -> what
        c == Rational.MINUS_ONE -> "-$what"
        else -> Tex.rational(c) + what
    }

    // ---------------- checks and starting values ----------------

    /** Plugs the general solution back into the equation at a few points. */
    private fun verify(d: S, sol: Sol, x: Char) {
        if (sol !is Sol.Explicit) return
        val y = sol.y
        val y1 = Differentiator(x, -1).d(y)
        val y2 = Differentiator(x, -1).d(y1)
        val constants = mapOf('C' to 0.8, Sym.C1 to 0.7, Sym.C2 to -0.4)
        var checked = 0
        for (xv in listOf(0.35, 0.8, 1.3, 1.9, 2.6)) {
            val env = constants + (x to xv)
            val yv = Sym.eval(y, env)
            val y1v = Sym.eval(y1, env)
            val y2v = Sym.eval(y2, env)
            val r = Sym.eval(d, env + mapOf(Y to yv, Sym.Y1 to y1v, Sym.Y2 to y2v))
            if (!r.isFinite() || !yv.isFinite()) continue
            if (Math.abs(r) > 1e-6 * Math.max(1.0, Math.abs(y1v) + Math.abs(yv) + Math.abs(y2v))) {
                throw MathError("Sorry, LocalMath couldn't verify this solution, so it won't show a possibly wrong answer.")
            }
            checked++
        }
    }

    private fun finish(sol: Sol, x: Char, conditions: List<Condition>, order: Int, steps: MutableList<Step>): Solution {
        val kind = if (order == 2) "Second-order differential equation" else "First-order differential equation"
        if (conditions.isEmpty()) {
            return when (sol) {
                is Sol.Explicit -> Solution(kind, steps, "y = ${Sym.latex(sol.y, x)}", null, family(sol.y, x, order))
                is Sol.Implicit -> Solution(kind, steps, "${Sym.latex(sol.g, Y)} = ${Sym.latex(sol.h, x)} + C")
            }
        }
        if (conditions.size != order) throw MathError("A ${if (order == 1) "first" else "second"}-order equation needs $order starting value${if (order > 1) "s (y and y')" else ""}")

        val particular: S = when (sol) {
            is Sol.Implicit -> {
                val cond = conditions.single()
                val cv = add(Sym.substitute(sol.g, Y, cond.value), neg(Sym.substitute(sol.h, x, cond.at)))
                steps += Step("Use \\(y(${Sym.latex(cond.at)}) = ${Sym.latex(cond.value)}\\) to find \\(C\\)", "C = ${Sym.latex(cv)}")
                return Solution(kind, steps, "${Sym.latex(sol.g, Y)} = ${Sym.latex(add(sol.h, cv), x)}")
            }
            is Sol.Explicit -> if (order == 1) {
                val cond = conditions.single()
                if (cond.order != 0) throw MathError("For a first-order equation give y at a point, like y(0) = 1")
                val e = add(Sym.substitute(sol.y, x, cond.at), neg(cond.value))
                val slope = Differentiator('C', -1).d(e)
                val cValue = if (!depends(slope, 'C') && slope != Sym.ZERO) Sym.div(neg(Sym.substitute(e, 'C', Sym.ZERO)), slope)
                else {
                    val rf = RatFunc.fromS(e, 'C') ?: throw MathError("Couldn't solve for C from the starting value")
                    val out = PolyEquation('C', mutableListOf()).solve(rf.num, Polynomial.constant(Rational.ZERO))
                    val root = (out as? Outcome.Roots)?.roots?.firstOrNull { it.exact != null && !rf.den.evaluate(it.exact).isZero }?.exact
                        ?: throw MathError("Couldn't solve for C from the starting value")
                    S.Num(root)
                }
                steps += Step("Use \\(y(${Sym.latex(cond.at)}) = ${Sym.latex(cond.value)}\\) to find \\(C\\)",
                    "${Sym.latex(Sym.substitute(sol.y, x, cond.at))} = ${Sym.latex(cond.value)} \\quad\\Rightarrow\\quad C = ${Sym.latex(cValue)}")
                Sym.substitute(sol.y, 'C', cValue)
            } else {
                val y0 = conditions.firstOrNull { it.order == 0 } ?: throw MathError("Give y(…) and y'(…) as starting values")
                val y1 = conditions.firstOrNull { it.order == 1 } ?: throw MathError("Give y(…) and y'(…) as starting values")
                val dy = Differentiator(x, -1).d(sol.y)
                // Both conditions are linear in C₁, C₂.
                fun row(expr: S, at: S, value: S): Triple<S, S, S> {
                    val e = add(Sym.substitute(expr, x, at), neg(value))
                    val a = Differentiator(Sym.C1, -1).d(e)
                    val b = Differentiator(Sym.C2, -1).d(e)
                    val c0 = neg(Sym.substitute(Sym.substitute(e, Sym.C1, Sym.ZERO), Sym.C2, Sym.ZERO))
                    return Triple(a, b, c0)
                }
                val (a1, b1, e1) = row(sol.y, y0.at, y0.value)
                val (a2, b2, e2) = row(dy, y1.at, y1.value)
                val det = add(mul(a1, b2), neg(mul(b1, a2)))
                if (Math.abs(Sym.eval(det, emptyMap())) < 1e-12) throw MathError("These starting values don't pin down C₁ and C₂")
                val c1 = Sym.div(add(mul(e1, b2), neg(mul(b1, e2))), det)
                val c2 = Sym.div(add(mul(a1, e2), neg(mul(e1, a2))), det)
                steps += Step("Use the starting values", "y(${Sym.latex(y0.at)}) = ${Sym.latex(y0.value)},\\quad y'(${Sym.latex(y1.at)}) = ${Sym.latex(y1.value)}",
                    "They give two equations for \\(C_1\\) and \\(C_2\\): \\(C_1 = ${Sym.latex(c1)}\\), \\(C_2 = ${Sym.latex(c2)}\\).")
                Sym.substitute(Sym.substitute(sol.y, Sym.C1, c1), Sym.C2, c2)
            }
        }
        steps += Step("Particular solution", "y = ${Sym.latex(particular, x)}")
        val first = conditions.first { it.order == 0 }
        val px = Sym.eval(first.at, emptyMap())
        val py = Sym.eval(first.value, emptyMap())
        val graph = Graphs.build(listOf(Graphs.curve(particular, x, "y = ${Sym.latex(particular, x)}")),
            listOf(GraphPoint(px, py, Graphs.label(px, py))), focus = listOf(px))
        return Solution(kind, steps, "y = ${Sym.latex(particular, x)}", null, graph)
    }

    /** A few members of the family of solutions. */
    private fun family(y: S, x: Char, order: Int): Graph? {
        val choices = if (order == 1) listOf(-2L, -1L, 1L, 2L).map { mapOf('C' to Sym.num(it)) to "C = $it" }
        else listOf(mapOf(Sym.C1 to Sym.ONE, Sym.C2 to Sym.ZERO) to "C_1 = 1,\\ C_2 = 0", mapOf(Sym.C1 to Sym.ZERO, Sym.C2 to Sym.ONE) to "C_1 = 0,\\ C_2 = 1")
        val curves = choices.map { (vals, label) ->
            var s = y
            for ((c, v) in vals) s = Sym.substitute(s, c, v)
            Graphs.curve(s, x, label)
        }
        return Graphs.build(curves, focus = listOf(-3.0, 3.0))
    }
}
