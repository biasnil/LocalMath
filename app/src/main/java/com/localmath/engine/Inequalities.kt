package com.localmath.engine

object InequalitySolver {

    private fun tex(op: String) = when (op) { "≤" -> "\\le"; "≥" -> "\\ge"; else -> op }
    private fun flip(op: String) = when (op) { "<" -> ">"; ">" -> "<"; "≤" -> "≥"; else -> "≤" }
    private fun holds(op: String, value: Double) = when (op) {
        "<" -> value < 0; ">" -> value > 0; "≤" -> value <= 0; else -> value >= 0
    }
    private fun strict(op: String) = op == "<" || op == ">"

    /** One piece of a solution set on the number line; a single point has lo = hi. */
    private data class Piece(val lo: Double, val loTex: String, val loClosed: Boolean,
                             val hi: Double, val hiTex: String, val hiClosed: Boolean) {
        val isPoint get() = lo == hi
    }

    /** What solving one inequality found: the pieces, and whether any endpoint is rounded. */
    private class SetResult(val kind: String, val pieces: List<Piece>, val approximate: Boolean, val focus: List<Double>)

    private const val NEG_INF = "-\\infty"
    private const val POS_INF = "\\infty"

    fun solve(input: Input.Inequality): Solution {
        val v = variableOf(listOf(input.left, input.right))
        val op = input.op
        val steps = mutableListOf(Step("Start with", "${Tex.expr(input.left)} ${tex(op)} ${Tex.expr(input.right)}"))
        val curves = listOf(Graphs.curveOf(input.left, v ?: 'x'), Graphs.curveOf(input.right, v ?: 'x').takeIf { !isZeroExpr(input.right) })

        if (v == null) return constantStatement(listOf(input.left, input.right), listOf(op), steps)
        val set = solveSet(input.left, op, input.right, v, steps)
        return finish(set, v, steps, curves)
    }

    /** a < f(x) < b: both inequalities must hold. */
    fun solveBetween(input: Input.Between): Solution {
        val parts = listOf(input.left, input.middle, input.right)
        val v = variableOf(parts)
        val start = "${Tex.expr(input.left)} ${tex(input.op1)} ${Tex.expr(input.middle)} ${tex(input.op2)} ${Tex.expr(input.right)}"
        val steps = mutableListOf(Step("Start with", start))
        val curves = parts.map { e -> Graphs.curveOf(e, v ?: 'x') }
        if (v == null) return constantStatement(parts, listOf(input.op1, input.op2), steps)

        threeParts(input, v, steps)?.let { return finish(it, v, steps, curves, compound = true) }

        steps += Step("It means two inequalities that must both be true",
            "${Tex.expr(input.left)} ${tex(input.op1)} ${Tex.expr(input.middle)} \\quad\\text{and}\\quad ${Tex.expr(input.middle)} ${tex(input.op2)} ${Tex.expr(input.right)}")
        val first = mutableListOf<Step>()
        val a = solveSet(input.left, input.op1, input.middle, v, first)
        steps += Step("Part 1", "${Tex.expr(input.left)} ${tex(input.op1)} ${Tex.expr(input.middle)}")
        steps += first.map { Step("  " + it.title, it.math, it.note) }
        steps += Step("  So part 1 gives", "$v \\in ${notation(a.pieces)}")
        val second = mutableListOf<Step>()
        val b = solveSet(input.middle, input.op2, input.right, v, second)
        steps += Step("Part 2", "${Tex.expr(input.middle)} ${tex(input.op2)} ${Tex.expr(input.right)}")
        steps += second.map { Step("  " + it.title, it.math, it.note) }
        steps += Step("  So part 2 gives", "$v \\in ${notation(b.pieces)}")
        val both = intersect(a.pieces, b.pieces)
        steps += Step("Keep only the values in both (the overlap)",
            "${grouped(a.pieces)} \\cap ${grouped(b.pieces)} = ${notation(both)}")
        val kind = if (a.kind.startsWith("Linear") && b.kind.startsWith("Linear")) "Compound inequality" else "Compound inequality (" +
            (if (a.kind.startsWith("Rational") || b.kind.startsWith("Rational")) "rational" else "polynomial") + ")"
        return finish(SetResult(kind, both, a.approximate || b.approximate, a.focus + b.focus), v, steps, curves, compound = true)
    }

    private fun variableOf(parts: List<Expr>): Char? {
        if (parts.any { it.hasCalculus() }) throw MathError("d/dx and ∫ can't be used inside an inequality")
        val vars = parts.flatMap { it.variables() }.toSet()
        if (vars.size > 1) throw MathError("Inequalities with more than one variable aren't supported yet")
        return vars.firstOrNull()
    }

    private fun constantStatement(parts: List<Expr>, ops: List<String>, steps: MutableList<Step>): Solution {
        val values = parts.map { RatFunc.from(it, null).evaluate(0.0) }
        val ok = ops.indices.all { holds(ops[it], values[it] - values[it + 1]) }
        steps += Step("Evaluate", values.mapIndexed { i, d -> Tex.decimal(d) + if (i < ops.size) " ${tex(ops[i])}" else "" }.joinToString(" "))
        return Solution("Check a statement", steps, if (ok) "\\text{True}" else "\\text{False}")
    }

    private fun isZeroExpr(e: Expr) = e is Expr.Num && e.value.isZero

    /** Solves left op right for [v], writing steps. */
    private fun solveSet(left: Expr, op: String, right: Expr, v: Char, steps: MutableList<Step>): SetResult {
        val l = RatFunc.from(left, v)
        val r = RatFunc.from(right, v)
        val f = (l - r)
        val (reduced, _) = f.reduced()
        if (l.isPolynomial && r.isPolynomial && f.zeros.isEmpty() && reduced.num.degree <= 1) {
            return linear(l.num.scale(Rational.ONE / l.den[0]), r.num.scale(Rational.ONE / r.den[0]), op, v, steps)
        }
        return signChart(f, op, v, steps, "${Tex.expr(left)} ${tex(op)} ${Tex.expr(right)}")
    }

    // ---------------- building the answer ----------------

    private fun finish(set: SetResult, v: Char, steps: MutableList<Step>, curves: List<Curve?>, compound: Boolean = false): Solution {
        val pieces = set.pieces
        val intervals = pieces.map { XInterval(it.lo, it.hi, it.loClosed, it.hiClosed) }
        val graph = Graphs.build(curves, focus = set.focus + pieces.flatMap { listOf(it.lo, it.hi) }, intervals = intervals)
        if (pieces.isEmpty()) {
            if (steps.last().math != "\\text{No solution}") steps += Step("No value works", "\\text{No solution}")
            return Solution(set.kind, steps, "\\text{No solution}", "$v \\in \\varnothing", graph)
        }
        val all = pieces.size == 1 && pieces[0].lo.isInfinite() && pieces[0].hi.isInfinite()
        if (all && set.kind == "Linear inequality") {
            return Solution(set.kind, steps, "\\text{All real numbers}", "$v \\in \\mathbb{R}", graph)
        }
        val notation = notation(pieces)
        val words = pieces.joinToString(" \\;\\text{or}\\; ") { words(it, v) }
        val rounded = if (set.approximate) "\\text{(endpoints rounded)}\\;\\; " else ""
        // A single ray from a linear inequality reads best as "x < 3".
        if (set.kind == "Linear inequality" && pieces.size == 1 && (pieces[0].lo.isInfinite() || pieces[0].hi.isInfinite())) {
            return Solution(set.kind, steps, words, "$v \\in $notation", graph)
        }
        if (compound && pieces.size == 1 && !pieces[0].isPoint && !all) {
            if (steps.last().math != "$v \\in $notation") steps += Step("Answer", "$v \\in $notation")
            return Solution(set.kind, steps, words, rounded + "$v \\in $notation", graph)
        }
        if (steps.last().math != "$v \\in $notation") steps += Step("Collect the intervals that work", "$v \\in $notation")
        return Solution(set.kind, steps, "$v \\in $notation", rounded + if (all) "\\text{all } $v" else words, graph)
    }

    private fun notation(pieces: List<Piece>): String {
        if (pieces.isEmpty()) return "\\varnothing"
        if (pieces.size == 1 && pieces[0].lo.isInfinite() && pieces[0].hi.isInfinite()) return "\\mathbb{R}"
        return pieces.joinToString(" \\cup ") { p ->
            if (p.isPoint) "\\{${p.loTex}\\}"
            else (if (p.loClosed) "[" else "(") + "${p.loTex}, ${p.hiTex}" + (if (p.hiClosed) "]" else ")")
        }
    }

    private fun grouped(pieces: List<Piece>) = if (pieces.size > 1) "\\left(${notation(pieces)}\\right)" else notation(pieces)

    private fun words(p: Piece, v: Char): String = when {
        p.isPoint -> "$v = ${p.loTex}"
        p.lo.isInfinite() && p.hi.isInfinite() -> "\\text{all } $v"
        p.lo.isInfinite() -> "$v ${if (p.hiClosed) "\\le" else "<"} ${p.hiTex}"
        p.hi.isInfinite() -> "$v ${if (p.loClosed) "\\ge" else ">"} ${p.loTex}"
        else -> "${p.loTex} ${if (p.loClosed) "\\le" else "<"} $v ${if (p.hiClosed) "\\le" else "<"} ${p.hiTex}"
    }

    /** Values in both sets. */
    private fun intersect(a: List<Piece>, b: List<Piece>): List<Piece> {
        val out = mutableListOf<Piece>()
        for (p in a) for (q in b) {
            val (lo, loTex, loClosed) = when {
                p.lo > q.lo + 1e-12 -> Triple(p.lo, p.loTex, p.loClosed)
                q.lo > p.lo + 1e-12 -> Triple(q.lo, q.loTex, q.loClosed)
                else -> Triple(p.lo, p.loTex, p.loClosed && q.loClosed)
            }
            val (hi, hiTex, hiClosed) = when {
                p.hi < q.hi - 1e-12 -> Triple(p.hi, p.hiTex, p.hiClosed)
                q.hi < p.hi - 1e-12 -> Triple(q.hi, q.hiTex, q.hiClosed)
                else -> Triple(p.hi, p.hiTex, p.hiClosed && q.hiClosed)
            }
            if (lo < hi - 1e-12) out += Piece(lo, loTex, loClosed, hi, hiTex, hiClosed)
            else if (Math.abs(lo - hi) <= 1e-12 && loClosed && hiClosed) out += Piece(lo, loTex, true, lo, loTex, true)
        }
        return out.sortedBy { it.lo }
    }

    // ---------------- a < linear < b: work on all three parts at once ----------------

    private fun threeParts(input: Input.Between, v: Char, steps: MutableList<Step>): SetResult? {
        if (input.left.variables().isNotEmpty() || input.right.variables().isNotEmpty()) return null
        val m = RatFunc.from(input.middle, v)
        if (!m.isPolynomial || m.zeros.isNotEmpty()) return null
        val mid = m.num.scale(Rational.ONE / m.den[0])
        if (mid.degree != 1) return null
        var lo = RatFunc.from(input.left, v).let { it.num[0] / it.den[0] }
        var hi = RatFunc.from(input.right, v).let { it.num[0] / it.den[0] }
        var op1 = input.op1
        var op2 = input.op2
        val a = mid[1]
        val c = mid[0]
        val term = Polynomial.monomial(a, 1).format(v)
        fun line(l: Rational, o1: String, middle: String, o2: String, h: Rational) =
            "${Tex.rational(l)} ${tex(o1)} $middle ${tex(o2)} ${Tex.rational(h)}"

        if (!(c.isZero && a.isOne)) {
            steps += Step("Whatever you do to the middle, do to all three parts", line(lo, op1, mid.format(v), op2, hi),
                "The goal is to get \\($v\\) on its own in the middle.")
        }
        if (!c.isZero) {
            lo -= c; hi -= c
            steps += Step(if (c.sign > 0) "Subtract \\(${Tex.rational(c)}\\) from all three parts" else "Add \\(${Tex.rational(-c)}\\) to all three parts",
                line(lo, op1, term, op2, hi))
        }
        if (!a.isOne) {
            lo /= a; hi /= a
            if (a.sign < 0) {
                op1 = flip(op1); op2 = flip(op2)
                steps += Step("Divide all three parts by \\(${Tex.rational(a)}\\). Dividing by a negative flips both signs",
                    line(lo, op1, "$v", op2, hi))
            } else {
                steps += Step("Divide all three parts by \\(${Tex.rational(a)}\\)", line(lo, op1, "$v", op2, hi))
            }
        }
        // Now lo op1 v op2 hi with both signs pointing the same way.
        if (op1 == ">" || op1 == "≥") {
            val t = lo; lo = hi; hi = t
            val o = op1; op1 = flip(op2); op2 = flip(o)
            steps += Step("Read it from left to right (smallest first)", line(lo, op1, "$v", op2, hi))
        }
        val loClosed = op1 == "≤"
        val hiClosed = op2 == "≤"
        val pieces = when {
            lo < hi -> listOf(Piece(lo.toDouble(), Tex.rational(lo), loClosed, hi.toDouble(), Tex.rational(hi), hiClosed))
            lo == hi && loClosed && hiClosed -> listOf(Piece(lo.toDouble(), Tex.rational(lo), true, lo.toDouble(), Tex.rational(lo), true))
            else -> {
                steps += Step("The left end isn't below the right end", "\\text{No solution}",
                    "No number can be bigger than \\(${Tex.rational(lo)}\\) and smaller than \\(${Tex.rational(hi)}\\) at the same time.")
                emptyList()
            }
        }
        return SetResult("Compound inequality", pieces, false, listOf(lo.toDouble(), hi.toDouble()))
    }

    // ---------------- linear: isolate x ----------------

    private fun linear(left: Polynomial, right: Polynomial, op0: String, v: Char, steps: MutableList<Step>): SetResult {
        val diff = left - right
        val a = diff[1]
        val c = -diff[0]
        var op = op0
        if (a.isZero) {
            val value = -c.toDouble()
            steps += Step("The \\($v\\) terms cancel out", "${Tex.rational(-c)} ${tex(op)} 0")
            val ok = holds(op, value)
            steps += Step(if (ok) "This is always true" else "This is never true", if (ok) "\\text{every } $v \\text{ works}" else "\\text{no } $v \\text{ works}")
            return SetResult("Linear inequality",
                if (ok) listOf(Piece(Double.NEGATIVE_INFINITY, NEG_INF, false, Double.POSITIVE_INFINITY, POS_INF, false)) else emptyList(),
                false, emptyList())
        }
        steps += Step("Collect the \\($v\\) terms on the left and the numbers on the right",
            "${Polynomial.monomial(a, 1).format(v)} ${tex(op)} ${Tex.rational(c)}")
        val x = c / a
        if (!a.isOne) {
            val title = if (a.sign < 0) {
                op = flip(op)
                "Divide both sides by \\(${Tex.rational(a)}\\). Dividing by a negative number flips the sign"
            } else "Divide both sides by \\(${Tex.rational(a)}\\)"
            steps += Step(title, "$v ${tex(op)} ${Tex.rational(x)}")
        }
        val xd = x.toDouble()
        val xTex = Tex.rational(x)
        val piece = when (op) {
            "<" -> Piece(Double.NEGATIVE_INFINITY, NEG_INF, false, xd, xTex, false)
            "≤" -> Piece(Double.NEGATIVE_INFINITY, NEG_INF, false, xd, xTex, true)
            ">" -> Piece(xd, xTex, false, Double.POSITIVE_INFINITY, POS_INF, false)
            else -> Piece(xd, xTex, true, Double.POSITIVE_INFINITY, POS_INF, false)
        }
        return SetResult("Linear inequality", listOf(piece), false, listOf(xd))
    }

    // ---------------- general: sign chart ----------------

    private class Crit(val root: Root, var numeratorZero: Boolean, var excluded: Boolean)

    private fun signChart(f: RatFunc, op: String, v: Char, steps: MutableList<Step>, start: String): SetResult {
        val (g, _) = f.reduced()
        val fTex = g.format(v)
        val oneSide = "$fTex ${tex(op)} 0"
        if (oneSide.replace(" ", "") != start.replace(" ", "")) {
            steps += Step(if (g.isPolynomial) "Move everything to one side" else "Move everything to one side and write it as a single fraction", oneSide)
        }

        val crits = mutableListOf<Crit>()
        fun addRoots(p: Polynomial, numeratorZero: Boolean) {
            if (p.isConstant) return
            val out = PolyEquation(v, mutableListOf()).solve(p, Polynomial.constant(Rational.ZERO))
            if (out !is Outcome.Roots) return
            for (root in out.roots.filter { it.isReal }) {
                val same = crits.firstOrNull { Math.abs(it.root.re - root.re) < 1e-9 }
                if (same == null) crits += Crit(root, numeratorZero, !numeratorZero)
                else if (numeratorZero) same.numeratorZero = true else same.excluded = true
            }
        }
        addRoots(g.num, numeratorZero = true)
        addRoots(g.den, numeratorZero = false)
        f.zeros.forEach { addRoots(it, numeratorZero = false) }
        crits.sortBy { it.root.re }

        val zerosText = crits.filter { it.numeratorZero && !it.excluded }.joinToString(",\\; ") { "$v = ${it.root.latex}" }
        val undefinedText = crits.filter { it.excluded }.joinToString(",\\; ") { "$v = ${it.root.latex}" }
        val lines = mutableListOf<String>()
        if (zerosText.isNotEmpty()) lines += "\\text{zero at } $zerosText"
        if (undefinedText.isNotEmpty()) lines += "\\text{undefined at } $undefinedText"
        steps += Step("Find the critical points (where it is zero or undefined)",
            if (lines.isEmpty()) "\\text{none — the sign never changes}" else lines.joinToString(" \\\\ ", "\\begin{gathered}", "\\end{gathered}"))

        // Regions between critical points, with a test value in each.
        val xs = crits.map { it.root.re }
        val regions = (0..xs.size).map { i ->
            val lo = if (i == 0) Double.NEGATIVE_INFINITY else xs[i - 1]
            val hi = if (i == xs.size) Double.POSITIVE_INFINITY else xs[i]
            val test = when {
                lo.isInfinite() && hi.isInfinite() -> 0.0
                lo.isInfinite() -> Math.floor(hi) - 1
                hi.isInfinite() -> Math.ceil(lo) + 1
                else -> niceBetween(lo, hi)
            }
            Triple(lo, hi, test)
        }
        val signs = regions.map { (_, _, t) -> g.evaluate(t) }

        fun end(i: Int, low: Boolean): String = when {
            low && i == 0 -> NEG_INF
            !low && i == xs.size -> POS_INF
            low -> crits[i - 1].root.latex
            else -> crits[i].root.latex
        }
        val header = regions.indices.joinToString(" & ") { "(${end(it, true)}, ${end(it, false)})" }
        val tests = regions.joinToString(" & ") { Graphs.short(it.third) }
        val signRow = signs.joinToString(" & ") { if (it > 0) "+" else if (it < 0) "-" else "0" }
        steps += Step("Test one value in each interval",
            "\\begin{array}{l|${"c".repeat(regions.size)}} \\text{interval} & $header \\\\ \\hline \\text{test } $v & $tests \\\\ \\text{sign} & $signRow \\end{array}",
            "We want the expression to be ${when (op) { "<" -> "negative"; ">" -> "positive"; "≤" -> "negative or zero"; else -> "positive or zero" }}.")

        // Which regions and which critical points are included.
        val regionOk = signs.map { holds(op, it) && it != 0.0 }
        val pointOk = crits.map { it.numeratorZero && !it.excluded && !strict(op) }

        val pieces = mutableListOf<Piece>()
        // Walk the line: region 0, point 0, region 1, point 1, …
        val items = mutableListOf<Pair<Boolean, Int>>()   // (isRegion, index)
        for (k in regions.indices) {
            items += true to k
            if (k < crits.size) items += false to k
        }
        fun ok(item: Pair<Boolean, Int>) = if (item.first) regionOk[item.second] else pointOk[item.second]
        var i = 0
        while (i < items.size) {
            if (!ok(items[i])) { i++; continue }
            var j = i
            while (j + 1 < items.size && ok(items[j + 1])) j++
            val a = items[i]
            val b = items[j]
            if (!a.first && i == j) {
                val c = crits[a.second]
                pieces += Piece(c.root.re, c.root.latex, true, c.root.re, c.root.latex, true)
            } else {
                val loTex = if (a.first) end(a.second, true) else crits[a.second].root.latex
                val hiTex = if (b.first) end(b.second, false) else crits[b.second].root.latex
                val loVal = if (a.first) regions[a.second].first else crits[a.second].root.re
                val hiVal = if (b.first) regions[b.second].second else crits[b.second].root.re
                pieces += Piece(loVal, loTex, !a.first, hiVal, hiTex, !b.first)
            }
            i = j + 1
        }
        if (pieces.isEmpty()) steps += Step("No interval works", "\\text{No solution}")
        else steps += Step("Collect the intervals that work" + if (!strict(op)) " (including zeros, but never where it's undefined)" else "",
            "$v \\in ${notation(pieces)}")
        val kind = if (g.isPolynomial) "Polynomial inequality" else "Rational inequality"
        return SetResult(kind, pieces, crits.any { it.root.approximate }, xs)
    }

    /** A simple test value strictly between a and b (an integer if one fits). */
    private fun niceBetween(a: Double, b: Double): Double {
        val c = Math.floor(a) + 1
        if (c < b && c > a) return c
        val h = Math.round((a + b) / 2 * 100) / 100.0
        return if (h > a && h < b) h else (a + b) / 2
    }
}
