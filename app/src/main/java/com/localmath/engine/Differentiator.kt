package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.depends
import com.localmath.engine.Sym.mul
import com.localmath.engine.Sym.neg
import com.localmath.engine.Sym.pow

/**
 * Differentiates with the textbook rules (sum, constant multiple, power, product, quotient, chain …)
 * and writes a step for each rule used, down to [maxDepth] levels of nesting.
 * Use maxDepth = -1 for silent differentiation.
 */
class Differentiator(private val v: Char, private val maxDepth: Int = 3) {

    val steps = mutableListOf<Step>()

    private fun tex(s: S) = Sym.latex(s, v)
    private fun ddx(s: S) = "\\frac{d}{d$v}\\left[${tex(s)}\\right]"
    private val dudx = "\\frac{du}{d$v}"

    private fun record(depth: Int, title: String, s: S, result: S, note: String? = null, middle: String? = null) {
        if (depth > maxDepth) return
        val math = if (middle != null) "${ddx(s)} = $middle = ${tex(result)}" else "${ddx(s)} = ${tex(result)}"
        steps += Step(title, math, note)
    }

    fun d(s: S, depth: Int = 0): S {
        if (!depends(s, v)) return Sym.ZERO
        return when (s) {
            is S.Var -> Sym.ONE
            is S.Sum -> sum(s, depth)
            is S.Prod -> product(s, depth)
            is S.Pow -> power(s, depth)
            is S.Func -> function(s, depth)
            is S.Num, is S.Const -> Sym.ZERO
        }
    }

    private fun sum(s: S.Sum, depth: Int): S {
        if (depth == 0 && maxDepth >= 0) {
            steps += Step(
                "Sum rule: differentiate each term separately",
                ddx(s) + " = " + Sym.orderTerms(s.terms, v).joinToString(" + ") { ddx(it) }
            )
        }
        return add(Sym.orderTerms(s.terms, v).map { d(it, depth + 1) })
    }

    private fun product(s: S.Prod, depth: Int): S {
        val constant = s.factors.filter { !depends(it, v) }
        val varying = Sym.orderFactors(s.factors.filter { depends(it, v) }, v)

        if (constant.isNotEmpty()) {
            val c = mul(constant)
            val g = mul(varying)
            val gd = d(g, depth + 1)
            val r = mul(c, gd)
            val middle = if (c == Sym.MINUS_ONE) "-${ddx(g)}" else "${tex(c)} \\cdot ${ddx(g)}"
            record(depth, "Constant multiple rule: keep the constant, differentiate the rest", s, r, middle = middle)
            return r
        }

        val numer = varying.filter { !isDenominator(it) }
        val denomFactors = varying.filter { isDenominator(it) }
        if (numer.isNotEmpty() && denomFactors.isNotEmpty()) {
            val u = mul(numer)
            val w = mul(denomFactors.map { f -> (f as S.Pow).let { p -> pow(p.base, Sym.num(-(p.exp as S.Num).v)) } })
            val du = d(u, depth + 1)
            val dw = d(w, depth + 1)
            val r = mul(add(mul(du, w), neg(mul(u, dw))), pow(w, Sym.num(-2)))
            record(
                depth, "Quotient rule", s, r,
                note = "\\(\\left(\\frac{u}{v}\\right)' = \\frac{u'v - uv'}{v^2}\\) with \\(u = ${tex(u)}\\), \\(v = ${tex(w)}\\), " +
                    "\\(u' = ${tex(du)}\\), \\(v' = ${tex(dw)}\\)"
            )
            return r
        }

        val u = varying.first()
        val w = mul(varying.drop(1))
        val du = d(u, depth + 1)
        val dw = d(w, depth + 1)
        val r = add(mul(du, w), mul(u, dw))
        record(
            depth, "Product rule", s, r,
            note = "\\((uv)' = u'v + uv'\\) with \\(u = ${tex(u)}\\), \\(v = ${tex(w)}\\), " +
                "\\(u' = ${tex(du)}\\), \\(v' = ${tex(dw)}\\)"
        )
        return r
    }

    private fun isDenominator(f: S) = f is S.Pow && f.exp is S.Num && (f.exp as S.Num).v.sign < 0

    private fun power(s: S.Pow, depth: Int): S {
        val b = s.base
        val e = s.exp
        val isPlainVar = b == S.Var(v)

        if (!depends(e, v)) {
            // Power rule (with the chain rule when the base isn't just x).
            val db = if (isPlainVar) Sym.ONE else d(b, depth + 1)
            val r = mul(e, pow(b, add(e, Sym.MINUS_ONE)), db)
            if (isPlainVar) {
                record(depth, "Power rule: \\(\\frac{d}{d$v}$v^n = n$v^{n-1}\\)", s, r)
            } else {
                record(depth, "Chain rule with the power rule", s, r,
                    note = "\\(u = ${tex(b)}\\), \\($dudx = ${tex(db)}\\), so the result is \\(n u^{n-1} \\cdot $dudx\\)")
            }
            return r
        }

        if (!depends(b, v)) {
            // a^u  ->  a^u · ln a · u'
            val de = if (e == S.Var(v)) Sym.ONE else d(e, depth + 1)
            val r = mul(s, Sym.func("ln", b), de)
            val rule = if (b == Sym.E) "Exponential rule: \\(\\frac{d}{d$v}e^{u} = e^{u}\\,$dudx\\)"
            else "Exponential rule: \\(\\frac{d}{d$v}a^{u} = a^{u}\\ln a\\,$dudx\\)"
            record(depth, rule, s, r, note = if (e == S.Var(v)) null else "\\(u = ${tex(e)}\\), \\($dudx = ${tex(de)}\\)")
            return r
        }

        // f^g: logarithmic differentiation.
        val db = d(b, depth + 1)
        val de = d(e, depth + 1)
        val r = mul(s, add(mul(de, Sym.func("ln", b)), mul(e, db, pow(b, Sym.MINUS_ONE))))
        record(depth, "Logarithmic differentiation", s, r,
            note = "Write \\(y = f^{g}\\), so \\(\\ln y = g \\ln f\\) and \\(y' = y\\left(g' \\ln f + \\frac{g f'}{f}\\right)\\)")
        return r
    }

    private fun function(s: S.Func, depth: Int): S {
        val u = s.arg

        // ln|u|  ->  u'/u
        if (s.name == "ln" && u is S.Func && u.name == "abs") {
            val inner = u.arg
            val di = d(inner, depth + 1)
            val r = mul(di, pow(inner, Sym.MINUS_ONE))
            record(depth, "Derivative of \\(\\ln|u|\\) is \\(\\frac{u'}{u}\\)", s, r)
            return r
        }

        val du = if (u == S.Var(v)) Sym.ONE else d(u, depth + 1)
        val outer: S = when (s.name) {
            "sin" -> Sym.func("cos", u)
            "cos" -> neg(Sym.func("sin", u))
            "tan" -> pow(Sym.func("cos", u), Sym.num(-2))
            "ln" -> pow(u, Sym.MINUS_ONE)
            "log" -> pow(mul(u, Sym.func("ln", Sym.num(10))), Sym.MINUS_ONE)
            "abs" -> mul(u, pow(Sym.func("abs", u), Sym.MINUS_ONE))
            "arcsin" -> pow(add(Sym.ONE, neg(pow(u, Sym.num(2)))), Sym.num(-1, 2))
            "arccos" -> neg(pow(add(Sym.ONE, neg(pow(u, Sym.num(2)))), Sym.num(-1, 2)))
            "arctan" -> pow(add(Sym.ONE, pow(u, Sym.num(2))), Sym.MINUS_ONE)
            "sinh" -> Sym.func("cosh", u)
            "cosh" -> Sym.func("sinh", u)
            "tanh" -> pow(Sym.func("cosh", u), Sym.num(-2))
            "arsinh" -> pow(add(pow(u, Sym.num(2)), Sym.ONE), Sym.num(-1, 2))
            "arcosh" -> pow(add(pow(u, Sym.num(2)), Sym.MINUS_ONE), Sym.num(-1, 2))
            "artanh" -> pow(add(Sym.ONE, neg(pow(u, Sym.num(2)))), Sym.MINUS_ONE)
            else -> throw MathError("Can't differentiate ${s.name} yet")
        }
        val r = mul(outer, du)
        val rule = when (s.name) {
            "sin" -> "\\(\\frac{d}{du}\\sin u = \\cos u\\)"
            "cos" -> "\\(\\frac{d}{du}\\cos u = -\\sin u\\)"
            "tan" -> "\\(\\frac{d}{du}\\tan u = \\sec^2 u = \\frac{1}{\\cos^2 u}\\)"
            "ln" -> "\\(\\frac{d}{du}\\ln u = \\frac{1}{u}\\)"
            "log" -> "\\(\\frac{d}{du}\\log u = \\frac{1}{u \\ln 10}\\)"
            "arcsin" -> "\\(\\frac{d}{du}\\arcsin u = \\frac{1}{\\sqrt{1 - u^2}}\\)"
            "arccos" -> "\\(\\frac{d}{du}\\arccos u = -\\frac{1}{\\sqrt{1 - u^2}}\\)"
            "arctan" -> "\\(\\frac{d}{du}\\arctan u = \\frac{1}{1 + u^2}\\)"
            "sinh" -> "\\(\\frac{d}{du}\\sinh u = \\cosh u\\)"
            "cosh" -> "\\(\\frac{d}{du}\\cosh u = \\sinh u\\)"
            "tanh" -> "\\(\\frac{d}{du}\\tanh u = \\frac{1}{\\cosh^2 u}\\)"
            "arsinh" -> "\\(\\frac{d}{du}\\operatorname{arsinh} u = \\frac{1}{\\sqrt{u^2 + 1}}\\)"
            "arcosh" -> "\\(\\frac{d}{du}\\operatorname{arcosh} u = \\frac{1}{\\sqrt{u^2 - 1}}\\)"
            "artanh" -> "\\(\\frac{d}{du}\\operatorname{artanh} u = \\frac{1}{1 - u^2}\\)"
            else -> "\\(\\frac{d}{du}|u| = \\frac{u}{|u|}\\)"
        }
        if (u == S.Var(v)) {
            record(depth, "Standard derivative: ${rule.replace("u", v.toString())}", s, r)
        } else {
            record(depth, "Chain rule — outer rule $rule", s, r,
                note = "\\(u = ${tex(u)}\\), \\($dudx = ${tex(du)}\\), then multiply by \\($dudx\\)")
        }
        return r
    }
}
