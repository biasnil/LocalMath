package com.localmath.engine

import java.math.BigInteger

/** Exact matrix arithmetic (fractions) and the step-by-step working for matrix questions. */
internal object Matrices {

    // ================= plain arithmetic =================

    fun identity(n: Int): List<List<Rational>> = List(n) { i -> List(n) { j -> if (i == j) Rational.ONE else Rational.ZERO } }

    fun transpose(m: List<List<Rational>>) = m[0].indices.map { j -> m.indices.map { i -> m[i][j] } }

    fun addSub(a: List<List<Rational>>, b: List<List<Rational>>, plus: Boolean): List<List<Rational>> {
        if (a.size != b.size || a[0].size != b[0].size) {
            throw MathError("Matrices must be the same size to ${if (plus) "add" else "subtract"} (${a.size} × ${a[0].size} and ${b.size} × ${b[0].size})")
        }
        return a.indices.map { i -> a[i].indices.map { j -> if (plus) a[i][j] + b[i][j] else a[i][j] - b[i][j] } }
    }

    fun multiply(a: List<List<Rational>>, b: List<List<Rational>>): List<List<Rational>> {
        if (a[0].size != b.size) {
            throw MathError("Can't multiply a ${a.size} × ${a[0].size} matrix by a ${b.size} × ${b[0].size} one: " +
                "the first needs as many columns (${a[0].size}) as the second has rows (${b.size})")
        }
        return a.indices.map { i -> b[0].indices.map { j -> a[i].indices.fold(Rational.ZERO) { acc, k -> acc + a[i][k] * b[k][j] } } }
    }

    fun power(m: List<List<Rational>>, k: Int): List<List<Rational>> {
        if (m.size != m[0].size) throw MathError("Only square matrices have powers")
        var base = if (k < 0) inverse(m) ?: throw MathError("This matrix has no inverse (its determinant is 0), so negative powers don't exist") else m
        var e = Math.abs(k)
        var out = identity(m.size)
        while (e > 0) {
            if (e and 1 == 1) out = multiply(out, base)
            base = multiply(base, base)
            e = e shr 1
        }
        return out
    }

    fun trace(m: List<List<Rational>>) = m.indices.fold(Rational.ZERO) { acc, i -> acc + m[i][i] }

    fun det(m: List<List<Rational>>): Rational {
        val r = Reducer(m, 0, null)
        r.run(reduced = false, scale = false)
        if (r.pivots.size < m.size) return Rational.ZERO
        var d = if (r.swaps % 2 == 0) Rational.ONE else Rational.MINUS_ONE
        for (i in m.indices) d *= r.a[i][i]
        return d
    }

    fun inverse(m: List<List<Rational>>): List<List<Rational>>? {
        val n = m.size
        val r = Reducer(m.indices.map { m[it] + identity(n)[it] }, n, null)
        r.run(reduced = true)
        if (r.pivots.size < n) return null
        return r.a.map { it.subList(n, 2 * n).toList() }
    }

    fun rref(m: List<List<Rational>>) = Reducer(m, 0, null).also { it.run(true) }.a.map { it.toList() }
    fun ref(m: List<List<Rational>>) = Reducer(m, 0, null).also { it.run(false) }.a.map { it.toList() }
    fun rank(m: List<List<Rational>>) = Reducer(m, 0, null).also { it.run(false) }.pivots.size

    // ================= row reduction with steps =================

    /** Gaussian elimination. [aug] = how many columns sit to the right of a bar (0 for none). */
    class Reducer(rows: List<List<Rational>>, private val aug: Int, private val steps: MutableList<Step>?) {
        val a: MutableList<MutableList<Rational>> = rows.map { it.toMutableList() }.toMutableList()
        private val n = a.size
        private val m = a[0].size
        var swaps = 0
        val pivots = mutableListOf<Int>()

        fun tex(): String {
            if (aug == 0) return LA.matTex(a)
            val cols = "c".repeat(m - aug) + "|" + "c".repeat(aug)
            return "\\left[\\begin{array}{$cols}" + a.joinToString(" \\\\ ") { r -> r.joinToString(" & ") { Tex.rational(it) } } + "\\end{array}\\right]"
        }

        private fun r(i: Int) = "R_{${i + 1}}"

        /** [reduced] = zeros above pivots too (RREF); [scale] = make each pivot 1. */
        fun run(reduced: Boolean, scale: Boolean = true) {
            var row = 0
            for (col in 0 until m - aug) {
                if (row >= n) break
                val cands = (row until n).filter { !a[it][col].isZero }
                if (cands.isEmpty()) continue
                val p = cands.firstOrNull { a[it][col].abs().isOne } ?: cands[0]
                if (p != row) {
                    val t = a[p]; a[p] = a[row]; a[row] = t
                    swaps++
                    steps?.add(Step("Swap rows: \\(${r(row)} \\leftrightarrow ${r(p)}\\)", tex(),
                        if (cands[0] != row) "Column ${col + 1} needs a non-zero entry at the top." else "A pivot of 1 keeps the fractions away."))
                }
                if (scale && !a[row][col].isOne) {
                    val k = Rational.ONE / a[row][col]
                    a[row] = a[row].map { it * k }.toMutableList()
                    steps?.add(Step("Make the pivot 1: \\(${r(row)} \\to ${coef(k)}${r(row)}\\)", tex()))
                }
                val ops = mutableListOf<String>()
                val targets = if (reduced) (0 until n).filter { it != row } else (row + 1 until n).toList()
                for (t in targets) {
                    if (a[t][col].isZero) continue
                    val f = a[t][col] / a[row][col]
                    a[t] = a[t].indices.map { j -> a[t][j] - f * a[row][j] }.toMutableList()
                    ops += "${r(t)} \\to ${r(t)} ${if (f.sign > 0) "-" else "+"} ${coef(f.abs())}${r(row)}"
                }
                if (ops.isNotEmpty()) steps?.add(Step(
                    if (reduced) "Clear column ${col + 1} (above and below the pivot)" else "Make zeros below the pivot in column ${col + 1}",
                    tex(), ops.joinToString(",\\quad ") { "\\($it\\)" }))
                pivots += col
                row++
            }
        }

        /** "3", "\frac{1}{2}", "" for 1, "-" for −1. */
        private fun coef(k: Rational): String = when {
            k.isOne -> ""
            k == Rational.MINUS_ONE -> "-"
            else -> Tex.rational(k)
        }
    }

    // ================= questions =================

    fun present(p: Presenter, n: LN, name: Char?): Solution {
        val ev = p.ev
        val steps = p.steps
        return when {
            n is LN.Call -> call(p, n, name)
            n is LN.Bin && (n.op == '+' || n.op == '-') -> sum(p, n, name)
            n is LN.Bin -> {
                val x = ev.ev(n.a)
                val y = ev.ev(n.b)
                when {
                    n.op == '/' && x is LVal.Mat && y is LVal.Scal -> scaled(p, n.a, Sym.pow(y.s, Sym.MINUS_ONE), x.m, name, n)
                    x is LVal.Scal && y is LVal.Mat -> scaled(p, n.b, x.s, y.m, name, n)
                    x is LVal.Mat && y is LVal.Scal -> scaled(p, n.a, y.s, x.m, name, n)
                    x is LVal.Mat && y is LVal.Mat -> product(p, n.a, n.b, x.m, y.m, name, n)
                    x is LVal.Mat && y is LVal.Vec -> product(p, n.a, n.b, x.m, y.c.map { listOf(LA.rat(it)) }, name, n, vectorResult = true)
                    x is LVal.Vec && y is LVal.Mat -> product(p, n.a, n.b, listOf(x.c.map { LA.rat(it) }), y.m, name, n, vectorResult = true)
                    else -> throw MathError("Can't do that with ${Ops.what(x)} and ${Ops.what(y)}")
                }
            }
            n is LN.Neg -> scaled(p, n.a, Sym.MINUS_ONE, ev.mat(n.a, "−").m, name, n)
            n is LN.Pow -> {
                val m = ev.mat(n.a, "A power").m
                val e = n.e
                if (e is LN.Name && e.c == 'T' && 'T' !in ev.env) transposeQ(p, n.a, m, name)
                else {
                    val k = ev.intPower(ev.scal(e, "The power must be a number"))
                    if (k == -1) inverseQ(p, n.a, m, name) else powerQ(p, n.a, m, k, name)
                }
            }
            else -> info(p, n, ev.mat(n, "This").m, name)
        }.also { if (steps.isEmpty()) throw MathError("Nothing to work out") }
    }

    private fun lbl(n: LN) = if (n is LN.Name) n.c.toString() else LinAlg.show(n)
    private fun short(n: LN) = if (n is LN.Name) n.c.toString() else "A"

    /** Shows a matrix typed straight into the question (named matrices are already in "Given"). */
    private fun showOperand(p: Presenter, n: LN, m: List<List<Rational>>, as_: String = "A") {
        if (n !is LN.Name) p.steps += Step("The matrix", "$as_ = ${LA.matTex(m)}", "Size ${m.size} × ${m[0].size}.")
    }

    private fun result(label: String?, name: Char?, n: LN): String = when {
        name != null -> "$name = "
        label != null -> "$label = "
        else -> LinAlg.show(n).takeIf { it.length < 60 }?.let { "$it = " } ?: ""
    }

    private fun info(p: Presenter, n: LN, m: List<List<Rational>>, name: Char?): Solution {
        val nm = name?.toString() ?: lbl(n).takeIf { it.length == 1 } ?: "A"
        p.steps += Step("The matrix", "$nm = ${LA.matTex(m)}", "Size ${m.size} × ${m[0].size} (rows × columns).")
        var approx: String? = "${m.size} \\times ${m[0].size}"
        if (m.size == m[0].size) {
            val d = det(m)
            p.steps += Step("Determinant", "\\det $nm = ${Tex.rational(d)}",
                if (d.isZero) "It is 0, so \\($nm\\) has no inverse." else "It isn't 0, so \\($nm\\) has an inverse.")
            approx += ",\\quad \\det $nm = ${Tex.rational(d)}"
        }
        return Solution("Matrix", p.steps, "$nm = ${LA.matTex(m)}", approx)
    }

    // ---------------- + − and scalar multiples ----------------

    private fun sum(p: Presenter, n: LN, name: Char?): Solution {
        fun flat(x: LN, s: Int): List<Pair<Int, LN>> = when {
            x is LN.Bin && x.op == '+' -> flat(x.a, s) + flat(x.b, s)
            x is LN.Bin && x.op == '-' -> flat(x.a, s) + flat(x.b, -s)
            else -> listOf(s to x)
        }
        val terms = flat(n, 1)
        val mats = terms.map { p.ev.mat(it.second, "+").m }
        for ((i, t) in terms.withIndex()) if (t.second !is LN.Name) {
            p.steps += Step("Work out ${LinAlg.show(t.second)}", "${LinAlg.show(t.second)} = ${LA.matTex(mats[i])}")
        }
        var acc = mats[0].map { r -> r.map { it * Rational.of(terms[0].first.toLong()) } }
        for (i in 1 until terms.size) acc = addSub(acc, mats[i], terms[i].first > 0)
        val grid = mats[0].indices.map { r -> mats[0][0].indices.map { c ->
            terms.indices.joinToString("") { i ->
                val v = mats[i][r][c]
                when {
                    i == 0 -> (if (terms[0].first < 0) "-" else "") + LA.parR(v).let { if (terms[0].first < 0) it else Tex.rational(v) }
                    terms[i].first < 0 -> " - " + LA.parR(v)
                    else -> " + " + LA.parR(v)
                }
            }
        } }
        val anySub = terms.any { it.first < 0 }
        p.steps += Step(if (anySub) "Combine matching entries" else "Add matching entries", LA.gridTex(grid) + " = " + LA.matTex(acc),
            "Only matrices of the same size can be added or subtracted.")
        return Solution(if (terms.size == 2 && terms[1].first < 0) "Matrix subtraction" else "Matrix addition", p.steps,
            result(null, name, n) + LA.matTex(acc))
    }

    private fun scaled(p: Presenter, mn: LN, k: S, m: List<List<Rational>>, name: Char?, whole: LN): Solution {
        val kr = LA.rat(k, "A number multiplying a matrix")
        showOperand(p, mn, m)
        val out = m.map { r -> r.map { it * kr } }
        val grid = m.map { r -> r.map { "${LA.parR(kr)} \\cdot ${LA.parR(it)}" } }
        p.steps += Step("Multiply every entry by \\(${Tex.rational(kr)}\\)", LA.gridTex(grid) + " = " + LA.matTex(out))
        return Solution("Scalar multiple of a matrix", p.steps, result(null, name, whole) + LA.matTex(out))
    }

    // ---------------- products and powers ----------------

    private fun product(p: Presenter, an: LN, bn: LN, a: List<List<Rational>>, b: List<List<Rational>>, name: Char?, whole: LN, vectorResult: Boolean = false): Solution {
        if (an !is LN.Name) p.steps += Step("First", "${LA.matTex(a)}")
        if (bn !is LN.Name) p.steps += Step("Second", if (vectorResult && b[0].size == 1) LA.vecTex(b.map { S.Num(it[0]) }) else LA.matTex(b))
        val out = multiply(a, b)
        p.steps += Step("Check the sizes", "(${a.size} \\times ${a[0].size})(${b.size} \\times ${b[0].size}) \\to ${out.size} \\times ${out[0].size}",
            "The inside numbers match (${a[0].size}), so they can be multiplied. The outside numbers give the size of the answer.")
        val showEntries = out.size * out[0].size <= 9 && a[0].size <= 4
        if (showEntries) {
            val grid = out.indices.map { i -> out[0].indices.map { j ->
                a[i].indices.joinToString(" + ") { k -> "${LA.parR(a[i][k])} \\cdot ${LA.parR(b[k][j])}" }
            } }
            p.steps += Step("Each entry = row of the first · column of the second", LA.gridTex(grid),
                "Entry (row i, column j) multiplies row i of the first matrix with column j of the second, matching entries, and adds.")
        }
        p.steps += Step("Work out each entry", LA.matTex(out))
        if (!showEntries) p.steps.last().let { p.steps[p.steps.lastIndex] = it.copy(note = "Each entry is a row of the first matrix times a column of the second.") }
        val ans = if (vectorResult && out[0].size == 1) LA.vecTex(out.map { S.Num(it[0]) })
            else if (vectorResult && out.size == 1) LA.vecTex(out[0].map { S.Num(it) }) else LA.matTex(out)
        return Solution("Matrix multiplication", p.steps, result(null, name, whole) + ans)
    }

    private fun powerQ(p: Presenter, an: LN, m: List<List<Rational>>, k: Int, name: Char?): Solution {
        if (m.size != m[0].size) throw MathError("Only square matrices have powers")
        val a = short(an)
        showOperand(p, an, m, a)
        val label = "$a^{$k}"
        if (k == 0) {
            p.steps += Step("Any square matrix to the power 0 is the identity", "$label = I = ${LA.matTex(identity(m.size))}")
            return Solution("Matrix power", p.steps, "$label = ${LA.matTex(identity(m.size))}")
        }
        var base = m
        if (k < 0) {
            val inv = inverse(m) ?: throw MathError("This matrix has no inverse (its determinant is 0), so negative powers don't exist")
            p.steps += Step("A negative power uses the inverse", "$a^{$k} = \\left($a^{-1}\\right)^{${-k}},\\quad $a^{-1} = ${LA.matTex(inv)}")
            base = inv
        }
        val kk = Math.abs(k)
        val b = if (k < 0) "$a^{-1}" else a
        var cur = base
        for (i in 2..kk) {
            cur = multiply(cur, base)
            if (i <= 6 || i == kk) {
                p.steps += Step(if (i == 2) "Multiply \\($b\\) by itself" else "Multiply by \\($b\\) again",
                    (if (k < 0) "\\left($b\\right)^{$i}" else "$a^{$i}") + " = " + (if (i == 2) "$b\\,$b" else if (k < 0) "\\left($b\\right)^{${i - 1}}$b" else "$a^{${i - 1}}$a") +
                        " = ${LA.matTex(cur)}", if (i == 6 && kk > 7) "(Carrying on the same way up to the power $kk.)" else null)
            }
        }
        return Solution("Matrix power", p.steps, "${name?.let { "$it = " } ?: ""}$label = ${LA.matTex(cur)}")
    }

    private fun transposeQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        val a = short(an)
        showOperand(p, an, m, a)
        val t = transpose(m)
        p.steps += Step("Rows become columns", "$a^{T} = ${LA.matTex(t)}",
            "Row 1 of \\($a\\) is column 1 of \\($a^{T}\\), and so on. A ${m.size} × ${m[0].size} matrix becomes ${m[0].size} × ${m.size}.")
        return Solution("Transpose", p.steps, "${name?.let { "$it = " } ?: ""}$a^{T} = ${LA.matTex(t)}")
    }

    // ---------------- determinant ----------------

    private fun detQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        if (m.size != m[0].size) throw MathError("Only square matrices have a determinant (this one is ${m.size} × ${m[0].size})")
        val a = short(an)
        showOperand(p, an, m, a)
        val d = detSteps(p.steps, a, m)
        p.steps += Step("What it means", "\\det $a = ${Tex.rational(d)}",
            if (d.isZero) "The determinant is 0: \\($a\\) is singular (it has no inverse)."
            else "Not 0, so \\($a\\) has an inverse." + (if (m.size == 2) " Its size, ${Tex.rational(d.abs())}, is how much \\($a\\) scales areas." else ""))
        return Solution("Determinant", p.steps, "${name?.let { "$it = " } ?: ""}\\det $a = ${Tex.rational(d)}")
    }

    private fun vmat(m: List<List<Rational>>) = "\\begin{vmatrix}" + m.joinToString(" \\\\ ") { r -> r.joinToString(" & ") { Tex.rational(it) } } + "\\end{vmatrix}"

    private fun minor(m: List<List<Rational>>, i: Int, j: Int) =
        m.indices.filter { it != i }.map { r -> m[r].indices.filter { it != j }.map { m[r][it] } }

    /** Adds determinant steps and returns the value. */
    fun detSteps(steps: MutableList<Step>, a: String, m: List<List<Rational>>): Rational {
        val n = m.size
        when (n) {
            1 -> { steps += Step("A 1 × 1 determinant is the entry itself", "\\det $a = ${Tex.rational(m[0][0])}"); return m[0][0] }
            2 -> {
                val d = m[0][0] * m[1][1] - m[0][1] * m[1][0]
                steps += Step("2 × 2 determinant: \\(ad - bc\\)",
                    "\\det $a = ${vmat(m)} = ${LA.parR(m[0][0])} \\cdot ${LA.parR(m[1][1])} - ${LA.parR(m[0][1])} \\cdot ${LA.parR(m[1][0])} = ${Tex.rational(d)}",
                    "Multiply down the main diagonal, then subtract the other diagonal.")
                return d
            }
            3 -> {
                // Expand along the row or column with the most zeros.
                val rowZeros = m.indices.map { i -> m[i].count { it.isZero } }
                val colZeros = m.indices.map { j -> m.indices.count { m[it][j].isZero } }
                val bestRow = rowZeros.indices.maxByOrNull { rowZeros[it] }!!
                val bestCol = colZeros.indices.maxByOrNull { colZeros[it] }!!
                val useCol = colZeros[bestCol] > rowZeros[bestRow]
                val line = if (useCol) bestCol else bestRow
                val cells = (0 until 3).map { k -> if (useCol) k to line else line to k }
                val signs = listOf("+", "-", "+", "-")
                val parts = cells.map { (i, j) ->
                    val s = if ((i + j) % 2 == 0) 1 else -1
                    Triple(s, m[i][j], minor(m, i, j))
                }
                val formula = parts.withIndex().joinToString(" ") { (k, t) ->
                    val (s, v, mn) = t
                    val sym = if (k == 0) (if (s < 0) "-" else "") else if (s < 0) "-" else "+"
                    "$sym ${LA.parR(v)}${vmat(mn)}"
                }.trim()
                val where = if (useCol) "column ${line + 1}" else "row ${line + 1}"
                steps += Step("Expand along $where (cofactors)", "\\det $a = $formula",
                    "Each entry of $where times the 2 × 2 determinant left when its row and column are crossed out, with signs " +
                        "\\(\\begin{smallmatrix} + & - & + \\\\ - & + & - \\\\ + & - & + \\end{smallmatrix}\\)" +
                        (if (parts.any { it.second.isZero }) ". Zero entries drop out." else "."))
                val minors = parts.filter { !it.second.isZero }.map { (_, _, mn) -> mn to (mn[0][0] * mn[1][1] - mn[0][1] * mn[1][0]) }
                if (minors.isNotEmpty()) steps += Step("Work out the 2 × 2 determinants", minors.joinToString(",\\quad ") { (mn, d) ->
                    "${vmat(mn)} = ${LA.parR(mn[0][0])} \\cdot ${LA.parR(mn[1][1])} - ${LA.parR(mn[0][1])} \\cdot ${LA.parR(mn[1][0])} = ${Tex.rational(d)}"
                })
                var total = Rational.ZERO
                val terms = mutableListOf<String>()
                for ((s, v, mn) in parts) {
                    if (v.isZero) continue
                    val d = mn[0][0] * mn[1][1] - mn[0][1] * mn[1][0]
                    total += Rational.of(s.toLong()) * v * d
                    terms += (if (s < 0) "- " else "+ ") + "${LA.parR(v)} \\cdot ${LA.parR(d)}"
                }
                val t = terms.joinToString(" ").removePrefix("+ ").let { if (it.isEmpty()) "0" else it }
                steps += Step("Add them up", "\\det $a = $t = ${Tex.rational(total)}")
                return total
            }
            else -> {
                steps += Step("Row-reduce to a triangle", "\\det $a = ${vmat(m)}",
                    "Adding a multiple of one row to another doesn't change the determinant; each row swap flips its sign.")
                val r = Reducer(m, 0, steps)
                r.run(reduced = false, scale = false)
                if (r.pivots.size < n) {
                    steps += Step("A zero appears on the diagonal", "\\det $a = 0", "A column has no pivot, so the determinant is 0.")
                    return Rational.ZERO
                }
                var d = if (r.swaps % 2 == 0) Rational.ONE else Rational.MINUS_ONE
                for (i in 0 until n) d *= r.a[i][i]
                steps += Step("Multiply the diagonal", "\\det $a = " + (if (r.swaps % 2 == 1) "-" else "") +
                    (0 until n).joinToString(" \\cdot ") { LA.parR(r.a[it][it]) } + " = ${Tex.rational(d)}",
                    if (r.swaps > 0) "There ${if (r.swaps == 1) "was 1 row swap" else "were ${r.swaps} row swaps"}, so the sign ${if (r.swaps % 2 == 1) "flips" else "stays"}." else null)
                return d
            }
        }
    }

    // ---------------- inverse ----------------

    private fun inverseQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        if (m.size != m[0].size) throw MathError("Only square matrices can have an inverse (this one is ${m.size} × ${m[0].size})")
        val a = short(an)
        showOperand(p, an, m, a)
        val n = m.size
        val d = det(m)
        p.steps += Step("Check the determinant", "\\det $a = ${Tex.rational(d)}",
            if (d.isZero) "It is 0, so \\($a\\) has no inverse." else "It isn't 0, so the inverse exists.")
        if (d.isZero) return Solution("Inverse matrix", p.steps, "\\text{No inverse: } \\det $a = 0")
        val inv: List<List<Rational>>
        if (n == 2) {
            inv = inverse(m)!!
            val adj = listOf(listOf(m[1][1], -m[0][1]), listOf(-m[1][0], m[0][0]))
            p.steps += Step("2 × 2 inverse: swap \\(a\\) and \\(d\\), change the signs of \\(b\\) and \\(c\\), divide by the determinant",
                "$a^{-1} = \\frac{1}{${Tex.rational(d)}}${LA.matTex(adj)} = ${LA.matTex(inv)}")
        } else {
            p.steps += Step("Put the identity next to \\($a\\)", Reducer(m.indices.map { m[it] + identity(n)[it] }, n, null).tex(),
                "Row-reduce the left side to the identity; the same row operations turn the right side into \\($a^{-1}\\).")
            val r = Reducer(m.indices.map { m[it] + identity(n)[it] }, n, p.steps)
            r.run(reduced = true)
            inv = r.a.map { it.subList(n, 2 * n).toList() }
            p.steps += Step("Read off the right side", "$a^{-1} = ${LA.matTex(inv)}")
        }
        if (multiply(m, inv) != identity(n)) throw MathError("The inverse didn't check out — please report this problem")
        p.steps += Step("Check", "$a\\,$a^{-1} = ${LA.matTex(identity(n))} = I", "Multiplying back gives the identity, so the answer is right.")
        return Solution("Inverse matrix", p.steps, "${name?.let { "$it = " } ?: ""}$a^{-1} = ${LA.matTex(inv)}")
    }

    // ---------------- row echelon forms, rank, trace ----------------

    private fun echelonQ(p: Presenter, an: LN, m: List<List<Rational>>, reduced: Boolean, name: Char?, rankOnly: Boolean = false): Solution {
        val a = short(an)
        showOperand(p, an, m, a)
        if (an is LN.Name && p.steps.none { it.title.startsWith("Given") }) p.steps += Step("Start with", "$a = ${LA.matTex(m)}")
        val r = Reducer(m, 0, p.steps)
        r.run(reduced)
        val out = r.a.map { it.toList() }
        if (rankOnly) {
            val k = r.pivots.size
            p.steps += Step("Count the non-zero rows", "\\operatorname{rank} $a = $k",
                "The rank is the number of pivots (leading 1s)" + (if (k < Math.min(m.size, m[0].size)) " — some rows were combinations of others." else "."))
            return Solution("Rank", p.steps, "${name?.let { "$it = " } ?: ""}\\operatorname{rank} $a = $k")
        }
        if (p.steps.lastOrNull()?.math != LA.matTex(out)) p.steps += Step("Result", LA.matTex(out))
        val title = if (reduced) "Reduced row echelon form" else "Row echelon form"
        p.steps += Step(title, LA.matTex(out), if (reduced) "Each pivot is 1 and is the only non-zero entry in its column." else
            "Each pivot is 1 and has only zeros below it.")
        return Solution(title, p.steps, "${name?.let { "$it = " } ?: ""}\\operatorname{${if (reduced) "rref" else "ref"}}($a) = ${LA.matTex(out)}")
    }

    private fun traceQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        if (m.size != m[0].size) throw MathError("The trace needs a square matrix")
        val a = short(an)
        showOperand(p, an, m, a)
        val t = trace(m)
        p.steps += Step("Add the main diagonal", "\\operatorname{tr} $a = " + m.indices.joinToString(" + ") { LA.parR(m[it][it]) } + " = ${Tex.rational(t)}")
        return Solution("Trace", p.steps, "${name?.let { "$it = " } ?: ""}\\operatorname{tr} $a = ${Tex.rational(t)}")
    }

    // ---------------- eigenvalues ----------------

    /** det(A − λI) as a polynomial (Faddeev–LeVerrier). */
    fun charPoly(m: List<List<Rational>>): Polynomial {
        val n = m.size
        val c = Array(n + 1) { Rational.ZERO }
        c[n] = Rational.ONE
        var mk = List(n) { List(n) { Rational.ZERO } }
        for (k in 1..n) {
            val prev = multiply(m, mk)
            mk = prev.indices.map { i -> prev[i].indices.map { j -> prev[i][j] + (if (i == j) c[n - k + 1] else Rational.ZERO) } }
            val am = multiply(m, mk)
            c[n - k] = -trace(am) / Rational.of(k.toLong())
        }
        // c gives det(λI − A); det(A − λI) = (−1)^n det(λI − A).
        val sign = if (n % 2 == 0) Rational.ONE else Rational.MINUS_ONE
        return Polynomial((0..n).associateWith { c[it] * sign })
    }

    /** Basis of the null space of [m], scaled to whole numbers. */
    fun nullSpace(m: List<List<Rational>>): List<List<Rational>> {
        val r = Reducer(m, 0, null)
        r.run(reduced = true)
        val cols = m[0].size
        val free = (0 until cols).filter { it !in r.pivots }
        return free.map { f ->
            val v = MutableList(cols) { Rational.ZERO }
            v[f] = Rational.ONE
            r.pivots.forEachIndexed { row, pc -> v[pc] = -r.a[row][f] }
            integer(v)
        }
    }

    private fun integer(v: List<Rational>): List<Rational> {
        val lcm = v.fold(BigInteger.ONE) { acc, x -> acc / acc.gcd(x.den) * x.den }
        var w = v.map { it * Rational.of(lcm) }
        val g = w.fold(BigInteger.ZERO) { acc, x -> acc.gcd(x.num) }
        if (g.signum() != 0) w = w.map { it / Rational.of(g) }
        val first = w.firstOrNull { !it.isZero }
        if (first != null && first.sign < 0) w = w.map { -it }
        return w
    }

    private fun eigQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        val n = m.size
        if (n != m[0].size) throw MathError("Eigenvalues need a square matrix")
        val a = short(an)
        showOperand(p, an, m, a)
        val lam = "\\lambda"
        val minus = m.indices.map { i -> m[i].indices.map { j ->
            if (i == j) (if (m[i][j].isZero) "-$lam" else "${Tex.rational(m[i][j])} - $lam") else Tex.rational(m[i][j])
        } }
        val poly = charPoly(m)
        val polyTex = poly.format('λ').replace("λ", "\\lambda ")
        val detLine = if (n == 2) {
            "\\det($a - $lam I) = \\begin{vmatrix}${minus[0][0]} & ${minus[0][1]} \\\\ ${minus[1][0]} & ${minus[1][1]}\\end{vmatrix} = " +
                "(${minus[0][0]})(${minus[1][1]}) - ${LA.parR(m[0][1])} \\cdot ${LA.parR(m[1][0])} = $polyTex"
        } else "\\det($a - $lam I) = \\begin{vmatrix}" + minus.joinToString(" \\\\ ") { it.joinToString(" & ") } + "\\end{vmatrix} = $polyTex"
        p.steps += Step("Characteristic equation: \\(\\det($a - \\lambda I) = 0\\)", "$detLine = 0",
            "An eigenvector \\(v\\) satisfies \\($a v = \\lambda v\\), i.e. \\(($a - \\lambda I)v = 0\\) with \\(v \\neq 0\\), which needs \\(\\det($a - \\lambda I) = 0\\).")

        val polySteps = mutableListOf<Step>()
        val outcome = PolyEquation('λ', polySteps).solve(poly, Polynomial(emptyMap()))
        p.steps += polySteps.drop(0).map { it.copy(title = it.title.replace("λ", "\\lambda "), math = it.math.replace("λ", "\\lambda "), note = it.note?.replace("λ", "\\lambda ")) }
        val roots = when (outcome) {
            is Outcome.Roots -> PolyEquation.distinctRoots(outcome.roots)
            else -> throw MathError("Couldn't find the eigenvalues")
        }

        val lines = mutableListOf<String>()
        if (n == 2 && roots.any { it.exact == null }) {
            // Irrational or complex eigenvalues of a 2 × 2: exact formula.
            eigen2x2(p, a, m, lines)
        } else {
            var k = 1
            for (root in roots) {
                val l = root.exact
                if (l == null) {
                    lines += "\\lambda_{$k} ${if (root.approximate) "\\approx" else "="} ${root.latex.replace("λ", "\\lambda ")}"
                    k++; continue
                }
                val shifted = m.indices.map { i -> m[i].indices.map { j -> if (i == j) m[i][j] - l else m[i][j] } }
                val basis = nullSpace(shifted)
                for (v in basis) {
                    if (multiply(m, v.map { listOf(it) }) != v.map { listOf(it * l) }) throw MathError("An eigenvector didn't check out — please report this problem")
                }
                val vTex = basis.joinToString(",\\ ") { LA.vecTex(it.map { x -> S.Num(x) }) }
                p.steps += Step("For \\(\\lambda = ${Tex.rational(l)}\\): solve \\(($a - ${Tex.rational(l)}I)v = 0\\)",
                    "$a - ${if (l.sign < 0) "\\left(${Tex.rational(l)}\\right)" else Tex.rational(l)}I = ${LA.matTex(shifted)} \\;\\to\\; ${LA.matTex(rref(shifted))}" +
                        ",\\quad v = $vTex",
                    "Any non-zero multiple of " + (if (basis.size == 1) "this vector" else "a combination of these vectors") + " is also an eigenvector.")
                lines += "\\lambda_{$k} = ${Tex.rational(l)}: & v = $vTex"
                k++
            }
            if (roots.any { it.exact == null }) p.steps += Step("Eigenvectors",
                "\\text{Worked out for the whole-number and fraction eigenvalues}",
                "For matrices bigger than 2 × 2, eigenvectors for irrational or complex eigenvalues aren't worked out yet.")
        }
        val answer = if (lines.all { it.contains("&") }) "\\begin{aligned}" + lines.joinToString(" \\\\ ") + "\\end{aligned}"
            else "\\begin{aligned}" + lines.joinToString(" \\\\ ") { if (it.contains("&")) it else "$it &" } + "\\end{aligned}"
        return Solution("Eigenvalues and eigenvectors", p.steps, answer)
    }

    /** λ = (tr ± √D)/2, with eigenvector (b, λ − a) or (λ − d, c). */
    private fun eigen2x2(p: Presenter, a: String, m: List<List<Rational>>, lines: MutableList<String>) {
        val tr = m[0][0] + m[1][1]
        val dt = m[0][0] * m[1][1] - m[0][1] * m[1][0]
        val disc = tr * tr - Rational.of(4) * dt
        val half = Rational.of(1, 2)
        val sq = Sym.pow(S.Num(disc.abs()), Sym.HALF)
        val re = S.Num(tr * half)
        val part = Sym.mul(Sym.HALF, sq)
        for ((k, sign) in listOf(1, -1).withIndex()) {
            val s = Sym.num(sign.toLong())
            val eigen: Cx = if (disc.sign >= 0) Cx(Sym.add(re, Sym.mul(s, part)), Sym.ZERO) else Cx(re, Sym.mul(s, part))
            val vec: List<Cx> = if (!m[0][1].isZero) listOf(Complex.real(S.Num(m[0][1])), Complex.sub(eigen, Complex.real(S.Num(m[0][0]))))
                else if (!m[1][0].isZero) listOf(Complex.sub(eigen, Complex.real(S.Num(m[1][1]))), Complex.real(S.Num(m[1][0])))
                else if (k == 0) listOf(Complex.real(Sym.ONE), Complex.real(Sym.ZERO)) else listOf(Complex.real(Sym.ZERO), Complex.real(Sym.ONE))
            // Check A v = λ v numerically.
            val (v0r, v0i) = vec[0].approx(); val (v1r, v1i) = vec[1].approx(); val (lr, li) = eigen.approx()
            val r0 = m[0][0].toDouble() * v0r + m[0][1].toDouble() * v1r - (lr * v0r - li * v0i)
            val i0 = m[0][0].toDouble() * v0i + m[0][1].toDouble() * v1i - (lr * v0i + li * v0r)
            if (Math.abs(r0) > 1e-6 || Math.abs(i0) > 1e-6) throw MathError("An eigenvector didn't check out — please report this problem")
            val lt = Complex.tex(eigen)
            val vt = "\\begin{pmatrix}${Complex.tex(vec[0])} \\\\ ${Complex.tex(vec[1])}\\end{pmatrix}"
            p.steps += Step("For \\(\\lambda = $lt\\)", "v = $vt",
                if (!m[0][1].isZero) "The first row of \\(($a - \\lambda I)v = 0\\) reads \\((a - \\lambda)v_1 + b v_2 = 0\\), so \\(v = (b,\\ \\lambda - a)\\) works."
                else "The second row gives \\(v = (\\lambda - d,\\ c)\\).")
            lines += "\\lambda_{${k + 1}} = $lt: & v = $vt"
        }
        if (disc.sign < 0) p.steps += Step("Complex eigenvalues", "\\lambda = ${Complex.tex(Cx(re, part))},\\ ${Complex.tex(Cx(re, Sym.neg(part)))}",
            "They come as a conjugate pair: the matrix rotates vectors, so no real direction is left unchanged.")
    }

    // ---------------- A x = b ----------------

    fun solveSystem(p: Presenter, a: List<List<Rational>>, b: List<Rational>, xName: Char, aTex: String, bTex: String): Solution {
        if (a.size != b.size) throw MathError("A has ${a.size} rows, so b needs ${a.size} entries (it has ${b.size})")
        val n = a[0].size
        val names = (1..n).map { "${xName}_{$it}" }
        p.steps += Step("Write it as an augmented matrix \\([A \\mid b]\\)",
            Reducer(a.indices.map { a[it] + b[it] }, 1, null).tex(),
            "Each row is one equation: " + (0 until Math.min(a.size, 2)).joinToString("; ") { i ->
                "\\(" + (0 until n).joinToString(" + ") { j -> "${LA.parR(a[i][j])}${names[j]}" } + " = ${Tex.rational(b[i])}\\)"
            } + (if (a.size > 2) "; …" else "") + ".")
        val r = Reducer(a.indices.map { a[it] + b[it] }, 1, p.steps)
        r.run(reduced = true)
        val rows = r.a
        // A row 0 … 0 | c with c ≠ 0 means no solution.
        val bad = rows.indexOfFirst { row -> (0 until n).all { row[it].isZero } && !row[n].isZero }
        if (bad >= 0) {
            p.steps += Step("Row ${bad + 1} says \\(0 = ${Tex.rational(rows[bad][n])}\\)", "0 = ${Tex.rational(rows[bad][n])}",
                "That can never be true, so the system has no solution (the equations contradict each other).")
            return Solution("Matrix equation Ax = b", p.steps, "\\text{No solution}")
        }
        val free = (0 until n).filter { it !in r.pivots }
        if (free.isEmpty()) {
            val x = (0 until n).map { j -> rows[r.pivots.indexOf(j)][n] }
            if (multiply(a, x.map { listOf(it) }).map { it[0] } != b) throw MathError("The solution didn't check out — please report this problem")
            p.steps += Step("Read off the solution", names.indices.joinToString(",\\quad ") { "${names[it]} = ${Tex.rational(x[it])}" })
            p.steps += Step("Check: \\(A$xName = b\\)", "$aTex${LA.vecTex(x.map { S.Num(it) })} = ${LA.vecTex(b.map { S.Num(it) })}")
            return Solution("Matrix equation Ax = b", p.steps, "$xName = ${LA.vecTex(x.map { S.Num(it) })}")
        }
        // Infinitely many: free variables become parameters.
        val params = listOf("t", "s", "u", "w", "q", "p").filter { it[0] != xName }.take(free.size)
        val particular = MutableList(n) { Rational.ZERO }
        r.pivots.forEachIndexed { row, pc -> particular[pc] = rows[row][n] }
        val dirs = free.map { f ->
            val v = MutableList(n) { Rational.ZERO }
            v[f] = Rational.ONE
            r.pivots.forEachIndexed { row, pc -> v[pc] = -rows[row][f] }
            v
        }
        p.steps += Step("Some columns have no pivot",
            free.withIndex().joinToString(",\\quad ") { (k, f) -> "${names[f]} = ${params[k]}" },
            "So there are infinitely many solutions. " + (if (free.size == 1) "That variable is free" else "Those variables are free") +
                " — it can be any number.")
        val ans = "$xName = " + (if (particular.all { it.isZero }) "" else LA.vecTex(particular.map { S.Num(it) }) + " + ") +
            dirs.withIndex().joinToString(" + ") { (k, d) -> "${params[k]}${LA.vecTex(d.map { S.Num(it) })}" }
        p.steps += Step("Write the solution as vectors", ans)
        return Solution("Matrix equation Ax = b (infinitely many solutions)", p.steps, ans)
    }

    // ---------------- LU, Cholesky, diagonalization ----------------

    /** A = LU by elimination without scaling; the multipliers fill L. Row swaps (if a pivot is 0) give PA = LU. */
    private fun luQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        val n = m.size
        if (n != m[0].size) throw MathError("LU decomposition needs a square matrix")
        val a = short(an)
        showOperand(p, an, m, a)
        val u = m.map { it.toMutableList() }.toMutableList()
        val l = MutableList(n) { i -> MutableList(n) { j -> if (i == j) Rational.ONE else Rational.ZERO } }
        val perm = (0 until n).toMutableList()
        p.steps += Step("The idea", "$a = LU",
            "Row-reduce \\($a\\) to an upper-triangular \\(U\\) using only \\(R_i \\to R_i - mR_k\\). Each multiplier \\(m\\) is written into \\(L\\) (below its diagonal of 1s).")
        for (k in 0 until n) {
            if (u[k][k].isZero) {
                val r = (k + 1 until n).firstOrNull { !u[it][k].isZero } ?: continue
                u[k] = u[r].also { u[r] = u[k] }
                perm[k] = perm[r].also { perm[r] = perm[k] }
                for (j in 0 until k) l[k][j] = l[r][j].also { l[r][j] = l[k][j] }
                p.steps += Step("Pivot ${k + 1} is 0, so swap \\(R_{${k + 1}} \\leftrightarrow R_{${r + 1}}\\)", "U = ${LA.matTex(u)}",
                    "The swap is recorded in a permutation matrix \\(P\\), so the result is \\(PA = LU\\).")
            }
            val ops = mutableListOf<String>()
            for (i in k + 1 until n) {
                if (u[i][k].isZero) continue
                val f = u[i][k] / u[k][k]
                l[i][k] = f
                u[i] = u[i].indices.map { j -> u[i][j] - f * u[k][j] }.toMutableList()
                ops += "m_{${i + 1}${k + 1}} = ${Tex.rational(f)}:\\ R_{${i + 1}} \\to R_{${i + 1}} ${if (f.sign > 0) "-" else "+"} ${if (f.abs().isOne) "" else Tex.rational(f.abs())}R_{${k + 1}}"
            }
            if (ops.isNotEmpty()) p.steps += Step("Column ${k + 1}", "L = ${LA.matTex(l)},\\quad U = ${LA.matTex(u)}",
                ops.joinToString(",\\quad ") { "\\($it\\)" })
        }
        val pm = perm.map { r -> List(n) { j -> if (j == r) Rational.ONE else Rational.ZERO } }
        val pa = multiply(pm, m)
        if (multiply(l, u) != pa) throw MathError("The LU decomposition didn't check out — please report this problem")
        val swapped = perm != (0 until n).toList()
        p.steps += Step("Check", (if (swapped) "P$a" else a) + " = LU = ${LA.matTex(pa)}", "Multiplying \\(L\\) by \\(U\\) gives back " +
            (if (swapped) "\\($a\\) with its rows swapped." else "\\($a\\)."))
        val ans = (if (swapped) "P = ${LA.matTex(pm)},\\quad " else "") + "L = ${LA.matTex(l)},\\quad U = ${LA.matTex(u)}"
        return Solution(if (swapped) "LU decomposition (PA = LU)" else "LU decomposition", p.steps, ans)
    }

    /** A = LLᵀ, through A = L′DL′ᵀ with fractions: then L = L′√D, so every square root is of a fraction. */
    private fun cholQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        val n = m.size
        if (n != m[0].size) throw MathError("Cholesky decomposition needs a square matrix")
        val a = short(an)
        showOperand(p, an, m, a)
        if (transpose(m) != m) {
            p.steps += Step("Check symmetry", "$a^{T} \\neq $a", "Cholesky needs a symmetric matrix (the entries mirror across the diagonal).")
            return Solution("Cholesky decomposition", p.steps, "\\text{No Cholesky decomposition: } $a \\text{ isn't symmetric}")
        }
        p.steps += Step("Check symmetry", "$a^{T} = $a", "Good — Cholesky needs a symmetric matrix.")
        val lp = MutableList(n) { i -> MutableList(n) { j -> if (i == j) Rational.ONE else Rational.ZERO } }
        val d = MutableList(n) { Rational.ZERO }
        val lS = MutableList(n) { MutableList<S>(n) { Sym.ZERO } }
        for (j in 0 until n) {
            d[j] = m[j][j] - (0 until j).fold(Rational.ZERO) { acc, k -> acc + lp[j][k] * lp[j][k] * d[k] }
            if (d[j].sign <= 0) {
                p.steps += Step("Diagonal entry ${j + 1}", "l_{${j + 1}${j + 1}}^2 = ${Tex.rational(d[j])}",
                    "That isn't positive, so \\($a\\) isn't positive definite and has no Cholesky decomposition.")
                return Solution("Cholesky decomposition", p.steps, "\\text{No Cholesky decomposition: } $a \\text{ isn't positive definite}")
            }
            for (i in j + 1 until n) {
                lp[i][j] = (m[i][j] - (0 until j).fold(Rational.ZERO) { acc, k -> acc + lp[i][k] * lp[j][k] * d[k] }) / d[j]
            }
            val root = Sym.pow(S.Num(d[j]), Sym.HALF)
            for (i in j until n) lS[i][j] = Sym.mul(S.Num(lp[i][j]), root)
            val jj = "${j + 1}${j + 1}"
            val below = (j + 1 until n).joinToString("") { i ->
                ",\\quad l_{${i + 1}${j + 1}} = \\frac{a_{${i + 1}${j + 1}}" + (if (j > 0) " - \\sum_k l_{${i + 1}k}l_{${j + 1}k}" else "") +
                    "}{l_{$jj}} = ${LA.sTex(lS[i][j])}"
            }
            p.steps += Step("Column ${j + 1}",
                "l_{$jj} = \\sqrt{a_{$jj}" + (if (j > 0) " - \\sum_k l_{${j + 1}k}^2" else "") + "} = \\sqrt{${Tex.rational(d[j])}}" + (if (root is S.Num) " = ${LA.sTex(root)}" else "") + below,
                if (j == 0) "Work down one column at a time: the diagonal entry is a square root, the ones below are divided by it." else null)
        }
        // Check L Lᵀ = A numerically.
        for (i in 0 until n) for (j in 0 until n) {
            val v = (0 until n).sumOf { k -> LA.dbl(lS[i][k]) * LA.dbl(lS[j][k]) }
            if (Math.abs(v - m[i][j].toDouble()) > 1e-7 * (1 + Math.abs(v))) throw MathError("The Cholesky decomposition didn't check out — please report this problem")
        }
        val lTex = LA.gridTex(lS.map { r -> r.map { LA.sTex(it) } })
        p.steps += Step("Check", "LL^{T} = $a", "Multiplying \\(L\\) by its transpose gives back \\($a\\).")
        return Solution("Cholesky decomposition", p.steps, "L = $lTex,\\quad $a = LL^{T}")
    }

    /** A = PDP⁻¹ when every eigenvalue is a fraction and there are enough eigenvectors. */
    private fun diagQ(p: Presenter, an: LN, m: List<List<Rational>>, name: Char?): Solution {
        val n = m.size
        if (n != m[0].size) throw MathError("Only square matrices can be diagonalized")
        val a = short(an)
        showOperand(p, an, m, a)
        val poly = charPoly(m)
        val polyTex = poly.format('λ').replace("λ", "\\lambda ")
        val roots = when (val o = PolyEquation('λ', mutableListOf()).solve(poly, Polynomial(emptyMap()))) {
            is Outcome.Roots -> PolyEquation.distinctRoots(o.roots)
            else -> throw MathError("Couldn't find the eigenvalues")
        }
        p.steps += Step("Eigenvalues: solve \\(\\det($a - \\lambda I) = 0\\)", "$polyTex = 0",
            "\\(\\lambda = " + roots.joinToString(",\\ ") { it.latex.replace("λ", "\\lambda ") } + "\\)")
        if (roots.any { it.exact == null }) {
            return Solution("Diagonal matrix", p.steps, "\\text{Not worked out: the eigenvalues aren't all whole numbers or fractions}")
                .also { p.steps += Step("Stop here", "\\lambda = " + roots.joinToString(",\\ ") { it.latex },
                    "LocalMath diagonalizes only when every eigenvalue is a whole number or a fraction. Try eig($a) to see the eigenvectors.") }
        }
        val cols = mutableListOf<List<Rational>>()
        val diag = mutableListOf<Rational>()
        for (r in roots) {
            val l = r.exact!!
            val shifted = m.indices.map { i -> m[i].indices.map { j -> if (i == j) m[i][j] - l else m[i][j] } }
            val basis = nullSpace(shifted)
            p.steps += Step("Eigenvectors for \\(\\lambda = ${Tex.rational(l)}\\)",
                "($a - ${if (l.sign < 0) "\\left(${Tex.rational(l)}\\right)" else Tex.rational(l)}I)v = 0 \\;\\Rightarrow\\; v = " +
                    basis.joinToString(",\\ ") { LA.vecTex(it.map { x -> S.Num(x) }) })
            for (v in basis) { cols += v; diag += l }
        }
        if (cols.size < n) {
            p.steps += Step("Not enough eigenvectors", "${cols.size} < $n",
                "A repeated eigenvalue has fewer independent eigenvectors than it repeats, so \\($a\\) can't be written as \\(PDP^{-1}\\).")
            return Solution("Diagonal matrix", p.steps, "\\text{$a is not diagonalizable}")
        }
        val pm = transpose(cols)
        val dm = List(n) { i -> List(n) { j -> if (i == j) diag[i] else Rational.ZERO } }
        val pinv = inverse(pm) ?: throw MathError("The eigenvectors weren't independent — please report this problem")
        if (multiply(multiply(pm, dm), pinv) != m) throw MathError("The diagonalization didn't check out — please report this problem")
        p.steps += Step("Build P and D", "P = ${LA.matTex(pm)},\\quad D = ${LA.matTex(dm)}",
            "The eigenvectors are the columns of \\(P\\); the matching eigenvalues go down the diagonal of \\(D\\), in the same order.")
        p.steps += Step("Find \\(P^{-1}\\) and check", "P^{-1} = ${LA.matTex(pinv)},\\quad PDP^{-1} = ${LA.matTex(m)} = $a")
        return Solution("Diagonal matrix", p.steps, "$a = PDP^{-1},\\quad P = ${LA.matTex(pm)},\\quad D = ${LA.matTex(dm)}")
    }

    // ---------------- dispatch for function calls ----------------

    private fun call(p: Presenter, n: LN.Call, name: Char?): Solution {
        if (n.args.size != 1) throw MathError("${n.name} needs one matrix, like ${n.name}(A)")
        val an = n.args[0]
        val m = p.ev.mat(an, n.name).m
        return when (n.name) {
            "det", "abs", "mag", "norm" -> detQ(p, an, m, name)
            "inv", "inverse" -> inverseQ(p, an, m, name)
            "transpose" -> transposeQ(p, an, m, name)
            "trace" -> traceQ(p, an, m, name)
            "rank" -> echelonQ(p, an, m, false, name, rankOnly = true)
            "rref" -> echelonQ(p, an, m, true, name)
            "ref" -> echelonQ(p, an, m, false, name)
            "eig", "eigen" -> eigQ(p, an, m, name)
            "lu" -> luQ(p, an, m, name)
            "chol" -> cholQ(p, an, m, name)
            "diag" -> diagQ(p, an, m, name)
            else -> throw MathError("${n.name} doesn't work on matrices")
        }
    }
}
