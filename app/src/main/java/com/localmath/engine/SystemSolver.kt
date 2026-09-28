package com.localmath.engine

/** a₁x + a₂y + … + c, used to read each equation of a system. */
private class LinearForm(val coef: Map<Char, Rational>, val constant: Rational) {

    val isConstant get() = coef.values.all { it.isZero }

    operator fun plus(o: LinearForm) = LinearForm(merge(o, 1), constant + o.constant)
    operator fun minus(o: LinearForm) = LinearForm(merge(o, -1), constant - o.constant)
    fun scale(k: Rational) = LinearForm(coef.mapValues { it.value * k }, constant * k)

    private fun merge(o: LinearForm, sign: Int): Map<Char, Rational> {
        val out = coef.toMutableMap()
        for ((k, c) in o.coef) out[k] = (out[k] ?: Rational.ZERO) + (if (sign > 0) c else -c)
        return out.filterValues { !it.isZero }
    }

    companion object {
        fun constant(c: Rational) = LinearForm(emptyMap(), c)

        fun from(e: Expr): LinearForm = when (e) {
            is Expr.Num -> constant(e.value)
            is Expr.Var -> LinearForm(mapOf(e.name to Rational.ONE), Rational.ZERO)
            is Expr.Neg -> from(e.inner).scale(Rational.MINUS_ONE)
            is Expr.Add -> from(e.left) + from(e.right)
            is Expr.Sub -> from(e.left) - from(e.right)
            is Expr.Mul -> {
                val a = from(e.left)
                val b = from(e.right)
                when {
                    a.isConstant -> b.scale(a.constant)
                    b.isConstant -> a.scale(b.constant)
                    else -> throw MathError("Systems must be linear — no terms like xy or x²")
                }
            }
            is Expr.Div -> {
                val d = from(e.right)
                if (!d.isConstant) throw MathError("Systems must be linear — no variables in a denominator")
                if (d.constant.isZero) throw MathError("Division by zero")
                from(e.left).scale(Rational.ONE / d.constant)
            }
            is Expr.Pow -> {
                val base = from(e.base)
                val exp = from(e.exponent)
                if (!exp.isConstant || !exp.constant.isInteger) throw MathError("Systems must be linear")
                val n = exp.constant.num.toInt()
                when {
                    n == 1 -> base
                    base.isConstant -> constant(base.constant.pow(n))
                    n == 0 -> constant(Rational.ONE)
                    else -> throw MathError("Systems must be linear — no powers of variables")
                }
            }
            else -> throw MathError("Systems can only contain linear equations (no sin, ln, √, e, π)")
        }
    }
}

object SystemSolver {

    private const val MAX_VARS = 6

    fun solve(equations: List<Input.Equation>): Solution {
        val forms = equations.map { LinearForm.from(it.left) - LinearForm.from(it.right) }
        val vars = forms.flatMap { it.coef.keys }.toSortedSet().toList()
        if (vars.isEmpty()) throw MathError("The system has no variables")
        if (vars.size > MAX_VARS) throw MathError("Up to $MAX_VARS variables are supported")

        // Augmented rows: [a₁ … aₙ | b] for a₁x₁ + … + aₙxₙ = b
        val rows = forms.map { f -> (vars.map { f.coef[it] ?: Rational.ZERO } + (-f.constant)).toMutableList() }.toMutableList()
        val n = vars.size
        val steps = mutableListOf<Step>()

        steps += Step("Start with the system", cases(equations.map { Tex.equation(it.left, it.right) }))
        val standard = systemTex(rows, vars)
        val original = cases(equations.map { Tex.equation(it.left, it.right) })
        if (standard.replace(" ", "") != original.replace(" ", "")) {
            steps += Step("Write each equation in standard form", standard)
        }

        // Gauss–Jordan elimination.
        var pivotRow = 0
        val pivotCols = mutableListOf<Int>()
        for (col in 0 until n) {
            if (pivotRow >= rows.size) break
            val found = (pivotRow until rows.size).firstOrNull { !rows[it][col].isZero } ?: continue
            val name = vars[col]
            if (found != pivotRow) {
                val tmp = rows[found]; rows[found] = rows[pivotRow]; rows[pivotRow] = tmp
                steps += Step("Swap equations (${pivotRow + 1}) and (${found + 1}) so \\($name\\) appears in equation (${pivotRow + 1})",
                    systemTex(rows, vars))
            }
            val p = rows[pivotRow][col]
            if (!p.isOne) {
                val inv = Rational.ONE / p
                rows[pivotRow] = rows[pivotRow].map { it * inv }.toMutableList()
                val title = if (p.isInteger) "Divide equation (${pivotRow + 1}) by \\(${Tex.rational(p)}\\)"
                else "Multiply equation (${pivotRow + 1}) by \\(${Tex.rational(inv)}\\)"
                steps += Step(title, systemTex(rows, vars))
            }
            val ops = mutableListOf<String>()
            for (r in rows.indices) {
                if (r == pivotRow || rows[r][col].isZero) continue
                val f = rows[r][col]
                rows[r] = rows[r].mapIndexed { i, x -> x - f * rows[pivotRow][i] }.toMutableList()
                val sign = if (f.sign > 0) "-" else "+"
                val amount = f.abs()
                val mult = if (amount.isOne) "" else "${Tex.rational(amount)} \\times "
                ops += "\\((${r + 1}) $sign $mult(${pivotRow + 1})\\)"
            }
            if (ops.isNotEmpty()) {
                steps += Step("Eliminate \\($name\\) from the other equations", systemTex(rows, vars),
                    ops.joinToString(",  "))
            }
            pivotCols += col
            pivotRow++
        }

        // 0 = c with c ≠ 0 means no solution.
        rows.forEachIndexed { i, row ->
            if (row.dropLast(1).all { it.isZero } && !row.last().isZero) {
                steps += Step("Equation (${i + 1}) now says \\(0 = ${Tex.rational(row.last())}\\)",
                    "0 \\neq ${Tex.rational(row.last())}", "That's impossible, so the equations contradict each other.")
                return Solution("System of equations", steps, "\\text{No solution}", graph = graph(equations, forms, vars, null))
            }
        }

        val free = (0 until n).filter { it !in pivotCols }
        val assignments = pivotCols.mapIndexed { r, col ->
            // x_col = b − Σ (coef of free vars)·free
            val terms = mutableListOf<Pair<Rational, Char?>>()
            terms += rows[r][n] to null
            for (fc in free) if (!rows[r][fc].isZero) terms += -rows[r][fc] to vars[fc]
            "${vars[col]} = ${linear(terms)}"
        }

        if (free.isEmpty()) {
            val values = pivotCols.mapIndexed { r, col -> vars[col] to rows[r][n] }.toMap()
            steps += Step("Read off the solution", assignments.joinToString(",\\quad "))
            val checks = equations.mapIndexed { i, eq ->
                val l = evalLinear(LinearForm.from(eq.left), values)
                val r = evalLinear(LinearForm.from(eq.right), values)
                "\\text{(${i + 1})}\\;\\; ${Tex.rational(l)} = ${Tex.rational(r)}\\;\\checkmark"
            }
            steps += Step("Check in the original equations", checks.joinToString(" \\\\ ", "\\begin{gathered}", "\\end{gathered}"))
            val approx = if (values.values.all { it.isInteger }) null
            else values.entries.joinToString(",\\quad ") { (k, x) -> "$k \\approx ${Tex.decimal(x.toDouble())}" }
            return Solution("System of ${n} linear equations", steps, assignments.joinToString(",\\quad "), approx,
                graph(equations, forms, vars, values))
        }

        val freeNames = free.joinToString(", ") { vars[it].toString() }
        steps += Step("Some variables are free",
            (assignments + free.map { "${vars[it]} \\text{ is any number}" }).joinToString(",\\quad "),
            "There are fewer independent equations than variables, so \\($freeNames\\) can be chosen freely.")
        return Solution("System of equations", steps, "\\text{Infinitely many solutions}", graph = graph(equations, forms, vars, null), approx =
            (assignments).joinToString(",\\quad "))
    }

    /** For two variables: one line per equation, and the intersection point. */
    private fun graph(equations: List<Input.Equation>, forms: List<LinearForm>, vars: List<Char>, values: Map<Char, Rational>?): Graph? {
        if (vars.size != 2) return null
        val (h, k) = vars
        val curves = forms.mapIndexed { i, f ->
            val a = f.coef[h] ?: Rational.ZERO
            val b = f.coef[k] ?: Rational.ZERO
            val label = Tex.equation(equations[i].left, equations[i].right)
            when {
                !b.isZero -> {   // k = (−c − a·h) / b
                    val line = Sym.add(Sym.num(-f.constant / b), Sym.mul(Sym.num(-a / b), S.Var(h)))
                    Curve(Graphs.toJs(line, h), label)
                }
                !a.isZero -> Curve("NaN", label, verticalX = (-f.constant / a).toDouble())
                else -> null
            }
        }
        val points = if (values != null) {
            val x = values[h]!!.toDouble()
            val y = values[k]!!.toDouble()
            listOf(GraphPoint(x, y, Graphs.label(x, y)))
        } else emptyList()
        return Graphs.build(curves, points)
    }

    private fun evalLinear(f: LinearForm, values: Map<Char, Rational>) =
        f.coef.entries.fold(f.constant) { acc, (k, c) -> acc + c * (values[k] ?: Rational.ZERO) }

    private fun cases(lines: List<String>) = lines.mapIndexed { i, l -> "$l & \\text{(${i + 1})}" }
        .joinToString(" \\\\ ", "\\begin{cases} ", " \\end{cases}")

    private fun systemTex(rows: List<List<Rational>>, vars: List<Char>): String = cases(rows.map { row ->
        val lhs = linear(vars.indices.map { row[it] to vars[it] }, dropConstantZero = true)
        "$lhs = ${Tex.rational(row.last())}"
    })

    /** "2x - \frac{1}{2}y + 3" from (coef, var) pairs; var = null is the constant. */
    private fun linear(terms: List<Pair<Rational, Char?>>, dropConstantZero: Boolean = false): String {
        val nonZero = terms.filter { !it.first.isZero }
        if (nonZero.isEmpty()) return "0"
        // Equations list variables first ("2x + y"); solved forms put the number first ("x = 2 - y").
        val ordered = if (dropConstantZero) nonZero.filter { it.second != null } + nonZero.filter { it.second == null }
        else nonZero.filter { it.second == null } + nonZero.filter { it.second != null }
        val sb = StringBuilder()
        ordered.forEachIndexed { i, (c, name) ->
            val abs = c.abs()
            if (i == 0) { if (c.sign < 0) sb.append("-") } else sb.append(if (c.sign < 0) " - " else " + ")
            if (name == null) sb.append(Tex.rational(abs))
            else sb.append(if (abs.isOne) "" else Tex.rational(abs)).append(name)
        }
        return sb.toString()
    }
}
