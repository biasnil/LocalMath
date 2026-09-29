package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.depends
import com.localmath.engine.Sym.mul
import com.localmath.engine.Sym.neg
import com.localmath.engine.Sym.pow

/** Rebuilds an expression tree, letting [f] replace nodes (return null to keep going). */
internal fun Expr.mapNodes(f: (Expr) -> Expr?): Expr {
    f(this)?.let { return it }
    return when (this) {
        is Expr.Func -> Expr.Func(name, arg.mapNodes(f))
        is Expr.Neg -> Expr.Neg(inner.mapNodes(f))
        is Expr.Add -> Expr.Add(left.mapNodes(f), right.mapNodes(f))
        is Expr.Sub -> Expr.Sub(left.mapNodes(f), right.mapNodes(f))
        is Expr.Mul -> Expr.Mul(left.mapNodes(f), right.mapNodes(f))
        is Expr.Div -> Expr.Div(left.mapNodes(f), right.mapNodes(f))
        is Expr.Pow -> Expr.Pow(base.mapNodes(f), exponent.mapNodes(f))
        else -> this
    }
}

object Sequences {

    private val PREV = listOf('α', 'β', 'γ')   // a_{n−1}, a_{n−2}, a_{n−3} while working

    /** Tidier LaTeX for a second-order closed form (the symbolic form multiplies everything out). */
    private var closedTex: String? = null

    /** Set when the closed form oscillates (complex roots), where the limit engine can't judge. */
    private var limitOverride: LV? = null

    fun solve(equations: List<Input.Equation>): Solution {
        val definitions = equations.filter { it.left is Expr.Seq && (it.left as Expr.Seq).index.variables().isNotEmpty() }
        if (definitions.size != 1) throw MathError("Give one rule for the sequence, like a_n = 2n + 1 or a_n = 2a_(n-1); a_1 = 3")
        val def = definitions[0]
        val lhs = def.left as Expr.Seq
        val name = lhs.name
        val n = lhs.index.variables().single()
        val shift = offset(lhs.index, n) ?: throw MathError("Write the left side as a_n (or a_(n+1))")

        // Initial values like a_1 = 3.
        val initial = mutableMapOf<Long, S>()
        for (eq in equations - def) {
            val l = eq.left as? Expr.Seq ?: throw MathError("Extra parts should be starting values, like a_1 = 3")
            if (l.name != name) throw MathError("Use the same letter for the sequence everywhere")
            val idx = (Sym.from(l.index) as? S.Num)?.v?.takeIf { it.isInteger }?.num?.toLong()
                ?: throw MathError("Starting values need a whole-number index, like a_1 = 3")
            val value = Sym.from(eq.right)
            if (Sym.variables(value).isNotEmpty()) throw MathError("Starting values must be numbers")
            initial[idx] = value
        }

        // Replace a_(n−j) on the right by placeholders.
        var order = 0
        val rhs = def.right.mapNodes { e ->
            if (e is Expr.Seq) {
                if (e.name != name) throw MathError("Use the same letter for the sequence everywhere")
                val off = offset(e.index, n) ?: throw MathError("Refer to earlier terms as a_(n-1), a_(n-2)")
                val j = (shift - off).toInt()
                if (j < 1 || j > 3) throw MathError("The rule can use up to three earlier terms, like a_(n-1) … a_(n-3)")
                order = Math.max(order, j)
                Expr.Var(PREV[j - 1])
            } else null
        }
        val rule = Sym.from(rhs).let { if (shift != 0L) Sym.substitute(it, n, add(S.Var(n), Sym.num(-shift))) else it }
        val steps = mutableListOf(Step("Start with",
            equations.joinToString(",\\quad ") { Tex.equation(it.left, it.right) }))

        return if (order == 0) explicit(name, n, rule, steps) else recurrence(name, n, rule, order, initial, steps)
    }

    /** index − n as a whole number (a_(n−1) → −1), or null. */
    private fun offset(index: Expr, n: Char): Long? {
        val d = Sym.sub(Sym.from(index), S.Var(n))
        return (d as? S.Num)?.v?.takeIf { it.isInteger }?.num?.toLong()
    }

    private fun sub(name: Char, i: Any) = "${name}_{$i}"

    // ---------------- a_n = f(n) ----------------

    private fun explicit(name: Char, n: Char, f: S, steps: MutableList<Step>): Solution {
        if (Sym.variables(f).any { it != n }) throw MathError("The formula can only use $n")
        val terms = (1L..6L).map { Sym.substitute(f, n, Sym.num(it)) }
        steps += Step("Work out the first few terms", terms.mapIndexed { i, t -> "${sub(name, i + 1)} = ${Sym.latex(t)}" }.joinToString(",\\; ") + ",\\; \\dots")

        var kind = "Sequence"
        val diff = Sym.sub(Sym.substitute(f, n, add(S.Var(n), Sym.ONE)), f)
        val factors = if (f is S.Prod) f.factors else listOf(f)
        val ratio = mul(factors.map { Sym.div(Sym.substitute(it, n, add(S.Var(n), Sym.ONE)), it) })
        if (!depends(diff, n)) {
            kind = "Arithmetic sequence"
            steps += Step("Each term adds the same amount, so it's arithmetic",
                "d = ${sub(name, "$n+1")} - ${sub(name, n)} = ${Sym.latex(diff)}",
                "\\(${sub(name, n)} = ${sub(name, 1)} + ($n - 1)d\\)")
        } else if (!depends(ratio, n) && depends(f, n)) {
            kind = "Geometric sequence"
            steps += Step("Each term is multiplied by the same number, so it's geometric",
                "r = \\frac{${sub(name, "$n+1")}}{${sub(name, n)}} = ${Sym.latex(ratio)}",
                "\\(${sub(name, n)} = ${sub(name, 1)}\\,r^{$n-1}\\)")
        }

        val limitSteps = mutableListOf<Step>()
        val engine = LimitEngine(n, null, 1, 0, limitSteps)
        val l = try { engine.solve(f) } catch (_: MathError) { null }
        steps += limitSteps.map { Step("Long run: ${it.title.replaceFirstChar { c -> c.lowercase() }}", it.math, it.note) }
        val (answer, verdict) = verdict(l)
        steps += Step(verdict, "\\lim_{$n \\to \\infty} ${sub(name, n)} = ${answer}")
        val list = terms.take(5).joinToString(",\\; ") { Sym.latex(it) } + ",\\; \\dots"
        return Solution(kind, steps, conclusion(l), list, graphOf(name, (1L..15L).map { it to Sym.eval(f, mapOf(n to it.toDouble())) }, l))
    }

    private fun verdict(l: LV?): Pair<String, String> = when (l) {
        is LV.Fin -> Sym.latex(l.s) to "It converges"
        is LV.Approx -> "\\approx ${Tex.decimal(l.value)}" to "It converges"
        is LV.Inf -> (if (l.sign > 0) "\\infty" else "-\\infty") to "It diverges"
        else -> "\\text{does not exist}" to "It diverges (the terms don't settle)"
    }

    private fun conclusion(l: LV?) = when (l) {
        is LV.Fin -> "\\text{Converges to } ${Sym.latex(l.s)}"
        is LV.Approx -> "\\text{Converges to } \\approx ${Tex.decimal(l.value)}"
        is LV.Inf -> "\\text{Diverges to } ${if (l.sign > 0) "\\infty" else "-\\infty"}"
        else -> "\\text{Diverges}"
    }

    // ---------------- a_n from earlier terms ----------------

    private fun recurrence(name: Char, n: Char, rule: S, order: Int, initial: Map<Long, S>, steps: MutableList<Step>): Solution {
        if (initial.size < order) {
            throw MathError("This rule needs $order starting value${if (order > 1) "s" else ""}, e.g. ; ${name}_1 = 1" +
                if (order > 1) "; ${name}_2 = 1" else "")
        }
        val start = initial.keys.min()
        for (i in 0 until order) if ((start + i) !in initial) throw MathError("Give starting values for consecutive terms, like ${name}_1 and ${name}_2")
        if (Sym.variables(rule).any { it != n && it !in PREV }) throw MathError("The rule can only use $n and earlier terms")

        // Compute terms one after another.
        val values = mutableMapOf<Long, S>()
        for (i in 0 until order) values[start + i] = initial.getValue(start + i)
        val last = start + 11
        var exact = true
        val numbers = mutableMapOf<Long, Double>()
        for ((k, s) in values) numbers[k] = Sym.eval(s, emptyMap())
        for (m in (start + order)..last) {
            var s = Sym.substitute(rule, n, Sym.num(m))
            for (j in 1..order) s = Sym.substitute(s, PREV[j - 1], values.getValue(m - j))
            if (s.toString().length > 400) exact = false
            values[m] = s
            numbers[m] = Sym.eval(s, emptyMap())
        }
        val shownTerms = (start..(start + 6)).joinToString(",\\; ") { m ->
            "${sub(name, m)} = " + if (exact) Sym.latex(values.getValue(m)) else Tex.decimal(numbers.getValue(m))
        }
        steps += Step("Use the rule again and again", "$shownTerms,\\; \\dots",
            "Each new term comes from the ${if (order == 1) "one" else "$order"} before it.")

        closedTex = null
        limitOverride = null
        val closed = when (order) {
            1 -> firstOrder(name, n, rule, start, initial.getValue(start), steps)
            2 -> secondOrder(name, n, rule, start, initial.getValue(start), initial.getValue(start + 1), steps)
            else -> null
        }?.takeIf { cf ->
            (start..last).all { m ->
                val a = Sym.eval(cf, mapOf(n to m.toDouble()))
                val b = numbers.getValue(m)
                Math.abs(a - b) <= 1e-6 * Math.max(1.0, Math.abs(b))
            }
        }

        val list = (start..(start + 4)).joinToString(",\\; ") { m ->
            if (exact) Sym.latex(values.getValue(m)) else Tex.decimal(numbers.getValue(m))
        } + ",\\; \\dots"
        val pts = (start..last).map { it to numbers.getValue(it) }
        if (closed != null) {
            val tex = closedTex ?: Sym.latex(closed, n)
            steps += Step("Formula for the \\(n\\)th term", "${sub(name, n)} = $tex",
                "Checked against the terms worked out above.")
            val l = limitOverride ?: try { LimitEngine(n, null, 1, 0, mutableListOf()).solve(closed) } catch (_: MathError) { null }
            val (ans, verdict) = verdict(l)
            steps += Step(verdict, "\\lim_{$n \\to \\infty} ${sub(name, n)} = $ans")
            return Solution("Recursive sequence", steps, "${sub(name, n)} = $tex", list, graphOf(name, pts, l))
        }
        steps += Step("No simple formula here", "${sub(name, last)} = ${if (exact) Sym.latex(values.getValue(last)) else Tex.decimal(numbers.getValue(last))}",
            "LocalMath finds formulas for rules like \\(${name}_n = r\\,${name}_{n-1} + d\\) and \\(${name}_n = p\\,${name}_{n-1} + q\\,${name}_{n-2}\\).")
        return Solution("Recursive sequence", steps, list, null, graphOf(name, pts, null))
    }

    /** a_n = r·a_{n−1} + d with constant r, d. */
    private fun firstOrder(name: Char, n: Char, rule: S, start: Long, a0: S, steps: MutableList<Step>): S? {
        val p = PREV[0]
        val r = Differentiator(p, -1).d(rule)
        val d = Sym.substitute(rule, p, Sym.ZERO)
        if (depends(r, p) || depends(r, n) || depends(d, n)) return null
        val m = add(S.Var(n), Sym.num(-start))           // n − start
        if (r == Sym.ONE) {
            steps += Step("Each step adds \\(${Sym.latex(d)}\\), so it's arithmetic",
                "${sub(name, n)} = ${sub(name, start)} + ($n - $start)\\cdot ${bracket(d)}")
            return add(a0, mul(m, d))
        }
        if (d == Sym.ZERO) {
            steps += Step("Each step multiplies by \\(${Sym.latex(r)}\\), so it's geometric",
                "${sub(name, n)} = ${sub(name, start)} \\cdot ${bracket(r)}^{$n - $start}")
            return scaledPower(a0, r, m)
        }
        val fixed = Sym.div(d, add(Sym.ONE, Sym.neg(r)))
        steps += Step("Find the fixed point \\(L\\), where \\(L = ${Sym.latex(r)}L + ${bracket(d)}\\)",
            "L = \\frac{${Sym.latex(d)}}{1 - ${bracket(r)}} = ${Sym.latex(fixed)}",
            "The gap from \\(L\\) gets multiplied by \\(${Sym.latex(r)}\\) each step: \\(${sub(name, n)} - L = ${bracket(r)}\\left(${sub(name, "$n-1")} - L\\right)\\).")
        return add(fixed, scaledPower(add(a0, Sym.neg(fixed)), r, m))
    }

    /** c·r^m, but 2·2^(n−1) becomes 2^n. */
    private fun scaledPower(c: S, r: S, m: S): S {
        if (c is S.Num && r is S.Num && r.v.isInteger && r.v.num.toLong() >= 2) {
            val base = r.v.num
            var mag = c.v.abs()
            var j = 0
            while (mag.isInteger && mag.num > java.math.BigInteger.ONE && mag.num.mod(base).signum() == 0 && j < 60) {
                mag = mag / r.v; j++
            }
            if (j > 0 && mag.isOne) {
                val p = pow(r, add(m, Sym.num(j.toLong())))
                return if (c.v.sign < 0) neg(p) else p
            }
        }
        return mul(c, pow(r, m))
    }

    /** a_n = p·a_{n−1} + q·a_{n−2}: characteristic equation x² = p x + q. */
    private fun secondOrder(name: Char, n: Char, rule: S, start: Long, a0: S, a1: S, steps: MutableList<Step>): S? {
        val (p1, p2) = PREV
        val p = Differentiator(p1, -1).d(rule)
        val q = Differentiator(p2, -1).d(rule)
        if (p !is S.Num || q !is S.Num) return null
        if (Sym.substitute(Sym.substitute(rule, p1, Sym.ZERO), p2, Sym.ZERO) != Sym.ZERO) return null
        val disc = p.v * p.v + Rational.of(4) * q.v
        steps += Step("Try \\(${sub(name, n)} = x^{$n}\\): the characteristic equation",
            "x^2 = ${Polynomial(mapOf(1 to p.v, 0 to q.v)).format('x')} \\quad\\Rightarrow\\quad ${Polynomial(mapOf(2 to Rational.ONE, 1 to -p.v, 0 to -q.v)).format('x')} = 0",
            "Its discriminant is \\(${Tex.rational(disc)}\\).")
        if (disc.sign < 0) return complexRoots(name, n, p.v, q.v, disc, start, a0, a1, steps)
        val half = S.Num(p.v / Rational.of(2))
        val rootPart = mul(Sym.HALF, pow(S.Num(disc), Sym.HALF))
        val x1 = add(half, rootPart)
        val x2 = add(half, Sym.neg(rootPart))
        val m = add(S.Var(n), Sym.num(-start))            // n − start, so the starting term has power 0
        val mTex = if (start == 0L) "$n" else "$n - $start"
        if (disc.isZero) {
            // a_n = (A + B·m)·x^m  with A = a_start, B = a_(start+1)/x − A
            if (x1 == Sym.ZERO) return null
            val A = a0
            val B = add(Sym.div(a1, x1), neg(a0))
            steps += Step("Repeated root \\(x = ${Sym.latex(x1)}\\)", "${sub(name, n)} = \\left(A + B($mTex)\\right)${bracket(x1)}^{$mTex}",
                "Using the starting values: \\(A = ${Sym.latex(A)}\\), \\(B = ${Sym.latex(B)}\\).")
            closedTex = "\\left(${Sym.latex(add(A, mul(B, m)), n)}\\right)${bracket(x1)}^{${Sym.latex(m, n)}}"
            return mul(add(A, mul(B, m)), pow(x1, m))
        }
        // a_n = A·x₁^m + B·x₂^m.  With x₂ − x₁ = −√D:  A = (a₁ − a₀x₂)√D / D,  B = (a₀x₁ − a₁)√D / D
        val rootD = pow(S.Num(disc), Sym.HALF)
        val overD = S.Num(Rational.ONE / disc)
        val A = mul(add(a1, neg(mul(a0, x2))), rootD, overD)
        val B = mul(add(mul(a0, x1), neg(a1)), rootD, overD)
        steps += Step("Roots \\(x_1 = ${Sym.latex(x1)}\\) and \\(x_2 = ${Sym.latex(x2)}\\)",
            "${sub(name, n)} = A\\,x_1^{$mTex} + B\\,x_2^{$mTex}",
            "Using the starting values: \\(A = ${Sym.latex(A)}\\), \\(B = ${Sym.latex(B)}\\).")
        val mt = Sym.latex(m, n)
        closedTex = "${bracket(A)}\\,${bracket(x1)}^{$mt} + ${bracket(B)}\\,${bracket(x2)}^{$mt}".replace("+ \\left(-", "- \\left(")
        return add(mul(A, pow(x1, m)), mul(B, pow(x2, m)))
    }

    /**
     * Characteristic roots x = p/2 ± i·√(−D)/2 = ρ(cos θ ± i sin θ), so
     * a_n = ρ^m (A cos mθ + B sin mθ) with ρ = √(−q), cos θ = p/(2ρ), A = a₀, B = (2a₁ − p·a₀)/√(−D).
     */
    private fun complexRoots(name: Char, n: Char, p: Rational, q: Rational, disc: Rational, start: Long, a0: S, a1: S,
                             steps: MutableList<Step>): S? {
        val half = S.Num(p / Rational.of(2))
        val beta = mul(Sym.HALF, pow(S.Num(-disc), Sym.HALF))
        steps += Step("The roots are complex", "x = ${Sym.latex(half)} \\pm ${Complex.imagTex(beta)}",
            "Complex roots come in a conjugate pair, so the terms can be written with \\(\\cos\\) and \\(\\sin\\).")
        val rho = pow(S.Num(-q), Sym.HALF)                      // |x|² = (p/2)² + (−D/4) = −q
        val cosTheta = Sym.div(half, rho)
        val theta = Complex.angleOf(Sym.eval(cosTheta, emptyMap()).let { Math.acos(it) })
            ?: Sym.func("arccos", cosTheta)
        steps += Step("Write them in polar form \\(x = \\rho\\,(\\cos\\theta \\pm i\\sin\\theta)\\)",
            "\\rho = \\sqrt{${Tex.rational(-q)}}" + (if (Sym.latex(rho) == "\\sqrt{${Tex.rational(-q)}}") "" else " = ${Sym.latex(rho)}") + ",\\quad \\cos\\theta = \\frac{${Sym.latex(half)}}{${Sym.latex(rho)}} \\;\\Rightarrow\\; \\theta = ${Sym.latex(theta)}",
            "\\(\\rho\\) is the distance from 0 (\\(\\rho^2\\) = product of the roots) and \\(\\theta\\) is the angle.")
        val m = add(S.Var(n), Sym.num(-start))
        val mTex = if (start == 0L) "$n" else "$n - $start"
        val A = a0
        val B = Sym.div(add(mul(Sym.num(2), a1), neg(mul(S.Num(p), a0))), pow(S.Num(-disc), Sym.HALF))
        val mTheta = if (start == 0L) "$n\\theta" else "($mTex)\\theta"
        steps += Step("So the terms are", "${sub(name, n)} = \\rho^{$mTex}\\left(A\\cos $mTheta + B\\sin $mTheta\\right)",
            "Using the starting values: \\(A = ${sub(name, start)} = ${Sym.latex(A)}\\), \\(B = \\frac{2${sub(name, start + 1)} - ${bracket(S.Num(p))}${sub(name, start)}}{\\sqrt{${Tex.rational(-disc)}}} = ${Sym.latex(B)}\\).")

        val angle = mul(theta, m)
        val angleTex = if (start == 0L) Sym.latex(angle, n) else "${Sym.latex(theta)}\\left($mTex\\right)"
        val parts = mutableListOf<String>()
        fun part(c: S, fn: String) {
            if (c == Sym.ZERO) return
            val (k, _) = Sym.splitCoef(c)
            val trig = "\\$fn\\left($angleTex\\right)"
            val body = when {
                c == Sym.ONE -> trig
                c == Sym.MINUS_ONE -> "-$trig"
                c is S.Sum -> "\\left(${Sym.latex(c)}\\right)$trig"
                else -> "${Sym.latex(c)}\\,$trig"
            }
            parts += if (parts.isNotEmpty() && k.sign < 0) body else if (parts.isNotEmpty()) "+ $body" else body
        }
        part(A, "cos")
        part(B, "sin")
        val inner = parts.joinToString(" ").ifEmpty { "0" }
        val rhoTex = if (rho is S.Num) bracket(rho) else "\\left(${Sym.latex(rho)}\\right)"
        closedTex = if (rho == Sym.ONE) inner
            else "$rhoTex^{${Sym.latex(m, n)}}" + if (parts.size > 1 || inner.startsWith("-")) "\\left($inner\\right)" else inner
        limitOverride = when {
            Sym.eval(rho, emptyMap()) < 1 - 1e-12 -> LV.Fin(Sym.ZERO)
            A == Sym.ZERO && B == Sym.ZERO -> LV.Fin(Sym.ZERO)
            else -> LV.DNE("oscillates")
        }
        return mul(pow(rho, m), add(mul(A, Sym.func("cos", angle)), mul(B, Sym.func("sin", angle))))
    }

    /** Solves a·A + b·B = e,  c·A + d·B = f  (Cramer's rule). */
    private fun solve2(a: S, b: S, e: S, c: S, d: S, f: S): Pair<S, S>? {
        val det = add(mul(a, d), Sym.neg(mul(b, c)))
        if (Math.abs(Sym.eval(det, emptyMap())) < 1e-12) return null
        val A = Sym.div(add(mul(e, d), Sym.neg(mul(b, f))), det)
        val B = Sym.div(add(mul(a, f), Sym.neg(mul(e, c))), det)
        return A to B
    }

    private fun bracket(s: S) = Sym.latex(s).let { if (it.startsWith("-") || s is S.Sum) "\\left($it\\right)" else it }

    private fun graphOf(name: Char, pts: List<Pair<Long, Double>>, l: LV?): Graph? {
        val points = pts.filter { it.second.isFinite() }.map { GraphPoint(it.first.toDouble(), it.second, "") }
        if (points.isEmpty()) return null
        val limit = when (l) { is LV.Fin -> Sym.eval(l.s, emptyMap()); is LV.Approx -> l.value; else -> null }
        val curves = if (limit != null && limit.isFinite()) listOf(Curve("($limit)", "y = ${Tex.decimal(limit)}\\text{ (limit)}")) else emptyList()
        return Graphs.build(curves, points, focus = listOf(points.first().x, points.last().x))
    }
}
