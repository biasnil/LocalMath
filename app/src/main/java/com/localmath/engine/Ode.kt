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

    /** No exact method fits; with starting values the equation is solved numerically instead. */
    private class NoMethod(message: String) : MathError(message)

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
        val before = steps.size
        val general = try {
            val g = if (order == 2) secondOrder(d, x, steps) else firstOrder(d, x, steps)
            verify(d, g, x)
            g
        } catch (e: MathError) {
            if (conditions.isEmpty()) {
                if (e is NoMethod) throw MathError(e.message + " Add starting values (like y(0) = 1" +
                    (if (order == 2) "; y'(0) = 0" else "") + ") and LocalMath will solve it numerically.")
                throw e
            }
            steps.subList(before, steps.size).clear()
            return numeric(d, x, order, conditions, steps, e.message)
        }
        return finish(general, x, conditions, order, steps)
    }

    // ---------------- first order ----------------

    private sealed class Sol {
        /** y = expression (with C, or C₁ and C₂). */
        data class Explicit(val y: S) : Sol()
        /** G(x, y) = H(x) + C when y can't be isolated. */
        data class Implicit(val g: S, val h: S) : Sol()
    }

    private fun firstOrder(d: S, x: Char, steps: MutableList<Step>): Sol {
        val a = Differentiator(Sym.Y1, -1).d(d)                // coefficient of y'
        if (depends(a, Sym.Y1) || a == Sym.ZERO) throw MathError("LocalMath solves equations where y' appears to the first power")
        val rest = Sym.substitute(d, Sym.Y1, Sym.ZERO)
        val f = Sym.div(neg(rest), a)                          // y' = f(x, y)
        val yp = "y'"
        val solvedStep = Step("Solve for \\($yp\\)", "$yp = ${Sym.latex(f, x)}")
        if (d != Sym.sub(S.Var(Sym.Y1), f)) steps += solvedStep

        // y' = f(x): just integrate.
        if (!depends(f, Y)) {
            val integ = Integrator(x, 1)
            val F = integ.integrate(f) ?: throw NoMethod("Couldn't integrate the right side.")
            steps += Step("Integrate both sides", "y = \\int ${bracket(f, x)}\\,d$x = ${Sym.latex(F, x)} + C")
            steps += integ.steps.map { Step("  " + it.title, it.math, it.note) }
            return Sol.Explicit(add(F, C))
        }

        // Linear: y' = P(x)·y + Q(x).
        val P = Differentiator(Y, -1).d(f)
        val Q = try { Sym.substitute(f, Y, Sym.ZERO) } catch (_: MathError) { null }   // y in a denominator: not linear
        if (Q != null && !depends(P, Y) && !depends(Q, Y) && Q != Sym.ZERO) return linear(neg(P), Q, x, steps)

        // Separable: y' = g(x)·h(y).  e^{x − y} counts too: it's e^{x}·e^{−y}.
        val factors = (if (f is S.Prod) f.factors else listOf(f)).flatMap { t ->
            if (t is S.Pow && !depends(t.base, x) && !depends(t.base, Y) && t.exp is S.Sum) (t.exp as S.Sum).terms.map { pow(t.base, it) }
            else listOf(t)
        }
        if (factors.all { !depends(it, x) || !depends(it, Y) }) {
            val gx = mul(factors.filter { !depends(it, Y) })
            val hy = mul(factors.filter { depends(it, Y) })
            try {
                return separable(gx, hy, x, steps)
            } catch (e: MathError) {
                // Separable but an integral failed; one of the other methods may still work.
            }
        }
        val n0 = steps.size
        bernoulli(f, x, steps)?.let { return it }
        homogeneous(f, x, steps)?.let { return it }
        exact(d, x, steps)?.let { return it }
        steps.subList(n0, steps.size).clear()
        throw NoMethod("There's no exact method for this one: it isn't y' = f(x), separable, linear, Bernoulli, homogeneous (a function of y/x) or exact.")
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

    /** u' + p(x)·u = q(x) with an integrating factor ([u] is y, or v after a substitution). */
    private fun linear(p: S, q: S, x: Char, steps: MutableList<Step>, u: String = "y"): Sol.Explicit {
        steps += Step("It's linear: write it as \\($u' + p($x)\\,$u = q($x)\\)",
            joinSigned(listOf("$u'", if (p == Sym.ZERO) "" else coefTerm(p, u, x))) + " = ${Sym.latex(q, x)}",
            "\\(p($x) = ${Sym.latex(p, x)}\\), \\(q($x) = ${Sym.latex(q, x)}\\)")
        val ip = Integrator(x, -1).integrate(p) ?: throw NoMethod("Couldn't integrate p(x).")
        val mu = pow(Sym.E, dropAbs(ip))
        val muRaw = "e^{${Sym.latex(dropAbs(ip), x)}}"
        val muTex = Sym.latex(mu, x)
        steps += Step("Integrating factor \\(\\mu = e^{\\int p\\,d$x}\\)", "\\mu = $muRaw" + if (muRaw != muTex) " = $muTex" else "",
            if (ip != dropAbs(ip)) "Taking \\($x > 0\\) so \\(\\ln|$x| = \\ln $x\\)." else null)
        val muQ = mul(mu, q)
        steps += Step("Multiply through by \\(\\mu\\): the left side becomes \\((\\mu $u)'\\)",
            "\\left(${Sym.latex(mu, x)}\\,$u\\right)' = ${Sym.latex(muQ, x)}")
        val integral = Integrator(x, -1).integrate(muQ) ?: throw NoMethod("LocalMath couldn't do the integral this equation needs (μ·q).")
        steps += Step("Integrate both sides", "${Sym.latex(mu, x)}\\,$u = ${Sym.latex(integral, x)} + C")
        val y = Sym.div(add(integral, C), mu)
        steps += Step("Divide by \\(\\mu\\)", "$u = ${Sym.latex(y, x)}")
        return Sol.Explicit(y)
    }

    /** dy/dx = g(x)·h(y)  ->  ∫ dy/h(y) = ∫ g(x) dx.  [yv] is the dependent letter (y, or v after a substitution). */
    private fun separable(g: S, h: S, x: Char, steps: MutableList<Step>, yv: Char = Y): Sol {
        val invH = pow(h, Sym.MINUS_ONE)
        steps += Step("Separate the variables", "\\frac{d$yv}{${Sym.latex(h, x)}} = ${bracket(g, x)}\\,d$x",
            "Everything with \\($yv\\) on one side, everything with \\($x\\) on the other.")
        val G = Integrator(yv, -1).integrate(invH) ?: throw NoMethod("Couldn't integrate the $yv side.")
        val H = Integrator(x, -1).integrate(g) ?: throw NoMethod("Couldn't integrate the $x side.")
        steps += Step("Integrate both sides", "${Sym.latex(G, yv)} = ${Sym.latex(H, x)} + C")

        // Try to get y on its own.
        val (c, core) = Sym.splitCoef(G)
        val cS = S.Num(c)
        if (core is S.Func && core.name == "ln" && core.arg is S.Func && (core.arg as S.Func).name == "abs") {
            val inner = (core.arg as S.Func).arg
            val slope = Differentiator(yv, -1).d(inner)
            if (!depends(slope, yv) && slope != Sym.ZERO) {
                val shift = Sym.substitute(inner, yv, Sym.ZERO)
                val y = Sym.div(add(mul(C, pow(Sym.E, dropAbs(Sym.div(H, cS)))), neg(shift)), slope)
                steps += Step("Undo the logarithm", "$yv = ${Sym.latex(y, x)}",
                    "\\(C\\) now stands for \\(\\pm e^{C}\\), which is still just some constant.")
                return Sol.Explicit(y)
            }
        }
        val dG = Differentiator(yv, -1).d(G)
        if (!depends(dG, yv)) {
            val y = Sym.div(add(H, C, neg(Sym.substitute(G, yv, Sym.ZERO))), dG)
            steps += Step("Solve for \\($yv\\)", "$yv = ${Sym.latex(y, x)}")
            return Sol.Explicit(y)
        }
        if (core is S.Pow && core.base == Sym.E && core.exp == S.Var(yv)) {
            val y = Sym.func("ln", Sym.div(add(H, C), cS))
            steps += Step("Take logarithms", "$yv = ${Sym.latex(y, x)}")
            return Sol.Explicit(y)
        }
        if (core is S.Pow && core.base == S.Var(yv) && core.exp is S.Num) {
            val y = pow(Sym.div(add(H, C), cS), pow(core.exp, Sym.MINUS_ONE))
            steps += Step("Solve for \\($yv\\)", "$yv = ${Sym.latex(y, x)}",
                if ((core.exp as S.Num).v.den.toInt() == 1 && (core.exp as S.Num).v.num.toInt() % 2 == 0) "Taking the positive root; the negative root also works." else null)
            return Sol.Explicit(y)
        }
        steps += Step("Leave the answer in implicit form", "${Sym.latex(G, yv)} = ${Sym.latex(H, x)} + C",
            "It isn't possible to write $yv on its own neatly here.")
        return Sol.Implicit(G, H)
    }

    /** The power k when t = (something without y)·yᵏ, else null. */
    private fun yPower(t: S): Rational? {
        var k = Rational.ZERO
        for (f in if (t is S.Prod) t.factors else listOf(t)) {
            when {
                f == S.Var(Y) -> k += Rational.ONE
                f is S.Pow && f.base == S.Var(Y) && f.exp is S.Num -> k += (f.exp as S.Num).v
                depends(f, Y) -> return null
            }
        }
        return k
    }

    /** y' + p(x)·y = q(x)·yⁿ: substitute v = y^(1−n) to make it linear. */
    private fun bernoulli(f: S, x: Char, steps: MutableList<Step>): Sol? {
        val groups = LinkedHashMap<Rational, MutableList<S>>()
        for (t in if (f is S.Sum) f.terms else listOf(f)) {
            val k = yPower(t) ?: return null
            groups.getOrPut(k) { mutableListOf() } += t
        }
        if (groups.size != 2 || Rational.ONE !in groups) return null
        val n = groups.keys.first { it != Rational.ONE }
        if (n.isZero) return null
        val yS = S.Var(Y)
        val p = neg(Sym.div(add(groups.getValue(Rational.ONE)), yS))
        val q = Sym.div(add(groups.getValue(n)), pow(yS, S.Num(n)))
        if (depends(p, Y) || depends(q, Y)) return null
        val m = Rational.ONE - n
        val nTex = Tex.rational(n)
        val mTex = Tex.rational(m)
        steps += Step("It's a Bernoulli equation \\(y' + p($x)\\,y = q($x)\\,y^{n}\\) with \\(n = $nTex\\)",
            joinSigned(listOf("y'", coefTerm(p, "y", x))) + " = " + coefTerm(q, "y^{$nTex}", x),
            "The \\(y^{$nTex}\\) term stops it being linear, but a substitution fixes that.")
        steps += Step("Substitute \\(v = y^{1 - n} = y^{$mTex}\\)",
            "v' = " + coefTerm(S.Num(m), (if ((-n).isOne) "y" else "y^{${Tex.rational(-n)}}") + "\\,y'", x) + " \\;\\Rightarrow\\; " +
                joinSigned(listOf("v'", coefTerm(mul(S.Num(m), p), "v", x))) + " = ${Sym.latex(mul(S.Num(m), q), x)}",
            "Divide the equation by \\(y^{$nTex}\\) and multiply by \\($mTex\\).")
        val v = linear(mul(S.Num(m), p), mul(S.Num(m), q), x, steps, "v")
        val y = pow(v.y, S.Num(Rational.ONE / m))
        steps += Step("Undo the substitution: \\(y = v^{${Tex.rational(Rational.ONE / m)}}\\)", "y = ${Sym.latex(y, x)}",
            if (m.den.toInt() == 1 && m.num.toInt() % 2 == 0) "Taking the positive root; the negative root also works." else null)
        return Sol.Explicit(y)
    }

    /** Writes a rational function of [v] as one reduced fraction: (1 + v²)/v − v -> 1/v. */
    private fun fraction(s: S, v: Char): S {
        val rf = RatFunc.fromS(s, v) ?: return s
        val (r, _) = rf.reduced()
        fun poly(p: Polynomial): S = add(p.terms.map { (k, c) -> mul(S.Num(c), pow(S.Var(v), Sym.num(k.toLong()))) })
        return Sym.div(poly(r.num), poly(r.den))
    }

    /** y' = F(y/x): substitute y = v·x, which makes it separable. */
    private fun homogeneous(f: S, x: Char, steps: MutableList<Step>): Sol? {
        for ((a, b) in listOf(0.7 to 1.3, 1.9 to 0.4, 2.3 to 3.1)) for (t in listOf(1.7, 3.2)) {
            val v1 = Sym.eval(f, mapOf(x to a, Y to b))
            val v2 = Sym.eval(f, mapOf(x to t * a, Y to t * b))
            if (!v1.isFinite() || !v2.isFinite() || Math.abs(v1 - v2) > 1e-9 * Math.max(1.0, Math.abs(v1))) return null
        }
        val V = if (x == 'v') 'u' else 'v'
        val F = Sym.substitute(Sym.substitute(f, Y, S.Var(V)), x, Sym.ONE)       // F(v) = f(1, v)
        val h = fraction(Sym.sub(F, S.Var(V)), V)
        if (h == Sym.ZERO) return null
        steps += Step("It's homogeneous: \\(y'\\) only depends on \\(\\frac{y}{$x}\\)",
            "y' = F\\left(\\frac{y}{$x}\\right),\\quad F($V) = ${Sym.latex(F, V)}",
            "Scaling \\($x\\) and \\(y\\) by the same factor doesn't change the right side.")
        steps += Step("Substitute \\(y = $V$x\\), so \\(y' = $V + $x\\,$V'\\)",
            "$V + $x\\,$V' = ${Sym.latex(F, V)} \\;\\Rightarrow\\; $x\\,$V' = ${Sym.latex(h, V)}",
            "Now the variables separate.")
        val sol = separable(pow(S.Var(x), Sym.MINUS_ONE), h, x, steps, V)
        val back = Sym.div(S.Var(Y), S.Var(x))
        return when (sol) {
            is Sol.Explicit -> {
                val y = mul(S.Var(x), sol.y)
                steps += Step("Put \\(y = $V$x\\) back", "y = ${Sym.latex(y, x)}")
                Sol.Explicit(y)
            }
            is Sol.Implicit -> {
                val g = Sym.substitute(sol.g, V, back)
                steps += Step("Put \\($V = \\frac{y}{$x}\\) back", "${Sym.latex(g, x)} = ${Sym.latex(sol.h, x)} + C")
                Sol.Implicit(g, sol.h)
            }
        }
    }

    /** M(x, y) + N(x, y)·y' = 0 with ∂M/∂y = ∂N/∂x: the solution is F(x, y) = C. */
    private fun exact(d: S, x: Char, steps: MutableList<Step>): Sol? {
        val N = Differentiator(Sym.Y1, -1).d(d)
        if (depends(N, Sym.Y1)) return null
        val M = Sym.substitute(d, Sym.Y1, Sym.ZERO)
        val My = Differentiator(Y, -1).d(M)
        val Nx = Differentiator(x, -1).d(N)
        val samples = listOf(0.7 to 1.3, 1.9 to 0.4, 2.3 to 3.1, 1.1 to -0.6)
        fun same(a: S, b: S) = samples.all { (xv, yv) ->
            val p = Sym.eval(a, mapOf(x to xv, Y to yv))
            val q = Sym.eval(b, mapOf(x to xv, Y to yv))
            p.isFinite() && q.isFinite() && Math.abs(p - q) <= 1e-9 * Math.max(1.0, Math.abs(p))
        }
        if (!same(My, Nx)) return null
        val Fx = Integrator(x, -1).integrate(M) ?: return null
        val hPrime = Sym.sub(N, Differentiator(Y, -1).d(Fx))
        if (samples.any { (xv, yv) -> Math.abs(Differentiator(x, -1).d(hPrime).let { Sym.eval(it, mapOf(x to xv, Y to yv)) }) > 1e-9 }) return null
        val hy = Sym.substitute(hPrime, x, Sym.ONE).takeIf { !depends(hPrime, x) } ?: return null
        val h = Integrator(Y, -1).integrate(hy) ?: return null
        val F = add(Fx, h)
        if (!same(Differentiator(x, -1).d(F), M) || !same(Differentiator(Y, -1).d(F), N)) return null

        steps += Step("Write it as \\(M\\,d$x + N\\,dy = 0\\)", "M = ${Sym.latex(M, x)},\\quad N = ${Sym.latex(N, x)}")
        steps += Step("Check that it's exact: \\(\\frac{\\partial M}{\\partial y} = \\frac{\\partial N}{\\partial $x}\\)",
            "\\frac{\\partial M}{\\partial y} = ${Sym.latex(My, x)},\\quad \\frac{\\partial N}{\\partial $x} = ${Sym.latex(Nx, x)}",
            "They match, so there is a function \\(F($x, y)\\) with \\(F_$x = M\\) and \\(F_y = N\\).")
        steps += Step("Integrate \\(M\\) with respect to \\($x\\) (treat \\(y\\) as a constant)", "F = ${Sym.latex(Fx, x)} + h(y)")
        steps += Step("Choose \\(h(y)\\) so that \\(F_y = N\\)", "h'(y) = ${Sym.latex(hy, Y)} \\;\\Rightarrow\\; h(y) = ${Sym.latex(h, Y)}")
        steps += Step("The solution is \\(F($x, y) = C\\)", "${Sym.latex(F, x)} = C")
        val slope = Differentiator(Y, -1).d(F)
        if (!depends(slope, Y) && slope != Sym.ZERO) {
            val y = Sym.div(Sym.sub(C, Sym.substitute(F, Y, Sym.ZERO)), slope)
            steps += Step("Solve for \\(y\\)", "y = ${Sym.latex(y, x)}")
            return Sol.Explicit(y)
        }
        return Sol.Implicit(F, Sym.ZERO)
    }

    // ---------------- second order ----------------

    private fun secondOrder(d: S, x: Char, steps: MutableList<Step>): Sol {
        val a = Differentiator(Sym.Y2, -1).d(d)
        val b = Differentiator(Sym.Y1, -1).d(d)
        val c = Differentiator(Y, -1).d(d)
        val rest = Sym.substitute(Sym.substitute(Sym.substitute(d, Sym.Y2, Sym.ZERO), Sym.Y1, Sym.ZERO), Y, Sym.ZERO)
        if (listOf(a, b, c).any { depends(it, Y) || depends(it, Sym.Y1) || depends(it, Sym.Y2) }) {
            throw NoMethod("This second-order equation is nonlinear (y, y' or y'' is squared, multiplied together or inside a function), so there's no general exact method.")
        }
        if (a == Sym.ZERO) throw MathError("The y'' term cancels out")
        if (a is S.Num && b is S.Num && c is S.Num) return constantCoefficients(a.v, b.v, c.v, neg(rest), x, steps)
        euler(a, b, c, rest, x, steps)?.let { return it }
        throw NoMethod("LocalMath solves second-order equations with constant coefficients (a·y'' + b·y' + c·y = f(x)) and Cauchy–Euler equations (a·x²y'' + b·x·y' + c·y = 0) exactly.")
    }

    /** The two basic solutions of a·y'' + b·y' + c·y = 0, and their Wronskian. */
    private class Basis(val y1: S, val y2: S, val wronskian: S)

    private fun constantCoefficients(av: Rational, bv: Rational, cv: Rational, k: S, x: Char, steps: MutableList<Step>): Sol {
        val homogeneous = k == Sym.ZERO
        val lhsTex = listOfNotNull(term(av, "y''"), term(bv, "y'"), term(cv, "y")).joinToString(" + ").replace("+ -", "- ")
        steps += Step("It's linear with constant coefficients", "$lhsTex = ${Sym.latex(k, x)}",
            if (homogeneous) null else "Solve the version with 0 on the right first, then add one particular solution.")

        val charPoly = Polynomial(mapOf(2 to av, 1 to bv, 0 to cv))
        steps += Step("Try \\(y = e^{r$x}\\): the characteristic equation", "${charPoly.format('r')} = 0")
        val disc = bv * bv - Rational.of(4) * av * cv
        val center = S.Num(-bv / (Rational.of(2) * av))
        val spread = mul(pow(S.Num(disc.abs()), Sym.HALF), S.Num(Rational.ONE / (Rational.of(2) * av)))
        val X = S.Var(x)
        val basis: Basis
        val yh: S = when {
            disc.sign > 0 -> {
                val r1 = add(center, spread)
                val r2 = add(center, neg(spread))
                steps += Step("Two real roots", "r_1 = ${Sym.latex(r1)},\\quad r_2 = ${Sym.latex(r2)}",
                    "So \\(y = C_1 e^{r_1 $x} + C_2 e^{r_2 $x}\\).")
                val e1 = pow(Sym.E, mul(r1, X))
                val e2 = pow(Sym.E, mul(r2, X))
                basis = Basis(e1, e2, mul(Sym.sub(r2, r1), pow(Sym.E, mul(add(r1, r2), X))))
                add(mul(C1, e1), mul(C2, e2))
            }
            disc.isZero -> {
                steps += Step("One repeated root", "r = ${Sym.latex(center)}", "So \\(y = (C_1 + C_2 $x)\\,e^{r$x}\\).")
                val e = pow(Sym.E, mul(center, X))
                basis = Basis(e, mul(X, e), pow(Sym.E, mul(Sym.num(2), center, X)))
                mul(add(C1, mul(C2, X)), e)
            }
            else -> {
                steps += Step("Complex roots \\(r = \\alpha \\pm \\beta i\\)", "\\alpha = ${Sym.latex(center)},\\quad \\beta = ${Sym.latex(spread)}",
                    "So \\(y = e^{\\alpha $x}\\left(C_1\\cos \\beta $x + C_2 \\sin \\beta $x\\right)\\).")
                val e = pow(Sym.E, mul(center, X))
                val y1 = mul(e, Sym.func("cos", mul(spread, X)))
                val y2 = mul(e, Sym.func("sin", mul(spread, X)))
                basis = Basis(y1, y2, mul(spread, pow(Sym.E, mul(Sym.num(2), center, X))))
                mul(e, add(mul(C1, Sym.func("cos", mul(spread, X))), mul(C2, Sym.func("sin", mul(spread, X)))))
            }
        }
        steps += Step(if (homogeneous) "General solution" else "Solution of the version with 0 on the right", "y_h = ${Sym.latex(yh, x)}")
        if (homogeneous) return Sol.Explicit(yh)

        val yp = undetermined(av, bv, cv, k, x, steps) ?: variationOfParameters(av, k, x, basis, steps)
        val y = add(yh, yp)
        steps += Step("General solution \\(y = y_h + y_p\\)", "y = ${Sym.latex(y, x)}")
        return Sol.Explicit(y)
    }

    /** Gaussian rational a + bi, for matching coefficients exactly. */
    private data class G(val re: Rational, val im: Rational = Rational.ZERO) {
        operator fun plus(o: G) = G(re + o.re, im + o.im)
        operator fun minus(o: G) = G(re - o.re, im - o.im)
        operator fun times(o: G) = G(re * o.re - im * o.im, re * o.im + im * o.re)
        operator fun div(o: G): G {
            val d = o.re * o.re + o.im * o.im
            return G((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d)
        }
        val isZero get() = re.isZero && im.isZero
    }

    /** One forcing term: poly(x)·e^{αx}·(cos βx or sin βx or 1). */
    private data class Forcing(val poly: Map<Int, Rational>, val alpha: Rational, val beta: Rational, val trig: String?)

    /** k·x when s = k·x exactly (k a number), else null. */
    private fun linearCoef(s: S, x: Char): Rational? {
        val k = Differentiator(x, -1).d(s) as? S.Num ?: return null
        return if (Sym.substitute(s, x, Sym.ZERO) == Sym.ZERO) k.v else null
    }

    private fun forcing(t: S, x: Char): List<Forcing>? {
        var coef = Rational.ONE
        var deg = 0
        var alpha = Rational.ZERO
        var beta = Rational.ZERO
        var trig: String? = null
        var hyper: Pair<String, Rational>? = null
        for (f in if (t is S.Prod) t.factors else listOf(t)) {
            when {
                f is S.Num -> coef *= f.v
                f == S.Var(x) -> deg += 1
                f is S.Pow && f.base == S.Var(x) && f.exp is S.Num && (f.exp as S.Num).v.let { it.isInteger && it.sign > 0 && it.num.toInt() <= 12 } ->
                    deg += (f.exp as S.Num).v.num.toInt()
                f is S.Pow && f.base == Sym.E -> alpha += linearCoef(f.exp, x) ?: return null
                f is S.Func && (f.name == "sin" || f.name == "cos") && trig == null && hyper == null -> {
                    beta = linearCoef(f.arg, x) ?: return null
                    trig = f.name
                }
                f is S.Func && (f.name == "sinh" || f.name == "cosh") && trig == null && hyper == null ->
                    hyper = f.name to (linearCoef(f.arg, x) ?: return null)
                else -> return null
            }
        }
        if (trig != null && beta.sign < 0) {
            beta = -beta
            if (trig == "sin") coef = -coef
        }
        if (trig != null && beta.isZero) {
            if (trig == "sin") return emptyList()
            trig = null
        }
        val poly = mapOf(deg to coef)
        if (hyper != null) {
            // cosh kx = (e^{kx} + e^{−kx})/2,  sinh kx = (e^{kx} − e^{−kx})/2
            val (name, kk) = hyper
            val half = Rational.of(1, 2)
            return listOf(
                Forcing(mapOf(deg to coef * half), alpha + kk, beta, trig),
                Forcing(mapOf(deg to coef * half * (if (name == "sinh") Rational.MINUS_ONE else Rational.ONE)), alpha - kk, beta, trig))
        }
        return listOf(Forcing(poly, alpha, beta, trig))
    }

    /** Undetermined coefficients for right sides made of polynomials, e^{αx}, sin βx and cos βx. */
    private fun undetermined(av: Rational, bv: Rational, cv: Rational, k: S, x: Char, steps: MutableList<Step>): S? {
        val terms = (if (k is S.Sum) k.terms else listOf(k)).map { forcing(it, x) ?: return null }.flatten()
        if (terms.isEmpty()) return null
        // Group by λ = α + βi: P(x) = P_cos(x) − i·P_sin(x), so the right side is Re(P e^{λx}).
        val groups = LinkedHashMap<Pair<Rational, Rational>, MutableMap<Int, G>>()
        for (f in terms) {
            val g = groups.getOrPut(f.alpha to f.beta) { mutableMapOf() }
            for ((d, c) in f.poly) {
                val add = if (f.trig == "sin") G(Rational.ZERO, -c) else G(c)
                g[d] = (g[d] ?: G(Rational.ZERO)) + add
            }
        }
        val a = G(av)
        val b = G(bv)
        val c = G(cv)
        var letter = 0
        var total: S = Sym.ZERO
        steps += Step("Find a particular solution \\(y_p\\) by guessing its form (undetermined coefficients)",
            "\\text{right side: } ${Sym.latex(k, x)}",
            "The guess has the same shape as the right side, with unknown numbers to find.")
        for ((lambda, pRaw) in groups) {
            val p = pRaw.filterValues { !it.isZero }
            if (p.isEmpty()) continue
            val (alpha, beta) = lambda
            val L = G(alpha, beta)
            val pL = a * L * L + b * L + c
            val dpL = G(Rational.of(2)) * a * L + b
            val s = when {
                !pL.isZero -> 0
                !dpL.isZero -> 1
                else -> 2
            }
            val m = p.keys.max()
            // Solve p(λ)Q + p'(λ)Q' + aQ'' = P for Q = x^s·R(x), deg R = m.
            val cols = (0..m).map { j ->
                val e = s + j
                val col = mutableMapOf<Int, G>()
                fun put(deg: Int, v: G) { if (deg >= 0 && !v.isZero) col[deg] = (col[deg] ?: G(Rational.ZERO)) + v }
                put(e, pL)
                put(e - 1, dpL * G(Rational.of(e.toLong())))
                put(e - 2, a * G(Rational.of(e.toLong() * (e - 1))))
                col
            }
            val R = arrayOfNulls<G>(m + 1)
            for (j in m downTo 0) {
                var rhs = p[j] ?: G(Rational.ZERO)
                for (jj in j + 1..m) rhs = rhs - (cols[jj][j] ?: G(Rational.ZERO)) * R[jj]!!
                val lead = cols[j][j] ?: return null
                if (lead.isZero) return null
                R[j] = rhs / lead
            }
            val X = S.Var(x)
            fun poly(part: (G) -> Rational): S = add((0..m).map { j -> mul(S.Num(part(R[j]!!)), pow(X, Sym.num(j.toLong()))) })
            val cosPart = poly { it.re }
            val sinPart = neg(poly { it.im })
            val expo = if (alpha.isZero) Sym.ONE else pow(Sym.E, mul(S.Num(alpha), X))
            val xs = if (s == 0) Sym.ONE else pow(X, Sym.num(s.toLong()))
            val yp = if (beta.isZero) mul(expo, xs, cosPart)
                else mul(expo, xs, add(mul(cosPart, Sym.func("cos", mul(S.Num(beta), X))), mul(sinPart, Sym.func("sin", mul(S.Num(beta), X)))))

            // Explain: the guess with letters, then the values found.
            val letters = mutableListOf<String>()
            fun letterPoly(): String {
                val parts = (m downTo 0).map { j ->
                    val l = ('A' + letter++).toString()
                    letters += l
                    when (j) { 0 -> l; 1 -> "$l$x"; else -> "$l$x^{$j}" }
                }
                return if (parts.size == 1) parts[0] else "\\left(${parts.joinToString(" + ")}\\right)"
            }
            val cosLetters = letterPoly()
            val expoTex = if (alpha.isZero) "" else "e^{${Sym.latex(mul(S.Num(alpha), X), x)}}"
            val xsTex = when (s) { 0 -> ""; 1 -> x.toString(); else -> "$x^{$s}" }
            val bare = alpha.isZero && s == 0
            val guessBody = if (beta.isZero) {
                if (m == 0) cosLetters else if (bare) cosLetters.removePrefix("\\left(").removeSuffix("\\right)") else cosLetters
            } else {
                val bt = if (beta.isOne) "$x" else "${Tex.rational(beta)}$x"
                val sinLetters = letterPoly()
                "$cosLetters\\cos $bt + $sinLetters\\sin $bt".let { if (bare) it else "\\left($it\\right)" }
            }
            // A e^{3x},  x(A cos x + B sin x),  x^2 (Ax + B) e^{x}
            val guess = if (beta.isZero && m == 0) "$guessBody$xsTex$expoTex" else "$xsTex$guessBody$expoTex"
            val rootNote = if (s == 0) null else {
                val lt = if (beta.isZero) Tex.rational(alpha) else "${Tex.rational(alpha)} \\pm ${Tex.rational(beta)}i"
                "\\(r = $lt\\) is ${if (s == 2) "a double" else "a"} root of the characteristic equation, so the plain guess would solve the version with 0 on the right. Multiply it by \\(${if (s == 1) "$x" else "$x^2"}\\)."
            }
            steps += Step("Guess", "y_p = $guess", rootNote)
            val values = mutableListOf<Rational>()
            for (j in m downTo 0) values += R[j]!!.re
            if (!beta.isZero) for (j in m downTo 0) values += -R[j]!!.im
            steps += Step("Put the guess into the equation and match the coefficients",
                letters.zip(values).joinToString(",\\quad ") { (l, v) -> "$l = ${Tex.rational(v)}" },
                "Work out \\(y_p'\\) and \\(y_p''\\), substitute, and make the coefficient of each kind of term agree on both sides.")
            total = add(total, yp)
        }
        steps += Step("Particular solution", "y_p = ${Sym.latex(total, x)}")
        return total
    }

    /** y_p = −y₁∫y₂g/W dx + y₂∫y₁g/W dx, for right sides that undetermined coefficients can't handle. */
    private fun variationOfParameters(av: Rational, k: S, x: Char, basis: Basis, steps: MutableList<Step>): S {
        val g = Sym.div(k, S.Num(av))
        steps += Step("The right side isn't a polynomial, exponential, sine or cosine, so use variation of parameters",
            "y_1 = ${Sym.latex(basis.y1, x)},\\quad y_2 = ${Sym.latex(basis.y2, x)},\\quad g($x) = ${Sym.latex(g, x)}",
            "\\(y_p = -y_1\\int \\frac{y_2\\,g}{W}\\,d$x + y_2\\int \\frac{y_1\\,g}{W}\\,d$x\\), with \\(g\\) the right side divided by the \\(y''\\) coefficient.")
        steps += Step("The Wronskian", "W = y_1 y_2' - y_1' y_2 = ${Sym.latex(basis.wronskian, x)}")
        val i1 = Sym.div(mul(basis.y2, g), basis.wronskian)
        val i2 = Sym.div(mul(basis.y1, g), basis.wronskian)
        val u1 = Integrator(x, -1).integrate(i1) ?: throw NoMethod("The integrals for variation of parameters are beyond LocalMath.")
        val u2 = Integrator(x, -1).integrate(i2) ?: throw NoMethod("The integrals for variation of parameters are beyond LocalMath.")
        steps += Step("Work out the two integrals",
            "\\int \\frac{y_2\\,g}{W}\\,d$x = ${Sym.latex(u1, x)},\\quad \\int \\frac{y_1\\,g}{W}\\,d$x = ${Sym.latex(u2, x)}")
        val yp = add(neg(mul(basis.y1, u1)), mul(basis.y2, u2))
        steps += Step("Particular solution", "y_p = ${Sym.latex(yp, x)}")
        return yp
    }

    /** a·x²y'' + b·x·y' + c·y = 0: try y = xʳ. */
    private fun euler(a: S, b: S, c: S, rest: S, x: Char, steps: MutableList<Step>): Sol? {
        if (rest != Sym.ZERO) return null
        val X = S.Var(x)
        val A = Sym.div(a, pow(X, Sym.num(2))) as? S.Num ?: return null
        val B = Sym.div(b, X) as? S.Num ?: return null
        val Cc = c as? S.Num ?: return null
        val av = A.v
        val bv = B.v - A.v
        val cv = Cc.v
        steps += Step("It's a Cauchy–Euler equation (powers of \\($x\\) match the derivatives)",
            joinSigned(listOf(coefTerm(a, "y''", x), coefTerm(b, "y'", x), if (cv.isZero) "" else coefTerm(c, "y", x))) + " = 0",
            "Assume \\($x > 0\\).")
        val charPoly = Polynomial(mapOf(2 to av, 1 to bv, 0 to cv))
        steps += Step("Try \\(y = $x^{r}\\): then \\($x^2y'' = r(r-1)$x^{r}\\) and \\($x y' = r$x^{r}\\)",
            joinSigned(listOf(coefTerm(A, "r(r-1)", x), if (B.v.isZero) "" else coefTerm(B, "r", x), if (cv.isZero) "" else Tex.rational(cv))) +
                " = 0 \\;\\Rightarrow\\; ${charPoly.format('r')} = 0")
        val disc = bv * bv - Rational.of(4) * av * cv
        val center = S.Num(-bv / (Rational.of(2) * av))
        val spread = mul(pow(S.Num(disc.abs()), Sym.HALF), S.Num(Rational.ONE / (Rational.of(2) * av)))
        val lnx = Sym.func("ln", X)
        val y = when {
            disc.sign > 0 -> {
                val r1 = add(center, spread)
                val r2 = add(center, neg(spread))
                steps += Step("Two real roots", "r_1 = ${Sym.latex(r1)},\\quad r_2 = ${Sym.latex(r2)}", "So \\(y = C_1 $x^{r_1} + C_2 $x^{r_2}\\).")
                add(mul(C1, pow(X, r1)), mul(C2, pow(X, r2)))
            }
            disc.isZero -> {
                steps += Step("One repeated root", "r = ${Sym.latex(center)}", "So \\(y = (C_1 + C_2\\ln $x)\\,$x^{r}\\).")
                mul(add(C1, mul(C2, lnx)), pow(X, center))
            }
            else -> {
                steps += Step("Complex roots \\(r = \\alpha \\pm \\beta i\\)", "\\alpha = ${Sym.latex(center)},\\quad \\beta = ${Sym.latex(spread)}",
                    "So \\(y = $x^{\\alpha}\\left(C_1\\cos(\\beta\\ln $x) + C_2\\sin(\\beta\\ln $x)\\right)\\).")
                mul(pow(X, center), add(mul(C1, Sym.func("cos", mul(spread, lnx))), mul(C2, Sym.func("sin", mul(spread, lnx)))))
            }
        }
        steps += Step("General solution", "y = ${Sym.latex(y, x)}")
        return Sol.Explicit(y)
    }

    /** "c·what" with the sign in front and 1 hidden: (2, y) -> "2y", (−1, y) -> "-y", (x + 1, y) -> "(x + 1)\\,y". */
    private fun coefTerm(c: S, what: String, x: Char): String = when {
        c == Sym.ONE -> what
        c == Sym.MINUS_ONE -> "-$what"
        c is S.Sum -> "\\left(${Sym.latex(c, x)}\\right)$what"
        c is S.Num -> Sym.latex(c, x) + what
        else -> Sym.latex(c, x) + "\\," + what
    }

    /** Joins terms, turning "+ -" into "-". */
    private fun joinSigned(parts: List<String>) = parts.filter { it.isNotEmpty() }.joinToString(" + ").replace("+ -", "- ")

    private fun term(c: Rational, what: String) = when {
        c.isZero -> null
        c.isOne -> what
        c == Rational.MINUS_ONE -> "-$what"
        else -> Tex.rational(c) + what
    }

    // ---------------- numerical solution ----------------

    /** Runge–Kutta (RK4) from the starting values, when no exact method works. */
    private fun numeric(d: S, x: Char, order: Int, conditions: List<Condition>, steps: MutableList<Step>, reason: String?): Solution {
        val top = if (order == 2) Sym.Y2 else Sym.Y1
        val a = Differentiator(top, -1).d(d)
        if (a == Sym.ZERO || depends(a, top)) {
            throw MathError("LocalMath can only solve equations where the highest derivative (${if (order == 2) "y''" else "y'"}) appears to the first power")
        }
        val F = Sym.div(neg(Sym.substitute(d, top, Sym.ZERO)), a)
        val topTex = if (order == 2) "y''" else "y'"
        if (conditions.size != order) throw MathError("A ${if (order == 1) "first" else "second"}-order equation needs $order starting value${if (order > 1) "s (y and y')" else ""}")
        val c0 = conditions.firstOrNull { it.order == 0 } ?: throw MathError("Give y at a point, like y(0) = 1")
        val c1 = if (order == 2) conditions.firstOrNull { it.order == 1 } ?: throw MathError("Give y(…) and y'(…) as starting values") else null
        val x0 = Sym.eval(c0.at, emptyMap())
        val y0 = Sym.eval(c0.value, emptyMap())
        val p0 = c1?.let { Sym.eval(it.value, emptyMap()) } ?: 0.0
        if (c1 != null && Math.abs(Sym.eval(c1.at, emptyMap()) - x0) > 1e-12) {
            throw MathError("For a numerical solution give y and y' at the same point, like y(0) = 1; y'(0) = 0")
        }
        if (!x0.isFinite() || !y0.isFinite() || !p0.isFinite()) throw MathError("Starting values must be numbers")

        steps += Step("No exact method fits, so solve it numerically", "$topTex = ${Sym.latex(F, x)}",
            (reason?.let { "$it " } ?: "") + "Starting from the given values, LocalMath follows the slope in many tiny steps.")
        steps += Step("Runge–Kutta method (RK4), step size \\(h = 0.001\\)",
            if (order == 1) "y_{n+1} = y_n + \\tfrac{h}{6}\\left(k_1 + 2k_2 + 2k_3 + k_4\\right)"
            else "\\begin{gathered} y' = p,\\quad p' = ${Sym.latex(Sym.substitute(F, Sym.Y1, S.Var('p')), x)} \\\\ \\text{RK4 on the pair } (y, p) \\end{gathered}",
            if (order == 1) "\\(k_1 = f(x_n, y_n)\\), \\(k_2 = f(x_n + \\tfrac{h}{2}, y_n + \\tfrac{h}{2}k_1)\\), \\(k_3 = f(x_n + \\tfrac{h}{2}, y_n + \\tfrac{h}{2}k_2)\\), \\(k_4 = f(x_n + h, y_n + hk_3)\\)."
            else "A second-order equation becomes two first-order ones by writing \\(p = y'\\).")

        fun f(xv: Double, yv: Double, pv: Double): Double =
            Sym.eval(F, mapOf(x to xv, Y to yv, Sym.Y1 to (if (order == 2) pv else Double.NaN)))
        fun slope(xv: Double, yv: Double, pv: Double): Pair<Double, Double> =
            if (order == 1) f(xv, yv, 0.0) to 0.0 else pv to f(xv, yv, pv)

        val h = 0.001
        val span = 4.0
        val stepsEach = (span / h).toInt()
        val sampleEvery = 20
        fun run(dir: Int): Pair<List<Double>, Double?> {
            val ys = mutableListOf(y0)
            var xv = x0
            var yv = y0
            var pv = p0
            val hh = h * dir
            for (i in 1..stepsEach) {
                val (k1y, k1p) = slope(xv, yv, pv)
                val (k2y, k2p) = slope(xv + hh / 2, yv + hh / 2 * k1y, pv + hh / 2 * k1p)
                val (k3y, k3p) = slope(xv + hh / 2, yv + hh / 2 * k2y, pv + hh / 2 * k2p)
                val (k4y, k4p) = slope(xv + hh, yv + hh * k3y, pv + hh * k3p)
                yv += hh / 6 * (k1y + 2 * k2y + 2 * k3y + k4y)
                pv += hh / 6 * (k1p + 2 * k2p + 2 * k3p + k4p)
                xv = x0 + i * hh
                if (!yv.isFinite() || Math.abs(yv) > 1e8) return ys to xv
                if (i % sampleEvery == 0) ys += yv
            }
            return ys to null
        }
        val (forward, blowUp) = run(1)
        val (backward, blowDown) = run(-1)
        if (forward.size < 2 && backward.size < 2) throw MathError("The numerical solution breaks down straight away at x = ${Graphs.short(x0)}")

        val dx = h * sampleEvery
        val rows = (0..8).mapNotNull { i ->
            val idx = (i * 0.5 / dx).toInt()
            forward.getOrNull(idx)?.let { (x0 + i * 0.5) to it }
        }
        steps += Step("Values of \\(y\\)",
            "\\begin{array}{c|c} $x & y \\\\ \\hline " + rows.joinToString(" \\\\ ") { (xv, yv) -> "${Graphs.short(xv)} & ${Tex.decimal(yv)}" } + " \\end{array}",
            "Rounded to 6 decimal places.")
        if (blowUp != null) {
            steps += Step("The solution stops near \\($x \\approx ${Graphs.short(blowUp)}\\)", "$x \\approx ${Graphs.short(blowUp)}",
                "It either blows up there (a vertical asymptote) or leaves the region where the equation is defined.")
        }

        val all = backward.drop(1).reversed() + forward
        val startX = x0 - (backward.size - 1) * dx
        val js = "tab(${num(startX)}, ${num(dx)}, [" + all.joinToString(",") { num(it) } + "], x)"
        val graph = Graphs.build(listOf(Curve(js, "y \\text{ (numerical)}")),
            listOf(GraphPoint(x0, y0, Graphs.label(x0, y0))),
            focus = listOf(startX, x0 + (forward.size - 1) * dx))

        val answerRows = rows.filter { it.first == x0 + 1 || it.first == x0 + 2 }
        val answer = if (answerRows.isEmpty()) "\\text{The solution stops near } $x \\approx ${Graphs.short(blowUp ?: x0)}"
            else answerRows.joinToString(",\\quad ") { (xv, yv) -> "y(${Graphs.short(xv)}) \\approx ${Tex.decimal(yv)}" }
        val kind = (if (order == 2) "Second" else "First") + "-order differential equation (numerical)"
        val note = if (blowDown != null) "\\text{(going left it stops near } $x \\approx ${Graphs.short(blowDown)}\\text{)}" else "\\text{Runge–Kutta (RK4); see the table and graph}"
        return Solution(kind, steps, answer, note, graph)
    }

    private fun num(d: Double) = if (d.isFinite()) String.format(java.util.Locale.US, "%.8g", d) else "NaN"

    // ---------------- checks and starting values ----------------

    /** Plugs the general solution back into the equation at a few points. */
    private fun verify(d: S, sol: Sol, x: Char) {
        if (sol !is Sol.Explicit) return
        val y = sol.y
        val y1 = Differentiator(x, -1).d(y)
        val y2 = Differentiator(x, -1).d(y1)
        val constants = mapOf('C' to 0.8, Sym.C1 to 0.7, Sym.C2 to -0.4)
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
        }
    }

    private fun implicitTex(g: S, h: S, x: Char, c: String = "C"): String =
        "${Sym.latex(g, x)} = " + if (h == Sym.ZERO) c else if (c.isEmpty()) Sym.latex(h, x) else "${Sym.latex(h, x)} + $c"

    private fun finish(sol: Sol, x: Char, conditions: List<Condition>, order: Int, steps: MutableList<Step>): Solution {
        val kind = if (order == 2) "Second-order differential equation" else "First-order differential equation"
        if (conditions.isEmpty()) {
            return when (sol) {
                is Sol.Explicit -> Solution(kind, steps, "y = ${Sym.latex(sol.y, x)}", null, family(sol.y, x, order))
                is Sol.Implicit -> Solution(kind, steps, implicitTex(sol.g, sol.h, x))
            }
        }
        if (conditions.size != order) throw MathError("A ${if (order == 1) "first" else "second"}-order equation needs $order starting value${if (order > 1) "s (y and y')" else ""}")

        val particular: S = when (sol) {
            is Sol.Implicit -> {
                val cond = conditions.single()
                if (cond.order != 0) throw MathError("For a first-order equation give y at a point, like y(0) = 1")
                val gAt = Sym.substitute(Sym.substitute(sol.g, Y, cond.value), x, cond.at)
                val cv = add(gAt, neg(Sym.substitute(sol.h, x, cond.at)))
                steps += Step("Use \\(y(${Sym.latex(cond.at)}) = ${Sym.latex(cond.value)}\\) to find \\(C\\)", "C = ${Sym.latex(cv)}")
                val answer = implicitTex(sol.g, add(sol.h, cv), x, "")
                return Solution(kind, steps, answer)
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
