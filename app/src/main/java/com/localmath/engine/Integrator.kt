package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.depends
import com.localmath.engine.Sym.mul
import com.localmath.engine.Sym.neg
import com.localmath.engine.Sym.pow

/**
 * Integrates with textbook techniques, in this order:
 * sum / constant-multiple rules → table of standard integrals → linear substitution (u = ax + b)
 * → u-substitution → integration by parts.
 * Returns null when none of them apply (the caller reports "not supported yet").
 */
class Integrator(private val v: Char, private val maxDepth: Int = 3) {

    val steps = mutableListOf<Step>()

    private fun tex(s: S) = Sym.latex(s, v)
    private fun int(s: S, variable: Char = v) = "\\int ${bracket(s)}\\,d$variable"
    private fun bracket(s: S) = if (s is S.Sum) "\\left(${Sym.latex(s, v)}\\right)" else Sym.latex(s, v)

    private fun record(depth: Int, title: String, s: S, result: S, note: String? = null, middle: String? = null) {
        if (depth > maxDepth) return
        val math = if (middle != null) "${int(s)} = $middle = ${tex(result)}" else "${int(s)} = ${tex(result)}"
        steps += Step(title, math, note)
    }

    fun integrate(s: S, depth: Int = 0): S? {
        if (depth > 8) return null
        if (!depends(s, v)) {
            val r = mul(s, S.Var(v))
            record(depth, "Constant rule: \\(\\int k\\,d$v = k$v\\)", s, r)
            return r
        }
        if (s is S.Sum) {
            if (depth == 0 && maxDepth >= 0) {
                steps += Step("Sum rule: integrate each term separately",
                    int(s) + " = " + Sym.orderTerms(s.terms, v).joinToString(" + ") { int(it) })
            }
            val parts = Sym.orderTerms(s.terms, v).map { integrate(it, depth + 1) ?: return null }
            return add(parts)
        }
        if (s is S.Prod) {
            val constant = s.factors.filter { !depends(it, v) }
            if (constant.isNotEmpty()) {
                val c = mul(constant)
                val g = mul(s.factors.filter { depends(it, v) })
                val gi = integrate(g, depth + 1) ?: return null
                val r = mul(c, gi)
                record(depth, "Constant multiple rule: take the constant outside", s, r,
                    middle = "${tex(c)} ${int(g)}")
                return r
            }
        }
        return table(s, depth) ?: partialFractions(s, depth) ?: linearSubstitution(s, depth) ?: uSubstitution(s, depth) ?: byParts(s, depth)
    }

    /** N(x)/D(x) where D splits into different linear factors: Σ A/(x − r), each giving A·ln|x − r|. */
    private fun partialFractions(s: S, depth: Int): S? {
        val rf = RatFunc.fromS(s, v) ?: return null
        val (red, _) = rf.reduced()
        val den = red.den
        if (den.degree < 2) return null
        var num = red.num
        var whole: Polynomial? = null
        if (num.degree >= den.degree) {
            val (q, r) = num.divMod(den)
            whole = q
            num = r
        }
        if (den.lowestPower > 1) return null
        val core = den.shiftDown(den.lowestPower)
        val prim = core.toPrimitiveIntegers().first.let { if (it[it.degree].sign < 0) -it else it }
        val found = if (core.degree == 0) emptyList()
            else (PolyEquation.rationalCandidates(prim) ?: return null).filter { core.evaluate(it).isZero }.distinct()
        val roots = (if (den.lowestPower == 1) listOf(Rational.ZERO) else emptyList()) + found
        if (roots.size != den.degree) return null
        val lead = den[den.degree]
        val parts = roots.map { r ->
            // A = N(r) / D'(r), and D'(r) = lead · Π (r − other roots)
            val dPrime = roots.filter { it != r }.fold(lead) { acc, o -> acc * (r - o) }
            r to num.evaluate(r) / dPrime
        }.filter { !it.second.isZero }
        val x = S.Var(v)
        val logs = parts.map { (r, a) -> mul(S.Num(a), Sym.func("ln", Sym.func("abs", add(x, S.Num(-r))))) }
        val polyPart = whole?.let { q -> integrate(add(q.terms.map { (k, c) -> mul(S.Num(c), pow(x, Sym.num(k.toLong()))) }), depth + 1) ?: return null }
        val result = add(logs + listOfNotNull(polyPart))
        val split = parts.joinToString(" + ") { (r, a) -> "\\frac{${Tex.rational(a)}}{${tex(add(x, S.Num(-r)))}}" }.replace("+ \\frac{-", "- \\frac{")
        record(depth, "Partial fractions: split into simple fractions", s, result,
            note = "Each \\(\\frac{A}{$v - r}\\) integrates to \\(A\\ln|$v - r|\\).",
            middle = "\\int \\left(${(listOfNotNull(whole?.format(v)) + split).joinToString(" + ")}\\right)d$v")
        return result
    }

    // ---------------- standard integrals ----------------

    private fun table(s: S, depth: Int): S? {
        val x = S.Var(v)
        val absX = Sym.func("abs", x)

        if (s == x) {
            val r = mul(Sym.HALF, pow(x, Sym.num(2)))
            record(depth, "Power rule: \\(\\int $v^n\\,d$v = \\frac{$v^{n+1}}{n+1}\\)", s, r)
            return r
        }
        if (s is S.Pow && s.base == x && !depends(s.exp, v)) {
            if (s.exp == Sym.MINUS_ONE) {
                val r = Sym.func("ln", absX)
                record(depth, "Standard integral: \\(\\int \\frac{1}{$v}\\,d$v = \\ln|$v|\\)", s, r)
                return r
            }
            val n1 = add(s.exp, Sym.ONE)
            val r = mul(pow(x, n1), pow(n1, Sym.MINUS_ONE))
            record(depth, "Power rule: \\(\\int $v^n\\,d$v = \\frac{$v^{n+1}}{n+1}\\)", s, r,
                note = if (s.exp is S.Num && !(s.exp as S.Num).v.isInteger) "Roots are powers too: \\(\\sqrt{$v} = $v^{1/2}\\)" else null)
            return r
        }
        if (s is S.Pow && s.exp == x && !depends(s.base, v)) {
            val r = if (s.base == Sym.E) s else mul(s, pow(Sym.func("ln", s.base), Sym.MINUS_ONE))
            record(depth, if (s.base == Sym.E) "Standard integral: \\(\\int e^{$v}\\,d$v = e^{$v}\\)"
                else "Standard integral: \\(\\int a^{$v}\\,d$v = \\frac{a^{$v}}{\\ln a}\\)", s, r)
            return r
        }
        if (s is S.Func && s.arg == x) {
            val r: S = when (s.name) {
                "sin" -> neg(Sym.func("cos", x))
                "cos" -> Sym.func("sin", x)
                "tan" -> neg(Sym.func("ln", Sym.func("abs", Sym.func("cos", x))))
                "ln" -> add(mul(x, Sym.func("ln", x)), neg(x))
                "log" -> mul(add(mul(x, Sym.func("ln", x)), neg(x)), pow(Sym.func("ln", Sym.num(10)), Sym.MINUS_ONE))
                "abs" -> mul(Sym.HALF, x, absX)
                "sinh" -> Sym.func("cosh", x)
                "cosh" -> Sym.func("sinh", x)
                "tanh" -> Sym.func("ln", Sym.func("cosh", x))
                else -> return null
            }
            val note = when (s.name) {
                "ln", "log" -> "This standard result comes from integration by parts with \\(u = \\ln $v\\), \\(dv = d$v\\)"
                "tan" -> "Write \\(\\tan $v = \\frac{\\sin $v}{\\cos $v}\\) and substitute \\(u = \\cos $v\\)"
                else -> null
            }
            record(depth, "Standard integral", s, r, note = note)
            return r
        }
        // 1/(x² + 1) -> arctan x,   1/√(1 − x²) -> arcsin x
        if (s == pow(add(pow(x, Sym.num(2)), Sym.ONE), Sym.MINUS_ONE)) {
            val r = Sym.func("arctan", x)
            record(depth, "Standard integral: \\(\\int \\frac{1}{1 + $v^2}\\,d$v = \\arctan $v\\)", s, r)
            return r
        }
        if (s == pow(add(Sym.ONE, neg(pow(x, Sym.num(2)))), Sym.num(-1, 2))) {
            val r = Sym.func("arcsin", x)
            record(depth, "Standard integral: \\(\\int \\frac{1}{\\sqrt{1 - $v^2}}\\,d$v = \\arcsin $v\\)", s, r)
            return r
        }
        // 1/cos²x = sec²x  ->  tan x
        if (s is S.Pow && s.exp == Sym.num(-2) && s.base == Sym.func("cos", x)) {
            val r = Sym.func("tan", x)
            record(depth, "Standard integral: \\(\\int \\sec^2 $v\\,d$v = \\tan $v\\)", s, r)
            return r
        }
        return null
    }

    // ---------------- u = ax + b ----------------

    /** The "inside" of a function or power, if there is one. */
    private fun innerOf(s: S): S? = when {
        s is S.Func && s.name != "abs" -> s.arg
        s is S.Pow && !depends(s.exp, v) -> s.base
        s is S.Pow && !depends(s.base, v) -> s.exp
        else -> null
    }

    private fun linearSubstitution(s: S, depth: Int): S? {
        val inner = innerOf(s) ?: return null
        if (inner == S.Var(v)) return null
        val a = Differentiator(v, -1).d(inner)
        if (a == Sym.ZERO || depends(a, v)) return null       // not linear
        val u = freshVar()
        val shape = Sym.replace(s, inner, S.Var(u))
        if (depends(shape, v)) return null
        val sub = Integrator(u, -1)
        val fu = sub.integrate(shape) ?: return null
        val r = mul(pow(a, Sym.MINUS_ONE), Sym.substitute(fu, u, inner))
        record(
            depth, "Substitution \\(u = ${tex(inner)}\\), so \\(d$v = \\frac{du}{${tex(a)}}\\)", s, r,
            middle = "${tex(pow(a, Sym.MINUS_ONE))} ${int(shape, u)} = ${tex(pow(a, Sym.MINUS_ONE))} ${if (fu is S.Sum) "\\left(${Sym.latex(fu, u)}\\right)" else Sym.latex(fu, u)}"
        )
        return r
    }

    // ---------------- u-substitution ----------------

    private fun uSubstitution(s: S, depth: Int): S? {
        val factors = if (s is S.Prod) s.factors else listOf(s)
        val candidates = LinkedHashSet<S>()
        for (f in factors) {
            innerOf(f)?.let { if (it != S.Var(v) && depends(it, v)) candidates += it }
            if (f is S.Func) candidates += f                           // e.g. u = ln x in (ln x)/x
            if (f is S.Pow && f.base is S.Func) candidates += f.base   // e.g. u = sin x in sin²x·cos x
        }
        for (g in candidates) {
            val dg = Differentiator(v, -1).d(g)
            if (dg == Sym.ZERO) continue
            val quotient = mul(s, pow(dg, Sym.MINUS_ONE))
            val u = freshVar()
            val inU = Sym.replace(quotient, g, S.Var(u))
            if (depends(inU, v)) continue
            val sub = Integrator(u, -1)
            val fu = sub.integrate(inU) ?: continue
            val r = Sym.substitute(fu, u, g)
            record(
                depth, "u-substitution: \\(u = ${tex(g)}\\), \\(du = ${bracketed(dg)}\\,d$v\\)", s, r,
                middle = "${int(inU, u)} = ${Sym.latex(fu, u)}",
                note = "Then put \\(u = ${tex(g)}\\) back in."
            )
            return r
        }
        return null
    }

    private fun differential(s: S) = if (s == Sym.ONE) "d$v" else "${bracketed(s)}\\,d$v"

    private fun bracketed(s: S) = if (s is S.Sum) "\\left(${tex(s)}\\right)" else tex(s)

    // ---------------- integration by parts ----------------

    private fun polyDegree(f: S): Int? = when {
        f == S.Var(v) -> 1
        f is S.Pow && f.base == S.Var(v) && f.exp is S.Num && (f.exp as S.Num).v.isInteger &&
            (f.exp as S.Num).v.sign > 0 && (f.exp as S.Num).v.num.toInt() <= 6 -> (f.exp as S.Num).v.num.toInt()
        else -> null
    }

    private fun byParts(s: S, depth: Int): S? {
        val factors = if (s is S.Prod) s.factors else listOf(s)
        if (factors.size != 2) return null
        val (a, b) = factors

        // x^n · (e^{…}, sin, cos): u = x^n
        for ((p, other) in listOf(a to b, b to a)) {
            if (polyDegree(p) == null) continue
            val ok = (other is S.Pow && other.base == Sym.E) || (other is S.Func && (other.name == "sin" || other.name == "cos"))
            if (!ok) continue
            return parts(s, u = p, dv = other, depth = depth)
        }
        // ln(…) · x^n: u = ln
        for ((l, p) in listOf(a to b, b to a)) {
            if (!(l is S.Func && l.name == "ln")) continue
            val isPower = p == S.Var(v) || (p is S.Pow && p.base == S.Var(v) && !depends(p.exp, v) && p.exp != Sym.MINUS_ONE)
            if (!isPower) continue
            return parts(s, u = l, dv = p, depth = depth)
        }
        return null
    }

    private fun parts(s: S, u: S, dv: S, depth: Int): S? {
        val vPart = Integrator(v, -1).integrate(dv) ?: return null
        val du = Differentiator(v, -1).d(u)
        val remaining = mul(vPart, du)
        val remainingIntegral = integrate(remaining, depth + 1) ?: return null
        val r = add(mul(u, vPart), neg(remainingIntegral))
        record(
            depth, "Integration by parts: \\(\\int u\\,dv = uv - \\int v\\,du\\)", s, r,
            middle = "${tex(mul(u, vPart))} - ${int(remaining)}",
            note = "\\(u = ${tex(u)}\\), \\(dv = ${differential(dv)}\\), so \\(du = ${differential(du)}\\) and \\(v = ${tex(vPart)}\\)"
        )
        return r
    }

    private fun freshVar() = if (v == 'u') 'w' else 'u'
}
