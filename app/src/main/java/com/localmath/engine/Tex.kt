package com.localmath.engine

import java.math.BigInteger

/**
 * Everything the engine shows is LaTeX, rendered on screen by KaTeX.
 * Step titles and notes are plain text with inline math wrapped in \( … \).
 */
object Tex {

    fun inline(latex: String) = "\\($latex\\)"

    fun rational(r: Rational): String = when {
        r.isInteger -> r.num.toString()
        r.sign < 0 -> "-\\frac{${r.num.negate()}}{${r.den}}"
        else -> "\\frac{${r.num}}{${r.den}}"
    }

    fun int(n: BigInteger) = n.toString()

    fun decimal(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "\\text{undefined}"
        val s = "%.6f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    fun assignment(variable: Char, value: Rational) = "$variable = ${rational(value)}"

    fun equation(left: Expr, right: Expr) = "${expr(left)} = ${expr(right)}"

    fun orList(items: List<String>) = items.joinToString(" \\quad\\text{or}\\quad ")

    /** "k\sqrt{m}" with k = 1 hidden. */
    fun surd(k: BigInteger, m: BigInteger) = (if (k == BigInteger.ONE) "" else k.toString()) + "\\sqrt{$m}"

    /** Writes n as k²·m with m square-free, so √n = k√m. */
    fun simplifySqrt(n: BigInteger): Pair<BigInteger, BigInteger> {
        require(n.signum() >= 0)
        var rest = n
        var outside = BigInteger.ONE
        var p = BigInteger.TWO
        var steps = 0
        while (p * p <= rest && steps < 200_000) {
            val sq = p * p
            while ((rest % sq).signum() == 0) { rest /= sq; outside *= p }
            p += BigInteger.ONE
            steps++
        }
        val r = rest.sqrt()
        if (r * r == rest) { outside *= r; rest = BigInteger.ONE }
        return outside to rest
    }

    // ---------- the user's input, as typed ----------

    fun expr(e: Expr): String = when (e) {
        is Expr.Num -> if (e.value.isInteger || e.text == null) rational(e.value) else e.text
        is Expr.Var -> e.name.toString()
        is Expr.Const -> if (e.name == 'π') "\\pi" else "e"
        is Expr.Func -> func(e)
        is Expr.Neg -> "-" + wrap(e.inner, 3)
        is Expr.Add -> "${expr(e.left)} + ${wrap(e.right, 1)}"
        is Expr.Sub -> "${expr(e.left)} - ${wrap(e.right, 2)}"
        is Expr.Mul -> {
            val l = wrap(e.left, 2)
            val r = wrap(e.right, 3)
            val leftIsNumber = e.left is Expr.Num && e.left.value.sign >= 0
            val rightStartsWithDigit = r.first().isDigit()
            if ((leftIsNumber || e.left !is Expr.Num) && !rightStartsWithDigit) "$l $r" else "$l \\cdot $r"
        }
        is Expr.Div -> "\\frac{${expr(e.left)}}{${expr(e.right)}}"
        is Expr.Pow -> "${wrap(e.base, 5)}^{${expr(e.exponent)}}"
        is Expr.Derivative -> derivativeOp(e.variable, e.order) + "\\left[${expr(e.body)}\\right]"
        is Expr.Inf -> "\\infty"
        is Expr.Limit -> {
            val v = e.variable ?: 'x'
            val side = when (e.side) { 1 -> "^{+}"; -1 -> "^{-}"; else -> "" }
            "\\lim_{$v \\to ${expr(e.to)}$side} ${wrap(e.body, 2)}"
        }
        is Expr.Sum -> {
            val v = e.variable ?: 'k'
            "\\sum_{$v=${expr(e.from)}}^{${expr(e.to)}} ${wrap(e.body, 2)}"
        }
        is Expr.Seq -> "${e.name}_{${expr(e.index)}}"
        is Expr.Prime -> e.name + "'".repeat(e.order)
        is Expr.Call -> e.name + "'".repeat(e.order) + "\\left(${expr(e.arg)}\\right)"
        is Expr.Integral -> {
            val v = e.variable ?: 'x'
            val limits = if (e.lower != null && e.upper != null) "_{${expr(e.lower)}}^{${expr(e.upper)}}" else ""
            "\\int$limits ${wrap(e.body, 2)}\\,d$v"
        }
    }

    private fun func(e: Expr.Func): String {
        val arg = expr(e.arg)
        return when (e.name) {
            "sqrt" -> "\\sqrt{$arg}"
            "abs" -> "\\left|$arg\\right|"
            "exp" -> "e^{$arg}"
            else -> {
                val simple = e.arg is Expr.Var || e.arg is Expr.Const || (e.arg is Expr.Num && e.arg.value.sign >= 0)
                funcName(e.name) + if (simple) " $arg" else "\\left($arg\\right)"
            }
        }
    }

    /** LaTeX for a function name; inverse hyperbolics aren't built-in KaTeX commands. */
    fun funcName(name: String) = when (name) {
        "arsinh", "arcosh", "artanh" -> "\\operatorname{$name}"
        else -> "\\$name"
    }

    /** d/dx, or d²/dx² for higher orders. */
    fun derivativeOp(v: Char, order: Int) =
        if (order == 1) "\\frac{d}{d$v}" else "\\frac{d^{$order}}{d$v^{$order}}"

    private fun precedence(e: Expr) = when (e) {
        is Expr.Add, is Expr.Sub -> 1
        is Expr.Mul, is Expr.Integral -> 2
        is Expr.Neg -> 3
        is Expr.Div -> 4
        is Expr.Pow -> 4
        is Expr.Num -> if (e.value.sign < 0 || (!e.value.isInteger && e.text == null)) 3 else 5
        is Expr.Func -> if (e.name == "sqrt" || e.name == "abs") 5 else 4
        is Expr.Var, is Expr.Const, is Expr.Derivative -> 5
        is Expr.Limit, is Expr.Sum -> 2
        is Expr.Inf, is Expr.Seq, is Expr.Prime, is Expr.Call -> 5
    }

    private fun wrap(e: Expr, minPrec: Int) =
        if (precedence(e) < minPrec) "\\left(${expr(e)}\\right)" else expr(e)
}
