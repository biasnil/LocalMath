package com.localmath.engine

import java.util.TreeMap

/** An exact complex number re + im·i. Both parts are ordinary (real) symbolic expressions. */
data class Cx(val re: S, val im: S) {
    val isReal get() = im == Sym.ZERO
    fun approx(): Pair<Double, Double> = Sym.eval(re, emptyMap()) to Sym.eval(im, emptyMap())
}

/** Complex arithmetic on [Cx], plus LaTeX helpers. */
object Complex {
    val ZERO = Cx(Sym.ZERO, Sym.ZERO)
    val ONE = Cx(Sym.ONE, Sym.ZERO)
    val I = Cx(Sym.ZERO, Sym.ONE)

    /** Functions that only make sense for complex numbers. */
    val FUNCS = setOf("conj", "arg", "real", "imag")

    fun real(s: S) = Cx(s, Sym.ZERO)

    private fun isImagUnit(e: Expr) = e is Expr.Const && e.name == 'i'

    /** True if [e] needs complex numbers: i, conj/arg/real/imag, or the square root of a negative number. */
    fun involved(e: Expr): Boolean = e.has { n ->
        isImagUnit(n) || (n is Expr.Func && n.name in FUNCS) ||
            (n is Expr.Func && (n.name == "sqrt" || n.name == "ln" || n.name == "log") && negativeConstant(n.arg))
    }

    private fun negativeConstant(e: Expr): Boolean {
        if (e.variables().isNotEmpty() || e.has { it is Expr.Special || isImagUnit(it) }) return false
        return try { Sym.eval(Sym.from(e), emptyMap()) < 0 } catch (_: MathError) { false }
    }

    // ---------------- arithmetic ----------------

    fun add(a: Cx, b: Cx) = Cx(Sym.add(a.re, b.re), Sym.add(a.im, b.im))
    fun sub(a: Cx, b: Cx) = Cx(Sym.sub(a.re, b.re), Sym.sub(a.im, b.im))
    fun neg(a: Cx) = Cx(Sym.neg(a.re), Sym.neg(a.im))
    fun conj(a: Cx) = Cx(a.re, Sym.neg(a.im))

    fun mul(a: Cx, b: Cx): Cx = when {
        a.isReal -> Cx(Sym.mul(a.re, b.re), Sym.mul(a.re, b.im))
        b.isReal -> Cx(Sym.mul(a.re, b.re), Sym.mul(a.im, b.re))
        else -> Cx(Sym.sub(Sym.mul(a.re, b.re), Sym.mul(a.im, b.im)), Sym.add(Sym.mul(a.re, b.im), Sym.mul(a.im, b.re)))
    }

    /** a² + b² = |z|² */
    fun normSq(a: Cx): S = Sym.add(Sym.mul(a.re, a.re), Sym.mul(a.im, a.im))

    fun isZero(a: Cx): Boolean {
        if (a.re == Sym.ZERO && a.im == Sym.ZERO) return true
        val (x, y) = a.approx()
        return Math.hypot(x, y) < 1e-13
    }

    fun div(a: Cx, b: Cx): Cx {
        if (isZero(b)) throw MathError("Division by zero")
        if (b.isReal) return Cx(Sym.div(a.re, b.re), Sym.div(a.im, b.re))
        val d = normSq(b)
        val n = mul(a, conj(b))
        return Cx(Sym.div(n.re, d), Sym.div(n.im, d))
    }

    fun powInt(a: Cx, n: Int): Cx {
        if (n < 0) return div(ONE, powInt(a, -n))
        if (n == 0) {
            if (isZero(a)) throw MathError("0⁰ is undefined")
            return ONE
        }
        if (a.isReal) return real(Sym.pow(a.re, Sym.num(n.toLong())))
        if (a.re == Sym.ZERO) {               // (bi)ⁿ = bⁿ·iⁿ
            val bn = Sym.pow(a.im, Sym.num(n.toLong()))
            return when (n % 4) { 0 -> real(bn); 1 -> Cx(Sym.ZERO, bn); 2 -> real(Sym.neg(bn)); else -> Cx(Sym.ZERO, Sym.neg(bn)) }
        }
        var result = ONE
        var base = a
        var k = n
        while (k > 0) {
            if (k and 1 == 1) result = mul(result, base)
            k = k shr 1
            if (k > 0) base = mul(base, base)
        }
        return result
    }

    /** |z| */
    fun abs(a: Cx): S = when {
        a.im == Sym.ZERO -> Sym.func("abs", a.re)
        a.re == Sym.ZERO -> Sym.func("abs", a.im)
        else -> Sym.pow(normSq(a), Sym.HALF)
    }

    /** Principal argument, in (−π, π]. */
    fun arg(a: Cx): S {
        val (x, y) = a.approx()
        if (Math.hypot(x, y) < 1e-15) throw MathError("The argument (angle) of 0 isn't defined")
        angleOf(Math.atan2(y, x))?.let { return it }
        val t = Sym.func("arctan", Sym.func("abs", Sym.div(a.im, a.re)))   // reference angle
        return when {
            x > 0 && y >= 0 -> t
            x > 0 -> Sym.neg(t)
            y >= 0 -> Sym.sub(Sym.PI, t)
            else -> Sym.sub(t, Sym.PI)
        }
    }

    /** θ as an exact multiple of π/12 (0, π/6, π/4, 2π/3 …) when it is one, else null. */
    fun angleOf(theta: Double): S? {
        if (!theta.isFinite()) return null
        val k = theta / Math.PI * 12
        val r = Math.rint(k)
        if (Math.abs(k - r) > 1e-9) return null
        return Sym.mul(Sym.num(r.toLong(), 12), Sym.PI)
    }

    fun exp(a: Cx): Cx {
        if (a.isReal) return real(Sym.pow(Sym.E, a.re))
        val m = Sym.pow(Sym.E, a.re)
        return Cx(Sym.mul(m, Sym.func("cos", a.im)), Sym.mul(m, Sym.func("sin", a.im)))
    }

    fun ln(a: Cx): Cx {
        val (x, y) = a.approx()
        if (Math.hypot(x, y) < 1e-15) throw MathError("ln 0 is undefined")
        if (a.isReal && x > 0) return real(Sym.func("ln", a.re))
        return Cx(Sym.func("ln", abs(a)), arg(a))
    }

    /** Principal square root (real part ≥ 0). */
    fun sqrt(a: Cx): Cx {
        val (x, y) = a.approx()
        if (a.isReal) return if (x >= 0) real(Sym.pow(a.re, Sym.HALF)) else Cx(Sym.ZERO, Sym.pow(Sym.neg(a.re), Sym.HALF))
        val r = abs(a)
        val re = Sym.pow(Sym.mul(Sym.HALF, Sym.add(r, a.re)), Sym.HALF)
        val im = Sym.pow(Sym.mul(Sym.HALF, Sym.sub(r, a.re)), Sym.HALF)
        return Cx(re, if (y < 0) Sym.neg(im) else im)
    }

    fun pow(a: Cx, b: Cx): Cx {
        if (b.isReal && b.re is S.Num) {
            val r = (b.re as S.Num).v
            if (r.isInteger && r.num.abs() <= java.math.BigInteger.valueOf(4096)) return powInt(a, r.num.toInt())
            if (a.isReal && a.approx().first >= 0) return real(Sym.pow(a.re, b.re))
            if (r == Rational.of(1, 2)) return sqrt(a)
        }
        if (a.isReal && b.isReal && a.approx().first > 0 && a.re != Sym.E) return real(Sym.pow(a.re, b.re))
        if (a.isReal && a.re == Sym.E) return exp(b)
        if (isZero(a)) {
            if (b.isReal && b.approx().first > 0) return ZERO
            throw MathError("0 to that power is undefined")
        }
        return exp(mul(b, ln(a)))
    }

    fun sin(a: Cx): Cx = if (a.isReal) real(Sym.func("sin", a.re)) else
        Cx(Sym.mul(Sym.func("sin", a.re), Sym.func("cosh", a.im)), Sym.mul(Sym.func("cos", a.re), Sym.func("sinh", a.im)))

    fun cos(a: Cx): Cx = if (a.isReal) real(Sym.func("cos", a.re)) else
        Cx(Sym.mul(Sym.func("cos", a.re), Sym.func("cosh", a.im)), Sym.neg(Sym.mul(Sym.func("sin", a.re), Sym.func("sinh", a.im))))

    // ---------------- LaTeX ----------------

    fun looksNegative(s: S) = Sym.latex(s).startsWith("-")

    /** s·i, e.g. 3i, \frac{1}{2}i, \sqrt{2}\,i, (1 + \sqrt{3})i */
    fun imagTex(s: S): String {
        if (s == Sym.ONE) return "i"
        if (s == Sym.MINUS_ONE) return "-i"
        val t = Sym.latex(s)
        return when {
            s is S.Sum -> "\\left($t\\right)i"
            t.last().isLetter() -> "$t\\,i"
            else -> "${t}i"
        }
    }

    fun tex(c: Cx): String {
        val reT = if (c.re == Sym.ZERO) null else Sym.latex(c.re)
        if (c.im == Sym.ZERO) return reT ?: "0"
        val negative = looksNegative(c.im)
        val imT = imagTex(if (negative) Sym.neg(c.im) else c.im)
        return when {
            reT == null -> if (negative) "-$imT" else imT
            else -> "$reT ${if (negative) "-" else "+"} $imT"
        }
    }

    /** tex(c) in brackets when it has two parts or starts with a minus sign. */
    fun wrap(c: Cx): String {
        val t = tex(c)
        return if ((c.re != Sym.ZERO && c.im != Sym.ZERO) || t.startsWith("-")) "\\left($t\\right)" else t
    }

    /** True when both parts are plain fractions (no √, π, cos …). */
    fun isRational(c: Cx) = c.re is S.Num && c.im is S.Num

    fun decimal(c: Cx): String {
        val (x, y) = c.approx()
        return decimal(x, y)
    }

    fun decimal(x: Double, y: Double): String {
        val xs = if (Math.abs(x) < 1e-12) null else Tex.decimal(x)
        if (Math.abs(y) < 1e-12) return xs ?: "0"
        val ys = (if (Math.abs(Math.abs(y) - 1) < 1e-12) "" else Tex.decimal(Math.abs(y))) + "i"
        return when {
            xs == null -> if (y < 0) "-$ys" else ys
            else -> "$xs ${if (y < 0) "-" else "+"} $ys"
        }
    }

    /** Plain text for graph labels. */
    fun plain(x: Double, y: Double): String {
        val xs = Graphs.short(x)
        if (Math.abs(y) < 1e-9) return xs
        val ys = (if (Math.abs(Math.abs(y) - 1) < 1e-9) "" else Graphs.short(Math.abs(y))) + "i"
        if (Math.abs(x) < 1e-9) return if (y < 0) "−$ys" else ys
        return "$xs ${if (y < 0) "−" else "+"} $ys"
    }

    /** Joins signed LaTeX terms: ["3", "-2i", "5i"] -> "3 - 2i + 5i". */
    fun joinTerms(terms: List<String>): String {
        val sb = StringBuilder()
        for (t in terms.filter { it.isNotEmpty() && it != "0" }) {
            if (sb.isEmpty()) sb.append(t)
            else if (t.startsWith("-")) sb.append(" - ").append(t.substring(1))
            else sb.append(" + ").append(t)
        }
        return if (sb.isEmpty()) "0" else sb.toString()
    }

    /** Points on the complex plane (real part across, imaginary part up). */
    fun argand(points: List<Pair<Cx, String?>>): Graph? {
        val pts = points.mapNotNull { (c, name) ->
            val (x, y) = c.approx()
            if (!x.isFinite() || !y.isFinite()) null
            else GraphPoint(x, y, (name?.let { "$it = " } ?: "") + plain(x, y))
        }
        if (pts.isEmpty()) return null
        return Graphs.build(emptyList(), pts + GraphPoint(0.0, 0.0, "0"), focus = pts.map { it.x } + pts.map { it.y } + 0.0)
    }
}

/** Solves problems with complex numbers: arithmetic, polar form, and equations like (1 + i)z = 3 − i or z² + 2z + 5 = 0. */
object ComplexSolver {

    private const val KIND = "Complex numbers"

    // ================= expressions =================

    fun expression(e: Expr): Solution {
        if (e.has { it is Expr.Special || it is Expr.Derivative || it is Expr.Integral }) {
            throw MathError("Complex numbers work in arithmetic and equations; d/dx, ∫, lim and Σ need real numbers for now")
        }
        val vars = e.variables()
        if (vars.size > 1) throw MathError("Use one letter (like z) with complex numbers")
        if (vars.size == 1) return expand(e, vars.single())

        val steps = mutableListOf(Step("Start with", Tex.expr(e)))
        val z = Evaluator(steps).eval(e)
        val result = Complex.tex(z)
        if (steps.size == 1 || !steps.last().math.endsWith(result)) {
            steps += Step("Collect the real and imaginary parts", result, "Real part \\(${Sym.latex(z.re)}\\), imaginary part \\(${Sym.latex(z.im)}\\).")
        }
        polarSteps(z, steps)
        val approx = if (Complex.isRational(z)) null else "\\approx ${Complex.decimal(z)}"
        return Solution(if (z.isReal) "Complex numbers (the answer is real)" else KIND, steps, result, approx,
            Complex.argand(listOf(z to "z")))
    }

    /** |z|, arg z and the polar / exponential forms. */
    private fun polarSteps(z: Cx, steps: MutableList<Step>) {
        if (z.isReal || Complex.isZero(z)) return
        val r = Complex.abs(z)
        val theta = Complex.arg(z)
        val (x, y) = z.approx()
        val quadrant = when {
            x > 0 && y > 0 -> "first"; x < 0 && y > 0 -> "second"; x < 0 && y < 0 -> "third"; x > 0 && y < 0 -> "fourth"; else -> null
        }
        fun sq(s: S) = Sym.latex(s).let { if (s is S.Num && s.v.isInteger && s.v.sign >= 0) it else "\\left($it\\right)" } + "^2"
        steps += Step("Modulus and argument",
            "|z| = \\sqrt{${sq(z.re)} + ${sq(z.im)}} = ${Sym.latex(r)},\\quad \\arg z = ${Sym.latex(theta)}",
            (if (quadrant != null) "The point is in the $quadrant quadrant. " else "") +
                "The argument is the angle from the positive real axis" + (if (Complex.angleOf(Math.atan2(y, x)) == null) " (≈ ${Tex.decimal(Math.atan2(y, x))} rad)." else "."))
        val rt = if (r == Sym.ONE) "" else Sym.latex(r).let { if (r is S.Sum) "\\left($it\\right)" else it }
        val th = Sym.latex(theta)
        val thArg = if (theta is S.Sum) "\\left($th\\right)" else th
        steps += Step("Polar form", "z = $rt\\left(\\cos $thArg + i\\sin $thArg\\right) = ${rt}e^{${Complex.imagTex(theta)}}")
    }

    /** (x + i)(x − i) -> x² + 1: multiplies out an expression in one letter with complex coefficients. */
    private fun expand(e: Expr, v: Char): Solution {
        val steps = mutableListOf(Step("Start with", Tex.expr(e)))
        val p = CPoly.of(e, v)
        val result = p.tex(v)
        steps += Step("Multiply out and collect like terms, using \\(i^2 = -1\\)", result)
        return Solution("Simplify (complex)", steps, result)
    }

    // ================= equations =================

    fun equation(left: Expr, right: Expr): Solution {
        if (left.has { it is Expr.Special || it is Expr.Derivative || it is Expr.Integral } ||
            right.has { it is Expr.Special || it is Expr.Derivative || it is Expr.Integral }) {
            throw MathError("Complex numbers work in arithmetic and equations; d/dx, ∫, lim and Σ need real numbers for now")
        }
        val vars = left.variables() + right.variables()
        val steps = mutableListOf(Step("Start with", Tex.equation(left, right)))
        if (vars.isEmpty()) {
            val a = Evaluator(mutableListOf()).eval(left)
            val b = Evaluator(mutableListOf()).eval(right)
            val same = Complex.isZero(Complex.sub(a, b))
            steps += Step("Work out both sides", "${Complex.tex(a)} \\;\\text{vs}\\; ${Complex.tex(b)}")
            return Solution("Check a statement", steps, if (same) "\\text{True}" else "\\text{False}")
        }
        if (vars.size > 1) throw MathError("Use one unknown (like z) in a complex equation")
        val v = vars.single()
        val p = CPoly.of(left, v).minus(CPoly.of(right, v))
        val shown = "${p.tex(v)} = 0"
        if (shown.replace(" ", "") != steps[0].math.replace(" ", "")) steps += Step("Move everything to one side and multiply out", shown)

        val roots: List<Cx> = when (p.degree) {
            -1 -> {
                steps += Step("Both sides are always equal", "0 = 0")
                return Solution("Identity", steps, "\\text{Every value of } $v \\text{ works}")
            }
            0 -> {
                steps += Step("This is never true", "${Complex.tex(p[0])} \\neq 0")
                return Solution("Contradiction", steps, "\\text{No solution}")
            }
            1 -> listOf(linear(p, v, steps))
            2 -> quadratic(p, v, steps)
            else -> if (p.isPurePower) pureRoots(p, v, steps) else numericRoots(p, v, steps)
        }
        verify(p, roots)

        val numeric = roots.any { numericMark[it] == true }
        val answer = Tex.orList(roots.map { if (numericMark[it] == true) "$v \\approx ${Complex.decimal(it)}" else "$v = ${Complex.tex(it)}" })
        val needsDecimals = !numeric && roots.any { !Complex.isRational(it) }
        val approx = if (needsDecimals) Tex.orList(roots.map { "$v \\approx ${Complex.decimal(it)}" }) else null
        numericMark.clear()
        val kind = when (p.degree) { 1 -> "Linear equation (complex)"; 2 -> "Quadratic equation (complex)"; else -> "Polynomial equation (complex, degree ${p.degree})" }
        return Solution(kind, steps, answer, approx, Complex.argand(roots.mapIndexed { i, r -> r to if (roots.size > 1) "${v}${i + 1}" else "$v" }))
    }

    /** Roots found numerically (shown with ≈). */
    private val numericMark = java.util.IdentityHashMap<Cx, Boolean>()

    private fun linear(p: CPoly, v: Char, steps: MutableList<Step>): Cx {
        val a = p[1]
        val b = p[0]
        val rhs = Complex.neg(b)
        if (b != Complex.ZERO) steps += Step("Move the constant to the other side", "${CPoly.term(a, 1, v)} = ${Complex.tex(rhs)}")
        if (a == Complex.ONE) return rhs
        val z = Complex.div(rhs, a)
        if (a.isReal) {
            steps += Step("Divide both sides by \\(${Complex.tex(a)}\\)", "$v = ${Complex.tex(z)}")
        } else {
            val num = Complex.mul(rhs, Complex.conj(a))
            steps += Step("Divide both sides by \\(${Complex.tex(a)}\\): multiply the top and bottom by the conjugate \\(${Complex.tex(Complex.conj(a))}\\)",
                "$v = \\frac{${Complex.tex(rhs)}}{${Complex.tex(a)}} = \\frac{${Complex.wrap(rhs)}${Complex.wrap(Complex.conj(a))}}{${Complex.wrap(a)}${Complex.wrap(Complex.conj(a))}} = \\frac{${Complex.tex(num)}}{${Sym.latex(Complex.normSq(a))}} = ${Complex.tex(z)}",
                "\\((a + bi)(a - bi) = a^2 + b^2\\) is a real number, so \\(i\\) disappears from the bottom.")
        }
        return z
    }

    private fun quadratic(p: CPoly, v: Char, steps: MutableList<Step>): List<Cx> {
        val a = p[2]
        val b = p[1]
        val c = p[0]
        steps += Step("Identify the coefficients", "a = ${Complex.tex(a)},\\quad b = ${Complex.tex(b)},\\quad c = ${Complex.tex(c)}")
        val disc = Complex.sub(Complex.powInt(b, 2), Complex.mul(Complex.real(Sym.num(4)), Complex.mul(a, c)))
        steps += Step("Compute the discriminant", "\\Delta = b^2 - 4ac = ${Complex.tex(disc)}")
        val twoA = Complex.mul(Complex.real(Sym.num(2)), a)
        if (Complex.isZero(disc)) {
            val z = Complex.div(Complex.neg(b), twoA)
            steps += Step("\\(\\Delta = 0\\), so there is one repeated root", "$v = \\frac{-b}{2a} = ${Complex.tex(z)}")
            return listOf(z)
        }
        val root = Complex.sqrt(disc)
        val note = when {
            disc.isReal && disc.approx().first < 0 -> "\\(\\sqrt{-1} = i\\), so a negative discriminant gives complex roots."
            !disc.isReal -> "For \\(\\sqrt{a + bi}\\) use \\(\\sqrt{\\tfrac{|w| + a}{2}} \\pm i\\sqrt{\\tfrac{|w| - a}{2}}\\) with \\(|w| = \\sqrt{a^2 + b^2}\\)."
            else -> null
        }
        steps += Step("Square root of the discriminant", "\\sqrt{\\Delta} = ${Complex.tex(root)}", note)
        val z1 = Complex.div(Complex.add(Complex.neg(b), root), twoA)
        val z2 = Complex.div(Complex.sub(Complex.neg(b), root), twoA)
        steps += Step("Apply the quadratic formula",
            "$v = \\frac{-b \\pm \\sqrt{\\Delta}}{2a} = \\frac{${Complex.tex(Complex.neg(b))} \\pm ${Complex.wrap(root)}}{${Complex.tex(twoA)}}")
        steps += Step("So", Tex.orList(listOf("$v = ${Complex.tex(z1)}", "$v = ${Complex.tex(z2)}")),
            if (a.isReal && b.isReal && c.isReal) "With real coefficients the complex roots are conjugates of each other." else null)
        return listOf(z1, z2)
    }

    /** zⁿ = w: n roots evenly spaced around a circle (De Moivre). */
    private fun pureRoots(p: CPoly, v: Char, steps: MutableList<Step>): List<Cx> {
        val n = p.degree
        val w = Complex.neg(Complex.div(p[0], p[n]))
        steps += Step("Isolate \\($v^{$n}\\)", "$v^{$n} = ${Complex.tex(w)}")
        if (Complex.isZero(w)) {
            steps += Step("Only zero works", "$v = 0")
            return listOf(Complex.ZERO)
        }
        val r = Complex.abs(w)
        val theta = Complex.arg(w)
        val rootR = Sym.pow(r, Sym.num(Rational.of(1, n.toLong())))
        steps += Step("Write the right side in polar form", "${Complex.tex(w)} = ${Sym.latex(r)}\\,e^{${Complex.imagTex(theta)}}")
        steps += Step("De Moivre: take the ${ordinal(n)} root of the size and divide the angle by \\($n\\)",
            "${v}_k = \\sqrt[$n]{${Sym.latex(r)}}\\,e^{i\\frac{${Sym.latex(theta)} + 2\\pi k}{$n}},\\quad k = 0, 1, \\dots, ${n - 1}",
            "Adding \\(2\\pi\\) to the angle gives the same number, so there are \\($n\\) different roots.")
        val roots = (0 until n).map { k ->
            val angle = Sym.div(Sym.add(theta, Sym.mul(Sym.num(2L * k), Sym.PI)), Sym.num(n.toLong()))
            val a = Complex.angleOf(Sym.eval(angle, emptyMap())) ?: angle
            Cx(Sym.mul(rootR, Sym.func("cos", a)), Sym.mul(rootR, Sym.func("sin", a)))
        }
        steps += Step("The $n roots", roots.mapIndexed { k, z -> "${v}_$k = ${Complex.tex(z)}" }.joinToString(" \\\\ ", "\\begin{gathered}", "\\end{gathered}"))
        return roots
    }

    private fun bracketed(s: S) = if (s is S.Sum) "\\left(${Sym.latex(s)}\\right)" else Sym.latex(s)

    private fun ordinal(n: Int) = when (n) { 2 -> "square"; 3 -> "cube"; else -> "${n}th" }

    /** Durand–Kerner with complex coefficients. */
    private fun numericRoots(p: CPoly, v: Char, steps: MutableList<Step>): List<Cx> {
        val n = p.degree
        steps += Step("Find the roots numerically", "${p.tex(v)} = 0",
            "There's no general formula to show here, so LocalMath finds all \\($n\\) roots numerically (rounded).")
        val (lr, li) = p[n].approx()
        val lm = lr * lr + li * li
        val ar = DoubleArray(n + 1)
        val ai = DoubleArray(n + 1)
        for (k in 0..n) {
            val (cr, ci) = p[k].approx()
            ar[k] = (cr * lr + ci * li) / lm
            ai[k] = (ci * lr - cr * li) / lm
        }
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        var zr = 1.0
        var zi = 0.0
        for (k in 0 until n) {
            re[k] = zr; im[k] = zi
            val t = zr * 0.4 - zi * 0.9
            zi = zr * 0.9 + zi * 0.4
            zr = t
        }
        repeat(3000) {
            var change = 0.0
            for (k in 0 until n) {
                var pr = 1.0
                var pi = 0.0
                for (i in n - 1 downTo 0) {
                    val tr = pr * re[k] - pi * im[k] + ar[i]
                    pi = pr * im[k] + pi * re[k] + ai[i]
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
            if (change < 1e-15) return@repeat
        }
        return (0 until n).sortedWith(compareBy({ re[it] }, { im[it] })).map { k ->
            val z = Cx(round(re[k]), round(im[k]))
            numericMark[z] = true
            z
        }
    }

    private fun round(d: Double): S {
        if (Math.abs(d) < 1e-10) return Sym.ZERO
        val bd = java.math.BigDecimal(d).round(java.math.MathContext(7)).stripTrailingZeros()
        return S.Num(Rational.parse(bd.abs().toPlainString()).let { if (bd.signum() < 0) -it else it })
    }

    /** Puts each root back into the polynomial (numerically). */
    private fun verify(p: CPoly, roots: List<Cx>) {
        for (z in roots) {
            val (x, y) = z.approx()
            var vr = 0.0
            var vi = 0.0
            var scale = 0.0
            for (k in p.degree downTo 0) {
                val (cr, ci) = p[k].approx()
                val tr = vr * x - vi * y + cr
                vi = vr * y + vi * x + ci
                vr = tr
                scale = scale * Math.hypot(x, y) + Math.hypot(cr, ci)
            }
            val tol = if (numericMark[z] == true) 1e-4 else 1e-8
            if (!vr.isFinite() || !vi.isFinite() || Math.hypot(vr, vi) > tol * Math.max(1.0, scale)) {
                throw MathError("Sorry, LocalMath couldn't verify this answer, so it won't show a possibly wrong one.")
            }
        }
    }

    // ================= evaluating with explanations =================

    /** Works out an expression without letters, writing a step for each complex operation. */
    class Evaluator(private val steps: MutableList<Step>) {

        fun eval(e: Expr): Cx = when (e) {
            is Expr.Num -> Complex.real(S.Num(e.value))
            is Expr.Const -> if (e.name == 'i') Complex.I else Complex.real(Sym.from(e))
            is Expr.Var -> throw MathError("Use one letter (like z) with complex numbers")
            is Expr.Neg -> Complex.neg(eval(e.inner))
            is Expr.Add -> Complex.add(eval(e.left), eval(e.right))
            is Expr.Sub -> Complex.sub(eval(e.left), eval(e.right))
            is Expr.Mul -> multiply(eval(e.left), eval(e.right))
            is Expr.Div -> divide(eval(e.left), eval(e.right))
            is Expr.Pow -> power(eval(e.base), eval(e.exponent), e)
            is Expr.Func -> func(e.name, eval(e.arg))
            else -> throw MathError("d/dx, ∫, lim and Σ can't be used with complex numbers yet")
        }

        private fun multiply(a: Cx, b: Cx): Cx {
            val r = Complex.mul(a, b)
            if (a.isReal || b.isReal) return r
            if (a.re == Sym.ZERO && b.re == Sym.ZERO) {
                steps += Step("Multiply, using \\(i^2 = -1\\)",
                    "${Complex.wrap(a)} \\cdot ${Complex.wrap(b)} = ${Sym.latex(Sym.mul(a.im, b.im)).let { if (it == "1") "" else if (it == "-1") "-" else it }}i^2 = ${Complex.tex(r)}")
                return r
            }
            val t1 = Sym.mul(a.re, b.re)
            val t2 = Sym.mul(a.re, b.im)
            val t3 = Sym.mul(a.im, b.re)
            val t4 = Sym.mul(a.im, b.im)
            val i2 = when (t4) { Sym.ONE -> "i^2"; Sym.MINUS_ONE -> "-i^2"; else -> Sym.latex(t4).let { if (t4 is S.Sum) "\\left($it\\right)i^2" else "${it}i^2" } }
            val expanded = Complex.joinTerms(listOf(
                if (t1 == Sym.ZERO) "" else Sym.latex(t1),
                if (t2 == Sym.ZERO) "" else Complex.imagTex(t2),
                if (t3 == Sym.ZERO) "" else Complex.imagTex(t3),
                if (t4 == Sym.ZERO) "" else i2))
            steps += Step("Multiply out the brackets, then use \\(i^2 = -1\\)",
                "${Complex.wrap(a)}${Complex.wrap(b)} = $expanded = ${Complex.tex(r)}")
            return r
        }

        private fun divide(a: Cx, b: Cx): Cx {
            val r = Complex.div(a, b)
            if (b.isReal) return r
            val cb = Complex.conj(b)
            val top = Complex.mul(a, cb)
            steps += Step("Divide: multiply the top and bottom by the conjugate \\(${Complex.tex(cb)}\\)",
                "\\frac{${Complex.tex(a)}}{${Complex.tex(b)}} = \\frac{${Complex.wrap(a)}${Complex.wrap(cb)}}{${Complex.wrap(b)}${Complex.wrap(cb)}} = \\frac{${Complex.tex(top)}}{${Sym.latex(Complex.normSq(b))}} = ${Complex.tex(r)}",
                "\\((a + bi)(a - bi) = a^2 + b^2\\) is a real number, so \\(i\\) disappears from the bottom.")
            return r
        }

        private fun power(a: Cx, b: Cx, e: Expr.Pow): Cx {
            val r = Complex.pow(a, b)
            val intExp = (b.re as? S.Num)?.v?.takeIf { b.isReal && it.isInteger }?.num?.toInt()
            when {
                a.isReal && b.isReal -> {
                    if (a.approx().first < 0 && intExp == null) {
                        steps += Step("A negative number to a fractional power", "${Tex.expr(e)} = ${Complex.tex(r)}",
                            "This is the principal value \\(e^{b\\ln a}\\), using \\(\\ln a = \\ln|a| + i\\pi\\) for negative \\(a\\).")
                    }
                }
                a == Complex.I && intExp != null -> {
                    val m = Math.floorMod(intExp, 4)
                    steps += Step("Powers of \\(i\\) repeat every 4: \\(i, -1, -i, 1\\)",
                        "i^{$intExp} = " + (if (intExp != m) "i^{$m} = " else "") + Complex.tex(r),
                        if (intExp != m) "\\($intExp\\) leaves remainder \\($m\\) when divided by 4." else null)
                }
                a.isReal && a.re == Sym.E -> {
                    steps += Step("Euler's formula \\(e^{a + bi} = e^{a}(\\cos b + i\\sin b)\\)",
                        "e^{${Complex.tex(b).let { if (b.re == Sym.ZERO && b.im !is S.Num) "i" + Sym.latex(b.im).let { t -> if (b.im is S.Sum) "\\left($t\\right)" else t } else it }}} = " + (if (b.re == Sym.ZERO) "" else "e^{${Sym.latex(b.re)}}") +
                            "\\left(\\cos ${bracketed(b.im)} + i\\sin ${bracketed(b.im)}\\right) = ${Complex.tex(r)}")
                }
                intExp != null && intExp in 2..3 && !a.isReal -> {
                    steps += Step("Multiply it out, using \\(i^2 = -1\\)" + if (intExp == 3) " and \\(i^3 = -i\\)" else "",
                        "${Complex.wrap(a)}^{$intExp} = ${binomial(a, intExp)} = ${Complex.tex(r)}")
                }
                intExp != null && !a.isReal -> {
                    val theta = Complex.arg(a)
                    val exactAngle = Complex.angleOf(Math.atan2(a.approx().second, a.approx().first)) != null
                    if (exactAngle && intExp > 0) {
                        val mod = Complex.abs(a)
                        steps += Step("Use polar form and De Moivre's theorem \\((re^{i\\theta})^n = r^n e^{in\\theta}\\)",
                            "${Complex.wrap(a)} = ${Sym.latex(mod)}\\,e^{${Complex.imagTex(theta)}} \\;\\Rightarrow\\; ${Complex.wrap(a)}^{$intExp} = ${Sym.latex(Sym.pow(mod, Sym.num(intExp.toLong())))}\\,e^{${Complex.imagTex(Sym.mul(Sym.num(intExp.toLong()), theta))}} = ${Complex.tex(r)}")
                    } else {
                        steps += Step("Work out the power", "${Complex.wrap(a)}^{$intExp} = ${Complex.tex(r)}")
                    }
                }
                else -> steps += Step("Complex power \\(a^b = e^{b\\ln a}\\) (principal value)", "${Tex.expr(e)} = ${Complex.tex(r)}")
            }
            return r
        }

        /** (a + bi)² or (a + bi)³ written out term by term. */
        private fun binomial(z: Cx, n: Int): String {
            val a = z.re
            val b = z.im
            fun sq(s: S) = Sym.latex(s).let { if (it.startsWith("-") || s is S.Sum) "\\left($it\\right)" else it }
            return if (n == 2) {
                Complex.joinTerms(listOf("${sq(a)}^2", Complex.imagTex(Sym.mul(Sym.num(2), a, b)), "${sq(b)}^2i^2"))
            } else {
                Complex.joinTerms(listOf("${sq(a)}^3", "3${sq(a)}^2\\cdot ${Complex.imagTex(b)}", "3${sq(a)}\\cdot ${sq(b)}^2i^2", "${sq(b)}^3i^3"))
            }
        }

        private fun func(name: String, a: Cx): Cx {
            val r: Cx = when (name) {
                "sqrt" -> Complex.sqrt(a)
                "exp" -> Complex.exp(a)
                "abs" -> Complex.real(Complex.abs(a))
                "conj" -> Complex.conj(a)
                "real" -> Complex.real(a.re)
                "imag" -> Complex.real(a.im)
                "arg" -> Complex.real(Complex.arg(a))
                "ln" -> Complex.ln(a)
                "log" -> Complex.div(Complex.ln(a), Complex.real(Sym.func("ln", Sym.num(10))))
                "sin" -> Complex.sin(a)
                "cos" -> Complex.cos(a)
                "tan" -> Complex.div(Complex.sin(a), Complex.cos(a))
                else -> if (a.isReal) Complex.real(Sym.func(name, a.re))
                    else throw MathError("$name of a complex number isn't supported yet")
            }
            val z = Complex.tex(a)
            when (name) {
                "sqrt" -> if (a.isReal && a.approx().first < 0) {
                    steps += Step("Use \\(\\sqrt{-1} = i\\)", "\\sqrt{$z} = \\sqrt{${Sym.latex(Sym.neg(a.re))}}\\,i = ${Complex.tex(r)}")
                } else if (!a.isReal) {
                    steps += Step("Principal square root \\(\\sqrt{a + bi} = \\sqrt{\\tfrac{|w| + a}{2}} \\pm i\\sqrt{\\tfrac{|w| - a}{2}}\\)",
                        "|w| = ${Sym.latex(Complex.abs(a))} \\;\\Rightarrow\\; \\sqrt{$z} = ${Complex.tex(r)}",
                        "The sign of the \\(i\\) part matches the sign of \\(b\\). Check: \\(${Complex.wrap(r)}^2 = $z\\).")
                }
                "abs" -> if (!a.isReal) steps += Step("Modulus \\(|a + bi| = \\sqrt{a^2 + b^2}\\)",
                    "\\left|$z\\right| = \\sqrt{${Sym.latex(Complex.normSq(a))}} = ${Complex.tex(r)}")
                "conj" -> steps += Step("Conjugate: change the sign of the imaginary part", "\\overline{$z} = ${Complex.tex(r)}")
                "real" -> steps += Step("Real part", "\\operatorname{Re}\\left($z\\right) = ${Complex.tex(r)}")
                "imag" -> steps += Step("Imaginary part", "\\operatorname{Im}\\left($z\\right) = ${Complex.tex(r)}")
                "arg" -> {
                    val (x, y) = a.approx()
                    steps += Step("Argument: the angle from the positive real axis", "\\arg\\left($z\\right) = ${Complex.tex(r)}",
                        if (Complex.angleOf(Math.atan2(y, x)) == null) "≈ ${Tex.decimal(Math.atan2(y, x))} radians" else null)
                }
                "ln", "log" -> if (!(a.isReal && a.approx().first > 0)) steps += Step("Complex logarithm \\(\\ln z = \\ln|z| + i\\arg z\\) (principal value)",
                    "\\${if (name == "ln") "ln" else "log"}\\left($z\\right) = ${Complex.tex(r)}")
                "exp" -> if (!a.isReal) steps += Step("Euler's formula \\(e^{a + bi} = e^{a}(\\cos b + i\\sin b)\\)", "e^{$z} = ${Complex.tex(r)}")
                "sin", "cos", "tan" -> if (!a.isReal) steps += Step(
                    when (name) {
                        "sin" -> "\\(\\sin(a + bi) = \\sin a\\cosh b + i\\cos a\\sinh b\\)"
                        "cos" -> "\\(\\cos(a + bi) = \\cos a\\cosh b - i\\sin a\\sinh b\\)"
                        else -> "\\(\\tan z = \\sin z / \\cos z\\)"
                    }, "\\$name\\left($z\\right) = ${Complex.tex(r)}")
            }
            return r
        }
    }
}

/** Polynomial in one letter with complex coefficients (degree -> coefficient). */
class CPoly(private val c: TreeMap<Int, Cx>) {

    val degree: Int get() = c.keys.filter { !Complex.isZero(c.getValue(it)) }.maxOrNull() ?: -1

    operator fun get(k: Int): Cx = c[k] ?: Complex.ZERO

    /** Only zⁿ and a constant (n ≥ 3). */
    val isPurePower: Boolean get() = degree >= 3 && (1 until degree).all { Complex.isZero(get(it)) }

    private fun combine(o: CPoly, f: (Cx, Cx) -> Cx): CPoly {
        val out = TreeMap<Int, Cx>()
        for (k in c.keys + o.c.keys) out[k] = f(get(k), o[k])
        return CPoly(out).trim()
    }

    operator fun plus(o: CPoly) = combine(o) { a, b -> Complex.add(a, b) }
    fun minus(o: CPoly) = combine(o) { a, b -> Complex.sub(a, b) }

    operator fun times(o: CPoly): CPoly {
        val out = TreeMap<Int, Cx>()
        for ((i, a) in c) for ((j, b) in o.c) {
            out[i + j] = Complex.add(out[i + j] ?: Complex.ZERO, Complex.mul(a, b))
        }
        if ((out.keys.maxOrNull() ?: 0) > Polynomial.MAX_DEGREE) throw MathError("The degree is too high")
        return CPoly(out).trim()
    }

    private fun trim(): CPoly {
        val out = TreeMap<Int, Cx>()
        for ((k, v) in c) if (!(v.re == Sym.ZERO && v.im == Sym.ZERO)) out[k] = v
        return CPoly(out)
    }

    fun tex(v: Char): String {
        val parts = c.descendingMap().filter { !Complex.isZero(it.value) }.map { (k, a) -> term(a, k, v) }
        return Complex.joinTerms(parts)
    }

    companion object {
        fun constant(a: Cx) = CPoly(TreeMap(mapOf(0 to a))).trim()

        /** One term a·vᵏ in LaTeX. */
        fun term(a: Cx, k: Int, v: Char): String {
            if (k == 0) return Complex.tex(a)
            val power = if (k == 1) "$v" else "$v^{$k}"
            return when {
                a == Complex.ONE -> power
                a == Complex.neg(Complex.ONE) -> "-$power"
                a.isReal -> Sym.latex(a.re).let { if (a.re is S.Sum) "\\left($it\\right)" else it } + power
                a.re == Sym.ZERO -> Complex.imagTex(a.im) + power
                else -> "\\left(${Complex.tex(a)}\\right)$power"
            }
        }

        fun of(e: Expr, v: Char): CPoly = when {
            !e.variables().contains(v) -> constant(ComplexSolver.Evaluator(mutableListOf()).eval(e))
            e is Expr.Var -> CPoly(TreeMap(mapOf(1 to Complex.ONE)))
            e is Expr.Neg -> constant(Complex.neg(Complex.ONE)) * of(e.inner, v)
            e is Expr.Add -> of(e.left, v) + of(e.right, v)
            e is Expr.Sub -> of(e.left, v).minus(of(e.right, v))
            e is Expr.Mul -> of(e.left, v) * of(e.right, v)
            e is Expr.Div -> {
                if (e.right.variables().contains(v)) throw MathError("Complex equations with $v in a denominator aren't supported yet")
                val d = ComplexSolver.Evaluator(mutableListOf()).eval(e.right)
                val top = of(e.left, v)
                val out = TreeMap<Int, Cx>()
                for (k in 0..Math.max(top.degree, 0)) out[k] = Complex.div(top[k], d)
                CPoly(out).trim()
            }
            e is Expr.Pow -> {
                if (e.exponent.variables().isNotEmpty()) throw MathError("$v in an exponent isn't supported in complex equations")
                val n = ComplexSolver.Evaluator(mutableListOf()).eval(e.exponent)
                val k = (n.re as? S.Num)?.v?.takeIf { n.isReal && it.isInteger && it.sign >= 0 }?.num?.toInt()
                    ?: throw MathError("Only whole-number powers of $v are supported in complex equations")
                if (k > Polynomial.MAX_DEGREE) throw MathError("Exponent is too large")
                val base = of(e.base, v)
                var r = constant(Complex.ONE)
                repeat(k) { r = r * base }
                r
            }
            else -> throw MathError("Complex equations can use +, −, ×, ÷ and whole-number powers of $v")
        }
    }
}
