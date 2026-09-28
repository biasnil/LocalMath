package com.localmath.engine

object InequalitySolver {

    private fun tex(op: String) = when (op) { "≤" -> "\\le"; "≥" -> "\\ge"; else -> op }
    private fun flip(op: String) = when (op) { "<" -> ">"; ">" -> "<"; "≤" -> "≥"; else -> "≤" }
    private fun holds(op: String, value: Double) = when (op) {
        "<" -> value < 0; ">" -> value > 0; "≤" -> value <= 0; else -> value >= 0
    }
    private fun strict(op: String) = op == "<" || op == ">"

    fun solve(input: Input.Inequality): Solution {
        val vars = input.left.variables() + input.right.variables()
        if (input.left.hasCalculus() || input.right.hasCalculus()) throw MathError("d/dx and ∫ can't be used inside an inequality")
        if (vars.size > 1) throw MathError("Inequalities with more than one variable aren't supported yet")
        val v = vars.firstOrNull() ?: 'x'
        val op = input.op
        val steps = mutableListOf(Step("Start with", "${Tex.expr(input.left)} ${tex(op)} ${Tex.expr(input.right)}"))

        val l = RatFunc.from(input.left, v)
        val r = RatFunc.from(input.right, v)
        val f = (l - r)
        val (reduced, _) = f.reduced()

        val graphCurves = listOf(Graphs.curveOf(input.left, v), Graphs.curveOf(input.right, v).takeIf { !isZeroExpr(input.right) })

        if (vars.isEmpty()) {
            val value = f.evaluate(0.0)
            steps += Step("Evaluate", "${Tex.decimal(value)} ${tex(op)} 0")
            return Solution("Check a statement", steps, if (holds(op, value)) "\\text{True}" else "\\text{False}")
        }

        if (l.isPolynomial && r.isPolynomial && f.zeros.isEmpty() && reduced.num.degree <= 1) {
            return linear(l.num.scale(Rational.ONE / l.den[0]), r.num.scale(Rational.ONE / r.den[0]), op, v, steps, graphCurves)
        }
        return signChart(f, op, v, steps, graphCurves)
    }

    private fun isZeroExpr(e: Expr) = e is Expr.Num && e.value.isZero

    // ---------------- linear: isolate x ----------------

    private fun linear(left: Polynomial, right: Polynomial, op0: String, v: Char, steps: MutableList<Step>, curves: List<Curve?>): Solution {
        val diff = left - right
        val a = diff[1]
        val c = -diff[0]
        var op = op0
        if (a.isZero) {
            val value = -c.toDouble()
            steps += Step("The \\($v\\) terms cancel out", "${Tex.rational(-c)} ${tex(op)} 0")
            val ok = holds(op, value)
            steps += Step(if (ok) "This is always true" else "This is never true", if (ok) "\\text{every } $v \\text{ works}" else "\\text{no } $v \\text{ works}")
            return Solution("Linear inequality", steps, if (ok) "\\text{All real numbers}" else "\\text{No solution}",
                if (ok) "$v \\in \\mathbb{R}" else "$v \\in \\varnothing",
                Graphs.build(curves, intervals = if (ok) listOf(XInterval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, false, false)) else emptyList()))
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
        val interval = when (op) {
            "<" -> XInterval(Double.NEGATIVE_INFINITY, xd, false, false)
            "≤" -> XInterval(Double.NEGATIVE_INFINITY, xd, false, true)
            ">" -> XInterval(xd, Double.POSITIVE_INFINITY, false, false)
            else -> XInterval(xd, Double.POSITIVE_INFINITY, true, false)
        }
        val xTex = Tex.rational(x)
        val notation = when (op) {
            "<" -> "(-\\infty, $xTex)"
            "≤" -> "(-\\infty, $xTex]"
            ">" -> "($xTex, \\infty)"
            else -> "[$xTex, \\infty)"
        }
        val graph = Graphs.build(curves, focus = listOf(xd), intervals = listOf(interval))
        return Solution("Linear inequality", steps, "$v ${tex(op)} $xTex", "$v \\in $notation", graph)
    }

    // ---------------- general: sign chart ----------------

    private class Crit(val root: Root, var numeratorZero: Boolean, var excluded: Boolean)

    private fun signChart(f: RatFunc, op: String, v: Char, steps: MutableList<Step>, curves: List<Curve?>): Solution {
        val (g, _) = f.reduced()
        val fTex = g.format(v)
        val start = steps[0].math
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
            low && i == 0 -> "-\\infty"
            !low && i == xs.size -> "\\infty"
            low -> crits[i - 1].root.latex
            else -> crits[i].root.latex
        }
        val header = regions.indices.joinToString(" & ") { "(${end(it, true)}, ${end(it, false)})" }
        val tests = regions.joinToString(" & ") { Graphs.short(it.third) }
        val signRow = signs.joinToString(" & ") { if (it > 0) "+" else if (it < 0) "-" else "0" }
        steps += Step("Test one value in each interval",
            "\\begin{array}{l|${"c".repeat(regions.size)}} \\text{interval} & $header \\\\ \\hline \\text{test } $v & $tests \\\\ \\text{sign} & $signRow \\end{array}",
            "We want the expression to be ${when (op) { "<" -> "negative"; ">" -> "positive"; "≤" -> "negative or zero"; else -> "positive or zero" }}.")

        // Build the answer: which regions and which critical points are included.
        val regionOk = signs.map { holds(op, it) && it != 0.0 }
        val pointOk = crits.map { it.numeratorZero && !it.excluded && !strict(op) }

        val intervals = mutableListOf<XInterval>()
        val texParts = mutableListOf<String>()
        val wordParts = mutableListOf<String>()
        var i = 0
        // Walk the line: region 0, point 0, region 1, point 1, …
        val items = mutableListOf<Pair<Boolean, Int>>()   // (isRegion, index)
        for (k in regions.indices) {
            items += true to k
            if (k < crits.size) items += false to k
        }
        fun ok(item: Pair<Boolean, Int>) = if (item.first) regionOk[item.second] else pointOk[item.second]
        while (i < items.size) {
            if (!ok(items[i])) { i++; continue }
            var j = i
            while (j + 1 < items.size && ok(items[j + 1])) j++
            val a = items[i]
            val b = items[j]
            if (!a.first && i == j) {
                val c = crits[a.second]
                texParts += "\\{${c.root.latex}\\}"
                wordParts += "$v = ${c.root.latex}"
                intervals += XInterval(c.root.re, c.root.re, true, true)
            } else {
                val loTex = if (a.first) end(a.second, true) else crits[a.second].root.latex
                val hiTex = if (b.first) end(b.second, false) else crits[b.second].root.latex
                val loClosed = !a.first
                val hiClosed = !b.first
                val loVal = if (a.first) regions[a.second].first else crits[a.second].root.re
                val hiVal = if (b.first) regions[b.second].second else crits[b.second].root.re
                texParts += (if (loClosed) "[" else "(") + "$loTex, $hiTex" + (if (hiClosed) "]" else ")")
                intervals += XInterval(loVal, hiVal, loClosed, hiClosed)
                wordParts += when {
                    loVal.isInfinite() && hiVal.isInfinite() -> "\\text{all } $v"
                    loVal.isInfinite() -> "$v ${if (hiClosed) "\\le" else "<"} $hiTex"
                    hiVal.isInfinite() -> "$v ${if (loClosed) "\\ge" else ">"} $loTex"
                    else -> "$loTex ${if (loClosed) "\\le" else "<"} $v ${if (hiClosed) "\\le" else "<"} $hiTex"
                }
            }
            i = j + 1
        }

        val kind = if (g.isPolynomial) "Polynomial inequality" else "Rational inequality"
        val graph = Graphs.build(curves, focus = xs, intervals = intervals)
        if (texParts.isEmpty()) {
            steps += Step("No interval works", "\\text{No solution}")
            return Solution(kind, steps, "\\text{No solution}", "$v \\in \\varnothing", graph)
        }
        val notation = if (texParts.size == 1 && texParts[0] == "(-\\infty, \\infty)") "\\mathbb{R}" else texParts.joinToString(" \\cup ")
        val words = wordParts.joinToString(" \\;\\text{or}\\; ")
        steps += Step("Collect the intervals that work" + if (!strict(op)) " (including zeros, but never where it's undefined)" else "",
            "$v \\in $notation")
        val approxNeeded = crits.any { it.root.approximate }
        return Solution(kind, steps, "$v \\in $notation", if (approxNeeded) "\\text{(endpoints rounded)}\\;\\; $words" else words, graph)
    }

    /** A simple test value strictly between a and b (an integer if one fits). */
    private fun niceBetween(a: Double, b: Double): Double {
        val c = Math.floor(a) + 1
        if (c < b && c > a) return c
        val h = Math.round((a + b) / 2 * 100) / 100.0
        return if (h > a && h < b) h else (a + b) / 2
    }
}
