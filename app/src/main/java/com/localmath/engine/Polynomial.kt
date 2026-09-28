package com.localmath.engine

import java.math.BigInteger

/**
 * Polynomial in one variable, stored as degree -> coefficient (zero terms dropped).
 * Expanding an AST into this form is how "combine like terms" happens.
 */
class Polynomial(terms: Map<Int, Rational>) {

    val terms: Map<Int, Rational> = terms.filterValues { !it.isZero }.toSortedMap(reverseOrder())

    val degree: Int get() = terms.keys.firstOrNull() ?: 0
    val isZero get() = terms.isEmpty()
    val isConstant get() = terms.keys.all { it == 0 }

    operator fun get(power: Int): Rational = terms[power] ?: Rational.ZERO

    operator fun plus(o: Polynomial) = combine(o) { a, b -> a + b }
    operator fun minus(o: Polynomial) = combine(o) { a, b -> a - b }
    operator fun unaryMinus() = Polynomial(terms.mapValues { -it.value })

    operator fun times(o: Polynomial): Polynomial {
        val out = mutableMapOf<Int, Rational>()
        for ((p1, c1) in terms) for ((p2, c2) in o.terms) {
            val p = p1 + p2
            if (p > MAX_DEGREE) throw MathError("Degree is too high (max $MAX_DEGREE)")
            out[p] = (out[p] ?: Rational.ZERO) + c1 * c2
        }
        return Polynomial(out)
    }

    fun scale(k: Rational) = Polynomial(terms.mapValues { it.value * k })

    fun pow(n: Int): Polynomial {
        if (n > MAX_DEGREE) throw MathError("Exponent is too large")
        var result = constant(Rational.ONE)
        repeat(n) { result *= this }
        return result
    }

    private fun combine(o: Polynomial, f: (Rational, Rational) -> Rational): Polynomial {
        val keys = terms.keys + o.terms.keys
        return Polynomial(keys.associateWith { f(this[it], o[it]) })
    }

    /** Scales to whole-number coefficients with no common factor: ½x² − ¾ becomes 2x² − 3. */
    fun toPrimitiveIntegers(): Pair<Polynomial, Rational> {
        if (isZero) return this to Rational.ONE
        val lcm = terms.values.fold(BigInteger.ONE) { acc, c -> acc / acc.gcd(c.den) * c.den }
        val scaled = terms.mapValues { (it.value * Rational.of(lcm)).num }
        val gcd = scaled.values.fold(BigInteger.ZERO) { acc, n -> acc.gcd(n) }
        val factor = Rational.of(lcm, gcd)
        return scale(factor) to factor
    }

    fun evaluate(x: Rational): Rational =
        terms.entries.fold(Rational.ZERO) { acc, (p, c) -> acc + c * x.pow(p) }

    fun evaluate(x: Double): Double = terms.entries.sumOf { (p, c) -> c.toDouble() * Math.pow(x, p.toDouble()) }

    /** Lowest power that appears (the k in x^k · (…)). */
    val lowestPower: Int get() = terms.keys.minOrNull() ?: 0

    /** Coefficients from the highest power down to the constant, zeros included. */
    fun coefficientsDescending(): List<Rational> = (degree downTo 0).map { this[it] }

    /** Long division: returns (quotient, remainder). */
    fun divMod(d: Polynomial): Pair<Polynomial, Polynomial> {
        if (d.isZero) throw MathError("Division by zero")
        var r = this
        val q = mutableMapOf<Int, Rational>()
        val dd = d.degree
        val lead = d[dd]
        var guard = 0
        while (!r.isZero && r.degree >= dd && guard++ < 200) {
            val k = r.degree - dd
            val c = r[r.degree] / lead
            q[k] = c
            r -= d * monomial(c, k)
        }
        return Polynomial(q) to r
    }

    /** Divides so the leading coefficient is 1. */
    fun monic(): Polynomial = if (isZero) this else scale(Rational.ONE / this[degree])

    /** Divides out x^k. */
    fun shiftDown(k: Int) = Polynomial(terms.mapKeys { it.key - k })

    /** LaTeX like "2x^{2} - \frac{3}{2}x + 1". */
    fun format(variable: Char): String {
        if (isZero) return "0"
        val sb = StringBuilder()
        terms.entries.forEachIndexed { i, (power, coef) ->
            val abs = coef.abs()
            if (i == 0) { if (coef.sign < 0) sb.append("-") } else sb.append(if (coef.sign < 0) " - " else " + ")
            val varPart = when (power) {
                0 -> ""
                1 -> variable.toString()
                else -> "$variable^{$power}"
            }
            when {
                power == 0 -> sb.append(Tex.rational(abs))
                abs.isOne -> sb.append(varPart)
                else -> sb.append(Tex.rational(abs)).append(varPart)
            }
        }
        return sb.toString()
    }

    /**
     * Factored LaTeX using rational roots, e.g. n³/3 + n²/2 + n/6  ->  \frac{n(n + 1)(2n + 1)}{6}.
     * Whatever doesn't split into rational linear factors stays as one bracket.
     */
    fun factorTex(v: Char): String {
        if (isZero) return "0"
        if (degree == 0) return Tex.rational(this[0])
        var rest = this
        val factors = mutableListOf<Pair<Polynomial, Int>>()   // (integer linear factor, power)
        if (rest.lowestPower > 0) {
            factors += X to rest.lowestPower
            rest = rest.shiftDown(rest.lowestPower)
        }
        var guard = 0
        while (rest.degree >= 1 && guard++ < 60) {
            val prim = rest.toPrimitiveIntegers().first.let { if (it[it.degree].sign < 0) -it else it }
            val candidates = PolyEquation.rationalCandidates(prim) ?: break
            val r = candidates.firstOrNull { rest.evaluate(it).isZero } ?: break
            val lin = Polynomial(mapOf(1 to Rational.of(r.den), 0 to Rational.of(-r.num)))   // (q·x − p)
            var power = 0
            while (!rest.isZero && rest.degree >= 1 && rest.evaluate(r).isZero) {
                rest = rest.divMod(lin).first
                power++
            }
            factors += lin to power
        }
        // rest = c · (primitive leftover)
        val (leftover, factor) = if (rest.degree >= 1) rest.toPrimitiveIntegers().let { (p, f) ->
            if (p[p.degree].sign < 0) -p to -f else p to f
        } else Polynomial.constant(Rational.ONE) to (Rational.ONE / rest[0])
        val c = Rational.ONE / factor
        val parts = factors.map { (lin, k) ->
            val body = if (lin == X) v.toString() else "\\left(${lin.format(v)}\\right)"
            if (k == 1) body else "$body^{$k}"
        } + (if (leftover.degree >= 1) listOf("\\left(${leftover.format(v)}\\right)") else emptyList())
        val product = parts.joinToString("")
        val sign = if (c.sign < 0) "-" else ""
        val a = c.abs()
        return when {
            a.isOne -> sign + product
            a.isInteger -> sign + a.num + product
            else -> sign + "\\frac{" + (if (a.num == BigInteger.ONE) "" else a.num.toString()) + product + "}{${a.den}}"
        }
    }

    override fun equals(other: Any?) = other is Polynomial && terms == other.terms
    override fun hashCode() = terms.hashCode()

    companion object {
        const val MAX_DEGREE = 50

        fun constant(c: Rational) = Polynomial(mapOf(0 to c))

        val X = Polynomial(mapOf(1 to Rational.ONE))

        /** Greatest common divisor, made monic (Euclid's algorithm over the rationals). */
        fun gcd(a: Polynomial, b: Polynomial): Polynomial {
            var x = a
            var y = b
            var guard = 0
            while (!y.isZero && guard++ < 200) {
                val r = x.divMod(y).second
                x = y
                y = r
            }
            return x.monic()
        }
        fun monomial(c: Rational, power: Int) = Polynomial(mapOf(power to c))

        /** Expands an AST into a polynomial in [variable]. Throws MathError if it isn't one. */
        fun fromExpr(e: Expr, variable: Char?): Polynomial = when (e) {
            is Expr.Num -> constant(e.value)
            is Expr.Var -> {
                if (e.name != variable) throw MathError("Only one variable is supported here (found '${e.name}')")
                monomial(Rational.ONE, 1)
            }
            is Expr.Neg -> -fromExpr(e.inner, variable)
            is Expr.Add -> fromExpr(e.left, variable) + fromExpr(e.right, variable)
            is Expr.Sub -> fromExpr(e.left, variable) - fromExpr(e.right, variable)
            is Expr.Mul -> fromExpr(e.left, variable) * fromExpr(e.right, variable)
            is Expr.Div -> {
                val d = fromExpr(e.right, variable)
                if (!d.isConstant) throw MathError("Equations with ${variable ?: "a variable"} in a denominator aren't supported yet")
                if (d.isZero) throw MathError("Division by zero")
                fromExpr(e.left, variable).scale(Rational.ONE / d[0])
            }
            is Expr.Pow -> {
                val exp = fromExpr(e.exponent, variable)
                if (!exp.isConstant) throw MathError("Variables in exponents aren't supported in equations yet")
                val k = exp[0]
                if (k.num.abs() > BigInteger.valueOf(1000)) throw MathError("Exponent is too large")
                val base = fromExpr(e.base, variable)
                when {
                    k.isInteger && k.sign >= 0 -> base.pow(k.num.toInt())
                    k.isInteger && base.isConstant -> constant(base[0].pow(k.num.toInt()))
                    else -> throw MathError("Only whole-number powers are supported in equations for now")
                }
            }
            is Expr.Const, is Expr.Func ->
                throw MathError("Equations with sin, ln, √, e or π aren't supported yet — only polynomial equations")
            is Expr.Derivative, is Expr.Integral ->
                throw MathError("Put d/dx or ∫ on its own, not inside an equation")
            is Expr.Special -> throw MathError("lim, Σ, ∞, aₙ and y' can't be used here")
        }
    }
}
