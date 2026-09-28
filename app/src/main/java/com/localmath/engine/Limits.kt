package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.depends
import com.localmath.engine.Sym.mul
import com.localmath.engine.Sym.pow

/** The value of a limit (or what's blocking it). */
sealed class LV {
    data class Fin(val s: S) : LV()
    data class Inf(val sign: Int) : LV()
    /** Keeps oscillating between finite bounds, like sin x as x → ∞. */
    object Bounded : LV()
    /** 0/0, ∞/∞, 0·∞, ∞ − ∞ or a power form: needs algebra first. */
    data class Indet(val form: String) : LV()
    data class DNE(val why: String) : LV()
    /** A numerical estimate, when no exact rule applies. */
    data class Approx(val value: Double) : LV()
}

/**
 * Limit of expressions in [v] as v → [a] (finite) or ±∞ ([toInf] = ±1).
 * [side] is +1 (from the right), −1 (from the left) or 0 (only meaningful at ±∞).
 */
class LimitEngine(
    private val v: Char,
    private val a: S?,
    private val toInf: Int,
    private val side: Int,
    private val steps: MutableList<Step>
) {
    private var squeezeUsed = false
    private val ad: Double = a?.let { Sym.eval(it, emptyMap()) } ?: 0.0

    private fun tex(s: S) = Sym.latex(s, v)
    private val target: String = when {
        toInf > 0 -> "\\infty"
        toInf < 0 -> "-\\infty"
        else -> Sym.latex(a!!) + when (side) { 1 -> "^{+}"; -1 -> "^{-}"; else -> "" }
    }
    fun lim(s: S) = "\\lim_{$v \\to $target} ${if (s is S.Sum) "\\left(${tex(s)}\\right)" else tex(s)}"

    // ---------------- numbers near the target ----------------

    /** x values approaching the target from our side: step k = 1, 2, 3 … gets closer. */
    fun point(k: Int): Double = when {
        toInf != 0 -> toInf * Math.pow(10.0, k.toDouble())
        else -> ad + side * Math.pow(10.0, -k.toDouble())
    }

    private fun near(s: S, k: Int) = Sym.eval(s, mapOf(v to point(k)))

    /** Sign of s just next to the target (0 if it can't tell). */
    private fun signNear(s: S): Int {
        val y = near(s, if (toInf != 0) 6 else 6)
        return if (y.isNaN()) 0 else if (y > 0) 1 else if (y < 0) -1 else 0
    }

    /** Best numerical guess at the limit, used as a cross-check and as a last resort. */
    fun numeric(s: S): LV? {
        val ks = if (toInf != 0) (2..9).toList() else (2..7).toList()
        val ys = ks.map { near(s, it) }
        val good = ys.filter { it.isFinite() }
        if (good.size < 3) {
            val last = ys.lastOrNull()
            if (last != null && last.isInfinite()) return LV.Inf(if (last > 0) 1 else -1)
            return null
        }
        val tail = good.takeLast(3)
        if (tail.all { Math.abs(it) > 1e6 } && Math.abs(tail[2]) > Math.abs(tail[0]) && tail.all { Math.signum(it) == Math.signum(tail[0]) }) {
            return LV.Inf(Math.signum(tail[2]).toInt())
        }
        val d1 = Math.abs(tail[1] - tail[0])
        val d2 = Math.abs(tail[2] - tail[1])
        return if (d2 <= d1 + 1e-12 && d2 < 1e-3 * Math.max(1.0, Math.abs(tail[2]))) LV.Approx(tail[2]) else null
    }

    /** Does the function actually head towards [result]? (guards against rule mistakes) */
    fun agrees(s: S, result: LV): Boolean {
        val ks = if (toInf != 0) listOf(3, 5, 7, 9) else listOf(2, 3, 4, 5)
        val ys = ks.map { near(s, it) }
        return when (result) {
            is LV.Fin -> {
                val L = Sym.eval(result.s, emptyMap())
                if (!L.isFinite()) return true
                val dist = ys.filter { it.isFinite() }.map { Math.abs(it - L) }
                if (dist.size < 2) return true
                val tol = 1e-3 * Math.max(1.0, Math.abs(L))
                dist.last() < tol || (dist.last() < 0.2 * Math.max(1.0, Math.abs(L)) && dist.zipWithNext().all { (p, q) -> q <= p * 1.0001 })
            }
            is LV.Inf -> {
                val f = ys.filter { !it.isNaN() }
                f.size < 2 || (Math.signum(f.last()).toInt() == result.sign && Math.abs(f.last()) >= Math.abs(f.first()))
            }
            else -> true
        }
    }

    // ---------------- limit arithmetic ----------------

    private fun isZero(s: S) = s == Sym.ZERO || Math.abs(Sym.eval(s, emptyMap())) < 1e-300

    fun arith(s: S): LV = when (s) {
        is S.Num, is S.Const -> LV.Fin(s)
        is S.Var -> when {
            s.name != v -> LV.Fin(s)
            toInf != 0 -> LV.Inf(toInf)
            else -> LV.Fin(a!!)
        }
        is S.Sum -> sumOf(s.terms.map { arith(it) })
        is S.Prod -> productOf(s, s.factors.map { arith(it) })
        is S.Pow -> powerOf(s)
        is S.Func -> functionOf(s)
    }

    private fun sumOf(parts: List<LV>): LV {
        parts.firstOrNull { it is LV.DNE }?.let { return it }
        parts.firstOrNull { it is LV.Indet }?.let { return it }
        val infs = parts.filterIsInstance<LV.Inf>().map { it.sign }.toSet()
        if (infs.size == 2) return LV.Indet("\\infty - \\infty")
        if (infs.size == 1) return LV.Inf(infs.first())
        if (parts.any { it == LV.Bounded }) return LV.Bounded
        return LV.Fin(add(parts.map { (it as LV.Fin).s }))
    }

    private fun productOf(s: S, parts: List<LV>): LV {
        parts.firstOrNull { it is LV.DNE }?.let { return it }
        parts.firstOrNull { it is LV.Indet }?.let { return it }
        val fins = parts.filterIsInstance<LV.Fin>()
        val zeros = fins.filter { isZero(it.s) }
        val infs = parts.filterIsInstance<LV.Inf>()
        val bounded = parts.count { it == LV.Bounded }
        if (zeros.isNotEmpty() && infs.isNotEmpty()) return LV.Indet("0 \\cdot \\infty")
        if (bounded > 0) {
            return when {
                zeros.isNotEmpty() -> { squeezeUsed = true; LV.Fin(Sym.ZERO) }
                infs.isNotEmpty() -> LV.DNE("it keeps oscillating with growing size")
                else -> LV.Bounded
            }
        }
        if (infs.isNotEmpty()) {
            val finiteSign = Math.signum(Sym.eval(mul(fins.map { it.s }), emptyMap())).toInt()
            val sign = infs.fold(finiteSign) { acc, i -> acc * i.sign }
            return if (sign == 0) LV.Indet("0 \\cdot \\infty") else LV.Inf(sign)
        }
        return LV.Fin(mul(fins.map { it.s }))
    }

    private fun powerOf(s: S.Pow): LV {
        val b = s.base
        val e = s.exp
        val bv = depends(b, v)
        val ev = depends(e, v)
        if (!ev) {
            val lb = arith(b)
            val n = Sym.eval(e, emptyMap())
            return when (lb) {
                is LV.DNE, is LV.Indet -> lb
                is LV.Fin -> when {
                    isZero(lb.s) && n > 0 -> LV.Fin(Sym.ZERO)
                    isZero(lb.s) -> blowUp(s)
                    else -> {
                        val r = pow(lb.s, e)
                        if (Sym.eval(r, emptyMap()).isFinite()) LV.Fin(r) else LV.DNE("it isn't defined near there")
                    }
                }
                is LV.Inf -> when {
                    n < 0 -> LV.Fin(Sym.ZERO)
                    lb.sign > 0 -> LV.Inf(1)
                    n == Math.rint(n) -> LV.Inf(if (n.toLong() % 2 == 0L) 1 else -1)
                    else -> LV.DNE("it isn't defined for negative values")
                }
                LV.Bounded -> if (n > 0) LV.Bounded else LV.DNE("it keeps oscillating")
                is LV.Approx -> lb
            }
        }
        if (!bv) {
            val B = Sym.eval(b, emptyMap())
            return when (val le = arith(e)) {
                is LV.DNE, is LV.Indet -> le
                is LV.Fin -> LV.Fin(pow(b, le.s))
                is LV.Inf -> when {
                    B == 1.0 -> LV.Fin(Sym.ONE)
                    B == -1.0 -> LV.Bounded                               // (−1)ⁿ keeps flipping
                    B < -1.0 -> LV.DNE("the terms flip sign and grow")
                    B < 0.0 -> LV.Fin(Sym.ZERO)
                    (B > 1) == (le.sign > 0) -> LV.Inf(1)
                    else -> LV.Fin(Sym.ZERO)
                }
                LV.Bounded -> if (B > 0) LV.Bounded else LV.DNE("it isn't defined")
                is LV.Approx -> le
            }
        }
        // f^g with both varying: look at g·ln f.
        return when (val li = arith(mul(e, Sym.func("ln", b)))) {
            is LV.Fin -> LV.Fin(pow(Sym.E, li.s))
            is LV.Inf -> if (li.sign > 0) LV.Inf(1) else LV.Fin(Sym.ZERO)
            is LV.Indet -> LV.Indet("power")
            else -> li
        }
    }

    /** Something heads to a nonzero number divided by something heading to 0. */
    private fun blowUp(s: S): LV {
        val sg = signNear(s)
        if (side == 0 && toInf == 0) return LV.DNE("it blows up")   // two-sided handled by the caller
        return if (sg == 0) LV.DNE("it isn't defined near there") else LV.Inf(sg)
    }

    private fun functionOf(s: S.Func): LV {
        val la = arith(s.arg)
        return when (la) {
            is LV.DNE, is LV.Indet -> la
            is LV.Fin -> {
                val r = try { Sym.func(s.name, la.s) } catch (_: MathError) { null }
                if (r != null && Sym.eval(r, emptyMap()).isFinite()) LV.Fin(r)
                else if ((s.name == "ln" || s.name == "log") && isZero(la.s)) {
                    // ln u with u → 0: −∞ if u stays positive
                    if (signNear(s.arg) > 0 || (s.arg is S.Func && (s.arg as S.Func).name == "abs")) LV.Inf(-1)
                    else LV.DNE("ln isn't defined for negative numbers")
                } else {
                    // e.g. tan(π/2): look at the values right next to it
                    val y = near(s, 6)
                    if (y.isFinite() && Math.abs(y) > 1e4) LV.Inf(if (y > 0) 1 else -1)
                    else LV.DNE("${s.name} isn't defined there")
                }
            }
            is LV.Inf -> when (s.name) {
                "sin", "cos" -> LV.Bounded
                "tan" -> LV.DNE("tan keeps repeating and blowing up")
                "ln", "log", "arcosh", "arsinh" -> if (la.sign > 0 || s.name == "arsinh") LV.Inf(la.sign) else LV.DNE("${s.name} isn't defined for large negative numbers")
                "abs", "cosh" -> LV.Inf(1)
                "sinh" -> LV.Inf(la.sign)
                "arctan" -> LV.Fin(mul(Sym.num(la.sign.toLong(), 2), Sym.PI))
                "tanh" -> LV.Fin(Sym.num(la.sign.toLong()))
                else -> LV.DNE("${s.name} isn't defined there")
            }
            LV.Bounded -> when (s.name) {
                "sin", "cos", "arctan", "tanh", "abs" -> LV.Bounded
                else -> LV.DNE("it keeps oscillating")
            }
            is LV.Approx -> la
        }
    }

    // ---------------- solving, with steps ----------------

    private val seen = mutableSetOf<S>()

    fun solve(f0: S, depth: Int = 0): LV {
        if (depth > 7 || f0 in seen) return fallback(f0)
        seen += f0
        val f = if (depth == 0) removeAbs(f0) else f0
        var r = arith(f)
        if (r == LV.Bounded) r = LV.DNE("it keeps oscillating and never settles on one value")
        if (r !is LV.Indet) {
            describeDirect(f, r, depth)
            return r
        }
        var form = r.form
        if (form == "0 \\cdot \\infty") {
            val (n, d) = split(f)
            if (depends(d, v)) {
                val ln = arith(n)
                val ld = arith(d)
                if (ln is LV.Fin && isZero(ln.s) && ld is LV.Fin && isZero(ld.s)) form = "0/0"
                else if (ln is LV.Inf && ld is LV.Inf) form = "inf/inf"
            }
        }
        steps += Step("Substituting gives the indeterminate form \\(${formTex(form)}\\)", lim(f),
            "That doesn't tell us the answer yet, so rewrite the expression first.")
        return rewrite(f, r.form, depth)
    }

    /** Next to the target, |u| is just u or −u. */
    private fun removeAbs(f: S): S {
        val found = mutableListOf<Pair<S, S>>()
        fun walk(s: S) {
            when (s) {
                is S.Func -> {
                    if (s.name == "abs" && depends(s.arg, v)) {
                        val sg = signNear(s.arg)
                        if (sg != 0) found += s to (if (sg > 0) s.arg else Sym.neg(s.arg))
                    }
                    walk(s.arg)
                }
                is S.Pow -> { walk(s.base); walk(s.exp) }
                is S.Sum -> s.terms.forEach { walk(it) }
                is S.Prod -> s.factors.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(f)
        if (found.isEmpty()) return f
        var g = f
        for ((absPart, plain) in found) g = Sym.replace(g, absPart, plain)
        val where = if (toInf != 0) "For large \\(|$v|\\)" else "Just ${if (side > 0) "right" else "left"} of \\($v = ${Sym.latex(a!!)}\\)"
        steps += Step("Remove the absolute value", found.joinToString(",\\quad ") { (p, q) -> "${tex(p)} = ${tex(q)}" },
            "$where the inside has a fixed sign.")
        return g
    }

    private fun formTex(form: String) = when (form) {
        "0/0" -> "\\frac{0}{0}"
        "inf/inf" -> "\\frac{\\infty}{\\infty}"
        "power" -> "1^{\\infty},\\ 0^{0}\\text{ or }\\infty^{0}"
        else -> form
    }

    private fun describeDirect(f: S, r: LV, depth: Int) {
        val value = show(r)
        val (title, note) = when {
            r is LV.Fin && squeezeUsed -> "Squeeze theorem" to "A bounded factor (like \\(\\sin\\) or \\(\\cos\\), always between −1 and 1) times something that goes to 0 goes to 0."
            r is LV.Fin && toInf == 0 -> "Substitute \\($v = ${Sym.latex(a!!)}\\)" to (if (depth == 0) "The function is continuous there, so we can plug the value in." else null)
            r is LV.Fin -> "Let \\($v\\) grow without bound" to "Look at what each part does as \\($v \\to $target\\)."
            r is LV.Inf && toInf == 0 -> "The function grows without bound" to "The bottom goes to 0 while the top doesn't, so the size blows up; the sign comes from the side we approach from."
            r is LV.Inf -> "Let \\($v\\) grow without bound" to "Look at which part grows fastest."
            else -> "The limit doesn't exist" to (r as? LV.DNE)?.why?.let { "Because $it." }
        }
        steps += Step(title, "${lim(f)} = $value", note)
    }

    fun show(r: LV): String = when (r) {
        is LV.Fin -> Sym.latex(r.s)
        is LV.Inf -> if (r.sign > 0) "\\infty" else "-\\infty"
        LV.Bounded -> "\\text{does not exist}"
        is LV.Indet -> "\\text{?}"
        is LV.DNE -> "\\text{does not exist}"
        is LV.Approx -> Tex.decimal(r.value)
    }

    private fun rewrite(f: S, form: String, depth: Int): LV {
        // 1) Fractions of polynomials.
        RatFunc.fromS(f, v)?.let { rf -> rational(rf, depth)?.let { return it } }
        // 1b) At ±∞, algebraic expressions (with roots) behave like their highest powers.
        dominant(f)?.let { return it }

        // 2) Powers: take logarithms.
        if (form == "power" && f is S.Pow) {
            val inner = mul(f.exp, Sym.func("ln", f.base))
            steps += Step("Use logarithms: \\(y = ${tex(f)}\\) means \\(\\ln y = ${tex(inner)}\\)", lim(inner),
                "Find the limit of \\(\\ln y\\), then undo the log with \\(e^{\\,\\cdot}\\).")
            return when (val li = solve(inner, depth + 1)) {
                is LV.Fin -> LV.Fin(pow(Sym.E, li.s)).also {
                    steps += Step("Undo the logarithm", "${lim(f)} = e^{${Sym.latex(li.s)}} = ${Sym.latex((it as LV.Fin).s)}")
                }
                is LV.Inf -> (if (li.sign > 0) LV.Inf(1) else LV.Fin(Sym.ZERO)).also {
                    steps += Step("Undo the logarithm", "${lim(f)} = ${show(it)}")
                }
                else -> li
            }
        }

        // 3) ∞ − ∞ with a square root: multiply by the conjugate.
        if (form == "\\infty - \\infty" && f is S.Sum && f.terms.size == 2) {
            val (p, q) = f.terms
            if (hasRoot(p) || hasRoot(q)) {
                val num = add(pow(p, Sym.num(2)), Sym.neg(pow(q, Sym.num(2))))
                val den = add(p, Sym.neg(q))
                steps += Step("Multiply top and bottom by the conjugate \\(${tex(den)}\\)",
                    "${lim(f)} = ${lim2(num, den)}", "Using \\((A + B)(A - B) = A^2 - B^2\\) to clear the square root.")
                return quotient(num, den, depth)
            }
        }

        // 4) A fraction N/D (0/0 or ∞/∞) or a product 0·∞ rewritten as a fraction.
        val (n, d) = split(f)
        if (!d.isConstantIn(v)) return quotient(n, d, depth)
        if (form == "0 \\cdot \\infty" && f is S.Prod) {
            // Move one part to the bottom as 1/part: exponentials first (x·e^{−x} = x / e^{x}),
            // then plain powers of x (x·ln x = ln x / (1/x)), otherwise the part that goes to 0.
            val parts = f.factors.filter { depends(it, v) }
            val constant = f.factors.filter { !depends(it, v) }
            val exps = parts.filter { it is S.Pow && !depends(it.base, v) }
            val powers = parts.filter { it == S.Var(v) || (it is S.Pow && it.base == S.Var(v) && it.exp is S.Num) }
            val zeros = parts.filter { val l = arith(it); l is LV.Fin && isZero(l.s) }
            val move = when {
                exps.isNotEmpty() && exps.size < parts.size -> exps
                powers.isNotEmpty() && powers.size < parts.size -> powers
                else -> zeros
            }
            val rest = parts - move.toSet()
            if (move.isNotEmpty() && rest.isNotEmpty()) {
                val top = mul(rest + constant)
                val bottom = pow(mul(move), Sym.MINUS_ONE)
                steps += Step("Rewrite the \\(0 \\cdot \\infty\\) form as a fraction", "${lim(f)} = ${lim2(top, bottom)}")
                return quotient(top, bottom, depth)
            }
        }
        return fallback(f)
    }

    private fun S.isConstantIn(x: Char) = !depends(this, x)

    /** Leading behaviour c·x^d of an algebraic expression as x → +∞ (null if it can't tell). */
    private data class Lead(val coef: S, val deg: Rational)

    private fun lead(s: S): Lead? = when {
        !depends(s, v) -> Lead(s, Rational.ZERO)
        s == S.Var(v) -> Lead(Sym.ONE, Rational.ONE)
        s is S.Sum -> {
            val ls = s.terms.map { lead(it) ?: return null }
            val top = ls.maxOf { it.deg }
            val c = add(ls.filter { it.deg == top }.map { it.coef })
            if (c == Sym.ZERO || Math.abs(Sym.eval(c, emptyMap())) < 1e-12) null else Lead(c, top)
        }
        s is S.Prod -> {
            val ls = s.factors.map { lead(it) ?: return null }
            Lead(mul(ls.map { it.coef }), ls.fold(Rational.ZERO) { acc, l -> acc + l.deg })
        }
        s is S.Pow && s.exp is S.Num -> {
            val r = (s.exp as S.Num).v
            val lb = lead(s.base) ?: return null
            if (!r.isInteger && Sym.eval(lb.coef, emptyMap()) <= 0) null
            else Lead(pow(lb.coef, s.exp), lb.deg * r)
        }
        else -> null
    }

    private fun dominant(f: S): LV? {
        if (toInf == 0) return null
        val g = if (toInf > 0) f else Sym.substitute(f, v, Sym.neg(S.Var(v)))
        val l = lead(g) ?: return null
        if (!Sym.variables(l.coef).isEmpty()) return null
        val c = Sym.eval(l.coef, emptyMap())
        val result: LV = when {
            l.deg.sign > 0 -> LV.Inf(if (c > 0) 1 else -1)
            l.deg.isZero -> LV.Fin(l.coef)
            else -> LV.Fin(Sym.ZERO)
        }
        val xd = if (l.deg.isOne) "$v" else "$v^{${Tex.rational(l.deg)}}"
        steps += Step("Keep only the highest power in each part",
            "${lim(f)} = ${lim(mul(l.coef, pow(S.Var(v), S.Num(l.deg))))} = ${show(result)}",
            "For large \\($v\\), lower powers are tiny compared with the highest one, so the expression behaves like \\(${Sym.latex(l.coef)}\\,${if (l.deg.isZero) "" else xd}\\)." +
                if (toInf < 0) " (Replace \\($v\\) by \\(-$v\\) to handle \\(-\\infty\\).)" else "")
        return result
    }

    private fun hasRoot(s: S): Boolean = when (s) {
        is S.Pow -> (s.exp is S.Num && !(s.exp as S.Num).v.isInteger) || hasRoot(s.base)
        is S.Prod -> s.factors.any { hasRoot(it) }
        is S.Sum -> s.terms.any { hasRoot(it) }
        else -> false
    }

    /** Numerator and denominator (factors with negative powers go to the bottom). */
    private fun split(f: S): Pair<S, S> {
        val factors = if (f is S.Prod) f.factors else listOf(f)
        val top = mutableListOf<S>()
        val bottom = mutableListOf<S>()
        for (x in factors) {
            if (x is S.Pow && x.exp is S.Num && (x.exp as S.Num).v.sign < 0) bottom += pow(x.base, Sym.num(-(x.exp as S.Num).v))
            else top += x
        }
        return mul(top) to mul(bottom)
    }

    private fun lim2(n: S, d: S) = "\\lim_{$v \\to $target} \\frac{${tex(n)}}{${tex(d)}}"

    private fun quotient(n: S, d: S, depth: Int): LV {
        if (depth < 7) dominant(Sym.div(n, d))?.let { return it }
        val ln = arith(n)
        val ld = arith(d)
        val zeroOverZero = ln is LV.Fin && isZero(ln.s) && ld is LV.Fin && isZero(ld.s)
        val infOverInf = ln is LV.Inf && ld is LV.Inf
        if (zeroOverZero || infOverInf) {
            val dn = Differentiator(v, -1).d(n)
            val dd = Differentiator(v, -1).d(d)
            if (dd == Sym.ZERO) return fallback(Sym.div(n, d))
            val next = Sym.div(dn, dd)
            steps += Step(
                "L'Hôpital's rule (\\(${if (zeroOverZero) "\\frac{0}{0}" else "\\frac{\\infty}{\\infty}"}\\) form): differentiate top and bottom",
                "${lim2(n, d)} = ${lim2(dn, dd)}",
                "Top: \\(\\frac{d}{d$v}\\left[${tex(n)}\\right] = ${tex(dn)}\\).  Bottom: \\(\\frac{d}{d$v}\\left[${tex(d)}\\right] = ${tex(dd)}\\)."
            )
            return solve(next, depth + 1)
        }
        return solve(Sym.div(n, d), depth + 1)
    }

    private fun rational(rf: RatFunc, depth: Int): LV? {
        val (reduced, cancelled) = rf.reduced()
        if (toInf != 0) {
            val n = reduced.num
            val d = reduced.den
            if (d.isConstant) return null
            val dn = n.degree
            val dd = d.degree
            val ratio = n[dn] / d[dd]
            val xm = if (dd == 1) "$v" else "$v^{$dd}"
            val result: LV = when {
                dn < dd -> LV.Fin(Sym.ZERO)
                dn == dd -> LV.Fin(S.Num(ratio))
                else -> {
                    val parity = if ((dn - dd) % 2 == 0) 1 else toInf
                    LV.Inf(ratio.sign * parity)
                }
            }
            val why = when {
                dn < dd -> "The bottom has the higher power, so the fraction shrinks to 0."
                dn == dd -> "Same highest power on top and bottom: the limit is the ratio of the leading coefficients, \\(${Tex.rational(n[dn])} \\div ${Tex.rational(d[dd])}\\)."
                else -> "The top has the higher power, so the fraction grows without bound."
            }
            steps += Step("Compare the highest powers (divide top and bottom by \\($xm\\))",
                "${lim2(polyS(n), polyS(d))} = ${show(result)}", why)
            return result
        }
        if (cancelled == null) {
            if (rf.den.isConstant) return null
            val combined = Sym.div(polyS(reduced.num), polyS(reduced.den))
            steps += Step("Combine into a single fraction", "${lim2(polyS(reduced.num), polyS(reduced.den))}")
            val r = arith(combined)
            return if (r is LV.Indet) null else r.also { describeDirect(combined, it, depth + 1) }
        }
        val g = cancelled.toPrimitiveIntegers().first
        val simpler = Sym.div(polyS(reduced.num), polyS(reduced.den))
        steps += Step("Factor and cancel the common factor \\(${g.format(v)}\\)",
            "${lim2(polyS(rf.num), polyS(rf.den))} = ${lim(simpler)}",
            "It's allowed because \\($v\\) only gets close to ${Tex.inline(Sym.latex(a!!))}, it never equals it.")
        return solve(simpler, depth + 1)
    }

    fun polyS(p: Polynomial): S = add(p.terms.map { (k, c) -> mul(S.Num(c), pow(S.Var(v), Sym.num(k.toLong()))) })

    private fun fallback(f: S): LV {
        val n = numeric(f)
        steps += Step("Estimate numerically",
            "${lim(f)} ${if (n is LV.Approx) "\\approx" else "="} ${n?.let { show(it) } ?: "\\text{?}"}",
            "No algebra rule in LocalMath fits this one, so it evaluates the function closer and closer to the target.")
        return n ?: LV.DNE("the values don't settle down")
    }
}

object Limits {

    fun solve(e: Expr.Limit): Solution {
        val f = Sym.from(e.body)
        val vars = Sym.variables(f)
        val v = e.variable ?: when {
            vars.isEmpty() -> 'x'
            vars.size == 1 -> vars.first()
            'x' in vars -> 'x'
            else -> throw MathError("Say which letter moves: lim(f, x → a)")
        }
        val toInf = when {
            e.to is Expr.Inf -> 1
            e.to is Expr.Neg && e.to.inner is Expr.Inf -> -1
            else -> 0
        }
        val a = if (toInf == 0) Sym.from(e.to) else null
        if (a != null && Sym.variables(a).isNotEmpty()) throw MathError("The value x approaches must be a number")
        if (a != null && !Sym.eval(a, emptyMap()).isFinite()) throw MathError("The value x approaches must be a real number")
        if (toInf != 0 && e.side != 0) throw MathError("One-sided limits only make sense at a number, not at ∞")

        val steps = mutableListOf(Step("Start with", Tex.expr(Expr.Limit(e.body, v, e.to, e.side))))
        val result: LV
        if (toInf != 0 || e.side != 0) {
            val engine = LimitEngine(v, a, toInf, if (toInf != 0) 0 else e.side, steps)
            result = checked(engine, f, engine.solve(f), steps)
        } else {
            // Two-sided: work it out from the right, then confirm from the left.
            val right = LimitEngine(v, a, 0, 1, steps)
            val r = checked(right, f, right.solve(f), steps)
            val leftSteps = mutableListOf<Step>()
            val left = LimitEngine(v, a, 0, -1, leftSteps)
            val l = checked(left, f, left.solve(f), leftSteps)
            // If nothing depended on the side, show the steps with a plain "x → a".
            if (same(l, r) && r is LV.Fin && steps.none { it.title.startsWith("Remove the absolute") }) {
                val plus = "\\to ${Sym.latex(a!!)}^{+}"
                val plain = "\\to ${Sym.latex(a)}"
                for (i in steps.indices) steps[i] = steps[i].let { st ->
                    Step(st.title.replace(plus, plain), st.math.replace(plus, plain), st.note?.replace(plus, plain))
                }
            }
            result = if (l is LV.DNE && r is LV.DNE) {
                steps += Step("From the left it doesn't settle either", "${left.lim(f)} = \\text{does not exist}")
                r
            } else if (same(l, r)) {
                if (r is LV.Inf || r is LV.DNE) steps += Step("Check from the left too", "${left.lim(f)} = ${left.show(l)}", "Both sides agree.")
                r
            } else {
                steps += Step("Check from the left", "${left.lim(f)} = ${left.show(l)}")
                steps += Step("The two sides disagree", "${left.show(l)} \\neq ${right.show(r)}",
                    "A two-sided limit only exists when the left and right limits are equal.")
                LV.DNE("the left and right limits differ")
            }
        }

        val answer = when (result) {
            is LV.Fin -> Sym.latex(result.s)
            is LV.Approx -> "\\approx ${Tex.decimal(result.value)}"
            is LV.Inf -> if (result.sign > 0) "\\infty" else "-\\infty"
            else -> "\\text{Does not exist}"
        }
        val approx = (result as? LV.Fin)?.let { r ->
            if (r.s is S.Num && (r.s as S.Num).v.isInteger) null
            else Sym.eval(r.s, emptyMap()).takeIf { it.isFinite() }?.let { "\\approx ${Tex.decimal(it)}" }
        }
        return Solution("Limit", steps, answer, approx, graph(f, v, a, toInf, result))
    }

    /** Numerically double-checks a symbolic answer; falls back to an estimate if they disagree. */
    private fun checked(engine: LimitEngine, f: S, r: LV, steps: MutableList<Step>): LV {
        if (engine.agrees(f, r)) return r
        val n = engine.numeric(f)
        steps += Step("Numerical check", "${engine.lim(f)} ${if (n is LV.Approx) "\\approx" else "="} ${n?.let { engine.show(it) } ?: "\\text{?}"}",
            "The exact rules above didn't match the numbers, so this uses a numerical estimate instead.")
        return n ?: LV.DNE("the values don't settle down")
    }

    private fun same(a: LV, b: LV): Boolean = when {
        a is LV.Fin && b is LV.Fin && a.s == b.s -> true
        a is LV.Fin && b is LV.Fin -> Math.abs(Sym.eval(a.s, emptyMap()) - Sym.eval(b.s, emptyMap())) < 1e-9
        a is LV.Inf && b is LV.Inf -> a.sign == b.sign
        (a is LV.Fin || a is LV.Approx) && (b is LV.Fin || b is LV.Approx) -> Math.abs(value(a) - value(b)) < 1e-4 * Math.max(1.0, Math.abs(value(a)))
        else -> false
    }

    private fun value(r: LV) = when (r) {
        is LV.Fin -> Sym.eval(r.s, emptyMap())
        is LV.Approx -> r.value
        else -> Double.NaN
    }

    private fun graph(f: S, v: Char, a: S?, toInf: Int, r: LV): Graph? {
        if (!Sym.variables(f).all { it == v }) return null
        val curves = mutableListOf(Graphs.curve(f, v))
        val points = mutableListOf<GraphPoint>()
        val L = value(r)
        if (L.isFinite()) {
            if (a != null) {
                val ad = Sym.eval(a, emptyMap())
                points += GraphPoint(ad, L, "limit " + Graphs.label(ad, L))
            } else curves += Curve("($L)", "y = " + if (r is LV.Fin) Sym.latex(r.s) else Tex.decimal(L))
        }
        val focus = when {
            a != null -> listOf(Sym.eval(a, emptyMap()))
            toInf > 0 -> listOf(0.0, 20.0)
            else -> listOf(-20.0, 0.0)
        }
        return Graphs.build(curves, points, focus)
    }
}
