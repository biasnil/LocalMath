package com.localmath.engine

import com.localmath.engine.Sym.add
import com.localmath.engine.Sym.mul

// ================= diagram (drawn as SVG by SolutionHtml) =================

/** INPUT = given vectors (blue), MOVED = moved head-to-tail (text colour), RESULT = resultant (red), GUIDE = helper lines. */
enum class DStyle { INPUT, MOVED, RESULT, GUIDE }

data class DArrow(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val name: String?, val style: DStyle)
data class DSeg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val style: DStyle)

/**
 * Text at (x, y) in vector units, nudged by (dx, dy) screen pixels. [align]: −1 = ends here, 0 = centred, 1 = starts here.
 * "_" makes the next character (or {group}) a subscript: "c_x = a_x + b_x".
 */
data class DLabel(val x: Double, val y: Double, val text: String, val style: DStyle, val align: Int = 0, val dx: Double = 0.0, val dy: Double = 0.0)

/** An angle mark at the origin from angle [from] to [to] (radians, anticlockwise). */
data class DArc(val from: Double, val to: Double, val label: String)

data class VectorDiagram(
    val arrows: List<DArrow>,
    val dashes: List<DSeg> = emptyList(),
    val labels: List<DLabel> = emptyList(),
    val arcs: List<DArc> = emptyList(),
    val legend: List<Pair<DStyle, String>> = emptyList()
)

// ================= evaluation (no steps) =================

internal class Eval(val env: Map<Char, LVal>) {
    /** Set when an angle had no exact sine/cosine and decimals were used. */
    var approx = false

    fun ev(n: LN): LVal = when (n) {
        is LN.Num -> LVal.Scal(S.Num(n.r))
        is LN.Const -> LVal.Scal(if (n.c == 'π') Sym.PI else Sym.E)
        is LN.Name -> env[n.c] ?: throw MathError(
            "'${n.c}' isn't defined yet — write it first, like ${n.c} = (3, 4) or ${n.c} = [[1, 2], [3, 4]], then ; and the question")
        is LN.VecLit -> LVal.Vec(n.items.map { scal(it, "A vector's entries must be numbers") })
        is LN.MatLit -> {
            if (n.rows.size > 6 || n.rows[0].size > 6) throw MathError("Matrices can be up to 6 × 6")
            LVal.Mat(n.rows.map { r -> r.map { LA.rat(scal(it, "A matrix's entries must be numbers")) } })
        }
        is LN.Deg -> LVal.Scal(mul(scal(n.inner), Sym.PI, Sym.num(1, 180)))
        is LN.Polar -> polar(n)
        is LN.Neg -> Ops.scale(Sym.MINUS_ONE, ev(n.a))
        is LN.Bin -> Ops.bin(n.op, ev(n.a), ev(n.b))
        is LN.Pow -> pow(n)
        is LN.Call -> call(n)
    }

    fun scal(n: LN, msg: String = "A number is needed here"): S {
        val v = ev(n)
        return (v as? LVal.Scal)?.s ?: throw MathError(msg)
    }

    fun vec(n: LN, what: String): LVal.Vec = ev(n) as? LVal.Vec ?: throw MathError("$what needs a vector, like (3, 4)")
    fun mat(n: LN, what: String): LVal.Mat = when (val v = ev(n)) {
        is LVal.Mat -> v
        else -> throw MathError("$what needs a matrix, like [[1, 2], [3, 4]]")
    }

    /** The angle in radians. A plain number means degrees; π or ° mean it is already an angle. */
    fun angle(a: LN): S {
        val s = scal(a, "The angle after ∠ must be a number")
        return if (hasDegOrPi(a)) s else mul(s, Sym.PI, Sym.num(1, 180))
    }

    private fun hasDegOrPi(n: LN): Boolean = when (n) {
        is LN.Deg -> true
        is LN.Const -> n.c == 'π'
        is LN.Neg -> hasDegOrPi(n.a)
        is LN.Bin -> hasDegOrPi(n.a) || hasDegOrPi(n.b)
        is LN.Pow -> hasDegOrPi(n.a) || hasDegOrPi(n.e)
        is LN.Call -> n.args.any { hasDegOrPi(it) }
        else -> false
    }

    fun polar(n: LN.Polar): LVal.Vec {
        val r = scal(n.r, "The length before ∠ must be a number")
        val th = angle(n.angle)
        return LVal.Vec(listOf(exactOr(mul(r, Sym.func("cos", th))), exactOr(mul(r, Sym.func("sin", th)))))
    }

    /** Keeps exact values like 5√3/2; cos 37° and friends become 12-figure decimals. */
    fun exactOr(s: S): S {
        if (!hasTrig(s)) return s
        approx = true
        return LA.approxS(LA.dbl(s))
    }

    private fun hasTrig(s: S): Boolean = when (s) {
        is S.Func -> true
        is S.Pow -> hasTrig(s.base) || hasTrig(s.exp)
        is S.Sum -> s.terms.any { hasTrig(it) }
        is S.Prod -> s.factors.any { hasTrig(it) }
        else -> false
    }

    private fun pow(n: LN.Pow): LVal {
        val base = ev(n.a)
        val e = n.e
        if (isT(e)) return when (base) {
            is LVal.Mat -> LVal.Mat(Matrices.transpose(base.m))
            is LVal.Vec -> LVal.Mat(listOf(base.c.map { LA.rat(it) }))
            else -> throw MathError("ᵀ (transpose) needs a matrix")
        }
        val k = scal(e, "The power must be a number")
        return when (base) {
            is LVal.Scal -> LVal.Scal(Sym.pow(base.s, k))
            is LVal.Vec -> {
                if (k != Sym.num(2)) throw MathError("A vector can only be squared (a² = a · a)")
                LVal.Scal(Ops.dot(base.c, base.c))
            }
            is LVal.Mat -> LVal.Mat(Matrices.power(base.m, intPower(k)))
        }
    }

    private fun isT(e: LN) = e is LN.Name && e.c == 'T' && 'T' !in env

    fun intPower(k: S): Int {
        val r = (k as? S.Num)?.v ?: throw MathError("Matrix powers must be whole numbers")
        if (!r.isInteger) throw MathError("Matrix powers must be whole numbers")
        if (r.num.abs() > java.math.BigInteger.valueOf(50)) throw MathError("Matrix powers can be up to 50")
        return r.num.toInt()
    }

    private fun call(n: LN.Call): LVal {
        val a = n.args
        fun need(k: Int) { if (a.size != k) throw MathError("${n.name} needs $k input${if (k > 1) "s" else ""}, like ${usage(n.name)}") }
        return when (n.name) {
            "dot" -> { need(2); LVal.Scal(Ops.dot(vec(a[0], "dot").c, vec(a[1], "dot").c)) }
            "cross" -> { need(2); Ops.cross(vec(a[0], "cross").c, vec(a[1], "cross").c) }
            "angle" -> { need(2); LVal.Scal(Ops.angleBetween(vec(a[0], "angle").c, vec(a[1], "angle").c).first) }
            "unit" -> { need(1); LVal.Vec(Ops.unit(vec(a[0], "unit").c)) }
            "proj" -> { need(2); LVal.Vec(Ops.proj(vec(a[0], "proj").c, vec(a[1], "proj").c)) }
            "mag", "norm" -> { need(1); LVal.Scal(Ops.mag(vec(a[0], "|v|").c)) }
            "polar" -> { need(1); vec(a[0], "polar") }
            "det" -> { need(1); LVal.Scal(S.Num(Matrices.det(square(a[0], "det")))) }
            "inv", "inverse" -> { need(1); LVal.Mat(Matrices.inverse(square(a[0], "inv")) ?: throw MathError("This matrix has no inverse (its determinant is 0)")) }
            "transpose" -> { need(1); LVal.Mat(Matrices.transpose(mat(a[0], "transpose").m)) }
            "trace" -> { need(1); LVal.Scal(S.Num(Matrices.trace(square(a[0], "trace")))) }
            "rank" -> { need(1); LVal.Scal(Sym.num(Matrices.rank(mat(a[0], "rank").m).toLong())) }
            "rref" -> { need(1); LVal.Mat(Matrices.rref(mat(a[0], "rref").m)) }
            "ref" -> { need(1); LVal.Mat(Matrices.ref(mat(a[0], "ref").m)) }
            "eig", "eigen", "solve", "lu", "chol", "diag" -> throw MathError("${n.name}( ) has to be the whole question, not part of a bigger one")
            "abs" -> { need(1); when (val v = ev(a[0])) {
                is LVal.Scal -> LVal.Scal(Sym.func("abs", v.s))
                is LVal.Vec -> LVal.Scal(Ops.mag(v.c))
                is LVal.Mat -> { if (!v.square) throw MathError("|A| (determinant) needs a square matrix"); LVal.Scal(S.Num(Matrices.det(v.m))) }
            } }
            "sqrt" -> { need(1); LVal.Scal(Sym.pow(scal(a[0], "√ needs a number"), Sym.HALF)) }
            "exp" -> { need(1); LVal.Scal(Sym.pow(Sym.E, scal(a[0], "exp needs a number"))) }
            else -> { need(1); LVal.Scal(Sym.func(n.name, scal(a[0], "${n.name} needs a number"))) }
        }
    }

    fun square(n: LN, what: String): List<List<Rational>> {
        val m = mat(n, what)
        if (!m.square) throw MathError("$what needs a square matrix (this one is ${m.rows} × ${m.cols})")
        return m.m
    }

    companion object {
        fun usage(name: String) = when (name) {
            "dot" -> "dot(a, b)"; "cross" -> "cross(a, b)"; "angle" -> "angle(a, b)"; "proj" -> "proj(a, b)"
            "solve" -> "solve(A, b)"; else -> "$name(a)"
        }
    }
}

/** Arithmetic on values. */
internal object Ops {

    /** a·b with brackets multiplied out, so (3 + 5√3/2)² becomes a plain sum. */
    fun times(a: S, b: S): S {
        val ta = if (a is S.Sum) a.terms else listOf(a)
        val tb = if (b is S.Sum) b.terms else listOf(b)
        return add(ta.flatMap { x -> tb.map { y -> mul(x, y) } })
    }

    fun dot(a: List<S>, b: List<S>): S {
        if (a.size != b.size) throw MathError("Both vectors need the same number of components")
        return add(a.indices.map { times(a[it], b[it]) })
    }

    fun mag(v: List<S>): S = Sym.pow(add(v.map { times(it, it) }), Sym.HALF)

    fun cross(a: List<S>, b: List<S>): LVal {
        if (a.size == 2 && b.size == 2) return LVal.Scal(Sym.sub(times(a[0], b[1]), times(a[1], b[0])))
        if (a.size == 3 && b.size == 3) return LVal.Vec(listOf(
            Sym.sub(times(a[1], b[2]), times(a[2], b[1])),
            Sym.sub(times(a[2], b[0]), times(a[0], b[2])),
            Sym.sub(times(a[0], b[1]), times(a[1], b[0]))))
        throw MathError("The cross product needs two 2D (or two 3D) vectors")
    }

    fun unit(v: List<S>): List<S> {
        val m = mag(v)
        if (LA.dbl(m) == 0.0) throw MathError("The zero vector has no direction, so it has no unit vector")
        return v.map { Sym.div(it, m) }
    }

    fun proj(a: List<S>, b: List<S>): List<S> {
        val bb = dot(b, b)
        if (LA.dbl(bb) == 0.0) throw MathError("Can't project onto the zero vector")
        val k = Sym.div(dot(a, b), bb)
        return b.map { mul(k, it) }
    }

    /** (θ in radians as an exact value when possible, cos θ). */
    fun angleBetween(a: List<S>, b: List<S>): Pair<S, S> {
        val d = mag(a)
        val e = mag(b)
        if (LA.dbl(d) == 0.0 || LA.dbl(e) == 0.0) throw MathError("The angle with the zero vector isn't defined")
        val cos = Sym.div(dot(a, b), mul(d, e))
        val theta = Sym.func("arccos", cos)
        return (if (theta is S.Func) LA.approxS(Math.acos(LA.dbl(cos).coerceIn(-1.0, 1.0))) else theta) to cos
    }

    fun scale(k: S, v: LVal): LVal = when (v) {
        is LVal.Scal -> LVal.Scal(mul(k, v.s))
        is LVal.Vec -> LVal.Vec(v.c.map { mul(k, it) })
        is LVal.Mat -> { val r = LA.rat(k, "A number multiplying a matrix"); LVal.Mat(v.m.map { row -> row.map { it * r } }) }
    }

    fun bin(op: Char, x: LVal, y: LVal): LVal = when (op) {
        '+', '-' -> addSub(op, x, y)
        '/' -> {
            val d = (y as? LVal.Scal)?.s ?: throw MathError("You can only divide by a number")
            if (LA.dbl(d) == 0.0) throw MathError("Division by zero")
            if (x is LVal.Scal) LVal.Scal(Sym.div(x.s, d)) else scale(Sym.pow(d, Sym.MINUS_ONE), x)
        }
        else -> times(op, x, y)
    }

    private fun addSub(op: Char, x: LVal, y: LVal): LVal {
        val s = if (op == '+') Sym.ONE else Sym.MINUS_ONE
        return when {
            x is LVal.Scal && y is LVal.Scal -> LVal.Scal(add(x.s, mul(s, y.s)))
            x is LVal.Vec && y is LVal.Vec -> {
                if (x.n != y.n) throw MathError("Both vectors need the same number of components")
                LVal.Vec(x.c.indices.map { add(x.c[it], mul(s, y.c[it])) })
            }
            x is LVal.Mat && y is LVal.Mat -> LVal.Mat(Matrices.addSub(x.m, y.m, op == '+'))
            else -> throw MathError("Can't ${if (op == '+') "add" else "subtract"} ${what(x)} and ${what(y)}")
        }
    }

    fun what(v: LVal) = when (v) { is LVal.Scal -> "a number"; is LVal.Vec -> "a vector"; is LVal.Mat -> "a matrix" }

    private fun times(op: Char, x: LVal, y: LVal): LVal = when {
        x is LVal.Scal -> scale(x.s, y)
        y is LVal.Scal -> scale(y.s, x)
        x is LVal.Vec && y is LVal.Vec -> when (op) {
            '·' -> LVal.Scal(dot(x.c, y.c))
            '×' -> cross(x.c, y.c)
            else -> throw MathError("Between two vectors use · for the dot product or × for the cross product")
        }
        x is LVal.Mat && y is LVal.Mat -> LVal.Mat(Matrices.multiply(x.m, y.m))
        x is LVal.Mat && y is LVal.Vec -> LVal.Vec(Matrices.multiply(x.m, y.c.map { listOf(LA.rat(it)) }).map { S.Num(it[0]) })
        x is LVal.Vec && y is LVal.Mat -> LVal.Vec(Matrices.multiply(listOf(x.c.map { LA.rat(it) }), y.m)[0].map { S.Num(it) })
        else -> throw MathError("Can't multiply ${what(x)} and ${what(y)}")
    }
}

// ================= solving with steps =================

internal object Vectors {

    fun solve(stmts: List<LStmt>, known: Map<Char, LVal>): Solution {
        var taskIndex = stmts.indexOfLast { it !is LStmt.Def }
        if (taskIndex < 0) taskIndex = stmts.lastIndex
        if (stmts.withIndex().any { (i, s) -> i < taskIndex && s !is LStmt.Def }) {
            throw MathError("Ask one question per problem. The other parts should give values, like a = (3, 4); b = (1, 2); a + b")
        }
        if (taskIndex < stmts.lastIndex) throw MathError("Give the values first, then the question last (a = (3, 4); a + b)")

        val env = LinkedHashMap(known)
        val steps = mutableListOf<Step>()
        val ev = Eval(env)
        val given = mutableListOf<String>()
        for (i in 0 until taskIndex) {
            val d = stmts[i] as LStmt.Def
            val v = Eval(env).also { e -> e.approx = ev.approx }.let { e -> val r = e.ev(d.value); if (e.approx) ev.approx = true; r }
            env[d.name] = v
            val shown = LinAlg.show(d.value)
            val value = LA.valTex(v)
            given += if (shown == value || d.value is LN.VecLit || d.value is LN.MatLit || d.value is LN.Num) "${d.name} = $value"
                else "${d.name} = $shown = $value"
            if (d.value is LN.Polar) polarSteps(d.name.toString(), d.value, v as LVal.Vec, Eval(env), steps, given = true)
        }
        if (given.isNotEmpty()) steps.add(0, Step(if (given.size == 1) "Given" else "Given values", given.joinToString(",\\quad ")))

        val p = Presenter(Eval(env).also { it.approx = ev.approx }, steps)
        return when (val t = stmts[taskIndex]) {
            is LStmt.Def -> p.present(t.value, t.name)
            is LStmt.Task -> p.present(t.e, null)
            is LStmt.Eq -> p.equation(t.left, t.right)
        }
    }

    /** Components of r∠θ: r cos θ and r sin θ. */
    fun polarSteps(name: String, n: LN.Polar, v: LVal.Vec, ev: Eval, steps: MutableList<Step>, given: Boolean = false) {
        val r = LinAlg.show(n.r)
        val a = LinAlg.show(n.angle).let { if (Regex("^-?[0-9.]+$").matches(it)) "$it^{\\circ}" else it }
        steps += Step(
            (if (given) "Write $name in components" else "Change $name to components") + ": \\(x = r\\cos\\theta,\\ y = r\\sin\\theta\\)",
            "$name = \\begin{pmatrix} $r\\cos $a \\\\ $r\\sin $a \\end{pmatrix} " + (if (ev.approx) "\\approx " else "= ") + LA.vecTex(v.c),
            "A length of \\($r\\) at \\($a\\), measured anticlockwise from the positive x-axis."
        )
    }
}

/** Builds the steps and answer for the question part of a vector / matrix problem. */
internal class Presenter(val ev: Eval, val steps: MutableList<Step>) {

    private val eq get() = if (ev.approx) "\\approx" else "="

    fun present(n: LN, name: Char?): Solution {
        when (n) {
            is LN.Call -> return call(n, name)
            is LN.Bin -> if (n.op == '+' || n.op == '-') {
                val terms = flatten(n)
                val values = terms.map { (sign, t) -> sign to ev.ev(t) }
                if (values.all { it.second is LVal.Vec }) return vectorSum(terms, values.map { it.first to (it.second as LVal.Vec) }, name)
                if (values.all { it.second is LVal.Mat }) return Matrices.present(this, n, name)
            } else {
                val x = ev.ev(n.a)
                val y = ev.ev(n.b)
                when {
                    x is LVal.Vec && y is LVal.Vec && n.op == '·' -> return dot(n.a, n.b, x.c, y.c)
                    x is LVal.Vec && y is LVal.Vec && n.op == '×' -> return cross(n.a, n.b, x.c, y.c)
                    x is LVal.Vec && y is LVal.Vec -> throw MathError("Between two vectors use · for the dot product or × for the cross product")
                    x is LVal.Scal && y is LVal.Vec && n.op != '/' -> return scaled(n, x.s, n.b, y.c, name)
                    x is LVal.Vec && y is LVal.Scal -> return scaled(n, if (n.op == '/') Sym.pow(y.s, Sym.MINUS_ONE) else y.s, n.a, x.c, name)
                    x is LVal.Mat || y is LVal.Mat -> return Matrices.present(this, n, name)
                }
            }
            is LN.Neg -> {
                val v = ev.ev(n.a)
                if (v is LVal.Vec) return scaled(n, Sym.MINUS_ONE, n.a, v.c, name)
                if (v is LVal.Mat) return Matrices.present(this, n, name)
            }
            is LN.Pow -> {
                val v = ev.ev(n.a)
                if (v is LVal.Mat) return Matrices.present(this, n, name)
                if (v is LVal.Vec) return dot(n.a, n.a, v.c, v.c)
            }
            else -> {}
        }
        return when (val v = ev.ev(n)) {
            is LVal.Vec -> vectorInfo(n, v.c, name, polarAnswer = false)
            is LVal.Mat -> Matrices.present(this, n, name)
            is LVal.Scal -> scalar(n, v.s, name)
        }
    }

    fun equation(left: LN, right: LN): Solution {
        // A x = b  (x not defined yet)
        if (left is LN.Bin && (left.op == 'j' || left.op == '·') && left.b is LN.Name && (left.b as LN.Name).c !in ev.env) {
            val a = ev.mat(left.a, "Ax = b")
            val b = ev.ev(right)
            val bv = when (b) {
                is LVal.Vec -> b.c.map { LA.rat(it, "The right-hand side") }
                is LVal.Mat -> if (b.cols == 1) b.m.map { it[0] } else throw MathError("In Ax = b, b must be a vector")
                else -> throw MathError("In Ax = b, b must be a vector, like (5, 1)")
            }
            return Matrices.solveSystem(this, a.m, bv, (left.b as LN.Name).c, LinAlg.show(left.a), LinAlg.show(right))
        }
        throw MathError("For a matrix equation write A x = b (x not defined yet), or solve(A, b)")
    }

    // ---------------- helpers ----------------

    /** Short name for a term, for diagram labels and "a_x": a, 2a, −b; null for anything longer. */
    private fun short(n: LN): String? = when {
        n is LN.Name -> n.c.toString()
        n is LN.Bin && n.op == 'j' && n.a is LN.Num && n.b is LN.Name && (n.a as LN.Num).r.isInteger -> (n.a as LN.Num).text + (n.b as LN.Name).c
        else -> null
    }

    private fun label(n: LN) = short(n) ?: LinAlg.show(n)

    /** Names for two operands: their own (a, 2a) or, for typed-in vectors, a and b. */
    private fun names2(an: LN, bn: LN): Pair<String, String> {
        val x = short(an) ?: if (short(bn) == "a") "u" else "a"
        val y = short(bn) ?: if (x == "b") "v" else "b"
        return x to y
    }

    private fun showPair(an: LN, bn: LN, x: String, y: String, a: List<S>, b: List<S>) {
        if (an !is LN.Name || bn !is LN.Name) steps += Step("The vectors", "$x = ${LA.vecTex(a)},\\quad $y = ${LA.vecTex(b)}")
    }

    private fun flatten(n: LN, sign: Int = 1): List<Pair<Int, LN>> = when {
        n is LN.Bin && n.op == '+' -> flatten(n.a, sign) + flatten(n.b, sign)
        n is LN.Bin && n.op == '-' -> flatten(n.a, sign) + flatten(n.b, -sign)
        n is LN.Neg && n.a !is LN.Num -> flatten(n.a, -sign)
        else -> listOf(sign to n)
    }

    private fun pickName(avoid: Set<Char>): Char = listOf('c', 'r', 'R', 's', 'w').first { it !in avoid && it !in ev.env }

    private fun comp(label: String, axis: String) = when {
        label.length == 1 -> "${label}_{$axis}"
        Regex("^[0-9]+[A-Za-z]$").matches(label) -> "${label.dropLast(1)}${label.last()}_{$axis}"
        else -> "\\left($label\\right)_{$axis}"
    }

    // ---------------- vector sums: the head-to-tail picture ----------------

    private fun vectorSum(terms: List<Pair<Int, LN>>, values: List<Pair<Int, LVal.Vec>>, name: Char?): Solution {
        val dim = values[0].second.n
        if (values.any { it.second.n != dim }) throw MathError("All the vectors need the same number of components")
        val rName = name ?: pickName(terms.mapNotNull { (_, t) -> short(t)?.lastOrNull() }.toSet())
        val labels = terms.map { (_, t) -> short(t) }
        // Fallback names u, v, w for terms without a short name.
        val spare = ArrayDeque(listOf("u", "v", "w", "p", "q").filter { s -> labels.none { it == s } && s[0] !in ev.env })
        val names = labels.map { it ?: spare.removeFirstOrNull() ?: "v" }

        // Values of each term (before its sign), and conversions / multiplications shown first.
        val raw = terms.map { (_, t) -> ev.ev(t) as LVal.Vec }
        terms.forEachIndexed { i, (_, t) ->
            if (t is LN.Polar) Vectors.polarSteps(names[i], t, raw[i], ev, steps)
        }
        val defs = terms.indices.filter { labels[it] == null && terms[it].second !is LN.Polar }
        if (defs.isNotEmpty()) steps += Step("Name the vectors",
            defs.joinToString(",\\quad ") { "${names[it]} = ${LinAlg.show(terms[it].second)} = ${LA.vecTex(raw[it].c)}" })
        val scaledTerms = terms.indices.filter { labels[it] != null && labels[it]!!.length > 1 }
        if (scaledTerms.isNotEmpty()) steps += Step("Multiply by the numbers in front",
            scaledTerms.joinToString(",\\quad ") { "${labels[it]} = ${LinAlg.show(terms[it].second)} = ${LA.vecTex(raw[it].c)}" },
            "Multiplying a vector by a number multiplies each component.")

        val result = values.fold(List<S>(dim) { Sym.ZERO }) { acc, (s, v) ->
            acc.indices.map { add(acc[it], mul(Sym.num(s.toLong()), v.c[it])) }
        }
        val axes = if (dim == 2) listOf("x", "y") else if (dim == 3) listOf("x", "y", "z") else (1..dim).map { "$it" }
        val anySub = terms.drop(1).any { it.first < 0 } || terms[0].first < 0
        for ((k, ax) in axes.withIndex()) {
            val formula = terms.indices.joinToString("") { i ->
                val s = terms[i].first
                val sym = if (i == 0) (if (s < 0) "-" else "") else if (s < 0) " - " else " + "
                sym + comp(names[i], ax)
            }
            val numbers = terms.indices.joinToString("") { i ->
                val s = terms[i].first
                val sym = if (i == 0) (if (s < 0) "-" else "") else if (s < 0) " - " else " + "
                sym + (if (i == 0 && s > 0) LA.sTex(raw[i].c[k]) else LA.par(raw[i].c[k]))
            }
            steps += Step("${if (anySub) "Combine" else "Add"} the ${if (dim <= 3) "$ax-" else "#$ax "}components",
                "${rName}_{$ax} = $formula = $numbers = ${LA.sTex(result[k])}")
        }
        steps += Step("The resultant", "$rName = ${LA.vecTex(result)}",
            if (dim == 2) "Put the components back together. On the diagram, the vectors are placed head to tail and \\($rName\\) goes from the start to the end." else null)

        var approx: String? = null
        var diagram: VectorDiagram? = null
        if (dim == 2) {
            val md = magDir(rName.toString(), result)
            approx = md
            diagram = chainDiagram(values.mapIndexed { i, (s, v) ->
                Triple(if (s < 0) "-${names[i]}" else names[i], s, if (s < 0) v.c.map { Sym.neg(it) } else v.c)
            }, rName.toString(), result, raw)
        }
        val kind = when {
            terms.size == 2 && terms[1].first < 0 -> "Vector subtraction"
            anySub -> "Vector sum"
            else -> "Vector addition"
        }
        return Solution(kind, steps, "$rName $eq ${LA.vecTex(result)}", approx, diagram = diagram)
    }

    /** |v| and direction steps; returns the approx line "|c| = …, θ = …". */
    private fun magDir(name: String, v: List<S>): String? {
        val sumSq = add(v.map { Ops.times(it, it) })
        val m = if (ev.approx) LA.approxS(Math.sqrt(LA.dbl(sumSq))) else Sym.pow(sumSq, Sym.HALF)
        val dim = v.size
        val formula = (0 until dim).joinToString(" + ") { "${name}_{${"xyz"[it.coerceAtMost(2)]}}^2" }
        val nums = v.joinToString(" + ") { LA.parPow(it) + "^2" }
        val mid = if (!ev.approx && sumSq is S.Num && m != sumSq && Sym.latex(m) != "\\sqrt{${Sym.latex(sumSq)}}") " = \\sqrt{${LA.sTex(sumSq)}}" else ""
        val mDec = LA.dbl(m)
        steps += Step("Magnitude (Pythagoras)", "|$name| = \\sqrt{$formula} = \\sqrt{$nums}$mid $eq ${LA.sTex(m)}",
            if (m !is S.Num) "\\(|$name| \\approx ${Tex.decimal(mDec)}\\)" else null)
        val magText = if (m is S.Num) LA.sTex(m) else "${LA.sTex(m)} \\approx ${Tex.decimal(mDec)}"
        if (dim != 2 || mDec == 0.0) return "|$name| $eq $magText"
        val (thetaTex, exact) = direction(name, v[0], v[1])
        return "|$name| $eq $magText,\\quad \\theta ${if (exact) "=" else "\\approx"} $thetaTex"
    }

    /** Direction steps (angle from the positive x-axis, 0° to 360°). Returns (angle LaTeX, exact?). */
    private fun direction(name: String, xs: S, ys: S): Pair<String, Boolean> {
        val x = LA.dbl(xs)
        val y = LA.dbl(ys)
        val theta = Math.atan2(y, x).let { if (it < 0) it + 2 * Math.PI else it }
        val (tTex, exact) = LA.degrees(theta)
        val eqs = if (exact) "=" else "\\approx"
        if (Math.abs(x) < 1e-12 || Math.abs(y) < 1e-12) {
            val where = when {
                Math.abs(y) < 1e-12 && x > 0 -> "along the positive x-axis"
                Math.abs(y) < 1e-12 -> "along the negative x-axis"
                y > 0 -> "straight up the positive y-axis"
                else -> "straight down the negative y-axis"
            }
            steps += Step("Direction", "\\theta = $tTex", "\\($name\\) points $where.")
            return tTex to exact
        }
        val ratio: S = if (xs is S.Num && ys is S.Num) S.Num(ys.v.abs() / xs.v.abs()) else LA.approxS(Math.abs(y / x))
        val ref = Math.atan(Math.abs(y / x))
        val (refTex, refExact) = LA.degrees(ref)
        val quad = when { x > 0 && y > 0 -> 1; x < 0 && y > 0 -> 2; x < 0 -> 3; else -> 4 }
        val how = when (quad) {
            1 -> null
            2 -> "\\theta = 180^{\\circ} - $refTex"
            3 -> "\\theta = 180^{\\circ} + $refTex"
            else -> "\\theta = 360^{\\circ} - $refTex"
        }
        val refLine = "\\alpha = \\tan^{-1}\\left(\\frac{|${name}_{y}|}{|${name}_{x}|}\\right) = \\tan^{-1}\\left(${LA.sTex(ratio)}\\right) ${if (refExact) "=" else "\\approx"} $refTex"
        val qName = listOf("first", "second", "third", "fourth")[quad - 1]
        steps += Step("Direction: angle from the positive x-axis",
            if (how == null) "$refLine,\\quad \\theta = \\alpha $eqs $tTex" else "$refLine,\\quad $how $eqs $tTex",
            "\\($name\\) points into the $qName quadrant" + (if (how == null) ", so the angle is \\(\\alpha\\) itself." else ", so \\(\\alpha\\) is turned into the angle measured anticlockwise from the positive x-axis."))
        return tTex to exact
    }

    // ---------------- single vectors ----------------

    private fun vectorInfo(n: LN, v: List<S>, name: Char?, polarAnswer: Boolean): Solution {
        val nm = name?.toString() ?: (n as? LN.Name)?.c?.toString() ?: "v"
        if (n is LN.Polar) Vectors.polarSteps(nm, n, LVal.Vec(v), ev, steps)
        else if (name != null && n !is LN.VecLit && n !is LN.Name) steps += Step("Work it out", "$nm = ${LinAlg.show(n)} $eq ${LA.vecTex(v)}")
        else if (steps.isEmpty()) steps += Step("Start with", "$nm = ${LA.vecTex(v)}")
        val md = magDir(nm, v)
        val diagram = if (v.size == 2) singleDiagram(nm, v) else null
        val polarLine = if (v.size == 2 && LA.dbl(Ops.mag(v)) != 0.0) {
            val m = Ops.mag(v)
            val th = Math.atan2(LA.dbl(v[1]), LA.dbl(v[0])).let { if (it < 0) it + 2 * Math.PI else it }
            val (t, ex) = LA.degrees(th)
            "$nm ${if (ex && m is S.Num && !ev.approx) "=" else "\\approx"} ${if (m is S.Num) LA.sTex(m) else Tex.decimal(LA.dbl(m))}\\angle $t"
        } else null
        return when {
            polarAnswer && polarLine != null -> Solution("Polar form", steps, polarLine, "$nm $eq ${LA.vecTex(v)}", diagram = diagram)
            n is LN.Polar || name != null -> Solution(if (n is LN.Polar) "Polar form to components" else "Vector", steps,
                "$nm $eq ${LA.vecTex(v)}", listOfNotNull(md, if (n is LN.Polar) null else polarLine?.let { "\\quad $it" }).joinToString("").ifEmpty { null }, diagram = diagram)
            else -> Solution("Magnitude and direction", steps, md ?: "|$nm| = 0", polarLine, diagram = diagram)
        }
    }

    private fun scaled(n: LN, k: S, vn: LN, v: List<S>, name: Char?): Solution {
        val res = v.map { mul(k, it) }
        val vName = short(vn) ?: "v"
        val rName = name?.toString() ?: short(n) ?: LinAlg.show(n)
        if (short(vn) == null) steps += Step("The vector", "$vName = ${LinAlg.show(vn)} = ${LA.vecTex(v)}")
        val kTex = LA.par(k)
        steps += Step("Multiply each component by \\(${LA.sTex(k)}\\)",
            "$rName = $kTex\\begin{pmatrix}" + v.joinToString(" \\\\ ") { LA.sTex(it) } + "\\end{pmatrix} = \\begin{pmatrix}" +
                v.joinToString(" \\\\ ") { "$kTex \\cdot ${LA.par(it)}" } + "\\end{pmatrix} = ${LA.vecTex(res)}",
            when {
                LA.dbl(k) < 0 -> "A negative number flips the direction (and scales the length by \\(${LA.sTex(Sym.func("abs", k))}\\))."
                else -> "The direction stays the same; the length is multiplied by \\(${LA.sTex(k)}\\)."
            })
        val approx = if (v.size == 2) magDir(if (rName.length == 1) rName else "w", res) else null
        val diagram = if (v.size == 2) VectorDiagram(
            arrows = listOf(arrow(0.0, 0.0, v, vName, DStyle.INPUT), arrow(0.0, 0.0, res, rName.takeIf { it.length <= 3 }, DStyle.RESULT)),
            legend = listOf(DStyle.INPUT to vName, DStyle.RESULT to rName.take(8))
        ) else null
        return Solution("Scalar multiple of a vector", steps, "$rName $eq ${LA.vecTex(res)}", approx, diagram = diagram)
    }

    private fun dot(an: LN, bn: LN, a: List<S>, b: List<S>): Solution {
        val (x, y) = names2(an, bn)
        val d = Ops.dot(a, b)
        val ax = if (a.size <= 3) "xyz" else ""
        val formula = a.indices.joinToString(" + ") { i -> if (ax.isNotEmpty()) "${comp(x, ax[i].toString())}${comp(y, ax[i].toString())}" else "${x}_{${i + 1}}${y}_{${i + 1}}" }
        val nums = a.indices.joinToString(" + ") { "${LA.par(a[it])} \\cdot ${LA.par(b[it])}" }
        val prods = a.indices.joinToString(" + ") { LA.par(Ops.times(a[it], b[it])) }
        showPair(an, bn, x, y, a, b)
        steps += Step("Multiply matching components and add", "$x \\cdot $y = $formula = $nums = $prods = ${LA.sTex(d)}")
        val dv = LA.dbl(d)
        val note = when {
            Math.abs(dv) < 1e-12 -> "The dot product is 0, so the vectors are perpendicular (at 90°)."
            dv > 0 -> "Positive, so the angle between them is less than 90°."
            else -> "Negative, so the angle between them is more than 90°."
        }
        steps += Step("What it means", "$x \\cdot $y = ${LA.sTex(d)}", note)
        val approx = if (d !is S.Num) "\\approx ${Tex.decimal(dv)}" else null
        return Solution("Dot product", steps, "$x \\cdot $y $eq ${LA.sTex(d)}", approx,
            diagram = if (a.size == 2) pairDiagram(x, a, y, b) else null)
    }

    private fun cross(an: LN, bn: LN, a: List<S>, b: List<S>): Solution {
        val (x, y) = names2(an, bn)
        showPair(an, bn, x, y, a, b)
        if (a.size == 2 && b.size == 2) {
            val c = (Ops.cross(a, b) as LVal.Scal).s
            steps += Step("2D cross product", "$x \\times $y = ${comp(x, "x")}${comp(y, "y")} - ${comp(x, "y")}${comp(y, "x")} = " +
                "${LA.par(a[0])} \\cdot ${LA.par(b[1])} - ${LA.par(a[1])} \\cdot ${LA.par(b[0])} = ${LA.sTex(c)}",
                "In 2D the cross product is a single number (the z-part of the 3D cross product).")
            val cv = LA.dbl(c)
            steps += Step("What it means", "\\text{Area of the parallelogram} = |${LA.sTex(c)}| = ${LA.sTex(Sym.func("abs", c))}",
                when {
                    Math.abs(cv) < 1e-12 -> "It is 0, so the vectors are parallel (they point along the same line)."
                    cv > 0 -> "Positive: turning from \\($x\\) to \\($y\\) is anticlockwise."
                    else -> "Negative: turning from \\($x\\) to \\($y\\) is clockwise."
                })
            return Solution("Cross product (2D)", steps, "$x \\times $y $eq ${LA.sTex(c)}", if (c !is S.Num) "\\approx ${Tex.decimal(cv)}" else null,
                diagram = pairDiagram(x, a, y, b, parallelogram = true))
        }
        val c = (Ops.cross(a, b) as LVal.Vec).c
        steps += Step("3D cross product", "$x \\times $y = \\begin{pmatrix} a_y b_z - a_z b_y \\\\ a_z b_x - a_x b_z \\\\ a_x b_y - a_y b_x \\end{pmatrix} = " +
            "\\begin{pmatrix} ${LA.par(a[1])} \\cdot ${LA.par(b[2])} - ${LA.par(a[2])} \\cdot ${LA.par(b[1])} \\\\ ${LA.par(a[2])} \\cdot ${LA.par(b[0])} - ${LA.par(a[0])} \\cdot ${LA.par(b[2])} \\\\ " +
            "${LA.par(a[0])} \\cdot ${LA.par(b[1])} - ${LA.par(a[1])} \\cdot ${LA.par(b[0])} \\end{pmatrix} = ${LA.vecTex(c)}", "The result is perpendicular to both vectors.")
        return Solution("Cross product", steps, "$x \\times $y $eq ${LA.vecTex(c)}")
    }

    private fun angle(an: LN, bn: LN, a: List<S>, b: List<S>): Solution {
        val (x, y) = names2(an, bn)
        showPair(an, bn, x, y, a, b)
        val d = Ops.dot(a, b)
        val ma = Ops.mag(a)
        val mb = Ops.mag(b)
        steps += Step("Dot product", "$x \\cdot $y = " + a.indices.joinToString(" + ") { "${LA.par(a[it])} \\cdot ${LA.par(b[it])}" } + " = ${LA.sTex(d)}")
        steps += Step("Lengths", "|$x| = \\sqrt{" + a.joinToString(" + ") { LA.parPow(it) + "^2" } + "} = ${LA.sTex(ma)},\\quad " +
            "|$y| = \\sqrt{" + b.joinToString(" + ") { LA.parPow(it) + "^2" } + "} = ${LA.sTex(mb)}")
        val (theta, cos) = Ops.angleBetween(a, b)
        steps += Step("Use \\(\\cos\\theta = \\dfrac{$x \\cdot $y}{|$x|\\,|$y|}\\)",
            "\\cos\\theta = \\frac{${LA.sTex(d)}}{${LA.par(ma)} \\cdot ${LA.par(mb)}} = ${LA.sTex(cos)}" +
                (if (cos !is S.Num || !(cos.v.isInteger)) " \\approx ${Tex.decimal(LA.dbl(cos))}" else ""))
        val rad = LA.dbl(theta)
        val (deg, exact) = LA.degrees(rad)
        val radTex = if (theta !is S.Num) Sym.latex(theta) else Tex.decimal(rad)
        steps += Step("Take the inverse cosine", "\\theta = \\cos^{-1}\\left(${LA.sTex(cos)}\\right) ${if (exact) "=" else "\\approx"} $deg",
            "In radians: \\(\\theta ${if (theta !is S.Num) "=" else "\\approx"} $radTex\\).")
        return Solution("Angle between vectors", steps, "\\theta ${if (exact) "=" else "\\approx"} $deg",
            "${if (theta !is S.Num) "=" else "\\approx"} $radTex\\ \\text{rad}", diagram = if (a.size == 2) pairDiagram(x, a, y, b) else null)
    }

    private fun unit(n: LN, v: List<S>): Solution {
        val x = short(n) ?: "v"
        if (n !is LN.Name) steps += Step("The vector", "$x = ${LA.vecTex(v)}")
        val m = Ops.mag(v)
        steps += Step("Find the length", "|$x| = \\sqrt{" + v.joinToString(" + ") { LA.parPow(it) + "^2" } + "} = ${LA.sTex(m)}")
        val u = Ops.unit(v)
        val hat = if (x.length == 1) "\\hat{$x}" else "\\hat{u}"
        steps += Step("Divide each component by the length", "$hat = \\frac{1}{${LA.sTex(m)}}${LA.vecTex(v)} = ${LA.vecTex(u)}",
            "A unit vector has length 1 and points the same way as \\($x\\).")
        val approx = if (u.all { it is S.Num }) null else "\\approx \\begin{pmatrix}" + u.joinToString(" \\\\ ") { Tex.decimal(LA.dbl(it)) } + "\\end{pmatrix}"
        val diagram = if (v.size == 2) VectorDiagram(
            arrows = listOf(arrow(0.0, 0.0, v, x.takeIf { it.length <= 3 }, DStyle.INPUT), arrow(0.0, 0.0, u, "û", DStyle.RESULT)),
            legend = listOf(DStyle.INPUT to x, DStyle.RESULT to "unit vector")) else null
        return Solution("Unit vector", steps, "$hat $eq ${LA.vecTex(u)}", approx, diagram = diagram)
    }

    private fun proj(an: LN, bn: LN, a: List<S>, b: List<S>): Solution {
        val (x, y) = names2(an, bn)
        showPair(an, bn, x, y, a, b)
        val d = Ops.dot(a, b)
        val bb = Ops.dot(b, b)
        steps += Step("Dot products", "$x \\cdot $y = ${LA.sTex(d)},\\quad $y \\cdot $y = |$y|^2 = ${LA.sTex(bb)}")
        val scalarProj = Sym.div(d, Sym.pow(bb, Sym.HALF))
        steps += Step("Scalar projection (how far \\($x\\) reaches along \\($y\\))",
            "\\frac{$x \\cdot $y}{|$y|} = \\frac{${LA.sTex(d)}}{${LA.sTex(Sym.pow(bb, Sym.HALF))}} = ${LA.sTex(scalarProj)}")
        val p = Ops.proj(a, b)
        val k = Sym.div(d, bb)
        steps += Step("Vector projection", "\\operatorname{proj}_{$y} $x = \\frac{$x \\cdot $y}{|$y|^2}\\,$y = ${LA.sTex(k)}${LA.vecTex(b)} = ${LA.vecTex(p)}",
            "The part of \\($x\\) that points along \\($y\\). What's left, \\($x - \\operatorname{proj}_{$y} $x\\), is perpendicular to \\($y\\).")
        val diagram = if (a.size == 2) {
            val (px, py) = LA.dbl(p[0]) to LA.dbl(p[1])
            VectorDiagram(
                arrows = listOf(arrow(0.0, 0.0, b, y.takeIf { it.length <= 3 }, DStyle.INPUT), arrow(0.0, 0.0, a, x.takeIf { it.length <= 3 }, DStyle.INPUT),
                    DArrow(0.0, 0.0, px, py, "proj", DStyle.RESULT)),
                dashes = listOf(DSeg(LA.dbl(a[0]), LA.dbl(a[1]), px, py, DStyle.GUIDE)),
                legend = listOf(DStyle.INPUT to "$x, $y", DStyle.RESULT to "projection of $x onto $y"))
        } else null
        return Solution("Projection", steps, "\\operatorname{proj}_{$y} $x $eq ${LA.vecTex(p)}", "\\text{scalar projection} = ${LA.sTex(scalarProj)}", diagram = diagram)
    }

    private fun scalar(n: LN, s: S, name: Char?): Solution {
        val shown = LinAlg.show(n)
        val value = LA.sTex(s)
        steps += Step("Work it out", if (shown == value) value else "$shown = $value")
        val approx = if (s !is S.Num || !s.v.isInteger) "\\approx ${Tex.decimal(LA.dbl(s))}" else null
        return Solution("Calculation", steps, (name?.let { "$it = " } ?: "") + value, approx)
    }

    // ---------------- function calls ----------------

    private fun call(n: LN.Call, name: Char?): Solution {
        val a = n.args
        fun need(k: Int) { if (a.size != k) throw MathError("${n.name} needs $k input${if (k > 1) "s" else ""}, like ${Eval.usage(n.name)}") }
        return when (n.name) {
            "dot" -> { need(2); dot(a[0], a[1], ev.vec(a[0], "dot").c, ev.vec(a[1], "dot").c) }
            "cross" -> { need(2); cross(a[0], a[1], ev.vec(a[0], "cross").c, ev.vec(a[1], "cross").c) }
            "angle" -> { need(2); angle(a[0], a[1], ev.vec(a[0], "angle").c, ev.vec(a[1], "angle").c) }
            "unit" -> { need(1); unit(a[0], ev.vec(a[0], "unit").c) }
            "proj" -> { need(2); proj(a[0], a[1], ev.vec(a[0], "proj").c, ev.vec(a[1], "proj").c) }
            "polar" -> { need(1); vectorInfo(a[0], ev.vec(a[0], "polar").c, (a[0] as? LN.Name)?.c, polarAnswer = true) }
            "mag", "norm", "abs" -> {
                need(1)
                when (val v = ev.ev(a[0])) {
                    is LVal.Vec -> vectorInfo(a[0], v.c, null, polarAnswer = false)
                    is LVal.Mat -> Matrices.present(this, LN.Call("det", a), name)
                    is LVal.Scal -> scalar(n, Sym.func("abs", v.s), name)
                }
            }
            "det", "inv", "inverse", "transpose", "trace", "rank", "rref", "ref", "eig", "eigen", "lu", "chol", "diag" -> Matrices.present(this, n, name)
            "solve" -> {
                need(2)
                val m = ev.mat(a[0], "solve")
                val b = when (val v = ev.ev(a[1])) {
                    is LVal.Vec -> v.c.map { LA.rat(it, "The right-hand side") }
                    is LVal.Mat -> if (v.cols == 1) v.m.map { it[0] } else throw MathError("solve(A, b) needs b to be a vector")
                    else -> throw MathError("solve(A, b) needs b to be a vector, like (5, 1)")
                }
                Matrices.solveSystem(this, m.m, b, 'x', LinAlg.show(a[0]), LinAlg.show(a[1]))
            }
            else -> scalar(n, ev.scal(n), name)
        }
    }

    // ---------------- diagrams ----------------

    private fun arrow(x0: Double, y0: Double, v: List<S>, name: String?, style: DStyle) =
        DArrow(x0, y0, x0 + LA.dbl(v[0]), y0 + LA.dbl(v[1]), name, style)

    /** One vector from the origin with its components dashed and its angle marked. */
    private fun singleDiagram(name: String, v: List<S>): VectorDiagram {
        val x = LA.dbl(v[0])
        val y = LA.dbl(v[1])
        val labels = mutableListOf<DLabel>()
        if (Math.abs(x) > 1e-12) labels += DLabel(x / 2, 0.0, "${name}_x", DStyle.INPUT, 0, dy = if (y >= 0) 16.0 else -8.0)
        if (Math.abs(y) > 1e-12) labels += DLabel(0.0, y / 2, "${name}_y", DStyle.INPUT, if (x >= 0) -1 else 1, dx = if (x >= 0) -6.0 else 6.0)
        val th = Math.atan2(y, x).let { if (it < 0) it + 2 * Math.PI else it }
        return VectorDiagram(
            arrows = listOf(DArrow(0.0, 0.0, x, y, name, DStyle.INPUT)),
            dashes = listOf(DSeg(x, y, x, 0.0, DStyle.INPUT), DSeg(x, y, 0.0, y, DStyle.INPUT)),
            labels = labels,
            arcs = if (x * x + y * y > 0 && th > 1e-6) listOf(DArc(0.0, th, "θ")) else emptyList(),
            legend = listOf(DStyle.INPUT to name)
        )
    }

    /** Two vectors from the origin with the angle between them (and the parallelogram for ×). */
    private fun pairDiagram(x: String, a: List<S>, y: String, b: List<S>, parallelogram: Boolean = false): VectorDiagram {
        val ax = LA.dbl(a[0]); val ay = LA.dbl(a[1]); val bx = LA.dbl(b[0]); val by = LA.dbl(b[1])
        val t1 = Math.atan2(ay, ax)
        var t2 = Math.atan2(by, bx)
        var from = t1
        // Mark the smaller angle between them.
        var diff = t2 - t1
        while (diff > Math.PI) diff -= 2 * Math.PI
        while (diff < -Math.PI) diff += 2 * Math.PI
        if (diff < 0) { from = t1 + diff; t2 = t1 } else t2 = t1 + diff
        val dashes = if (parallelogram) listOf(DSeg(ax, ay, ax + bx, ay + by, DStyle.GUIDE), DSeg(bx, by, ax + bx, ay + by, DStyle.GUIDE)) else emptyList()
        return VectorDiagram(
            arrows = listOf(DArrow(0.0, 0.0, ax, ay, x.takeIf { it.length <= 3 }, DStyle.INPUT), DArrow(0.0, 0.0, bx, by, y.takeIf { it.length <= 3 }, DStyle.MOVED)),
            dashes = dashes,
            arcs = if (Math.abs(diff) > 1e-6) listOf(DArc(from, t2, "θ")) else emptyList(),
            legend = listOf(DStyle.INPUT to x, DStyle.MOVED to y)
        )
    }

    /**
     * Head-to-tail picture like the NASA diagram: each vector from the origin (blue), the later ones moved so each
     * starts at the tip of the one before (dark), and the resultant from the origin to the end (red).
     */
    private fun chainDiagram(terms: List<Triple<String, Int, List<S>>>, rName: String, result: List<S>, raw: List<LVal.Vec>): VectorDiagram {
        val arrows = mutableListOf<DArrow>()
        val dashes = mutableListOf<DSeg>()
        val labels = mutableListOf<DLabel>()
        // Given vectors from the origin (for subtraction, the flipped one is drawn).
        for ((label, _, v) in terms) arrows += DArrow(0.0, 0.0, LA.dbl(v[0]), LA.dbl(v[1]), label, DStyle.INPUT)
        var x = LA.dbl(terms[0].third[0])
        var y = LA.dbl(terms[0].third[1])
        for ((label, _, v) in terms.drop(1)) {
            val nx = x + LA.dbl(v[0])
            val ny = y + LA.dbl(v[1])
            arrows += DArrow(x, y, nx, ny, label, DStyle.MOVED)
            x = nx; y = ny
        }
        val cx = LA.dbl(result[0])
        val cy = LA.dbl(result[1])
        arrows += DArrow(0.0, 0.0, cx, cy, rName, DStyle.RESULT)
        val ax = LA.dbl(terms[0].third[0])
        val ay = LA.dbl(terms[0].third[1])
        if (terms.size == 2) {
            // Components, labelled like the NASA picture: a_x, b_x along the x-axis and a_y, b_y up the y-axis.
            val n1 = terms[0].first
            val n2 = terms[1].first
            dashes += DSeg(ax, ay, ax, 0.0, DStyle.INPUT)
            dashes += DSeg(ax, ay, 0.0, ay, DStyle.INPUT)
            val below = if (cy >= 0) 16.0 else -8.0
            val side = if (cx >= 0) -1 else 1
            val sdx = if (cx >= 0) -6.0 else 6.0
            if (Math.abs(ax) > 1e-12) labels += DLabel(ax / 2, 0.0, sub(n1, "x"), DStyle.INPUT, 0, dy = below)
            if (Math.abs(cx - ax) > 1e-12) labels += DLabel(ax + (cx - ax) / 2, 0.0, sub(n2, "x"), DStyle.MOVED, 0, dy = below)
            if (Math.abs(ay) > 1e-12) labels += DLabel(0.0, ay / 2, sub(n1, "y"), DStyle.INPUT, side, dx = sdx)
            if (Math.abs(cy - ay) > 1e-12) labels += DLabel(0.0, ay + (cy - ay) / 2, sub(n2, "y"), DStyle.MOVED, side, dx = sdx)
            val sign = if (n2.startsWith("-")) " - " else " + "
            val n2b = n2.removePrefix("-")
            labels += DLabel(cx / 2, cy, "${rName}_x = ${sub(n1, "x")}$sign${sub(n2b, "x")}", DStyle.RESULT, 0, dy = if (cy >= 0) -9.0 else 18.0)
            labels += DLabel(cx, cy / 2, "${rName}_y = ${sub(n1, "y")}$sign${sub(n2b, "y")}", DStyle.RESULT, if (cx >= 0) 1 else -1, dx = if (cx >= 0) 8.0 else -8.0)
        }
        dashes += DSeg(cx, cy, cx, 0.0, DStyle.GUIDE)
        dashes += DSeg(cx, cy, 0.0, cy, DStyle.GUIDE)
        val flipped = terms.any { it.second < 0 }
        return VectorDiagram(arrows, dashes, labels,
            legend = listOf(
                DStyle.INPUT to (if (flipped) "the vectors (subtracting = adding the flipped vector)" else "the vectors, from the origin"),
                DStyle.MOVED to "moved head to tail",
                DStyle.RESULT to "resultant $rName"
            ))
    }

    /** "a" + "x" -> "a_x";  "2a" -> "2a_x";  "-b" -> "-b_x". */
    private fun sub(label: String, axis: String) = "${label}_$axis"
}
