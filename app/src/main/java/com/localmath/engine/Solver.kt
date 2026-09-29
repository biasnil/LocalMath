package com.localmath.engine

/**
 * Entry point. Decides what the input is and hands it to the right part of the engine.
 */
object Solver {

    fun solve(source: String): Solution = solve(Parser.parse(source))

    fun solve(input: Input): Solution = when (input) {
        is Input.System -> system(input.equations)
        is Input.Inequality -> {
            if (input.left.has { it is Expr.Special } || input.right.has { it is Expr.Special })
                throw MathError("lim, Σ, aₙ and y' can't be used in inequalities")
            InequalitySolver.solve(input)
        }
        is Input.Expression -> expression(input.expr)
        is Input.Equation -> when {
            isOde(input) -> Ode.solve(listOf(input))
            input.left is Expr.Seq -> Sequences.solve(listOf(input))
            input.left.has { it is Expr.Special } || input.right.has { it is Expr.Special } ->
                throw MathError("For sequences write a_n = …; for differential equations use y'. lim and Σ go on their own.")
            else -> equation(input.left, input.right)
        }
    }

    private fun isOde(e: Input.Equation) =
        e.left.has { it is Expr.Prime } || e.right.has { it is Expr.Prime }

    private fun system(equations: List<Input.Equation>): Solution {
        val all = { test: (Expr) -> Boolean -> equations.any { it.left.has(test) || it.right.has(test) } }
        return when {
            all { it is Expr.Prime || it is Expr.Call } -> Ode.solve(equations)
            all { it is Expr.Seq } -> Sequences.solve(equations)
            all { it is Expr.Special } -> throw MathError("lim, Σ and ∞ can't be used in a system of equations")
            else -> SystemSolver.solve(equations)
        }
    }

    // ================= expressions =================

    private fun expression(e: Expr): Solution {
        if (e is Expr.Derivative) return Calculus.derivative(e)
        if (e is Expr.Integral) return Calculus.integral(e)
        if (e is Expr.Limit) return Limits.solve(e)
        if (e is Expr.Sum) return Series.solve(e)
        if (e.has { it is Expr.Special }) throw MathError("lim, Σ and ∞ need to be at the start, on their own")
        if (e.hasCalculus()) throw MathError("Put d/dx or ∫ at the start, on its own")
        val vars = e.variables()
        if (!e.hasFunctions() && vars.size <= 1) {
            val v = vars.firstOrNull()
            val rf = try { RatFunc.from(e, v) } catch (_: MathError) { null }
            if (rf != null) {
                return if (rf.isPolynomial && rf.zeros.isEmpty()) polynomialSimplify(e, v, rf)
                else rationalSimplify(e, v ?: 'x', rf)
            }
        }
        return Calculus.simplify(e)
    }

    private fun polynomialSimplify(e: Expr, v: Char?, rf: RatFunc): Solution {
        val poly = rf.num.scale(Rational.ONE / rf.den[0])
        val start = Step("Start with", Tex.expr(e))
        if (v == null) {
            val value = poly[0]
            return Solution("Arithmetic", listOf(start, Step("Evaluate", Tex.rational(value))),
                Tex.rational(value), approxOf(value))
        }
        val result = poly.format(v)
        val same = result.replace(" ", "") == Tex.expr(e).replace(" ", "")
        val step = if (same) Step("Already fully simplified", result) else Step("Expand and combine like terms", result)
        val graph = Graphs.build(listOf(Graphs.curveOf(e, v)?.copy(label = "y = $result")), focus = realRoots(poly, v).map { it.re })
        return Solution("Simplify", listOf(start, step), result, graph = graph)
    }

    private fun rationalSimplify(e: Expr, v: Char, rf: RatFunc): Solution {
        val steps = mutableListOf(Step("Start with", Tex.expr(e)))
        val combined = rf.normalized()
        if (combined.format(v).replace(" ", "") != Tex.expr(e).replace(" ", "")) {
            steps += Step("Write it as a single fraction (common denominator)", combined.format(v))
        }
        val (reduced, cancelled) = combined.reduced()
        val result = reduced.format(v)
        if (cancelled != null) {
            val g = cancelled.toPrimitiveIntegers().first
            val gTex = "\\left(${g.format(v)}\\right)"
            val n = reduced.num.format(v)
            val top = if (reduced.num.isConstant) gTex + (if (reduced.num[0].isOne) "" else Tex.rational(reduced.num[0])) else "$gTex\\left($n\\right)"
            val bottom = if (reduced.den.isConstant && reduced.den[0].isOne) g.format(v) else "$gTex\\left(${reduced.den.format(v)}\\right)"
            steps += Step("Cancel the common factor \\(${g.format(v)}\\)",
                "\\frac{$top}{$bottom} = $result",
                "Found with polynomial division (the greatest common divisor of top and bottom)")
        }
        val restrictions = restrictionRoots(rf.zeros, v)
        val note = if (restrictions.isEmpty()) null else restrictions.joinToString(",\\; ") { "$v \\neq ${it.latex}" }
        if (cancelled == null && steps.size == 1) steps += Step("Already fully simplified", result)
        if (note != null) steps += Step("Remember where the original is undefined", note,
            "The simplified form is equal to the original everywhere else.")
        val graph = Graphs.build(listOf(Graphs.curveOf(e, v)?.copy(label = "y = $result")), focus = restrictions.map { it.re })
        return Solution("Simplify a fraction", steps, result, note?.let { "\\text{for } $it" }, graph)
    }

    // ================= equations =================

    private fun equation(leftExpr: Expr, rightExpr: Expr): Solution {
        if (leftExpr.hasCalculus() || rightExpr.hasCalculus()) throw MathError("Put d/dx or ∫ on its own, not inside an equation")
        val vars = leftExpr.variables() + rightExpr.variables()
        if (vars.size > 1) {
            throw MathError("One equation with ${vars.sorted().joinToString(", ")} can't be solved on its own. " +
                "For a system, separate the equations with ;")
        }
        val steps = mutableListOf(Step("Start with", Tex.equation(leftExpr, rightExpr)))
        val v = vars.firstOrNull() ?: return checkStatement(leftExpr, rightExpr, steps)

        val l = RatFunc.from(leftExpr, v)
        val r = RatFunc.from(rightExpr, v)
        if (l.isPolynomial && r.isPolynomial && l.zeros.isEmpty() && r.zeros.isEmpty()) {
            val left = l.num.scale(Rational.ONE / l.den[0])
            val right = r.num.scale(Rational.ONE / r.den[0])
            val shown = "${left.format(v)} = ${right.format(v)}"
            if (shown.replace(" ", "") != Tex.equation(leftExpr, rightExpr).replace(" ", "")) {
                steps += Step("Expand and combine like terms on each side", shown)
            }
            val outcome = PolyEquation(v, steps).solve(left, right)
            return fromOutcome(v, outcome, steps, equationGraph(leftExpr, rightExpr, v, outcome, left, right))
        }
        return rationalEquation(leftExpr, rightExpr, l, r, v, steps)
    }

    private fun checkStatement(leftExpr: Expr, rightExpr: Expr, steps: MutableList<Step>): Solution {
        val l = Sym.from(leftExpr)
        val r = Sym.from(rightExpr)
        val same = Sym.sub(l, r) == Sym.ZERO || Math.abs(Sym.eval(l, emptyMap()) - Sym.eval(r, emptyMap())) < 1e-12
        steps += Step("Evaluate both sides", "${Sym.latex(l)} \\;\\text{vs}\\; ${Sym.latex(r)}")
        return Solution("Check a statement", steps, if (same) "\\text{True}" else "\\text{False}")
    }

    private fun rationalEquation(leftExpr: Expr, rightExpr: Expr, l: RatFunc, r: RatFunc, v: Char, steps: MutableList<Step>): Solution {
        val restrictions = restrictionRoots(l.zeros + r.zeros, v)
        if (restrictions.isNotEmpty()) {
            steps += Step("Restrictions: a denominator can't be zero",
                restrictions.joinToString(",\\; ") { "$v \\neq ${it.latex}" })
        }
        val (lr, _) = l.reduced()
        val (rr, _) = r.reduced()
        val single = "${lr.format(v)} = ${rr.format(v)}"
        val bothPolynomial = lr.den.isConstant && rr.den.isConstant
        if (single.replace(" ", "") != Tex.equation(leftExpr, rightExpr).replace(" ", "")) {
            steps += Step(if (bothPolynomial) "Simplify each side (cancel common factors)" else "Write each side as a single fraction", single)
        }
        val newL = lr.num * rr.den
        val newR = rr.num * lr.den
        if (!bothPolynomial) {
            val title = when {
                rr.den.isConstant -> "Multiply both sides by \\(${lr.den.format(v)}\\)"
                lr.den.isConstant -> "Multiply both sides by \\(${rr.den.format(v)}\\)"
                else -> "Cross-multiply (multiply both sides by both denominators)"
            }
            steps += Step(title, "${newL.format(v)} = ${newR.format(v)}")
        }

        val outcome = PolyEquation(v, steps).solve(newL, newR)
        val zeros = l.zeros + r.zeros
        val restrictedText = restrictions.joinToString(",\\; ") { "$v \\neq ${it.latex}" }
        val final: Outcome = when (outcome) {
            Outcome.Identity -> {
                val except = restrictions.joinToString(",\\; ") { it.latex }
                return Solution("Rational equation", steps,
                    if (restrictions.isEmpty()) "\\text{Every value of } $v \\text{ works}"
                    else "\\text{Every } $v \\text{ except } $v = $except",
                    if (restrictions.isEmpty()) null else restrictedText,
                    equationGraph(leftExpr, rightExpr, v, outcome, null, null))
            }
            Outcome.Contradiction -> outcome
            is Outcome.Roots -> {
                fun bad(root: Root) = root.isReal && zeros.any { z ->
                    if (root.exact != null) z.evaluate(root.exact).isZero else Math.abs(z.evaluate(root.re)) < 1e-9
                }
                val rejected = outcome.roots.filter { bad(it) }
                val kept = outcome.roots.filter { !bad(it) }
                if (outcome.roots.any { it.isReal } && zeros.isNotEmpty()) {
                    if (rejected.isEmpty()) {
                        steps += Step("Check against the restrictions", kept.filter { it.isReal }.joinToString(",\\; ") { "$v = ${it.latex}" } + "\\;\\checkmark",
                            "None of them makes a denominator zero.")
                    } else {
                        steps += Step("Check against the restrictions",
                            rejected.joinToString(",\\; ") { "$v = ${it.latex}" } + "\\;\\text{ is rejected}",
                            "It makes a denominator zero, so it's an extraneous solution created by multiplying.")
                    }
                }
                Outcome.Roots("Rational equation", kept,
                    combined = if (rejected.isEmpty()) outcome.combined else null,
                    complexText = if (rejected.isEmpty()) outcome.complexText else null)
            }
        }
        return fromOutcome(v, final, steps, equationGraph(leftExpr, rightExpr, v, final, null, null))
    }

    /** Real roots of each polynomial in [zeros] (for "x ≠ …" restrictions), duplicates removed. */
    private fun restrictionRoots(zeros: List<Polynomial>, v: Char): List<Root> =
        PolyEquation.distinctRoots(zeros.filter { !it.isConstant }.flatMap { realRoots(it, v) })

    private fun realRoots(p: Polynomial, v: Char): List<Root> {
        if (p.isConstant) return emptyList()
        val out = PolyEquation(v, mutableListOf()).solve(p, Polynomial.constant(Rational.ZERO))
        return if (out is Outcome.Roots) out.roots.filter { it.isReal } else emptyList()
    }

    private fun fromOutcome(v: Char, outcome: Outcome, steps: List<Step>, graph: Graph?): Solution = when (outcome) {
        Outcome.Identity -> Solution("Identity", steps, "\\text{Every value of } $v \\text{ works}", graph = graph)
        Outcome.Contradiction -> Solution("Contradiction", steps, "\\text{No solution}", graph = graph)
        is Outcome.Roots -> {
            val real = PolyEquation.distinctRoots(outcome.roots.filter { it.isReal })
            val complex = outcome.roots.filter { !it.isReal }
            val answer = when {
                real.isNotEmpty() -> outcome.combined ?: Tex.orList(real.map {
                    if (it.approximate) "$v \\approx ${it.latex}" else "$v = ${it.latex}"
                })
                complex.isNotEmpty() -> "\\text{No real solutions}"
                else -> "\\text{No solution}"
            }
            val parts = mutableListOf<String>()
            val needsDecimals = real.any { !it.approximate && (it.exact == null || !it.exact.isInteger) }
            if (needsDecimals) parts += Tex.orList(real.map { "$v \\approx ${Tex.decimal(it.re)}" })
            if (complex.isNotEmpty()) {
                parts += "\\text{Complex: } " + (outcome.complexText ?: complex.joinToString(",\\; ") { "$v = ${it.latex}" })
            }
            Solution(outcome.kind, steps, answer, parts.takeIf { it.isNotEmpty() }?.joinToString(" \\qquad "), graph)
        }
    }

    // ================= graphs =================

    private fun isZero(e: Expr) = e is Expr.Num && e.value.isZero

    private fun equationGraph(leftExpr: Expr, rightExpr: Expr, v: Char, outcome: Outcome, left: Polynomial?, right: Polynomial?): Graph? {
        val lS = try { Sym.from(leftExpr) } catch (_: MathError) { return null }
        val curves = listOf(Graphs.curve(lS, v), if (isZero(rightExpr)) null else Graphs.curveOf(rightExpr, v))
        val points = mutableListOf<GraphPoint>()
        if (outcome is Outcome.Roots) {
            for (root in outcome.roots.filter { it.isReal }) {
                val y = Sym.eval(lS, mapOf(v to root.re))
                points += GraphPoint(root.re, y, Graphs.label(root.re, y))
            }
        }
        // Mark the vertex when the left side is a parabola and the right side is a constant.
        if (left != null && right != null && left.degree == 2 && right.isConstant) {
            val h = -left[1].toDouble() / (2 * left[2].toDouble())
            val k = left.evaluate(h)
            if (points.none { Math.abs(it.x - h) < 1e-9 }) points += GraphPoint(h, k, "vertex " + Graphs.label(h, k))
        }
        return Graphs.build(curves, points)
    }

    private fun approxOf(r: Rational) = if (r.isInteger) null else Tex.decimal(r.toDouble())
}
