package com.localmath.engine

import java.math.BigInteger

/**
 * One solution of an equation.
 * [latex] is the value only (no "x ="), [exact] is set for rational roots, [approximate] for numeric ones.
 */
data class Root(
    val latex: String,
    val re: Double,
    val im: Double = 0.0,
    val exact: Rational? = null,
    val approximate: Boolean = false
) {
    val isReal get() = im == 0.0

    companion object {
        fun exact(r: Rational) = Root(Tex.rational(r), r.toDouble(), exact = r)
    }
}

sealed class Outcome {
    object Identity : Outcome()
    object Contradiction : Outcome()
    /** [combined] replaces the list in the answer (e.g. "x = ±√2"); [complexText] describes complex roots. */
    data class Roots(val kind: String, val roots: List<Root>, val combined: String? = null, val complexText: String? = null) : Outcome()
}

/**
 * Solves  left = right  for polynomials in [v], writing explanation steps into [steps].
 * Pass a throwaway list to solve silently.
 */
class PolyEquation(private val v: Char, private val steps: MutableList<Step>) {

    private fun m(s: String) = Tex.inline(s)

    fun solve(left: Polynomial, right: Polynomial): Outcome {
        val diff = left - right
        return when {
            diff.degree == 0 -> noVariableLeft(diff)
            diff.degree == 1 -> linear(left, right)
            diff.degree == 2 -> quadratic(normalize(diff, right.isZero))
            else -> higher(normalize(diff, right.isZero))
        }
    }

    private fun noVariableLeft(diff: Polynomial): Outcome {
        steps += Step("The \\($v\\) terms cancel out", "${Tex.rational(diff[0])} = 0")
        return if (diff.isZero) {
            steps += Step("This is always true", "0 = 0")
            Outcome.Identity
        } else {
            steps += Step("This is never true", "${Tex.rational(diff[0])} \\neq 0")
            Outcome.Contradiction
        }
    }

    // ---------------- linear ----------------

    private fun linear(origLeft: Polynomial, origRight: Polynomial): Outcome {
        var l = origLeft
        var r = origRight
        fun eq() = "${l.format(v)} = ${r.format(v)}"

        val higher = Polynomial(r.terms.filterKeys { it >= 2 })
        if (!higher.isZero) {
            l -= higher; r -= higher
            steps += Step("Subtract ${m(higher.format(v))} from both sides (those terms cancel)", eq())
        }
        if (l[1].isZero) {
            val t = l; l = r; r = t
            steps += Step("Swap sides so ${m(v.toString())} is on the left", eq())
        }
        if (!r[1].isZero) {
            val c = r[1]
            val term = Polynomial.monomial(c.abs(), 1).format(v)
            val move = Polynomial.monomial(c, 1)
            l -= move; r -= move
            steps += Step(if (c.sign > 0) "Subtract ${m(term)} from both sides" else "Add ${m(term)} to both sides", eq())
        }
        if (!l[0].isZero) {
            val c = l[0]
            val move = Polynomial.constant(c)
            l -= move; r -= move
            val cs = Tex.rational(c.abs())
            steps += Step(if (c.sign > 0) "Subtract ${m(cs)} from both sides" else "Add ${m(cs)} to both sides", eq())
        }
        val a = l[1]
        if (!a.isOne) {
            val title = when {
                a == Rational.MINUS_ONE -> "Multiply both sides by ${m("-1")}"
                a.isInteger -> "Divide both sides by ${m(Tex.rational(a))}"
                else -> "Multiply both sides by ${m(Tex.rational(Rational.ONE / a))}"
            }
            l = l.scale(Rational.ONE / a); r = r.scale(Rational.ONE / a)
            steps += Step(title, eq())
        }
        val x = r[0]
        steps += Step(
            "Check",
            "\\text{left} = ${Tex.rational(origLeft.evaluate(x))},\\quad \\text{right} = ${Tex.rational(origRight.evaluate(x))}\\;\\checkmark",
            "Substituting ${m(Tex.assignment(v, x))} back in"
        )
        return Outcome.Roots("Linear equation", listOf(Root.exact(x)))
    }

    // ---------------- getting to  p(x) = 0  with whole numbers ----------------

    private fun normalize(diff: Polynomial, alreadyZero: Boolean): Polynomial {
        var p = diff
        fun eq() = "${p.format(v)} = 0"
        if (!alreadyZero) steps += Step("Move every term to the left side", eq())
        if (p[p.degree].sign < 0) {
            p = -p
            steps += Step("Multiply both sides by \\(-1\\) so the leading term is positive", eq())
        }
        val (prim, factor) = p.toPrimitiveIntegers()
        if (!factor.isOne) {
            p = prim
            val title = when {
                factor.isInteger -> "Multiply both sides by \\(${Tex.rational(factor)}\\) to clear the fractions"
                factor.num == BigInteger.ONE -> "Divide both sides by \\(${factor.den}\\)"
                else -> "Multiply both sides by \\(${Tex.rational(factor)}\\)"
            }
            steps += Step(title, eq())
        }
        return p
    }

    /** Positive leading coefficient, whole numbers, no common factor — without writing steps. */
    private fun quietNormalize(p: Polynomial): Polynomial {
        val q = if (p[p.degree].sign < 0) -p else p
        return q.toPrimitiveIntegers().first
    }

    // ---------------- quadratic ----------------

    private fun quadratic(p: Polynomial): Outcome.Roots {
        val a = p[2].num
        val b = p[1].num
        val c = p[0].num
        return when {
            c.signum() == 0 -> factorOutX(a, b)
            b.signum() == 0 -> squareRootMethod(a, c)
            else -> discriminantMethod(a, b, c)
        }
    }

    private fun factorOutX(a: BigInteger, b: BigInteger): Outcome.Roots {
        val inner = Polynomial(mapOf(1 to Rational.of(a), 0 to Rational.of(b))).format(v)
        steps += Step("Factor out \\($v\\)", "$v\\left($inner\\right) = 0")
        steps += Step("Set each factor equal to zero", Tex.orList(listOf("$v = 0", "$inner = 0")))
        val other = Rational.of(b.negate(), a)
        steps += Step("Solve \\($inner = 0\\)", Tex.assignment(v, other))
        return Outcome.Roots("Quadratic equation (factoring)", listOf(Root.exact(Rational.ZERO), Root.exact(other)))
    }

    private fun squareRootMethod(a: BigInteger, c: BigInteger): Outcome.Roots {
        val k = Rational.of(c.negate(), a)
        val sq = "$v^2"
        steps += Step(
            if (a == BigInteger.ONE) "Isolate \\($sq\\)" else "Isolate \\($sq\\) (move the constant, then divide by \\($a\\))",
            "$sq = ${Tex.rational(k)}"
        )
        val (outside, inside) = Tex.simplifySqrt(k.num.abs() * k.den)
        val coef = Rational.of(outside, k.den)
        val kind = "Quadratic equation (square roots)"
        val size = coef.toDouble() * Math.sqrt(inside.toDouble())

        if (k.sign < 0) {
            val root = imaginary(coef, inside)
            steps += Step("A square can't be negative", "\\text{No real number squared gives } ${Tex.rational(k)}")
            steps += Step("Complex solutions (for reference)", "$v = \\pm $root")
            return Outcome.Roots(kind, listOf(Root("-$root", 0.0, -size), Root(root, 0.0, size)), complexText = "$v = \\pm $root")
        }
        steps += Step("Take the square root of both sides (remember \\(\\pm\\))", "$v = \\pm\\sqrt{${Tex.rational(k)}}")
        if (inside == BigInteger.ONE) {
            steps += Step("Simplify", "$v = \\pm ${Tex.rational(coef)}")
            return Outcome.Roots(kind, listOf(Root.exact(-coef), Root.exact(coef)))
        }
        val surd = surdTimes(coef, inside)
        if (!(k.isInteger && outside == BigInteger.ONE)) steps += Step("Simplify the square root", "$v = \\pm $surd")
        return Outcome.Roots(kind, listOf(Root("-$surd", -size), Root(surd, size)), combined = "$v = \\pm $surd")
    }

    private fun discriminantMethod(a: BigInteger, b: BigInteger, c: BigInteger): Outcome.Roots {
        val d = b * b - BigInteger.valueOf(4) * a * c
        steps += Step("Identify the coefficients", "a = $a,\\quad b = $b,\\quad c = $c")
        steps += Step("Compute the discriminant", "\\Delta = b^2 - 4ac = ($b)^2 - 4($a)($c) = $d")

        val sqrtD = if (d.signum() >= 0) d.sqrt() else null
        if (sqrtD != null && sqrtD * sqrtD == d) return factorWithRationalRoots(a, b, sqrtD)

        steps += Step(
            "Apply the quadratic formula",
            "$v = \\frac{-b \\pm \\sqrt{\\Delta}}{2a} = \\frac{${b.negate()} \\pm \\sqrt{$d}}{${a * BigInteger.TWO}}"
        )
        val (k, mm) = Tex.simplifySqrt(d.abs())
        var num = b.negate()
        var den = a * BigInteger.TWO
        var kk = k
        if (d.signum() < 0) steps += Step("Simplify the square root (\\(\\sqrt{-1} = i\\))", "\\sqrt{$d} = ${imaginary(Rational.of(k), mm)}")
        else if (k != BigInteger.ONE) steps += Step("Simplify the square root", "\\sqrt{$d} = ${Tex.surd(k, mm)}")

        val g = num.gcd(kk).gcd(den)
        if (g > BigInteger.ONE) { num /= g; kk /= g; den /= g }
        val rad = if (d.signum() < 0) imaginary(Rational.of(kk), mm) else Tex.surd(kk, mm)
        fun over(top: String) = if (den == BigInteger.ONE) top else "\\frac{$top}{$den}"
        val combinedTop = if (num.signum() == 0) "\\pm $rad" else "$num \\pm $rad"
        val exact = "$v = " + over(combinedTop)
        steps += Step(if (g > BigInteger.ONE || num.signum() == 0) "Simplify" else "Result", exact)

        val center = num.toDouble() / den.toDouble()
        val spread = kk.toDouble() * Math.sqrt(mm.toDouble()) / den.toDouble()
        val minusTop = if (num.signum() == 0) "-$rad" else "$num - $rad"
        val plusTop = if (num.signum() == 0) rad else "$num + $rad"
        val kind = "Quadratic equation (formula)"
        return if (d.signum() < 0) {
            steps += Step("\\(\\Delta\\) is negative", "\\text{No real solutions, only the complex pair above}")
            Outcome.Roots(kind, listOf(Root(over(minusTop), center, -spread), Root(over(plusTop), center, spread)),
                complexText = "$exact \\approx ${Tex.decimal(center)} \\pm ${Tex.decimal(spread)}i")
        } else {
            Outcome.Roots(kind, listOf(Root(over(minusTop), center - spread), Root(over(plusTop), center + spread)), combined = exact)
        }
    }

    private fun factorWithRationalRoots(a: BigInteger, b: BigInteger, sqrtD: BigInteger): Outcome.Roots {
        val twoA = a * BigInteger.TWO
        val r1 = Rational.of(b.negate() - sqrtD, twoA)
        val r2 = Rational.of(b.negate() + sqrtD, twoA)
        val lead = Rational.of(a) / (Rational.of(r1.den) * Rational.of(r2.den))
        val leadText = when {
            lead.isOne -> ""
            lead == Rational.MINUS_ONE -> "-"
            else -> Tex.rational(lead)
        }
        val kind = "Quadratic equation (factoring)"
        if (r1 == r2) {
            steps += Step("\\(\\Delta = 0\\), so it's a perfect square", "$leadText${factorOf(r1, scaled = true)}^2 = 0")
            steps += Step("Set the factor equal to zero", Tex.assignment(v, r1), "This is a repeated (double) root")
            return Outcome.Roots(kind, listOf(Root.exact(r1)))
        }
        steps += Step("\\(\\Delta\\) is a perfect square, so the quadratic factors",
            "$leadText${factorOf(r1, scaled = true)}${factorOf(r2, scaled = true)} = 0")
        steps += Step("Set each factor equal to zero", Tex.orList(listOf(Tex.assignment(v, r1), Tex.assignment(v, r2))))
        return Outcome.Roots(kind, listOf(Root.exact(r1), Root.exact(r2)))
    }

    /** "(x − r)", or "(2x − 1)" for r = 1/2 when [scaled]. */
    private fun factorOf(r: Rational, scaled: Boolean): String {
        val p = if (scaled) Polynomial(mapOf(1 to Rational.of(r.den), 0 to Rational.of(r.num.negate())))
        else Polynomial(mapOf(1 to Rational.ONE, 0 to -r))
        return "\\left(${p.format(v)}\\right)"
    }

    // ---------------- degree 3 and up ----------------

    private fun higher(p0: Polynomial): Outcome.Roots {
        val n = p0.degree
        val kind = "Polynomial equation (degree $n)"
        val roots = mutableListOf<Root>()
        var q = p0

        // x^k · (…) = 0
        val k = q.lowestPower
        if (k > 0) {
            val rest = q.shiftDown(k)
            val xk = if (k == 1) "$v" else "$v^{$k}"
            steps += Step("Factor out \\($xk\\)", "$xk\\left(${rest.format(v)}\\right) = 0",
                "So \\($v = 0\\) is a solution" + if (k > 1) " (repeated $k times)" else "")
            roots += Root.exact(Rational.ZERO)
            q = rest
        }

        // Rational root test + synthetic division, while the degree is 3 or more.
        var explained = false
        while (q.degree >= 3) {
            val prim = quietNormalize(q)
            val candidates = rationalCandidates(prim) ?: break
            if (!explained) {
                val shown = candidates.filter { it.sign > 0 }.take(12).joinToString(", ") { "\\pm " + Tex.rational(it) }
                val more = if (candidates.size > 24) ", \\dots" else ""
                steps += Step(
                    "Rational root test: try \\(\\pm\\frac{p}{q}\\) where \\(p\\) divides ${m(prim[0].num.abs().toString())} and \\(q\\) divides ${m(prim[prim.degree].num.abs().toString())}",
                    shown + more
                )
                explained = true
            }
            val r = candidates.firstOrNull { q.evaluate(it).isZero }
            if (r == null) {
                steps += Step("None of the candidates make it zero", "${q.format(v)} = 0",
                    "So this part has no rational roots.")
                break
            }
            val (quotient, table) = syntheticDivision(q, r)
            steps += Step(
                "\\(${Tex.assignment(v, r)}\\) makes it zero, so divide by ${m(factorOf(r, scaled = false))} (synthetic division)",
                table,
                "\\(${q.format(v)} = ${factorOf(r, scaled = false)}\\left(${quotient.format(v)}\\right)\\)"
            )
            roots += Root.exact(r)
            q = quotient
        }

        when {
            q.degree == 0 -> Unit
            q.degree == 1 -> {
                val r = -q[0] / q[1]
                steps += Step("Solve the last factor \\(${q.format(v)} = 0\\)", Tex.assignment(v, r))
                roots += Root.exact(r)
            }
            q.degree == 2 -> {
                val qq = quietNormalize(q)
                steps += Step("Solve the remaining quadratic", "${qq.format(v)} = 0")
                val sub = quadratic(qq)
                roots += sub.roots
            }
            q.terms.size == 2 -> roots += pureRoot(q)
            else -> {
                steps += Step("No more rational roots, so find the rest numerically", "${q.format(v)} = 0",
                    "These values are rounded; exact forms need methods beyond this app.")
                roots += numericRoots(q)
            }
        }

        val distinct = distinctRoots(roots)
        val complex = distinct.filter { !it.isReal }
        return Outcome.Roots(kind, distinct,
            complexText = if (complex.isEmpty()) null else complex.joinToString(",\\; ") { "$v ${if (it.approximate) "\\approx" else "="} ${it.latex}" })
    }

    /** a·x^n + c = 0  ->  x = ⁿ√(−c/a) (and ± for even n). */
    private fun pureRoot(q: Polynomial): List<Root> {
        val n = q.degree
        val k = -q[0] / q[n]
        steps += Step("Isolate \\($v^{$n}\\)", "$v^{$n} = ${Tex.rational(k)}")
        val size = Math.pow(k.abs().toDouble(), 1.0 / n)
        val rootTex = Sym.latex(Sym.pow(Sym.num(k.abs()), Sym.num(Rational.of(1, n.toLong()))))
        val out = mutableListOf<Root>()
        if (n % 2 == 1) {
            val tex = if (k.sign < 0) "-$rootTex" else rootTex
            steps += Step("Take the ${ordinal(n)} root of both sides", "$v = $tex", "An odd root has exactly one real value")
            out += Root(tex, if (k.sign < 0) -size else size)
        } else if (k.sign > 0) {
            steps += Step("Take the ${ordinal(n)} root of both sides (remember \\(\\pm\\))", "$v = \\pm $rootTex")
            out += Root("-$rootTex", -size)
            out += Root(rootTex, size)
        } else {
            steps += Step("An even power can't be negative", "\\text{No real solutions from this factor}")
        }
        out += numericRoots(q).filter { !it.isReal }
        return out
    }

    private fun ordinal(n: Int) = when (n) { 3 -> "cube"; 4 -> "4th"; 5 -> "5th"; else -> "${n}th" }

    private fun syntheticDivision(q: Polynomial, r: Rational): Pair<Polynomial, String> {
        val coeffs = q.coefficientsDescending()
        val bottom = mutableListOf<Rational>()
        val middle = mutableListOf<Rational?>(null)
        var carry = Rational.ZERO
        for ((i, c) in coeffs.withIndex()) {
            val value = c + carry
            bottom += value
            carry = value * r
            if (i < coeffs.size - 1) middle += carry
        }
        val quotientCoeffs = bottom.dropLast(1)
        val deg = quotientCoeffs.size - 1
        val quotient = Polynomial(quotientCoeffs.mapIndexed { i, c -> (deg - i) to c }.toMap())

        val cols = "r|" + "r".repeat(coeffs.size)
        val row1 = Tex.rational(r) + " & " + coeffs.joinToString(" & ") { Tex.rational(it) }
        val row2 = " & " + middle.joinToString(" & ") { it?.let { x -> Tex.rational(x) } ?: "" }
        val row3 = " & " + bottom.joinToString(" & ") { Tex.rational(it) }
        return quotient to "\\begin{array}{$cols} $row1 \\\\ $row2 \\\\ \\hline $row3 \\end{array}"
    }

    // ---------------- helpers ----------------

    private fun surdTimes(coef: Rational, mm: BigInteger): String = when {
        coef.isOne -> "\\sqrt{$mm}"
        coef.isInteger -> "${coef.num}\\sqrt{$mm}"
        else -> "\\frac{${if (coef.num == BigInteger.ONE) "" else coef.num.toString()}\\sqrt{$mm}}{${coef.den}}"
    }

    private fun imaginary(coef: Rational, mm: BigInteger): String {
        val num = if (coef.num == BigInteger.ONE) "" else coef.num.toString()
        val root = if (mm == BigInteger.ONE) "" else "\\sqrt{$mm}"
        val body = "${num}i$root"
        return if (coef.isInteger) body else "\\frac{$body}{${coef.den}}"
    }

    companion object {
        private val LIMIT = BigInteger.TEN.pow(12)

        /** ±p/q for p | a₀ and q | aₙ, smallest first. Null if the numbers are too big to try. */
        fun rationalCandidates(p: Polynomial): List<Rational>? {
            val a0 = p[0].num.abs()
            val an = p[p.degree].num.abs()
            if (a0.signum() == 0 || a0 > LIMIT || an > LIMIT) return null
            val out = mutableSetOf<Rational>()
            for (pp in divisors(a0)) for (qq in divisors(an)) {
                val r = Rational.of(pp, qq)
                out += r
                out += -r
            }
            return out.sortedWith(compareBy<Rational>({ it.abs() }, { -it.sign }))
        }

        private fun divisors(n: BigInteger): List<BigInteger> {
            val x = n.toLong()
            val small = mutableListOf<Long>()
            val large = mutableListOf<Long>()
            var i = 1L
            while (i * i <= x) {
                if (x % i == 0L) { small += i; if (i != x / i) large += x / i }
                i++
            }
            return (small + large.reversed()).map { BigInteger.valueOf(it) }
        }

        /** All roots (complex included) by the Durand–Kerner method, real ones polished with Newton's method. */
        fun numericRoots(p: Polynomial): List<Root> {
            val n = p.degree
            if (n < 1) return emptyList()
            val lead = p[n].toDouble()
            val a = DoubleArray(n + 1) { p[it].toDouble() / lead }   // a[i] = coefficient of x^i, monic
            val re = DoubleArray(n)
            val im = DoubleArray(n)
            var zr = 1.0
            var zi = 0.0
            for (k in 0 until n) {   // start points (0.4 + 0.9i)^k
                re[k] = zr; im[k] = zi
                val nr = zr * 0.4 - zi * 0.9
                zi = zr * 0.9 + zi * 0.4
                zr = nr
            }
            for (iter in 0 until 2000) {
                var change = 0.0
                for (k in 0 until n) {
                    // p(z) by Horner
                    var pr = 1.0
                    var pi = 0.0
                    for (i in n - 1 downTo 0) {
                        val tr = pr * re[k] - pi * im[k] + a[i]
                        pi = pr * im[k] + pi * re[k]
                        pr = tr
                    }
                    var dr = 1.0
                    var di = 0.0
                    for (j in 0 until n) if (j != k) {
                        val xr = re[k] - re[j]
                        val xi = im[k] - im[j]
                        val tr = dr * xr - di * xi
                        di = dr * xi + di * xr
                        dr = tr
                    }
                    val mag = dr * dr + di * di
                    if (mag == 0.0) continue
                    val qr = (pr * dr + pi * di) / mag
                    val qi = (pi * dr - pr * di) / mag
                    re[k] -= qr; im[k] -= qi
                    change = Math.max(change, Math.hypot(qr, qi))
                }
                if (change < 1e-15) break
            }
            val out = mutableListOf<Root>()
            for (k in 0 until n) {
                if (Math.abs(im[k]) < 1e-7 * Math.max(1.0, Math.abs(re[k]))) {
                    var x = re[k]
                    repeat(30) {
                        var f = 0.0
                        var df = 0.0
                        for (i in n downTo 0) { df = df * x + f; f = f * x + a[i] }
                        if (df != 0.0) x -= f / df
                    }
                    out += Root(Tex.decimal(x), x, approximate = true)
                } else {
                    val sign = if (im[k] < 0) "-" else "+"
                    out += Root("${Tex.decimal(re[k])} $sign ${Tex.decimal(Math.abs(im[k]))}i", re[k], im[k], approximate = true)
                }
            }
            return out
        }

        /** Removes repeated roots (same value), keeping the exact form when there is one. */
        fun distinctRoots(roots: List<Root>): List<Root> {
            val out = mutableListOf<Root>()
            for (r in roots.sortedBy { if (it.approximate) 1 else 0 }) {
                if (out.none { Math.abs(it.re - r.re) < 1e-7 && Math.abs(it.im - r.im) < 1e-7 }) out += r
            }
            return out.sortedWith(compareBy({ it.re }, { it.im }))
        }
    }
}
