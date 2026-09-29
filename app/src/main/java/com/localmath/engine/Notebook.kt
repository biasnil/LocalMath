package com.localmath.engine

/**
 * Notebook mode: a running list of problems where later lines can use values found earlier
 * (2x + 3 = 11 gives x = 4, then y = 3x + 2 uses it). Plain numbers work too, like a calculator tape.
 */
object Notebook {

    /** What one notebook line produced. [used] lists the earlier values it relied on (LaTeX). */
    data class Result(val solution: Solution, val used: String?)

    /** Values of single letters found in earlier answers, oldest first (later ones win). */
    fun known(answers: List<String?>): Map<Char, Expr> {
        val out = linkedMapOf<Char, Expr>()
        for (a in answers) if (a != null) out.putAll(valuesIn(a))
        return out
    }

    /** "x = 4" -> {x: 4};  "x = 2,\quad y = 1" -> both;  two roots ("or", ±) -> nothing (ambiguous). */
    fun valuesIn(answer: String): Map<Char, Expr> {
        if (answer.contains("\\text{or}") || answer.contains("\\pm") || answer.contains("\\in")) return emptyMap()
        val out = linkedMapOf<Char, Expr>()
        for (piece in answer.split(",\\quad", ",\\;", "\\quad")) {
            val p = piece.trim().removePrefix(",").trim()
            if (!p.contains("=")) continue
            val eq = try { Parser.parse(LatexInput.toText(p)) } catch (_: Exception) { continue } as? Input.Equation ?: continue
            val v = eq.left as? Expr.Var ?: continue
            if (eq.right.variables().isNotEmpty() || eq.right.has { it is Expr.Special || it is Expr.Derivative || it is Expr.Integral }) continue
            out[v.name] = eq.right
        }
        return out
    }

    /** The value to drop into the next line for "Ans": (plain text, LaTeX), or null if there isn't one number. */
    fun ansOf(answer: String?): Pair<String, String>? {
        if (answer == null) return null
        val a = answer.trim().removePrefix("\\approx").trim()
        valuesIn(a).values.singleOrNull()?.let { e ->
            if (valuesIn(a).size == 1) return textOf(e) to Tex.expr(e)
        }
        if (a.contains("=") || a.contains("\\text")) return null
        return try {
            val text = LatexInput.toText(a)
            val input = Parser.parse(text) as? Input.Expression ?: return null
            if (input.expr.variables().isNotEmpty() || input.expr.has { it is Expr.Special }) return null
            textOf(input.expr) to a
        } catch (_: Exception) { null }
    }

    private fun textOf(e: Expr): String {
        var t = LatexInput.toText(Tex.expr(e))
        val frac = Regex("\\(\\((-?[\\d.]+)\\)/\\(([\\d.]+)\\)\\)")
        while (frac.containsMatchIn(t)) t = frac.replace(t) { "${it.groupValues[1]}/${it.groupValues[2]}" }
        val wrapped = t.startsWith("(") && t.endsWith(")") && t.count { it == '(' } == 1
        return if (!wrapped && (t.contains(' ') || t.contains('/') || t.startsWith("-"))) "($t)" else t
    }

    /** Solves one line, filling in letters already known from earlier lines. */
    fun evaluate(text: String, known: Map<Char, Expr>): Result {
        val input = Parser.parse(text)
        // Calculus and Σ/lim keep their own letters (x in d/dx(x²) is not the x found earlier).
        val special = { e: Expr -> e.has { it is Expr.Special || it is Expr.Derivative || it is Expr.Integral } }
        val (sub, used) = when (input) {
            is Input.Expression -> {
                if (special(input.expr)) return Result(Solver.solve(input), null)
                val use = known.filterKeys { it in input.expr.variables() }
                Input.Expression(fill(input.expr, use)) to use
            }
            is Input.Equation -> {
                if (special(input.left) || special(input.right)) return Result(Solver.solve(input), null)
                // "y = …" defines y, so y itself is never replaced.
                val target = (input.left as? Expr.Var)?.name?.takeIf { it !in input.right.variables() }
                val vars = input.left.variables() + input.right.variables()
                val use = pick(known, vars, target)
                val right = fill(input.right, use)
                // "t = √2" or "y = 3·4 + 2": just work out the right side.
                if (target != null && right.variables().isEmpty()) {
                    val s = Solver.solve(Input.Expression(right))
                    val note = if (use.isEmpty()) null else use.entries.joinToString(",\\; ") { (k, v) -> "$k = ${Tex.expr(v)}" }
                    return Result(Solution("Value of \\($target\\)", s.steps, "$target = ${s.answer}", s.approx, s.graph), note)
                }
                Input.Equation(fill(input.left, use), right) to use
            }
            is Input.System -> {
                if (input.equations.any { special(it.left) || special(it.right) }) return Result(Solver.solve(input), null)
                val vars = input.equations.flatMap { it.left.variables() + it.right.variables() }.toSet()
                val use = pick(known, vars, null)
                Input.System(input.equations.map { Input.Equation(fill(it.left, use), fill(it.right, use)) }) to use
            }
            is Input.Inequality -> {
                val vars = input.left.variables() + input.right.variables()
                val use = pick(known, vars, null)
                Input.Inequality(fill(input.left, use), input.op, fill(input.right, use)) to use
            }
            is Input.Between -> {
                val vars = input.left.variables() + input.middle.variables() + input.right.variables()
                val use = pick(known, vars, null)
                Input.Between(fill(input.left, use), input.op1, fill(input.middle, use), input.op2, fill(input.right, use)) to use
            }
        }
        val solution = Solver.solve(sub)
        val note = if (used.isEmpty()) null else used.entries.joinToString(",\\; ") { (k, v) -> "$k = ${Tex.expr(v)}" }
        return Result(solution, note)
    }

    /** Known letters to fill in: never the one being defined, and not all of them (then it's a fresh problem). */
    private fun pick(known: Map<Char, Expr>, vars: Set<Char>, target: Char?): Map<Char, Expr> {
        val use = known.filterKeys { it in vars && it != target }
        return if (vars.all { it in use }) emptyMap() else use
    }

    private fun fill(e: Expr, values: Map<Char, Expr>): Expr =
        if (values.isEmpty()) e else e.mapNodes { n -> if (n is Expr.Var) values[n.name] else null }
}
