package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.depends
import com.localmath.engine.Sym.mul
import com.localmath.engine.Sym.pow

/** Σ: finite sums, sums up to n (closed forms) and infinite series (convergence tests). */
object Series {

    fun solve(e: Expr.Sum): Solution {
        val f = Sym.from(e.body)
        val vars = Sym.variables(f)
        val k = e.variable ?: when {
            vars.isEmpty() -> 'k'
            vars.size == 1 -> vars.first()
            'k' in vars -> 'k'
            'i' in vars -> 'i'
            else -> throw MathError("Say which letter to sum over: Σ(f, k, a, b)")
        }
        val lower = integerOf(e.from) ?: throw MathError("The starting value of Σ must be a whole number")
        val steps = mutableListOf(Step("Start with", Tex.expr(Expr.Sum(e.body, k, e.from, e.to))))
        if (e.to is Expr.Inf) return infinite(f, k, lower, steps)
        val upper = Sym.from(e.to)
        if (Sym.variables(upper).isEmpty()) {
            val b = integerOf(e.to) ?: throw MathError("The end value of Σ must be a whole number, a letter like n, or ∞")
            return finite(f, k, lower, b, steps)
        }
        if (upper !is S.Var || upper.name == k) throw MathError("For a general sum, end at a single letter, like Σ(k², 1, n)")
        return symbolic(f, k, lower, upper.name, steps)
    }

    private fun integerOf(e: Expr): Long? {
        val s = try { Sym.from(e) } catch (_: MathError) { return null }
        return (s as? S.Num)?.v?.takeIf { it.isInteger && it.num.bitLength() < 40 }?.num?.toLong()
    }

    private fun sigma(k: Char, a: String, b: String, body: String) = "\\sum_{$k=$a}^{$b} $body"
    private fun bodyTex(f: S, k: Char) = if (f is S.Sum) "\\left(${Sym.latex(f, k)}\\right)" else Sym.latex(f, k)

    // ---------------- polynomial sums ----------------

    private fun polyOf(f: S, k: Char): Polynomial? {
        val rf = RatFunc.fromS(f, k) ?: return null
        if (!rf.den.isConstant) return null
        return rf.num.scale(Rational.ONE / rf.den[0])
    }

    /** P(n) = Σ_{k=1}^{n} p(k), found exactly by interpolating the partial sums. */
    private fun partialSumPolynomial(p: Polynomial): Polynomial {
        val d = p.degree + 1
        val s = mutableListOf(Rational.ZERO)
        for (j in 1..d) s += s.last() + p.evaluate(Rational.of(j.toLong()))
        // Newton forward differences: P(n) = Σ Δⁱs₀ · C(n, i)
        var diffs = s.toList()
        var result = Polynomial.constant(Rational.ZERO)
        var binom = Polynomial.constant(Rational.ONE)
        for (i in 0..d) {
            result += binom.scale(diffs[0])
            binom = binom * Polynomial(mapOf(1 to Rational.ONE, 0 to Rational.of(-i.toLong()))).scale(Rational.of(1, (i + 1).toLong()))
            diffs = diffs.zipWithNext { a, b -> b - a }
            if (diffs.isEmpty()) break
        }
        return result
    }

    private val FORMULAS = mapOf(
        0 to "\\sum_{k=1}^{n} 1 = n",
        1 to "\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}",
        2 to "\\sum_{k=1}^{n} k^2 = \\frac{n(n+1)(2n+1)}{6}",
        3 to "\\sum_{k=1}^{n} k^3 = \\left(\\frac{n(n+1)}{2}\\right)^2"
    )

    private fun explainPolynomial(p: Polynomial, k: Char, n: Char, steps: MutableList<Step>) {
        if (p.terms.size > 1 || p.terms.values.any { !it.isOne }) {
            val split = p.terms.entries.joinToString(" + ") { (pw, c) ->
                val term = when (pw) { 0 -> "1"; 1 -> "$k"; else -> "$k^{$pw}" }
                (if (c.isOne) "" else Tex.rational(c)) + sigma(k, "1", "$n", term)
            }.replace("+ -", "- ")
            steps += Step("Split into standard sums", split)
        }
        val used = p.terms.keys.sortedDescending()
        if (used.all { it <= 3 }) {
            steps += Step("Use the standard formulas",
                used.joinToString(" \\\\ ", "\\begin{gathered}", "\\end{gathered}") {
                    FORMULAS.getValue(it).replace("k", k.toString()).replace("n", n.toString())
                })
        } else {
            steps += Step("Use Faulhaber's formula for sums of powers", "\\sum_{$k=1}^{$n} $k^{${p.degree}}",
                "A sum of a degree-${p.degree} polynomial is a degree-${p.degree + 1} polynomial.")
        }
    }

    // ---------------- geometric ----------------

    /** Ratio r if each term is r times the previous one. */
    private fun ratioOf(f: S, k: Char): S? {
        if (!depends(f, k)) return null
        val r = termRatio(f, k)
        return if (depends(r, k) || Sym.variables(r).isNotEmpty()) null else r
    }

    /** a(k+1)/a(k), worked out factor by factor so powers like 2^k cancel cleanly. */
    private fun termRatio(f: S, k: Char): S {
        val factors = if (f is S.Prod) f.factors else listOf(f)
        return mul(factors.map { Sym.div(Sym.substitute(it, k, add(S.Var(k), Sym.ONE)), it) })
    }

    // ---------------- finite ----------------

    private fun finite(f: S, k: Char, a: Long, b: Long, steps: MutableList<Step>): Solution {
        if (b < a) {
            steps += Step("The end is before the start, so there are no terms", "0")
            return Solution("Sum", steps, "0")
        }
        val count = b - a + 1
        val poly = polyOf(f, k)
        if (poly != null) {
            explainPolynomial(poly, k, 'n', steps)
            val big = partialSumPolynomial(poly)
            val atB = big.evaluate(Rational.of(b))
            val atA = big.evaluate(Rational.of(a - 1))
            val value = atB - atA
            steps += Step("Together", "S(n) = \\sum_{$k=1}^{n} ${bodyTex(f, k)} = ${big.factorTex('n')}")
            if (a == 1L) {
                steps += Step("Put \\(n = $b\\)", "${sigma(k, "1", "$b", bodyTex(f, k))} = S($b) = ${Tex.rational(value)}")
            } else {
                steps += Step("Subtract the terms before \\($k = $a\\)",
                    "${sigma(k, "$a", "$b", bodyTex(f, k))} = S($b) - S(${a - 1}) = ${Tex.rational(atB)} - ${bracket(atA)} = ${Tex.rational(value)}")
            }
            return Solution("Sum", steps, Tex.rational(value), approx(value), graph(f, k, a, b, null))
        }
        ratioOf(f, k)?.let { r ->
            val t = Sym.substitute(f, k, Sym.num(a))
            val sum = if (r == Sym.ONE) mul(t, Sym.num(count))
            else Sym.div(mul(t, add(Sym.ONE, Sym.neg(pow(r, Sym.num(count))))), add(Sym.ONE, Sym.neg(r)))
            steps += Step("It's a geometric series: each term is \\(${Sym.latex(r)}\\) times the one before",
                "\\text{first term } a = ${Sym.latex(t)},\\quad r = ${Sym.latex(r)},\\quad N = $count\\text{ terms}")
            steps += Step("Use \\(S = a\\,\\frac{1 - r^N}{1 - r}\\)", "${sigma(k, "$a", "$b", bodyTex(f, k))} = ${Sym.latex(sum)}")
            return Solution("Geometric sum", steps, Sym.latex(sum), approxS(sum), graph(f, k, a, b, null))
        }
        if (count > 300) {
            val total = (a..b).sumOf { Sym.eval(f, mapOf(k to it.toDouble())) }
            steps += Step("Add the terms numerically ($count terms)", "${sigma(k, "$a", "$b", bodyTex(f, k))} \\approx ${Tex.decimal(total)}")
            return Solution("Sum", steps, "\\approx ${Tex.decimal(total)}", graph = graph(f, k, a, b, null))
        }
        val terms = (a..b).map { Sym.substitute(f, k, Sym.num(it)) }
        val shown = if (terms.size <= 6) terms.joinToString(" + ") { bracketS(it) }
        else (terms.take(3).map { bracketS(it) } + "\\dots" + terms.takeLast(2).map { bracketS(it) }).joinToString(" + ")
        val sum = add(terms)
        steps += Step("Write out the terms and add them", "$shown = ${Sym.latex(sum)}")
        return Solution("Sum", steps, Sym.latex(sum), approxS(sum), graph(f, k, a, b, null))
    }

    private fun bracket(r: Rational) = if (r.sign < 0) "\\left(${Tex.rational(r)}\\right)" else Tex.rational(r)
    private fun bracketS(s: S) = Sym.latex(s).let { if (it.startsWith("-")) "\\left($it\\right)" else it }
    private fun approx(r: Rational) = if (r.isInteger) null else "\\approx ${Tex.decimal(r.toDouble())}"
    private fun approxS(s: S) = if (s is S.Num && s.v.isInteger) null
        else Sym.eval(s, emptyMap()).takeIf { it.isFinite() }?.let { "\\approx ${Tex.decimal(it)}" }

    // ---------------- up to n ----------------

    private fun symbolic(f: S, k: Char, a: Long, n: Char, steps: MutableList<Step>): Solution {
        if (depends(f, n)) throw MathError("The terms can't use $n when $n is the end of the sum")
        val poly = polyOf(f, k)
        if (poly != null) {
            explainPolynomial(poly, k, n, steps)
            val big = partialSumPolynomial(poly)
            val result = if (a == 1L) big else big - Polynomial.constant(big.evaluate(Rational.of(a - 1)))
            if (a != 1L) steps += Step("Subtract the terms before \\($k = $a\\)", "S($n) - S(${a - 1}) = ${result.format(n)}")
            val factored = result.factorTex(n)
            steps += Step("Result", "${sigma(k, "$a", "$n", bodyTex(f, k))} = $factored",
                if (factored.replace(" ", "") != result.format(n).replace(" ", "")) "Expanded: \\(${result.format(n)}\\)" else null)
            return Solution("Sum formula", steps, factored)
        }
        ratioOf(f, k)?.let { r ->
            val t = Sym.substitute(f, k, Sym.num(a))
            val count = add(S.Var(n), Sym.num(1 - a))
            val sum = if (r == Sym.ONE) mul(t, count)
            else Sym.div(mul(t, add(Sym.ONE, Sym.neg(pow(r, count)))), add(Sym.ONE, Sym.neg(r)))
            steps += Step("It's a geometric series: each term is \\(${Sym.latex(r)}\\) times the one before",
                "a = ${Sym.latex(t)},\\quad r = ${Sym.latex(r)},\\quad N = ${Sym.latex(count, n)}\\text{ terms}")
            steps += Step("Use \\(S = a\\,\\frac{1 - r^N}{1 - r}\\)", "${sigma(k, "$a", "$n", bodyTex(f, k))} = ${Sym.latex(sum, n)}")
            return Solution("Sum formula", steps, Sym.latex(sum, n))
        }
        throw MathError("LocalMath can find formulas for sums of polynomials (like k²) and geometric sums (like 2^k). " +
            "Try numbers for the limits instead, e.g. Σ(f, 1, 10).")
    }

    // ---------------- infinite ----------------

    private fun infinite(f: S, k: Char, a: Long, steps: MutableList<Step>): Solution {
        if (Sym.variables(f).any { it != k }) throw MathError("An infinite series can only use the letter it sums over")
        val full = sigma(k, "$a", "\\infty", bodyTex(f, k))
        fun diverges(title: String, math: String, note: String?): Solution {
            steps += Step(title, math, note)
            return Solution("Infinite series (diverges)", steps, "\\text{Diverges}", graph = graph(f, k, a, a + 29, null))
        }
        fun converges(value: S?, estimate: Double?): Solution {
            val answer = value?.let { Sym.latex(it) } ?: "\\approx ${Tex.decimal(estimate!!)}"
            val ap = value?.let { approxS(it) }
            val lineValue = value?.let { Sym.eval(it, emptyMap()) } ?: estimate
            return Solution("Infinite series (converges)", steps, answer, ap, graph(f, k, a, a + 29, lineValue))
        }

        // Geometric.
        ratioOf(f, k)?.let { r ->
            val t = Sym.substitute(f, k, Sym.num(a))
            val rd = Sym.eval(r, emptyMap())
            steps += Step("It's a geometric series", "a = ${Sym.latex(t)},\\quad r = ${Sym.latex(r)}",
                "Each term is \\(r\\) times the one before.")
            if (Math.abs(rd) >= 1) return diverges("\\(|r| \\ge 1\\), so it diverges", "|r| = ${Tex.decimal(Math.abs(rd))} \\ge 1",
                "The terms don't shrink, so the sum never settles.")
            val sum = Sym.div(t, add(Sym.ONE, Sym.neg(r)))
            steps += Step("\\(|r| < 1\\), so it converges to \\(\\frac{a}{1 - r}\\)",
                "$full = \\frac{${Sym.latex(t)}}{1 - ${bracketS(r)}} = ${Sym.latex(sum)}")
            return converges(sum, null)
        }

        // nth-term (divergence) test.
        val termLimit = try { LimitEngine(k, null, 1, 0, mutableListOf()).solve(f) } catch (_: MathError) { null }
        val tendsToZero = (termLimit is LV.Fin && Sym.eval(termLimit.s, emptyMap()) == 0.0) ||
            (termLimit is LV.Approx && Math.abs(termLimit.value) < 1e-9)
        if (termLimit != null && !tendsToZero) {
            val shown = when (termLimit) {
                is LV.Fin -> Sym.latex(termLimit.s)
                is LV.Inf -> if (termLimit.sign > 0) "\\infty" else "-\\infty"
                is LV.Approx -> Tex.decimal(termLimit.value)
                else -> "\\text{does not exist}"
            }
            return diverges("Divergence test: the terms don't go to 0",
                "\\lim_{$k \\to \\infty} ${Sym.latex(f, k)} = $shown${if (termLimit is LV.Fin || termLimit is LV.Approx) " \\neq 0" else ""}",
                "If a series converges, its terms must shrink to 0. These don't, so it diverges.")
        }
        steps += Step("The terms go to 0", "\\lim_{$k \\to \\infty} ${Sym.latex(f, k)} = 0",
            "That's needed for convergence but isn't enough on its own, so use another test.")

        // Fractions of polynomials: compare with 1/k^p, then try telescoping and famous values.
        val rf = RatFunc.fromS(f, k)
        if (rf != null && !rf.den.isConstant) {
            val (red, _) = rf.reduced()
            val p = red.den.degree - red.num.degree
            val shape = "\\frac{${red.num.format(k)}}{${red.den.format(k)}}"
            if (p <= 1) return diverges("Compare with the harmonic series \\(\\sum \\frac{1}{$k}\\)",
                "$shape \\text{ behaves like } \\frac{c}{$k} \\text{ for large } $k",
                "The terms shrink only like \\(\\frac{1}{$k}\\), and \\(\\sum \\frac{1}{$k}\\) diverges, so this does too.")
            steps += Step("Compare with the p-series \\(\\sum \\frac{1}{$k^{$p}}\\) (limit comparison test)",
                "$shape \\text{ behaves like } \\frac{c}{$k^{$p}} \\text{ for large } $k",
                "\\(\\sum \\frac{1}{$k^p}\\) converges when \\(p > 1\\); here \\(p = $p\\), so this converges.")
            telescoping(red, k, a, steps)?.let { return converges(it, null) }
            knownZeta(red, k, a, steps)?.let { return converges(it, null) }
            val est = numericSum(f, k, a, alternating = false)
            steps += Step("Estimate the sum", "$full \\approx ${Tex.decimal(est)}",
                "There's no simple exact form, so LocalMath adds many terms and estimates the rest.")
            return converges(null, est)
        }

        // Plain powers k^(−p).
        if (f is S.Pow && f.base == S.Var(k) && f.exp is S.Num) {
            val p = -(f.exp as S.Num).v.toDouble()
            steps += Step("It's a p-series \\(\\sum \\frac{1}{$k^p}\\) with \\(p = ${Tex.decimal(p)}\\)", full,
                "p-series converge exactly when \\(p > 1\\).")
            if (p <= 1) return diverges("\\(p \\le 1\\), so it diverges", "p = ${Tex.decimal(p)} \\le 1", null)
            val est = numericSum(f, k, a, false)
            steps += Step("\\(p > 1\\), so it converges", "$full \\approx ${Tex.decimal(est)}")
            return converges(null, est)
        }

        // Alternating series.
        val factors = if (f is S.Prod) f.factors else listOf(f)
        val sign = factors.firstOrNull { it is S.Pow && it.base == Sym.MINUS_ONE && depends(it.exp, k) }
        if (sign != null) {
            val b = mul(factors - sign)
            val decreasing = (20..60).all {
                Math.abs(Sym.eval(b, mapOf(k to (it + 1).toDouble()))) <= Math.abs(Sym.eval(b, mapOf(k to it.toDouble()))) + 1e-15
            }
            if (decreasing) {
                steps += Step("Alternating series test", "b_$k = ${Sym.latex(b, k)}",
                    "The signs alternate and the sizes \\(b_$k\\) shrink to 0, so the series converges.")
                val est = numericSum(f, k, a, true)
                steps += Step("Estimate the sum", "$full \\approx ${Tex.decimal(est)}")
                return converges(null, est)
            }
        }

        // Ratio test.
        val ratio = termRatio(f, k)
        val L = try { LimitEngine(k, null, 1, 0, mutableListOf()).solve(Sym.func("abs", ratio)) } catch (_: MathError) { null }
        val Ld = when (L) { is LV.Fin -> Sym.eval(L.s, emptyMap()); is LV.Approx -> L.value; is LV.Inf -> Double.POSITIVE_INFINITY; else -> Double.NaN }
        if (!Ld.isNaN() && Math.abs(Ld - 1) > 1e-9) {
            val lTex = when (L) { is LV.Fin -> Sym.latex(L.s); is LV.Inf -> "\\infty"; else -> Tex.decimal(Ld) }
            val title = "Ratio test: \\(L = \\lim_{$k \\to \\infty} \\left|\\frac{a_{$k+1}}{a_$k}\\right|\\)"
            if (Ld > 1) return diverges(title, "L = $lTex > 1", "Eventually each term is bigger than the last, so it diverges.")
            steps += Step(title, "L = $lTex < 1", "Eventually the terms shrink at least as fast as a geometric series, so it converges.")
            val est = numericSum(f, k, a, false)
            steps += Step("Estimate the sum", "$full \\approx ${Tex.decimal(est)}")
            return converges(null, est)
        }

        // Last resort: watch the partial sums.
        val s1 = partial(f, k, a, 2000)
        val s2 = partial(f, k, a, 20000)
        if (s1.isFinite() && s2.isFinite() && Math.abs(s2 - s1) < 1e-4 * Math.max(1.0, Math.abs(s2))) {
            steps += Step("None of LocalMath's tests decide this one", "$full \\approx ${Tex.decimal(s2)}",
                "The partial sums settle down numerically, so it very likely converges, but this isn't a proof.")
            return converges(null, s2)
        }
        throw MathError("LocalMath couldn't decide whether this series converges")
    }

    /** Σ c / ((k + p)(k + q)) with whole-number p < q collapses (telescopes). */
    private fun telescoping(red: RatFunc, k: Char, a: Long, steps: MutableList<Step>): S? {
        if (!red.num.isConstant || red.den.degree != 2) return null
        val out = PolyEquation(k, mutableListOf()).solve(red.den, Polynomial.constant(Rational.ZERO))
        if (out !is Outcome.Roots) return null
        val rs = out.roots.mapNotNull { it.exact }.filter { it.isInteger }.distinct()
        if (rs.size != 2) return null
        val p = -rs.max()            // den = lead·(k + p)(k + q), p < q
        val q = -rs.min()
        if (Rational.of(a) + p <= Rational.ZERO) return null
        val c = red.num[0] / red.den[2]
        val gap = q - p
        val coef = c / gap
        steps += Step("Split with partial fractions",
            "\\frac{${Tex.rational(c)}}{${lin(k, p)}${lin(k, q)}} = ${coefTex(coef)}\\left(\\frac{1}{${lin(k, p).removeSurrounding("(", ")")}} - \\frac{1}{${lin(k, q).removeSurrounding("(", ")")}}\\right)",
            "Written like this, the sum telescopes: almost every term cancels with a later one.")
        val g = gap.num.toLong()
        val first = (0 until g).map { j -> Rational.ONE / (Rational.of(a + j) + p) }
        val total = first.fold(Rational.ZERO) { acc, x -> acc + x } * coef
        steps += Step("Only the first ${if (g == 1L) "term survives" else "$g terms survive"}",
            (if (coef.isOne && g == 1L) "" else "${coefTex(coef)}\\left(${first.joinToString(" + ") { Tex.rational(it) }}\\right) = ") + Tex.rational(total))
        return S.Num(total)
    }

    private fun sgn(r: Rational) = when {
        r.isZero -> ""
        r.sign < 0 -> "- ${Tex.rational(-r)}"
        else -> "+ ${Tex.rational(r)}"
    }
    private fun lin(k: Char, r: Rational) = if (r.isZero) "$k" else "($k ${sgn(r)})"
    private fun coefTex(c: Rational) = if (c.isOne) "" else Tex.rational(c)

    /** Σ 1/k² = π²/6 and Σ 1/k⁴ = π⁴/90. */
    private fun knownZeta(red: RatFunc, k: Char, a: Long, steps: MutableList<Step>): S? {
        if (!red.num.isConstant || red.den.terms.size != 1 || a < 1 || a > 20) return null
        val p = red.den.degree
        val c = red.num[0] / red.den[p]
        val base = when (p) {
            2 -> Sym.div(pow(Sym.PI, Sym.num(2)), Sym.num(6))
            4 -> Sym.div(pow(Sym.PI, Sym.num(4)), Sym.num(90))
            else -> return null
        }
        val skipped = (1 until a).fold(Rational.ZERO) { acc, j -> acc + Rational.ONE / Rational.of(j).pow(p) }
        val value = mul(S.Num(c), add(base, S.Num(-skipped)))
        steps += Step("A famous result", "\\sum_{$k=1}^{\\infty} \\frac{1}{$k^{$p}} = ${Sym.latex(base)}",
            if (p == 2) "This is the Basel problem, first solved by Euler." else "Also found by Euler.")
        if (a > 1 || !c.isOne) steps += Step("Adjust for the constant and the starting value", Sym.latex(value))
        return value
    }

    private fun partial(f: S, k: Char, a: Long, n: Int): Double {
        var s = 0.0
        for (j in 0 until n) s += Sym.eval(f, mapOf(k to (a + j).toDouble()))
        return s
    }

    /** Many terms plus a tail estimate (or averaging, for alternating series). */
    private fun numericSum(f: S, k: Char, a: Long, alternating: Boolean): Double {
        val n = 20000
        var s = 0.0
        var prev = 0.0
        for (j in 0 until n) {
            prev = s
            s += Sym.eval(f, mapOf(k to (a + j).toDouble()))
        }
        if (alternating) return (s + prev) / 2
        // Tail ≈ integral from (a + n − ½) to ∞; x = x₀ + t/(1 − t) maps it onto [0, 1).
        val x0 = a + n - 0.5
        val m = 2000
        val top = 0.999999
        var tail = 0.0
        for (i in 0..m) {
            val t = i.toDouble() / m * top
            val x = x0 + t / (1 - t)
            val w = if (i == 0 || i == m) 1 else if (i % 2 == 1) 4 else 2
            val y = Sym.eval(f, mapOf(k to x)) / ((1 - t) * (1 - t))
            if (y.isFinite()) tail += w * y
        }
        tail *= (top / m) / 3
        return s + tail
    }

    /** Partial sums S₁, S₂, … as dots, plus the value of the sum as a line. */
    private fun graph(f: S, k: Char, a: Long, b: Long, limit: Double?): Graph? {
        if (Sym.variables(f).any { it != k }) return null
        val last = Math.min(b, a + 29)
        var s = 0.0
        val points = (a..last).map { j ->
            s += Sym.eval(f, mapOf(k to j.toDouble()))
            GraphPoint(j.toDouble(), s, "")
        }.filter { it.y.isFinite() }
        if (points.isEmpty()) return null
        val curves = if (limit != null && limit.isFinite()) listOf(Curve("($limit)", "\\text{sum} \\approx ${Tex.decimal(limit)}")) else emptyList()
        return Graphs.build(curves, points, focus = listOf(a.toDouble(), last.toDouble()))
    }
}
