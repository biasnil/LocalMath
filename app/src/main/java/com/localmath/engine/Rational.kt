package com.localmath.engine

import java.math.BigInteger

/**
 * Exact fraction p/q, always stored in lowest terms with q > 0.
 * All engine arithmetic uses this so answers never pick up floating-point error.
 */
class Rational private constructor(val num: BigInteger, val den: BigInteger) : Comparable<Rational> {

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)
        val MINUS_ONE = Rational(BigInteger.ONE.negate(), BigInteger.ONE)

        fun of(n: Long, d: Long = 1): Rational = of(BigInteger.valueOf(n), BigInteger.valueOf(d))

        fun of(n: BigInteger, d: BigInteger = BigInteger.ONE): Rational {
            if (d.signum() == 0) throw MathError("Division by zero")
            var nn = n
            var dd = d
            if (dd.signum() < 0) { nn = nn.negate(); dd = dd.negate() }
            val g = nn.gcd(dd)
            return if (g > BigInteger.ONE) Rational(nn / g, dd / g) else Rational(nn, dd)
        }

        /** Parses "12", "3.75", ".5" exactly (3.75 -> 15/4). */
        fun parse(text: String): Rational {
            val dot = text.indexOf('.')
            if (dot < 0) return of(BigInteger(text))
            val intPart = text.substring(0, dot).ifEmpty { "0" }
            val fracPart = text.substring(dot + 1)
            if (fracPart.isEmpty()) return of(BigInteger(intPart))
            val scale = BigInteger.TEN.pow(fracPart.length)
            return of(BigInteger(intPart) * scale + BigInteger(fracPart), scale)
        }
    }

    val isZero get() = num.signum() == 0
    val isOne get() = num == BigInteger.ONE && den == BigInteger.ONE
    val isInteger get() = den == BigInteger.ONE
    val sign get() = num.signum()

    operator fun plus(o: Rational) = of(num * o.den + o.num * den, den * o.den)
    operator fun minus(o: Rational) = of(num * o.den - o.num * den, den * o.den)
    operator fun times(o: Rational) = of(num * o.num, den * o.den)
    operator fun div(o: Rational): Rational {
        if (o.isZero) throw MathError("Division by zero")
        return of(num * o.den, den * o.num)
    }
    operator fun unaryMinus() = Rational(num.negate(), den)

    fun abs() = if (sign < 0) -this else this

    fun pow(e: Int): Rational {
        if (e >= 0) return of(num.pow(e), den.pow(e))
        if (isZero) throw MathError("Division by zero")
        return of(den.pow(-e), num.pow(-e))
    }

    fun toDouble(): Double {
        // Very long numerators and denominators overflow a double on their own (∞/∞), so divide exactly first.
        if (num.bitLength() > 1000 || den.bitLength() > 1000) {
            return java.math.BigDecimal(num).divide(java.math.BigDecimal(den), java.math.MathContext.DECIMAL64).toDouble()
        }
        return num.toDouble() / den.toDouble()
    }

    override fun compareTo(other: Rational) = (num * other.den).compareTo(other.num * den)
    override fun equals(other: Any?) = other is Rational && num == other.num && den == other.den
    override fun hashCode() = num.hashCode() * 31 + den.hashCode()

    /** Plain form: "3", "-7/2". */
    override fun toString() = if (isInteger) num.toString() else "$num/$den"
}
