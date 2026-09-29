package com.localmath.engine

import java.math.BigInteger

/**
 * Simplified symbolic expression used for calculus.
 * Always build these through [Sym] (add, mul, pow, func …) so they stay in canonical form:
 * sums are flat with like terms combined, products are flat with like bases merged,
 * and the numeric coefficient of a product is always its first factor.
 */
sealed class S {
    data class Num(val v: Rational) : S()
    data class Var(val name: Char) : S()
    data class Const(val name: Char) : S()              // 'e' or 'π'
    data class Func(val name: String, val arg: S) : S()  // sin cos tan ln log abs
    data class Pow(val base: S, val exp: S) : S()
    data class Sum(val terms: List<S>) : S()
    data class Prod(val factors: List<S>) : S()
}

object Sym {
    val ZERO = S.Num(Rational.ZERO)
    val ONE = S.Num(Rational.ONE)
    val MINUS_ONE = S.Num(Rational.MINUS_ONE)
    val HALF = S.Num(Rational.of(1, 2))
    val E = S.Const('e')
    val PI = S.Const('π')

    fun num(r: Rational) = S.Num(r)
    fun num(n: Long, d: Long = 1) = S.Num(Rational.of(n, d))

    private fun key(s: S) = s.toString()

    // ================= building / simplifying =================

    fun add(vararg items: S) = add(items.toList())

    fun add(items: List<S>): S {
        val flat = items.flatMap { if (it is S.Sum) it.terms else listOf(it) }
        var constant = Rational.ZERO
        val groups = LinkedHashMap<String, Pair<S, Rational>>()
        for (t in flat) {
            if (t is S.Num) { constant += t.v; continue }
            val (c, rest) = splitCoef(t)
            val k = key(rest)
            groups[k] = rest to ((groups[k]?.second ?: Rational.ZERO) + c)
        }
        val out = groups.values.filter { !it.second.isZero }.map { (rest, c) -> withCoef(c, rest) }.toMutableList()
        if (!constant.isZero) out += S.Num(constant)
        return when (out.size) {
            0 -> ZERO
            1 -> out[0]
            else -> S.Sum(out.sortedBy { key(it) })
        }
    }

    fun neg(a: S) = mul(MINUS_ONE, a)
    fun sub(a: S, b: S) = add(a, neg(b))
    fun div(a: S, b: S) = mul(a, pow(b, MINUS_ONE))

    /** Splits "3x²" into (3, x²). */
    fun splitCoef(t: S): Pair<Rational, S> {
        if (t is S.Num) return t.v to ONE
        if (t is S.Prod && t.factors[0] is S.Num) {
            val rest = t.factors.drop(1)
            return (t.factors[0] as S.Num).v to (if (rest.size == 1) rest[0] else S.Prod(rest))
        }
        return Rational.ONE to t
    }

    private fun withCoef(c: Rational, rest: S): S = when {
        c.isOne -> rest
        rest is S.Num -> S.Num(c * rest.v)
        rest is S.Prod -> S.Prod(listOf(S.Num(c)) + rest.factors)
        else -> S.Prod(listOf(S.Num(c), rest))
    }

    fun mul(vararg items: S) = mul(items.toList())

    fun mul(items: List<S>): S = mulInternal(items, 0)

    private fun mulInternal(items: List<S>, depth: Int): S {
        var coef = Rational.ONE
        val bases = LinkedHashMap<String, Pair<S, MutableList<S>>>()
        val queue = ArrayDeque(items)
        while (queue.isNotEmpty()) {
            when (val f = queue.removeFirst()) {
                is S.Num -> coef *= f.v
                is S.Prod -> queue.addAll(f.factors)
                is S.Pow -> bases.getOrPut(key(f.base)) { f.base to mutableListOf() }.second += f.exp
                else -> bases.getOrPut(key(f)) { f to mutableListOf() }.second += ONE
            }
        }
        if (coef.isZero) return ZERO

        val factors = mutableListOf<S>()
        var needsAnotherPass = false
        for ((b, exps) in bases.values) {
            when (val p = pow(b, add(exps))) {
                is S.Num -> coef *= p.v
                is S.Prod -> { factors += p.factors; needsAnotherPass = true }
                else -> factors += p
            }
        }
        if (needsAnotherPass && depth < 3) return mulInternal(listOf(S.Num(coef)) + factors, depth + 1)
        if (coef.isZero) return ZERO

        // Distribute over a plain sum, e.g. 2x(x + 1) -> 2x² + 2x — but keep fractions like (x+1)/x² intact.
        val sumIndex = factors.indexOfFirst { it is S.Sum }
        if (sumIndex >= 0 && factors.none { isDenominator(it) }) {
            val sum = factors[sumIndex] as S.Sum
            val others = factors.filterIndexed { i, _ -> i != sumIndex } + S.Num(coef)
            val otherSums = others.filterIsInstance<S.Sum>().fold(1) { acc, s -> acc * s.terms.size }
            if (sum.terms.size * otherSums <= 48) return add(sum.terms.map { mul(others + it) })
        }

        val sorted = factors.sortedBy { key(it) }
        return when {
            sorted.isEmpty() -> S.Num(coef)
            coef.isOne && sorted.size == 1 -> sorted[0]
            coef.isOne -> S.Prod(sorted)
            else -> S.Prod(listOf(S.Num(coef)) + sorted)
        }
    }

    private fun isDenominator(f: S) = f is S.Pow && f.exp is S.Num && (f.exp as S.Num).v.sign < 0

    fun pow(b: S, e: S): S {
        if (e is S.Num) {
            val r = e.v
            if (r.isZero) return ONE
            if (r.isOne) return b
            if (b is S.Num) return numericPow(b.v, r) ?: S.Pow(b, e)
            if (b is S.Pow && r.isInteger) return pow(b.base, mul(b.exp, e))
            if (b is S.Prod && r.isInteger) return mul(b.factors.map { pow(it, e) })
        }
        if (b is S.Num && b.v.isOne) return ONE
        if (b is S.Num && b.v.isZero) return ZERO
        if (b == E && e is S.Func && e.name == "ln") return e.arg
        // e^{k ln u} = u^k
        if (b == E && e is S.Prod && e.factors.size == 2 && e.factors[0] is S.Num && (e.factors[1] as? S.Func)?.name == "ln") {
            return pow((e.factors[1] as S.Func).arg, e.factors[0])
        }
        return S.Pow(b, e)
    }

    /** Exact value of b^r where possible: integer powers, and square roots (√8 = 2√2). */
    private fun numericPow(b: Rational, r: Rational): S? {
        if (b.isZero) {
            if (r.sign < 0) throw MathError("Division by zero")
            return ZERO
        }
        if (b.isOne) return ONE
        if (r.isInteger) {
            if (r.num.abs() > BigInteger.valueOf(400)) return null
            return S.Num(b.pow(r.num.toInt()))
        }
        // Exact roots of perfect powers: 8^(1/3) = 2, (16/81)^(3/4) = 8/27.
        if (b.sign > 0 && r.den <= BigInteger.valueOf(12) && r.num.abs() < BigInteger.valueOf(40)) {
            val q = r.den.toInt()
            val top = exactRoot(b.num, q)
            val bottom = exactRoot(b.den, q)
            if (top != null && bottom != null) return S.Num(Rational.of(top, bottom).pow(r.num.toInt()))
        }
        // Odd roots of negatives: (−8)^(1/3) = −2.
        if (b.sign < 0 && r.den.testBit(0) && r.den <= BigInteger.valueOf(11) && r.num.abs() < BigInteger.valueOf(40)) {
            val q = r.den.toInt()
            val top = exactRoot(b.num.negate(), q)
            val bottom = exactRoot(b.den, q)
            if (top != null && bottom != null) return S.Num(Rational.of(top.negate(), bottom).pow(r.num.toInt()))
        }
        if (r.den == BigInteger.TWO && b.sign > 0 && r.num.abs() < BigInteger.valueOf(40)) {
            val k = r.num.toInt()                   // b^(k/2) = (b^k)^(1/2)
            val bk = b.pow(k)
            val (outside, inside) = Tex.simplifySqrt(bk.num * bk.den)
            val coef = Rational.of(outside, bk.den)
            if (inside == BigInteger.ONE) return S.Num(coef)
            val root = S.Pow(S.Num(Rational.of(inside)), HALF)
            return if (coef.isOne) root else S.Prod(listOf(S.Num(coef), root))
        }
        return null
    }

    /** The whole-number q-th root of n, or null if n isn't a perfect q-th power. */
    private fun exactRoot(n: BigInteger, q: Int): BigInteger? {
        if (n.signum() < 0 || n.bitLength() > 4000) return null
        if (n <= BigInteger.ONE) return n
        var lo = BigInteger.ONE
        var hi = BigInteger.ONE.shiftLeft(n.bitLength() / q + 1)
        while (lo <= hi) {
            val mid = (lo + hi).shiftRight(1)
            val p = mid.pow(q)
            val c = p.compareTo(n)
            if (c == 0) return mid
            if (c < 0) lo = mid + BigInteger.ONE else hi = mid - BigInteger.ONE
        }
        return null
    }

    fun func(name: String, arg: S): S {
        when (name) {
            "ln" -> {
                if (arg == ONE) return ZERO
                if (arg == E) return ONE
                if (arg is S.Pow && arg.base == E) return arg.exp
            }
            "log" -> {
                if (arg == ONE) return ZERO
                if (arg is S.Num && arg.v == Rational.of(10)) return ONE
                // log 1000 = 3, log 0.01 = −2
                if (arg is S.Num && arg.v.sign > 0) {
                    var r = arg.v
                    var k = 0L
                    val ten = Rational.of(10)
                    while (r.isInteger && r.num > java.math.BigInteger.ONE && r.num.mod(java.math.BigInteger.TEN).signum() == 0 && k < 400) { r = r / ten; k++ }
                    while (!r.isInteger && r.num == java.math.BigInteger.ONE && r.den.mod(java.math.BigInteger.TEN).signum() == 0 && k > -400) { r = r * ten; k-- }
                    if (r.isOne && k != 0L) return num(k)
                }
                if (arg is S.Pow && arg.base is S.Num && (arg.base as S.Num).v == Rational.of(10)) return arg.exp
            }
            "sin", "cos", "tan" -> exactTrig(name, arg)?.let { return it }
            "arcsin", "arccos", "arctan" -> exactInverseTrig(name, arg)?.let { return it }
            "sinh", "tanh", "arsinh", "artanh" -> if (arg == ZERO) return ZERO
            "cosh" -> if (arg == ZERO) return ONE
            "arcosh" -> if (arg == ONE) return ZERO
            "abs" -> {
                if (arg is S.Num) return S.Num(arg.v.abs())
                if (arg is S.Const || (arg is S.Pow && arg.base == E)) return arg
                if (arg is S.Prod && arg.factors[0] is S.Num) {
                    val (c, rest) = splitCoef(arg)
                    return mul(S.Num(c.abs()), func("abs", rest))
                }
            }
        }
        return S.Func(name, arg)
    }

    /** arcsin/arccos/arctan at the common exact values. */
    private fun exactInverseTrig(name: String, arg: S): S? {
        if (arg !is S.Num) return null
        val a = arg.v
        val table: Map<Rational, Rational> = when (name) {   // value -> multiple of π
            "arcsin" -> mapOf(Rational.ZERO to Rational.ZERO, Rational.of(1, 2) to Rational.of(1, 6), Rational.ONE to Rational.of(1, 2),
                Rational.of(-1, 2) to Rational.of(-1, 6), Rational.MINUS_ONE to Rational.of(-1, 2))
            "arccos" -> mapOf(Rational.ONE to Rational.ZERO, Rational.of(1, 2) to Rational.of(1, 3), Rational.ZERO to Rational.of(1, 2),
                Rational.of(-1, 2) to Rational.of(2, 3), Rational.MINUS_ONE to Rational.ONE)
            else -> mapOf(Rational.ZERO to Rational.ZERO, Rational.ONE to Rational.of(1, 4), Rational.MINUS_ONE to Rational.of(-1, 4))
        }
        val k = table[a] ?: return null
        return mul(S.Num(k), PI)
    }

    /** sin, cos, tan at multiples of π/6 and π/4 (and 0). */
    private fun exactTrig(name: String, arg: S): S? {
        val k: Rational = when {
            arg == ZERO -> Rational.ZERO
            arg == PI -> Rational.ONE
            arg is S.Prod && arg.factors.size == 2 && arg.factors[0] is S.Num && arg.factors[1] == PI -> (arg.factors[0] as S.Num).v
            else -> return null
        }
        val degrees = k * Rational.of(180)
        if (!degrees.isInteger) return null
        val d = Math.floorMod(degrees.num.toLong(), 360L).toInt()
        fun sinDeg(x: Int): S? {
            val a = Math.floorMod(x, 360)
            val sign = if (a > 180) -1 else 1
            val ref = if (a > 180) a - 180 else a
            val r = if (ref > 90) 180 - ref else ref
            val v: S = when (r) {
                0 -> ZERO
                30 -> HALF
                45 -> S.Prod(listOf(HALF, S.Pow(num(2), HALF)))
                60 -> S.Prod(listOf(HALF, S.Pow(num(3), HALF)))
                90 -> ONE
                else -> return null
            }
            return if (sign < 0) neg(v) else v
        }
        return when (name) {
            "sin" -> sinDeg(d)
            "cos" -> sinDeg(d + 90)
            else -> {
                val s = sinDeg(d) ?: return null
                val c = sinDeg(d + 90) ?: return null
                if (c == ZERO) throw MathError("tan is undefined there")
                div(s, c)
            }
        }
    }

    // ================= queries =================

    fun depends(s: S, v: Char): Boolean = when (s) {
        is S.Num, is S.Const -> false
        is S.Var -> s.name == v
        is S.Func -> depends(s.arg, v)
        is S.Pow -> depends(s.base, v) || depends(s.exp, v)
        is S.Sum -> s.terms.any { depends(it, v) }
        is S.Prod -> s.factors.any { depends(it, v) }
    }

    fun variables(s: S): Set<Char> = when (s) {
        is S.Num, is S.Const -> emptySet()
        is S.Var -> setOf(s.name)
        is S.Func -> variables(s.arg)
        is S.Pow -> variables(s.base) + variables(s.exp)
        is S.Sum -> s.terms.flatMap { variables(it) }.toSet()
        is S.Prod -> s.factors.flatMap { variables(it) }.toSet()
    }

    /** Replaces every occurrence of [target] (matched structurally) with [with], re-simplifying. */
    fun replace(s: S, target: S, with: S): S {
        if (s == target) return with
        return when (s) {
            is S.Num, is S.Var, is S.Const -> s
            is S.Func -> func(s.name, replace(s.arg, target, with))
            is S.Pow -> pow(replace(s.base, target, with), replace(s.exp, target, with))
            is S.Sum -> add(s.terms.map { replace(it, target, with) })
            is S.Prod -> mul(s.factors.map { replace(it, target, with) })
        }
    }

    fun substitute(s: S, v: Char, value: S) = replace(s, S.Var(v), value)

    fun eval(s: S, env: Map<Char, Double>): Double = when (s) {
        is S.Num -> s.v.toDouble()
        is S.Var -> env[s.name] ?: Double.NaN
        is S.Const -> if (s.name == 'π') Math.PI else Math.E
        is S.Func -> {
            val a = eval(s.arg, env)
            when (s.name) {
                "sin" -> Math.sin(a)
                "cos" -> Math.cos(a)
                "tan" -> Math.tan(a)
                "ln" -> if (a > 0) Math.log(a) else Double.NaN
                "log" -> if (a > 0) Math.log10(a) else Double.NaN
                "abs" -> Math.abs(a)
                "arcsin" -> Math.asin(a)
                "arccos" -> Math.acos(a)
                "arctan" -> Math.atan(a)
                "sinh" -> Math.sinh(a)
                "cosh" -> Math.cosh(a)
                "tanh" -> Math.tanh(a)
                "arsinh" -> Math.log(a + Math.sqrt(a * a + 1))
                "arcosh" -> if (a >= 1) Math.log(a + Math.sqrt(a * a - 1)) else Double.NaN
                "artanh" -> if (Math.abs(a) < 1) 0.5 * Math.log((1 + a) / (1 - a)) else Double.NaN
                else -> Double.NaN
            }
        }
        is S.Pow -> {
            val b = eval(s.base, env)
            val e = eval(s.exp, env)
            if (b < 0 && e != Math.rint(e)) {
                // Odd roots of negatives, e.g. (-8)^(1/3) = -2.
                val inv = 1 / e
                if (Math.abs(inv - Math.rint(inv)) < 1e-9 && Math.rint(inv).toLong() % 2L != 0L) -Math.pow(-b, e)
                else Double.NaN
            } else Math.pow(b, e)
        }
        is S.Sum -> s.terms.sumOf { eval(it, env) }
        is S.Prod -> s.factors.fold(1.0) { acc, f -> acc * eval(f, env) }
    }

    // ================= from the parser's AST =================

    fun from(e: Expr): S = when (e) {
        is Expr.Num -> S.Num(e.value)
        is Expr.Var -> S.Var(e.name)
        is Expr.Const -> when (e.name) {
            'π' -> PI
            'i' -> throw MathError("i (√−1) works in arithmetic and equations, but not with d/dx, ∫, lim, Σ, inequalities or formulas yet")
            else -> E
        }
        is Expr.Neg -> neg(from(e.inner))
        is Expr.Add -> add(from(e.left), from(e.right))
        is Expr.Sub -> sub(from(e.left), from(e.right))
        is Expr.Mul -> mul(from(e.left), from(e.right))
        is Expr.Div -> {
            val d = from(e.right)
            if (d == ZERO) throw MathError("Division by zero")
            div(from(e.left), d)
        }
        is Expr.Pow -> pow(from(e.base), from(e.exponent))
        is Expr.Func -> when (e.name) {
            "sqrt" -> pow(from(e.arg), HALF)
            "exp" -> pow(E, from(e.arg))
            else -> func(e.name, from(e.arg))
        }
        is Expr.Derivative, is Expr.Integral -> throw MathError("Put d/dx or ∫ at the start, on its own")
        is Expr.Inf -> throw MathError("∞ can only be used as a limit, e.g. lim(f, ∞) or Σ(f, 1, ∞)")
        is Expr.Special -> throw MathError("lim, Σ, aₙ and y' need to be on their own")
    }

    // ================= LaTeX =================

    fun latex(s: S, mainVar: Char = 'x'): String = Printer(mainVar).print(s)

    // Placeholder letters used by the differential-equation solver.
    const val C1 = '①'
    const val C2 = '②'
    const val Y1 = '′'   // y'
    const val Y2 = '″'   // y''

    fun varTex(c: Char) = when (c) {
        C1 -> "C_{1}"
        C2 -> "C_{2}"
        Y1 -> "y'"
        Y2 -> "y''"
        else -> c.toString()
    }

    /** Terms of a sum in reading order: highest power of [v] first, constants last. */
    fun orderTerms(terms: List<S>, v: Char = 'x'): List<S> = Printer(v).orderTerms(terms)

    /** Factors of a product in reading order: numbers, π/e, variables, e^…, brackets, functions. */
    fun orderFactors(factors: List<S>, v: Char = 'x'): List<S> = Printer(v).orderFactors(factors)

    private class Printer(val v: Char) {

        fun print(s: S): String = when (s) {
            is S.Num -> Tex.rational(s.v)
            is S.Var -> varTex(s.name)
            is S.Const -> if (s.name == 'π') "\\pi" else "e"
            is S.Func -> func(s)
            is S.Pow -> pow(s)
            is S.Sum -> sum(s)
            is S.Prod -> prod(s)
        }

        private fun simpleArg(a: S) = a is S.Var || a is S.Const || (a is S.Num && a.v.isInteger && a.v.sign > 0) ||
            (a is S.Prod && a.factors.size == 2 && a.factors[0] is S.Num && (a.factors[0] as S.Num).v.let { it.isInteger && it.sign > 0 } &&
                (a.factors[1] is S.Var || a.factors[1] is S.Const))

        private fun funcName(name: String) = Tex.funcName(name)

        private fun func(s: S.Func): String = when (s.name) {
            "abs" -> "\\left|${print(s.arg)}\\right|"
            else -> {
                val arg = s.arg
                if (arg is S.Func && arg.name == "abs") funcName(s.name) + print(arg)
                else if (simpleArg(arg)) funcName(s.name) + " " + print(arg)
                else funcName(s.name) + "\\left(" + print(arg) + "\\right)"
            }
        }

        private fun pow(s: S.Pow): String {
            val e = s.exp
            if (e is S.Num && e.v.sign < 0) return "\\frac{1}{${print(Sym.pow(s.base, S.Num(-e.v)))}}"
            if (negativeLooking(e) && (s.base is S.Num || s.base == E)) return "\\frac{1}{${print(Sym.pow(s.base, Sym.neg(e)))}}"
            if (s.base == E) return "e^{${print(e)}}"
            if (e is S.Num && e.v == Rational.of(1, 2)) return "\\sqrt{${print(s.base)}}"
            if (e is S.Num && e.v.num == BigInteger.ONE && e.v.den <= BigInteger.valueOf(9)) return "\\sqrt[${e.v.den}]{${print(s.base)}}"
            val b = s.base
            if (b is S.Func && b.name != "abs" && e is S.Num && e.v.isInteger) {
                val arg = if (b.arg is S.Func && (b.arg as S.Func).name == "abs") print(b.arg)
                else if (simpleArg(b.arg)) " " + print(b.arg) else "\\left(" + print(b.arg) + "\\right)"
                return "${funcName(b.name)}^{${print(e)}}$arg"
            }
            val needsParens = b is S.Sum || b is S.Prod || b is S.Pow || b is S.Func ||
                (b is S.Num && (b.v.sign < 0 || !b.v.isInteger))
            val baseText = if (needsParens) "\\left(${print(b)}\\right)" else print(b)
            return "$baseText^{${print(e)}}"
        }

        /** −k, −2x, −k − 1: exponents that read better as 1/(…) */
        private fun negativeLooking(e: S): Boolean = when (e) {
            is S.Prod -> e.factors[0] is S.Num && (e.factors[0] as S.Num).v.sign < 0
            is S.Sum -> e.terms.all { splitCoef(it).first.sign < 0 }
            else -> false
        }

        private fun degree(t: S): Rational = when (t) {
            is S.Var -> if (t.name == v) Rational.ONE else Rational.ZERO
            is S.Pow -> if (t.base == S.Var(v) && t.exp is S.Num) (t.exp as S.Num).v else Rational.ZERO
            is S.Prod -> t.factors.fold(Rational.ZERO) { acc, f -> acc + degree(f) }
            else -> Rational.ZERO
        }

        fun orderTerms(terms: List<S>): List<S> = terms.sortedWith(
            compareBy<S>({ it is S.Num }, { -degree(it).toDouble() }, { splitCoef(it).first.sign < 0 }, { it.toString() })
        )

        private fun sum(s: S.Sum): String {
            val sb = StringBuilder()
            val ordered = orderTerms(s.terms).toMutableList()
            // Prefer a positive first term: "1 - x^2" rather than "-x^2 + 1".
            if (splitCoef(ordered[0]).first.sign < 0) {
                val firstPositive = ordered.indexOfFirst { splitCoef(it).first.sign > 0 }
                if (firstPositive > 0) ordered.add(0, ordered.removeAt(firstPositive))
            }
            ordered.forEachIndexed { i, t ->
                val negative = splitCoef(t).first.sign < 0
                if (i == 0) sb.append(print(t))
                else if (negative) sb.append(" - ").append(print(Sym.neg(t)))
                else sb.append(" + ").append(print(t))
            }
            return sb.toString()
        }

        /** Display order inside a product: numeric roots, π/e, variables, e^…, brackets, functions. */
        private fun rank(f: S): Int = when {
            f is S.Num -> -1
            f is S.Pow && f.base is S.Num -> 0
            f is S.Const -> 1
            f is S.Var -> 2
            f is S.Pow && f.base is S.Var -> 2
            f is S.Pow && f.base == E -> 3
            f is S.Sum || (f is S.Pow && f.base is S.Sum) -> 4
            else -> 5
        }

        private fun varName(f: S): Char = when {
            f is S.Var -> if (f.name == v) '\uFFFF' else f.name     // main variable after the constants: "2ax"
            f is S.Pow && f.base is S.Var -> varName(f.base)
            else -> ' '
        }

        fun orderFactors(list: List<S>): List<S> =
            list.sortedWith(compareBy<S>({ rank(it) }, { varName(it) }, { it.toString() }))

        private fun factorText(f: S) = if (f is S.Sum) "\\left(${print(f)}\\right)" else print(f)

        /** [bare] = the list is the whole numerator/denominator of a fraction, so one sum needs no brackets. */
        private fun joinFactors(list: List<S>, bare: Boolean = false): String {
            if (bare && list.size == 1) return print(list[0])
            return orderFactors(list).joinToString(" ") { factorText(it) }
        }

        private fun prod(s: S.Prod): String {
            val (coef, _) = splitCoef(s)
            val rest = if (s.factors[0] is S.Num) s.factors.drop(1) else s.factors
            val numer = mutableListOf<S>()
            val denom = mutableListOf<S>()
            for (f in rest) {
                if (f is S.Pow && f.exp is S.Num && (f.exp as S.Num).v.sign < 0) {
                    denom += Sym.pow(f.base, S.Num(-(f.exp as S.Num).v))
                } else if (f is S.Pow && (f.base is S.Num || f.base == E) && negativeLooking(f.exp)) {
                    denom += Sym.pow(f.base, Sym.neg(f.exp))       // 2^{-k} -> 1/2^k
                } else numer += f
            }
            val sign = if (coef.sign < 0) "-" else ""
            val c = coef.abs()
            val p = c.num
            val q = c.den
            val numText = joinFactors(numer)

            if (denom.isEmpty()) {
                if (c.isInteger) {
                    return sign + (if (p == BigInteger.ONE && numText.isNotEmpty()) "" else "$p ") + numText
                }
                val monomial = numer.none { it is S.Sum }
                return if (monomial) {
                    sign + "\\frac{" + (if (p == BigInteger.ONE) "" else "$p ") + numText + "}{$q}"
                } else {
                    "$sign\\frac{$p}{$q} $numText"
                }
            }
            val top = when {
                numText.isEmpty() -> p.toString()
                p == BigInteger.ONE -> joinFactors(numer, bare = true)
                else -> "$p $numText"
            }
            val denText = joinFactors(denom, bare = q == BigInteger.ONE)
            val bottom = if (q == BigInteger.ONE) denText else "$q $denText"
            return "$sign\\frac{$top}{$bottom}"
        }
    }
}
