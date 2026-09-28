package com.localmath.engine

import java.math.BigInteger

/**
 * A fraction of two polynomials in one variable: num / den.
 * [zeros] lists every polynomial that appeared in a denominator of the original input, so
 * values that make the original undefined are remembered even after fractions cancel.
 */
class RatFunc(val num: Polynomial, val den: Polynomial, val zeros: List<Polynomial> = emptyList()) {

    val isPolynomial get() = den.isConstant

    operator fun plus(o: RatFunc): RatFunc {
        val g = Polynomial.gcd(den, o.den)
        val zs = zeros + o.zeros
        if (g.isConstant) return RatFunc(num * o.den + o.num * den, den * o.den, zs)
        val a = den.divMod(g).first       // den = a·g
        val b = o.den.divMod(g).first     // o.den = b·g, so the common denominator is a·b·g
        return RatFunc(num * b + o.num * a, den * b, zs)
    }

    operator fun unaryMinus() = RatFunc(-num, den, zeros)
    operator fun minus(o: RatFunc) = this + (-o)
    operator fun times(o: RatFunc) = RatFunc(num * o.num, den * o.den, zeros + o.zeros)

    operator fun div(o: RatFunc): RatFunc {
        if (o.num.isZero) throw MathError("Division by zero")
        val extra = if (o.num.isConstant) emptyList() else listOf(o.num)
        return RatFunc(num * o.den, den * o.num, zeros + o.zeros + extra)
    }

    fun pow(n: Int): RatFunc {
        if (Math.abs(n) > Polynomial.MAX_DEGREE) throw MathError("Exponent is too large")
        if (n == 0) return RatFunc(Polynomial.constant(Rational.ONE), Polynomial.constant(Rational.ONE), zeros)
        val base = if (n > 0) this else {
            if (num.isZero) throw MathError("Division by zero")
            RatFunc(den, num, if (num.isConstant) zeros else zeros + num)
        }
        var r = base
        repeat(Math.abs(n) - 1) { r = r * base }
        return r
    }

    /** Cancels the common factor; returns the reduced fraction and the factor that was cancelled (if any). */
    fun reduced(): Pair<RatFunc, Polynomial?> {
        val g = if (num.isZero) den.monic() else Polynomial.gcd(num, den)
        var n = num
        var d = den
        var cancelled: Polynomial? = null
        if (!g.isConstant) {
            n = num.divMod(g).first
            d = den.divMod(g).first
            cancelled = g
        }
        return RatFunc(n, d, zeros).normalized() to cancelled
    }

    /** Whole-number coefficients with a positive leading coefficient in the denominator. */
    fun normalized(): RatFunc {
        if (den.isConstant) return RatFunc(num.scale(Rational.ONE / den[0]), Polynomial.constant(Rational.ONE), zeros)
        val (_, factor) = den.toPrimitiveIntegers()
        var s = factor
        if (den[den.degree] * s < Rational.ZERO) s = -s
        var n = num.scale(s)
        var d = den.scale(s)
        // Clear fractions in the numerator too, if that keeps things whole.
        if (!n.isZero) {
            val lcm = n.terms.values.fold(BigInteger.ONE) { acc, c -> acc / acc.gcd(c.den) * c.den }
            if (lcm > BigInteger.ONE) { n = n.scale(Rational.of(lcm)); d = d.scale(Rational.of(lcm)) }
        }
        return RatFunc(n, d, zeros)
    }

    fun evaluate(x: Double) = num.evaluate(x) / den.evaluate(x)

    /** True if x makes any denominator of the original input zero. */
    fun undefinedAt(x: Rational?, xd: Double): Boolean = zeros.any { z ->
        if (x != null) z.evaluate(x).isZero
        else Math.abs(z.evaluate(xd)) < 1e-9 * Math.max(1.0, z.terms.values.maxOf { it.abs().toDouble() })
    }

    fun format(v: Char): String =
        if (den.isConstant) num.scale(Rational.ONE / den[0]).format(v)
        else "\\frac{${num.format(v)}}{${den.format(v)}}"

    companion object {
        private val ONE_POLY = Polynomial.constant(Rational.ONE)

        fun of(p: Polynomial) = RatFunc(p, ONE_POLY)

        /** Converts a simplified expression to a fraction of polynomials in [v], or null if it isn't one. */
        fun fromS(s: S, v: Char): RatFunc? {
          return try {
            when (s) {
                is S.Num -> of(Polynomial.constant(s.v))
                is S.Var -> if (s.name == v) of(Polynomial.X) else null
                is S.Sum -> s.terms.map { fromS(it, v) ?: return null }.reduce { a, b -> a + b }
                is S.Prod -> s.factors.map { fromS(it, v) ?: return null }.reduce { a, b -> a * b }
                is S.Pow -> {
                    val e = s.exp as? S.Num ?: return null
                    if (!e.v.isInteger || e.v.num.abs() > BigInteger.valueOf(Polynomial.MAX_DEGREE.toLong())) return null
                    (fromS(s.base, v) ?: return null).pow(e.v.num.toInt())
                }
                else -> null
            }
          } catch (_: MathError) { null }
        }

        fun from(e: Expr, v: Char?): RatFunc = when (e) {
            is Expr.Num -> of(Polynomial.constant(e.value))
            is Expr.Var -> {
                if (e.name != v) throw MathError("Only one variable is supported here (found '${e.name}')")
                of(Polynomial.X)
            }
            is Expr.Neg -> -from(e.inner, v)
            is Expr.Add -> from(e.left, v) + from(e.right, v)
            is Expr.Sub -> from(e.left, v) - from(e.right, v)
            is Expr.Mul -> from(e.left, v) * from(e.right, v)
            is Expr.Div -> from(e.left, v) / from(e.right, v)
            is Expr.Pow -> {
                val exp = from(e.exponent, v)
                if (!exp.isPolynomial || !exp.num.isConstant) throw MathError("Variables in exponents aren't supported in equations yet")
                val k = exp.num[0] / exp.den[0]
                if (!k.isInteger) throw MathError("Only whole-number powers are supported in equations for now")
                if (k.num.abs() > BigInteger.valueOf(Polynomial.MAX_DEGREE.toLong())) throw MathError("Exponent is too large")
                from(e.base, v).pow(k.num.toInt())
            }
            is Expr.Const, is Expr.Func ->
                throw MathError("Equations with sin, ln, √, e or π aren't supported yet — only polynomial and fraction equations")
            is Expr.Derivative, is Expr.Integral ->
                throw MathError("Put d/dx or ∫ on its own, not inside an equation")
            is Expr.Special -> throw MathError("lim, Σ, ∞, aₙ and y' can't be used here")
        }
    }
}
