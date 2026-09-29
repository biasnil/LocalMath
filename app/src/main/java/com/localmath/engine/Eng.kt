package com.localmath.engine

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale

/** Engineering notation: SI prefixes (k, M, m, µ …) for input and output. */
object Eng {

    /** Prefix letter -> power of ten. */
    val PREFIXES = linkedMapOf(
        "T" to 12, "G" to 9, "M" to 6, "k" to 3,
        "m" to -3, "µ" to -6, "μ" to -6, "u" to -6, "n" to -9, "p" to -12
    )

    private val OUT = mapOf(12 to "T", 9 to "G", 6 to "M", 3 to "k", 0 to "", -3 to "m", -6 to "\\mu ", -9 to "n", -12 to "p")

    /**
     * Reads a value typed into a formula box: "4.7k", "100n", "3.3e-6", "-12", "2π", "1/3", "√2".
     * Returns null for an empty box.
     */
    fun parseValue(raw: String): S? {
        var t = raw.trim().replace(" ", "")
        if (t.isEmpty()) return null
        // Money and percentages: "$1,250.50", "5%", "€300"
        t = t.trimStart('$', '€', '£', '¥', '₹').removeSuffix("%")
        if (Regex("^-?\\d{1,3}(,\\d{3})+(\\.\\d+)?$").matches(t)) t = t.replace(",", "")
        if (t.isEmpty()) return null
        // 3.3e-6 / 3.3E6
        Regex("^([-−]?[0-9.]+)[eE]([-−+]?[0-9]+)$").matchEntire(t)?.let { m ->
            t = "${m.groupValues[1]}×10^(${m.groupValues[2].replace('−', '-')})"
        }
        // trailing SI prefix after a number: 4.7k, 100n, 2.2M
        for ((p, exp) in PREFIXES) {
            if (t.length > p.length && t.endsWith(p) && (t[t.length - p.length - 1].isDigit() || t[t.length - p.length - 1] == '.')) {
                t = "(" + t.dropLast(p.length) + ")×10^(" + exp + ")"
                break
            }
        }
        val input = try { Parser.parse(t) } catch (e: MathError) { throw MathError("Couldn't read \"$raw\" as a number") }
        val expr = (input as? Input.Expression)?.expr ?: throw MathError("Couldn't read \"$raw\" as a number")
        val s = Sym.from(expr)
        if (Sym.variables(s).isNotEmpty() || !Sym.eval(s, emptyMap()).isFinite()) throw MathError("\"$raw\" must be a number (you can use k, M, m, µ, n, p)")
        return s
    }

    /** 0.0047 -> "4.7\,\mathrm{m}" + unit;  3200000 -> "3.2\,\mathrm{M}". Four significant figures. */
    fun format(d: Double, unit: String = ""): String {
        if (!d.isFinite()) return Tex.decimal(d)
        val u = unitTex(unit)
        if (d == 0.0) return "0" + if (u.isEmpty()) "" else "\\,$u"
        var exp3 = Math.floorDiv(Math.floor(Math.log10(Math.abs(d))).toInt(), 3) * 3
        var mant = BigDecimal(d / Math.pow(10.0, exp3.toDouble())).round(MathContext(4))
        if (mant.abs() >= BigDecimal(1000)) { exp3 += 3; mant = mant.movePointLeft(3).round(MathContext(4)) }
        val m = mant.stripTrailingZeros().toPlainString()
        val prefix = OUT[exp3]
        return if (prefix != null) {
            val body = (prefix + u).trim()
            if (body.isEmpty()) m else "$m\\,${if (prefix.isEmpty()) u else "\\mathrm{$prefix}$u"}"
        } else "$m \\times 10^{$exp3}" + if (u.isEmpty()) "" else "\\,$u"
    }

    /**
     * Plain notation for money, percentages and counts (finance formulas): 12{,}345.68 for money (two decimals),
     * 5.25\,\% and 7.2725\,years otherwise (up to four decimals).
     */
    fun plainFormat(d: Double, unit: String = ""): String {
        if (!d.isFinite()) return Tex.decimal(d)
        val decimals = if (unit == MONEY) 2 else 4
        val a = Math.abs(d)
        val body = if (a != 0.0 && (a >= 1e15 || a < 0.5 * Math.pow(10.0, -decimals.toDouble()))) {
            val exp = Math.floor(Math.log10(a)).toInt()
            val mant = BigDecimal(d / Math.pow(10.0, exp.toDouble())).round(MathContext(4)).stripTrailingZeros().toPlainString()
            "$mant \\times 10^{$exp}"
        } else {
            val bd = BigDecimal(d).setScale(decimals, java.math.RoundingMode.HALF_UP)
            val s = (if (unit == MONEY) bd else bd.stripTrailingZeros()).toPlainString()
            val negative = s.startsWith("-")
            val digits = s.removePrefix("-")
            val whole = digits.substringBefore('.')
            val frac = digits.substringAfter('.', "")
            val grouped = whole.reversed().chunked(3).map { it.reversed() }.reversed().joinToString("{,}")
            (if (negative && digits.any { it in '1'..'9' }) "-" else "") + grouped + (if (frac.isEmpty()) "" else ".$frac")
        }
        return body + when (unit) {
            "", MONEY -> ""
            "%" -> "\\%"
            else -> "\\,${unitTex(unit)}"
        }
    }

    /** True when [plainFormat] shows the exact value (no rounding). */
    fun isExactPlain(r: S, unit: String): Boolean {
        val v = (r as? S.Num)?.v ?: return false
        return try {
            val bd = BigDecimal(v.num).divide(BigDecimal(v.den))
            bd.stripTrailingZeros().scale() <= (if (unit == MONEY) 2 else 4)
        } catch (_: ArithmeticException) { false }
    }

    private fun unitTex(unit: String) = when (unit) {
        "" -> ""
        "Ω" -> "\\Omega"
        "°C" -> "{}^{\\circ}\\mathrm{C}"
        "%" -> "\\%"
        else -> "\\mathrm{" + unit.replace("µ", "\\mu ").replace("²", "^2").replace("³", "^3").replace("·", "\\cdot ") + "}"
    }

    /** Adds an engineering-notation version after the first decimal number in [tex], if it helps. */
    fun decorate(tex: String): String {
        val m = Regex("-?\\d+(\\.\\d+)?").find(tex) ?: return tex
        val v = m.value.toDoubleOrNull() ?: return tex
        if (v == 0.0 || (Math.abs(v) >= 0.1 && Math.abs(v) < 1000)) return tex
        val eng = format(v)
        return tex + "\\;\\left(= $eng\\right)"
    }

    fun plain(d: Double) = String.format(Locale.US, "%.6g", d)
}
