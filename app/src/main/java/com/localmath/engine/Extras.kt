package com.localmath.engine

import java.math.BigInteger

/**
 * Smaller school topics, recognised before the main parser:
 * gcd / lcm / prime factors, percentages, ratios, n!, nCr, nPr, (a + b)ⁿ, statistics, triangles, completing the square.
 */
object Extras {

    /** Function words used here (the editor turns \operatorname{…} of these into text). */
    val FUNCS = listOf("gcd", "hcf", "lcm", "factor", "primes", "nCr", "nPr", "stats", "mean", "median", "mode",
        "sd", "var", "quartiles", "triangle", "complete", "binomial")

    private val NUM = "(-?(?:\\d+\\.?\\d*|\\.\\d+))"

    private fun compact(text: String) = text.replace(Regex("\\s+"), "").replace('−', '-').replace('×', '*').replace('·', '*')

    private fun call(c: String): Pair<String, String>? {
        val m = Regex("^([A-Za-z]+)\\((.*)\\)$").matchEntire(c) ?: return null
        val name = FUNCS.firstOrNull { it.equals(m.groupValues[1], ignoreCase = true) } ?: return null
        // The brackets must be one pair around everything.
        var depth = 0
        val inner = m.groupValues[2]
        for (ch in inner) { if (ch == '(') depth++; if (ch == ')') depth--; if (depth < 0) return null }
        return if (depth == 0) name to inner else null
    }

    fun accepts(text: String): Boolean {
        val c = compact(text)
        if (c.isEmpty()) return false
        if (call(c) != null) return true
        if (Regex("^\\d+!$").matches(c) || Regex("^\\d+[CP]\\d+$").matches(c)) return true
        if (c.contains('%')) return true
        if (Regex("^[^:=;]+(:[^:=;]+)+$").matches(c) && !c.contains("(")) return true
        if (Regex("^[^:=;]+:[^:=;]+=[^:=;]+:[^:=;]+$").matches(c)) return true
        if (Regex("^share.+in.+:.+$", RegexOption.IGNORE_CASE).matches(c)) return true
        return binomialShape(text) != null
    }

    fun solve(text: String): Solution {
        val c = compact(text)
        call(c)?.let { (name, inner) ->
            return when (name) {
                "gcd", "hcf" -> gcdLcm(ints(inner), gcd = true)
                "lcm" -> gcdLcm(ints(inner), gcd = false)
                "factor", "primes" -> primeFactors(ints(inner).singleOrNull() ?: throw MathError("factor( ) takes one whole number"))
                "nCr", "nPr" -> {
                    val v = ints(inner)
                    if (v.size != 2) throw MathError("Write $name(n, r), like $name(10, 3)")
                    counting(v[0], v[1], name == "nCr")
                }
                "stats", "mean", "median", "mode", "sd", "var", "quartiles" -> Statistics.solve(numbers(inner), name)
                "triangle" -> Triangles.solve(inner)
                "complete" -> completeSquare(text.substringAfter('(').substringBeforeLast(')'))
                else -> binomial(text.substringAfter('(').substringBeforeLast(')'))
            }
        }
        Regex("^(\\d+)!$").matchEntire(c)?.let { return factorial(it.groupValues[1].toInt()) }
        Regex("^(\\d+)([CP])(\\d+)$").matchEntire(c)?.let {
            return counting(it.groupValues[1].toBigInteger(), it.groupValues[3].toBigInteger(), it.groupValues[2] == "C")
        }
        if (c.contains('%')) return Percent.solve(c)
        if (c.contains(':') || c.startsWith("share", ignoreCase = true)) return Ratios.solve(c)
        return binomial(text)
    }

    /** LaTeX for the editor, so these problems load back from History. */
    fun toLatex(text: String): String {
        var t = text
        for (f in FUNCS) t = t.replace(Regex("\\b$f\\s*\\(", RegexOption.IGNORE_CASE), "\\\\operatorname{$f}(")
        return t.replace("%", "\\%").replace("°", "^{\\circ}").replace(" of ", "\\text{ of }").replace(" as ", "\\text{ as }")
            .replace(" to ", "\\text{ to }").replace("√", "\\sqrt")
    }

    // ================= helpers =================

    internal fun exact(t: String): Rational {
        val e = (Parser.parse(t) as? Input.Expression)?.expr ?: throw MathError("\"$t\" isn't a number")
        val s = Sym.from(e)
        return (s as? S.Num)?.v ?: throw MathError("\"$t\" must be an exact number here")
    }

    internal fun value(t: String): Double {
        val e = (Parser.parse(t) as? Input.Expression)?.expr ?: throw MathError("\"$t\" isn't a number")
        if (e.variables().isNotEmpty()) throw MathError("\"$t\" must be a number")
        val v = Sym.eval(Sym.from(e), emptyMap())
        if (!v.isFinite()) throw MathError("\"$t\" isn't a number")
        return v
    }

    /** Splits on commas that aren't inside brackets. */
    internal fun args(inner: String): List<String> {
        val out = mutableListOf(StringBuilder())
        var depth = 0
        for (ch in inner) {
            if (ch == '(') depth++
            if (ch == ')') depth--
            if (ch == ',' && depth == 0) out += StringBuilder() else out.last().append(ch)
        }
        return out.map { it.toString() }.filter { it.isNotBlank() }
    }

    private fun ints(inner: String): List<BigInteger> = args(inner).map {
        val r = exact(it)
        if (!r.isInteger || r.sign < 0) throw MathError("Use whole numbers (0, 1, 2, …) here, not $it")
        r.num
    }

    private fun numbers(inner: String): List<Rational> {
        val v = args(inner).map { exact(it) }
        if (v.isEmpty()) throw MathError("Put the data inside the brackets, like stats(3, 5, 7)")
        if (v.size > 500) throw MathError("Up to 500 numbers at a time")
        return v
    }

    /** A decimal with at most [dp] places, no trailing zeros. */
    internal fun dec(d: Double, dp: Int = 4): String {
        if (!d.isFinite()) return "\\text{undefined}"
        val s = "%.${dp}f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    private fun tr(r: Rational) = Tex.rational(r)

    /** Exact value, with a decimal when it's a fraction. */
    internal fun withDec(r: Rational) = if (r.isInteger) tr(r) else "${tr(r)} \\approx ${dec(r.toDouble())}"

    // ================= primes, gcd, lcm =================

    private val LIMIT = BigInteger.TEN.pow(12)

    private fun factorMap(n: BigInteger): LinkedHashMap<BigInteger, Int> {
        if (n > LIMIT) throw MathError("Numbers up to 1 000 000 000 000 only")
        val out = linkedMapOf<BigInteger, Int>()
        var x = n.toLong()
        var p = 2L
        while (p * p <= x) {
            while (x % p == 0L) { out[BigInteger.valueOf(p)] = (out[BigInteger.valueOf(p)] ?: 0) + 1; x /= p }
            p += if (p == 2L) 1 else 2
        }
        if (x > 1) out[BigInteger.valueOf(x)] = (out[BigInteger.valueOf(x)] ?: 0) + 1
        return out
    }

    private fun powTex(f: Map<BigInteger, Int>) =
        if (f.isEmpty()) "1" else f.entries.joinToString(" \\times ") { (p, k) -> if (k == 1) "$p" else "$p^{$k}" }

    private fun primeFactors(n: BigInteger): Solution {
        if (n < BigInteger.TWO) throw MathError("Prime factors need a whole number bigger than 1")
        val steps = mutableListOf<Step>()
        val f = factorMap(n)
        // Division ladder.
        var x = n
        val ladder = mutableListOf<String>()
        for ((p, k) in f) repeat(k) { ladder += "$x \\div $p = ${x / p}"; x /= p }
        steps += Step("Divide by the smallest prime that goes in, again and again", if (ladder.size <= 14) ladder.joinToString(" \\\\ ") { it }
            .let { "\\begin{aligned}" + ladder.joinToString(" \\\\ ") { l -> l.replace(" = ", " &= ") } + "\\end{aligned}" } else ladder.take(12).joinToString(",\\quad ") + ",\\ \\dots",
            "Try 2, 3, 5, 7, 11, … and stop when you reach 1.")
        val isPrime = f.size == 1 && f.values.single() == 1
        steps += Step("Write the primes as powers", "$n = ${powTex(f)}", if (isPrime) "$n is itself prime." else null)
        return Solution("Prime factorisation", steps, "$n = ${powTex(f)}", if (isPrime) "\\text{$n is prime}" else null)
    }

    private fun gcdLcm(v: List<BigInteger>, gcd: Boolean): Solution {
        if (v.size < 2) throw MathError("Give at least two numbers, like ${if (gcd) "gcd" else "lcm"}(12, 18)")
        if (v.any { it.signum() == 0 }) throw MathError("Use whole numbers bigger than 0")
        val steps = mutableListOf<Step>()
        val maps = v.map { factorMap(it) }
        steps += Step("Prime factors of each number", v.indices.joinToString(",\\quad ") { "${v[it]} = ${powTex(maps[it])}" })
        val primes = maps.flatMap { it.keys }.distinct().sorted()
        val pick = primes.associateWith { p -> maps.map { it[p] ?: 0 }.let { if (gcd) it.min() else it.max() } }.filterValues { it > 0 }
        val result = pick.entries.fold(BigInteger.ONE) { acc, (p, k) -> acc * p.pow(k) }
        steps += Step(if (gcd) "Take each prime the fewest times it appears in all of them" else "Take each prime the most times it appears in any of them",
            (if (gcd) "\\gcd" else "\\operatorname{lcm}") + "(${v.joinToString(", ")}) = ${powTex(pick)} = $result",
            if (gcd) (if (pick.isEmpty()) "No prime is shared, so the highest common factor is 1." else "Only primes that every number has count.")
            else "Every number's primes must fit inside the answer.")
        if (v.size == 2) {
            val other = if (gcd) v[0] * v[1] / result else v[0] * v[1] / result
            steps += Step("Check with \\(\\gcd \\times \\operatorname{lcm} = a \\times b\\)",
                "${if (gcd) result else other} \\times ${if (gcd) other else result} = ${v[0] * v[1]} = ${v[0]} \\times ${v[1]}")
        }
        return Solution(if (gcd) "Highest common factor (GCD)" else "Lowest common multiple (LCM)", steps,
            (if (gcd) "\\gcd" else "\\operatorname{lcm}") + "(${v.joinToString(", ")}) = $result")
    }

    // ================= counting =================

    private fun factorial(n: Int): Solution {
        if (n > 200) throw MathError("n! is supported up to 200!")
        val value = (1..Math.max(n, 1)).fold(BigInteger.ONE) { acc, k -> acc * BigInteger.valueOf(k.toLong()) }
        val prod = when {
            n <= 1 -> "1"
            n <= 10 -> (n downTo 1).joinToString(" \\times ")
            else -> "$n \\times ${n - 1} \\times ${n - 2} \\times \\dots \\times 2 \\times 1"
        }
        val steps = listOf(Step("Multiply every whole number from \\($n\\) down to 1", "$n! = $prod = $value",
            if (n == 0) "By definition 0! = 1 (there is exactly one way to arrange nothing)." else "It counts the ways to arrange $n different things in a row."))
        return Solution("Factorial", steps, "$n! = $value", if (value.bitLength() > 60) "\\approx ${"%.6e".format(java.util.Locale.US, value.toDouble()).replace("e+", "\\times 10^{").plus("}")}" else null)
    }

    private fun counting(n: BigInteger, r: BigInteger, combination: Boolean): Solution {
        if (n > BigInteger.valueOf(1000)) throw MathError("n can be up to 1000")
        if (r > n) throw MathError("r can't be bigger than n (you can't choose ${r} from $n)")
        val ni = n.toInt(); val ri = r.toInt()
        val steps = mutableListOf<Step>()
        val top = (0 until ri).fold(BigInteger.ONE) { acc, k -> acc * BigInteger.valueOf((ni - k).toLong()) }
        val rf = (1..Math.max(ri, 1)).fold(BigInteger.ONE) { acc, k -> acc * BigInteger.valueOf(k.toLong()) }
        val topTex = when { ri == 0 -> "1"; ri <= 6 -> (0 until ri).joinToString(" \\times ") { "${ni - it}" }
            else -> "$ni \\times ${ni - 1} \\times \\dots \\times ${ni - ri + 1}" }
        if (combination) {
            val v = top / rf
            steps += Step("Formula: choosing \\(r\\) from \\(n\\) when order doesn't matter", "\\binom{n}{r} = \\frac{n!}{r!\\,(n-r)!} = \\frac{$ni!}{$ri!\\,${ni - ri}!}")
            steps += Step("Cancel \\(${ni - ri}!\\) from the top", "\\binom{$ni}{$ri} = \\frac{$topTex}{$ri!} = \\frac{$top}{$rf} = $v",
                "The top keeps the $ri biggest numbers of $ni!; dividing by $ri! removes the different orders of the same choice.")
            return Solution("Combinations (nCr)", steps, "{}^{$ni}C_{$ri} = \\binom{$ni}{$ri} = $v")
        }
        steps += Step("Formula: arranging \\(r\\) of \\(n\\) when order matters", "{}^{n}P_{r} = \\frac{n!}{(n-r)!} = \\frac{$ni!}{${ni - ri}!}")
        steps += Step("Cancel \\(${ni - ri}!\\)", "{}^{$ni}P_{$ri} = $topTex = $top", "$ni choices for the first place, ${ni - 1} for the second, and so on for $ri places.")
        return Solution("Permutations (nPr)", steps, "{}^{$ni}P_{$ri} = $top")
    }

    // ================= binomial expansion =================

    private fun binomialShape(text: String): Triple<Expr, Expr, Int>? {
        val e = try { (Parser.parse(text) as? Input.Expression)?.expr } catch (_: Exception) { null } ?: return null
        val p = e as? Expr.Pow ?: return null
        val n = (p.exponent as? Expr.Num)?.value ?: return null
        if (!n.isInteger || n.num < BigInteger.valueOf(3) || n.num > BigInteger.valueOf(12)) return null
        if (e.hasFunctions() || e.has { it is Expr.Special }) return null
        return when (val b = p.base) {
            is Expr.Add -> Triple(b.left, b.right, n.num.toInt())
            is Expr.Sub -> Triple(b.left, Expr.Neg(b.right), n.num.toInt())
            else -> null
        }
    }

    private fun binomial(text: String): Solution {
        val (u, v, n) = binomialShape(text) ?: throw MathError("Write it like binomial((2x + 3)^5) — two terms in brackets, to a power from 3 to 12")
        val a = Sym.from(u)
        val b = Sym.from(v)
        val steps = mutableListOf<Step>()
        steps += Step("Binomial theorem", "(a + b)^{n} = \\sum_{k=0}^{n} \\binom{n}{k} a^{n-k} b^{k}",
            "Here \\(a = ${Sym.latex(a)}\\), \\(b = ${Sym.latex(b)}\\), \\(n = $n\\). The coefficients \\(\\binom{$n}{k}\\) are row $n of Pascal's triangle.")
        val coef = (0..n).map { k -> (0 until k).fold(BigInteger.ONE) { acc, j -> acc * BigInteger.valueOf((n - j).toLong()) } /
            (1..Math.max(k, 1)).fold(BigInteger.ONE) { acc, j -> acc * BigInteger.valueOf(j.toLong()) } }
        steps += Step("Pascal's triangle, row $n", coef.joinToString(",\\ "))
        fun par(s: S) = Sym.latex(s).let { if (s is S.Num && s.v.isInteger && s.v.sign > 0 || s is S.Var) it else "\\left($it\\right)" }
        val raw = (0..n).joinToString(" + ") { k ->
            fun pw(x: String, e: Int) = if (e == 1) x else "$x^{$e}"
            listOfNotNull(if (coef[k] == BigInteger.ONE) null else "${coef[k]}", if (n - k > 0) pw(par(a), n - k) else null,
                if (k > 0) pw(par(b), k) else null).joinToString("\\,")
        }
        steps += Step("Write out every term", raw)
        val terms = (0..n).map { k -> Sym.mul(Sym.num(coef[k].toLong()), Sym.pow(a, Sym.num((n - k).toLong())), Sym.pow(b, Sym.num(k.toLong()))) }
        val tt = terms.map { Sym.latex(it) }
        steps += Step("Work out each term", tt.joinToString(",\\quad "))
        val result = terms.drop(1).fold(Sym.latex(terms[0])) { acc, t -> val l = Sym.latex(t); if (l.startsWith("-")) "$acc - ${l.substring(1)}" else "$acc + $l" }
        steps += Step("Add them up", result)
        return Solution("Binomial expansion", steps, result)
    }

    // ================= completing the square =================

    private fun completeSquare(inner: String): Solution {
        val input = Parser.parse(inner)
        val (poly, isEq, v) = when (input) {
            is Input.Expression -> { val v = input.expr.variables().singleOrNull() ?: throw MathError("Use one letter, like complete(x² + 6x + 5)")
                Triple(Polynomial.fromExpr(input.expr, v), false, v) }
            is Input.Equation -> { val v = (input.left.variables() + input.right.variables()).singleOrNull() ?: throw MathError("Use one letter, like complete(x² + 6x + 5 = 0)")
                Triple(Polynomial.fromExpr(input.left, v) - Polynomial.fromExpr(input.right, v), true, v) }
            else -> throw MathError("complete( ) takes a quadratic like x² + 6x + 5, or an equation like x² + 6x + 5 = 0")
        }
        if (poly.degree != 2) throw MathError("Completing the square needs a quadratic (highest power 2)")
        val a = poly[2]; val b = poly[1]; val c = poly[0]
        val steps = mutableListOf<Step>()
        val h = b / (Rational.of(2) * a)            // (x + h)²
        val k = c - a * h * h                       // a(x + h)² + k
        fun sq(): String = if (h.isZero) "$v^{2}" else "\\left($v ${if (h.sign > 0) "+" else "-"} ${tr(h.abs())}\\right)^{2}"
        fun coefTex(r: Rational) = when { r.isOne -> ""; r == Rational.MINUS_ONE -> "-"; else -> tr(r) }
        fun plus(r: Rational) = if (r.isZero) "" else if (r.sign > 0) " + ${tr(r)}" else " - ${tr(r.abs())}"
        steps += Step("Start with", poly.format(v) + if (isEq) " = 0" else "")
        val ba = b / a
        val xTerm = if (ba.isZero) "" else " ${if (ba.sign > 0) "+" else "-"} ${if (ba.abs().isOne) "" else tr(ba.abs())}$v"
        if (!a.isOne) steps += Step("Take out \\(${tr(a)}\\) from the \\($v\\) terms", "${coefTex(a)}\\left($v^{2}$xTerm\\right)${plus(c)}")
        val hh = h * h
        steps += Step("Halve the \\($v\\) coefficient and square it",
            "\\frac{1}{2} \\cdot ${if (ba.sign < 0) "\\left(${tr(ba)}\\right)" else tr(ba)} = ${tr(h)},\\quad \\left(${tr(h)}\\right)^{2} = ${tr(hh)}",
            "Adding and taking away \\(${tr(hh)}\\) doesn't change the value, but makes a perfect square.")
        steps += Step("Add and take away it inside",
            "${coefTex(a)}\\left($v^{2}$xTerm + ${tr(hh)} - ${tr(hh)}\\right)${plus(c)} = ${coefTex(a)}\\left(${sq()} - ${tr(hh)}\\right)${plus(c)}")
        val vertexForm = "${coefTex(a)}${sq()}${plus(k)}"
        steps += Step("Tidy the numbers", vertexForm, "Completed-square (vertex) form \\(a($v - p)^2 + q\\).")
        val px = -h
        steps += Step("Read off the turning point", "\\text{vertex } \\left(${tr(px)},\\ ${tr(k)}\\right),\\quad \\text{axis } $v = ${tr(px)}",
            "The square is never negative, so the ${if (a.sign > 0) "smallest" else "largest"} value is \\(${tr(k)}\\), when \\($v = ${tr(px)}\\).")

        val s = Sym.add(Sym.mul(S.Num(a), Sym.pow(S.Var(v), Sym.num(2))), Sym.mul(S.Num(b), S.Var(v)), S.Num(c))
        val graph = if (v == 'x') Graphs.build(listOf(Graphs.curve(s, 'x')), listOf(GraphPoint(px.toDouble(), k.toDouble(), "vertex " + Graphs.label(px.toDouble(), k.toDouble()))),
            focus = listOf(px.toDouble())) else null
        if (!isEq) return Solution("Completing the square", steps, "${poly.format(v)} = $vertexForm",
            "\\text{vertex } \\left(${tr(px)},\\ ${tr(k)}\\right)", graph)

        // Solve  a(x + h)² + k = 0.
        val rhs = -k / a
        steps += Step("Set it equal to 0 and get the square alone", "${sq()} = ${tr(rhs)}")
        if (rhs.sign < 0) {
            val im = Sym.pow(S.Num(-rhs), Sym.HALF)
            steps += Step("A square can't be negative", "${sq()} = ${tr(rhs)} < 0",
                "No real solutions. With complex numbers: \\($v = ${tr(px)} \\pm ${Sym.latex(im)}\\,i\\).")
            return Solution("Completing the square", steps, "\\text{No real solutions}",
                "$v = ${tr(px)} \\pm ${Sym.latex(im)}\\,i", graph)
        }
        val root = Sym.pow(S.Num(rhs), Sym.HALF)
        steps += Step("Square-root both sides (remember ±)", "$v ${if (h.sign >= 0) "+" else "-"} ${tr(h.abs())} = \\pm ${Sym.latex(root)}".replace("$v + 0", v.toString()).replace("$v - 0", v.toString()))
        val x1 = Sym.add(S.Num(px), Sym.neg(root))
        val x2 = Sym.add(S.Num(px), root)
        val ans = if (rhs.isZero) "$v = ${tr(px)}" else "$v = ${Sym.latex(x1)} \\quad\\text{or}\\quad $v = ${Sym.latex(x2)}"
        steps += Step("So", ans)
        val approx = if (root is S.Num) null else "$v \\approx ${dec(Sym.eval(x1, emptyMap()))} \\text{ or } ${dec(Sym.eval(x2, emptyMap()))}"
        return Solution("Completing the square", steps, ans, approx, graph)
    }
}

// ================= percentages =================

internal object Percent {
    private const val N = "([^%]+?)"

    fun solve(c: String): Solution {
        val steps = mutableListOf<Step>()
        fun r(t: String) = Extras.exact(t)
        fun t(x: Rational) = Extras.withDec(x)
        val hundred = Rational.of(100)

        Regex("^(.+)as%of(.+)$", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val a = r(m.groupValues[1]); val b = r(m.groupValues[2])
            if (b.isZero) throw MathError("Can't take a percentage of 0")
            val v = a / b * hundred
            steps += Step("Write it as a fraction, then multiply by 100", "\\frac{${tr(a)}}{${tr(b)}} \\times 100 = ${t(v)}\\%")
            return Solution("Writing as a percentage", steps, "${tr(a)} \\text{ is } ${t(v)}\\% \\text{ of } ${tr(b)}")
        }
        Regex("^$N%of(.+)$", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val p = r(m.groupValues[1]); val x = r(m.groupValues[2])
            val v = p / hundred * x
            steps += Step("Change the percentage to a fraction", "${tr(p)}\\% = \\frac{${tr(p)}}{100}")
            steps += Step("Multiply", "\\frac{${tr(p)}}{100} \\times ${tr(x)} = ${t(v)}", "\"Of\" means multiply.")
            return Solution("Percentage of an amount", steps, "${tr(p)}\\% \\text{ of } ${tr(x)} = ${t(v)}")
        }
        Regex("^(.+)to(.+)as%$", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val a = r(m.groupValues[1]); val b = r(m.groupValues[2])
            if (a.isZero) throw MathError("A percentage change from 0 isn't defined")
            val ch = b - a
            val v = ch / a * hundred
            steps += Step("Find the change", "${tr(b)} - ${tr(a)} = ${tr(ch)}")
            steps += Step("Divide by the original and multiply by 100", "\\frac{${tr(ch)}}{${tr(a)}} \\times 100 = ${t(v)}\\%",
                "Always divide by the value you started with.")
            val word = if (v.sign >= 0) "increase" else "decrease"
            return Solution("Percentage change", steps, "${t(v.abs())}\\% \\text{ $word}")
        }
        Regex("^(.+)([+-])$N%$").matchEntire(c)?.let { m ->
            val x = r(m.groupValues[1]); val p = r(m.groupValues[3]); val up = m.groupValues[2] == "+"
            val mult = Rational.ONE + (if (up) p else -p) / hundred
            steps += Step("Find the multiplier", "1 ${if (up) "+" else "-"} \\frac{${tr(p)}}{100} = ${t(mult)}",
                "${if (up) "Increasing" else "Decreasing"} by ${tr(p)}% leaves ${tr(mult * hundred)}% of the amount.")
            steps += Step("Multiply", "${tr(x)} \\times ${t(mult)} = ${t(x * mult)}")
            return Solution("Percentage ${if (up) "increase" else "decrease"}", steps, t(x * mult))
        }
        Regex("^$N%$").matchEntire(c)?.let { m ->
            val p = r(m.groupValues[1])
            steps += Step("Per cent means \"out of 100\"", "${tr(p)}\\% = \\frac{${tr(p)}}{100} = ${t(p / hundred)}")
            return Solution("Percentage", steps, t(p / hundred))
        }
        throw MathError("Try: 15% of 80,  80 + 15%,  80 − 15%,  20 as % of 80,  or  80 to 92 as %")
    }

    private fun tr(r: Rational) = Tex.rational(r)
}

// ================= ratios =================

internal object Ratios {
    fun solve(c: String): Solution {
        val steps = mutableListOf<Step>()
        Regex("^share(.+)in(.+)$", RegexOption.IGNORE_CASE).matchEntire(c)?.let { m ->
            val total = Extras.exact(m.groupValues[1])
            val parts = m.groupValues[2].split(':').map { Extras.exact(it) }
            if (parts.size < 2 || parts.any { it.sign <= 0 }) throw MathError("Write it like share 60 in 2:3")
            val sum = parts.fold(Rational.ZERO) { a, b -> a + b }
            steps += Step("Add the parts", parts.joinToString(" + ") { Tex.rational(it) } + " = ${Tex.rational(sum)}")
            val one = total / sum
            steps += Step("Find one part", "${Tex.rational(total)} \\div ${Tex.rational(sum)} = ${Extras.withDec(one)}")
            val shares = parts.map { it * one }
            steps += Step("Multiply each part", parts.indices.joinToString(",\\quad ") { "${Tex.rational(parts[it])} \\times ${Tex.rational(one)} = ${Extras.withDec(shares[it])}" })
            steps += Step("Check", shares.joinToString(" + ") { Tex.rational(it) } + " = ${Tex.rational(total)}")
            return Solution("Sharing in a ratio", steps, shares.joinToString(" : ") { Extras.withDec(it).substringBefore(" \\approx") })
        }
        Regex("^([^:=]+):([^:=]+)=([^:=]+):([^:=]+)$").matchEntire(c)?.let { m ->
            val g = m.groupValues.drop(1)
            val unknown = g.indexOfFirst { it.length == 1 && it[0].isLetter() }
            if (unknown < 0 || g.count { it.length == 1 && it[0].isLetter() } != 1) throw MathError("Put one letter in the proportion, like 3:5 = x:20")
            val v = g[unknown]
            val known = g.map { if (it == v) null else Extras.exact(it) }
            // a:b = c:d  ->  a·d = b·c
            val (a, b, cc, d) = known
            val value = when (unknown) {
                0 -> b!! * cc!! / d!!
                1 -> a!! * d!! / cc!!
                2 -> a!! * d!! / b!!
                else -> b!! * cc!! / a!!
            }
            fun s(i: Int) = if (i == unknown) v else Tex.rational(known[i]!!)
            steps += Step("Write the ratios as fractions", "\\frac{${s(0)}}{${s(1)}} = \\frac{${s(2)}}{${s(3)}}")
            steps += Step("Cross-multiply", "${s(0)} \\times ${s(3)} = ${s(1)} \\times ${s(2)}")
            steps += Step("Solve for \\($v\\)", "$v = ${Extras.withDec(value)}")
            return Solution("Proportion", steps, "$v = ${Extras.withDec(value)}")
        }
        val parts = c.split(':').map { Extras.exact(it) }
        if (parts.any { it.isZero }) throw MathError("Ratio parts can't be 0")
        val lcm = parts.fold(BigInteger.ONE) { acc, r -> acc / acc.gcd(r.den) * r.den }
        val whole = parts.map { (it * Rational.of(lcm)).num }
        if (lcm != BigInteger.ONE) steps += Step("Multiply by $lcm to clear the fractions / decimals", whole.joinToString(" : "))
        val g = whole.fold(BigInteger.ZERO) { acc, x -> acc.gcd(x) }
        val simple = whole.map { it / g }
        steps += Step("Divide every part by the highest common factor, $g", simple.joinToString(" : "),
            if (g == BigInteger.ONE) "Nothing divides all of them, so it's already as simple as it gets." else null)
        return Solution("Simplifying a ratio", steps, simple.joinToString(" : "),
            if (simple.size == 2) "\\text{or } 1 : ${Extras.dec(simple[1].toDouble() / simple[0].toDouble())}" else null)
    }
}

// ================= statistics =================

internal object Statistics {
    fun solve(data: List<Rational>, focus: String): Solution {
        val steps = mutableListOf<Step>()
        val n = data.size
        val sorted = data.sorted()
        fun t(r: Rational) = Tex.rational(r)
        fun list(v: List<Rational>) = if (v.size <= 30) v.joinToString(",\\ ") { t(it) } else v.take(28).joinToString(",\\ ") { t(it) } + ",\\ \\dots"
        steps += Step("Put the data in order", list(sorted), "\\(n = $n\\) values.")
        val sum = data.fold(Rational.ZERO) { a, b -> a + b }
        val mean = sum / Rational.of(n.toLong())
        val frac = "\\frac{${t(sum)}}{$n}"
        steps += Step("Mean: add them up, divide by how many", "\\bar{x} = $frac" + (if (frac == t(mean)) "" else " = ${t(mean)}") +
            (if (mean.isInteger) "" else " \\approx ${Extras.dec(mean.toDouble())}"))
        fun median(v: List<Rational>): Rational = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / Rational.of(2)
        val med = median(sorted)
        steps += Step("Median: the middle value", "\\text{median} = ${Extras.withDec(med)}",
            if (n % 2 == 1) "With $n values the middle one is value number ${(n + 1) / 2}."
            else "With $n values there are two middle ones (numbers ${n / 2} and ${n / 2 + 1}); the median is halfway between them.")
        val counts = sorted.groupingBy { it }.eachCount()
        val top = counts.values.max()
        val modes = counts.filterValues { it == top }.keys.toList()
        val modeText = if (top == 1) "\\text{no mode}" else modes.joinToString(",\\ ") { t(it) }
        steps += Step("Mode: the most common value", "\\text{mode} = $modeText",
            if (top == 1) "Every value appears once." else "Appears $top times." + if (modes.size > 1) " (More than one mode.)" else "")
        val range = sorted.last() - sorted.first()
        steps += Step("Range: largest − smallest", "${t(sorted.last())} - ${if (sorted.first().sign < 0) "\\left(${t(sorted.first())}\\right)" else t(sorted.first())} = ${t(range)}")

        var q1: Rational? = null
        var q3: Rational? = null
        if (n >= 4) {
            val lower = sorted.subList(0, n / 2)
            val upper = sorted.subList((n + 1) / 2, n)
            q1 = median(lower); q3 = median(upper)
            steps += Step("Quartiles: the medians of the lower and upper halves",
                "Q_1 = ${Extras.withDec(q1)},\\quad Q_3 = ${Extras.withDec(q3)},\\quad \\text{IQR} = Q_3 - Q_1 = ${Extras.withDec(q3 - q1)}",
                "Lower half: ${"\\("}${list(lower)}${"\\)"}; upper half: ${"\\("}${list(upper)}${"\\)"}" + (if (n % 2 == 1) " (the median itself is left out)." else "."))
        }

        val dev = data.map { (it - mean) * (it - mean) }
        val ss = dev.fold(Rational.ZERO) { a, b -> a + b }
        if (n <= 12) steps += Step("Squared distances from the mean", data.joinToString(" + ") {
            "\\left(${t(it)} - ${t(mean)}\\right)^{2}" } + " = ${t(ss)}")
        else steps += Step("Squared distances from the mean", "\\sum (x - \\bar{x})^{2} = ${t(ss)}")
        val varPop = ss / Rational.of(n.toLong())
        val sdPop = Sym.pow(S.Num(varPop), Sym.HALF)
        var line = "\\sigma^{2} = \\frac{${t(ss)}}{$n} = ${Extras.withDec(varPop)},\\quad \\sigma = ${Sym.latex(sdPop)}" +
            (if (sdPop is S.Num) "" else " \\approx ${Extras.dec(Sym.eval(sdPop, emptyMap()))}")
        var sdSampleText: String? = null
        if (n >= 2) {
            val varS = ss / Rational.of((n - 1).toLong())
            val sdS = Sym.pow(S.Num(varS), Sym.HALF)
            sdSampleText = Sym.latex(sdS) + (if (sdS is S.Num) "" else " \\approx ${Extras.dec(Sym.eval(sdS, emptyMap()))}")
            line += " \\\\ s^{2} = \\frac{${t(ss)}}{${n - 1}} = ${Extras.withDec(varS)},\\quad s = $sdSampleText"
        }
        steps += Step("Variance and standard deviation", "\\begin{gathered}$line\\end{gathered}",
            "σ (divide by n) is for a whole population; s (divide by n − 1) is for a sample. Calculators show both.")
        val sigmaText = Sym.latex(sdPop) + (if (sdPop is S.Num) "" else " \\approx ${Extras.dec(Sym.eval(sdPop, emptyMap()))}")

        val all = "\\bar{x} = ${Extras.withDec(mean)},\\ \\text{median} = ${Extras.withDec(med)},\\ \\text{mode} = $modeText"
        val answer = when (focus) {
            "mean" -> "\\bar{x} = ${Extras.withDec(mean)}"
            "median" -> "\\text{median} = ${Extras.withDec(med)}"
            "mode" -> "\\text{mode} = $modeText"
            "sd" -> "\\sigma = $sigmaText" + (sdSampleText?.let { ",\\quad s = $it" } ?: "")
            "var" -> "\\sigma^{2} = ${Extras.withDec(varPop)}"
            "quartiles" -> if (q1 != null) "Q_1 = ${Extras.withDec(q1)},\\ Q_2 = ${Extras.withDec(med)},\\ Q_3 = ${Extras.withDec(q3!!)}"
                else throw MathError("Quartiles need at least 4 values")
            else -> all
        }
        val approx = if (focus == "stats") "\\sigma = $sigmaText" + (sdSampleText?.let { ",\\ s = $it" } ?: "") +
            (if (q1 != null) ",\\ Q_1 = ${Extras.withDec(q1)},\\ Q_3 = ${Extras.withDec(q3!!)}" else "") else null
        return Solution("Statistics", steps, answer, approx)
    }
}

// ================= triangles =================

internal object Triangles {
    private fun d(x: Double) = Extras.dec(x, 3)
    private fun deg(x: Double) = Extras.dec(x, 2) + "^{\\circ}"
    private fun rad(x: Double) = Math.toRadians(x)

    fun solve(inner: String): Solution {
        val given = HashMap<Char, Double>()
        for (part in Extras.args(inner)) {
            val eq = part.split('=')
            if (eq.size != 2 || eq[0].length != 1 || eq[0][0] !in "abcABC") throw MathError("Write it like triangle(a = 5, b = 7, C = 60°): sides a, b, c and the angles A, B, C opposite them")
            val k = eq[0][0]
            var t = eq[1].replace("°", "")
            val v: Double = if (k.isUpperCase()) {
                if (t.contains('π') || t.contains("pi")) Math.toDegrees(Extras.value(t)) else Extras.value(t)
            } else Extras.value(t)
            if (v <= 0) throw MathError("$k must be bigger than 0")
            if (k.isUpperCase() && v >= 180) throw MathError("Angle $k must be less than 180°")
            given[k] = v
        }
        if (given.size != 3) throw MathError("Give exactly three of a, b, c, A, B, C (at least one a side), like triangle(a = 5, b = 7, C = 60°)")
        if (given.keys.none { it.isLowerCase() }) throw MathError("Three angles fix the shape but not the size — give at least one side")
        val steps = mutableListOf<Step>()
        steps += Step("Given", given.entries.sortedBy { it.key.lowercaseChar().toString() + (if (it.key.isUpperCase()) "1" else "0") }
            .joinToString(",\\quad ") { (k, v) -> "$k = " + if (k.isUpperCase()) deg(v) else d(v) },
            "Side a is opposite angle A, b opposite B, c opposite C.")
        val sides = "abc"
        val s = DoubleArray(3) { given[sides[it]] ?: Double.NaN }
        val ang = DoubleArray(3) { given[sides[it].uppercaseChar()] ?: Double.NaN }
        fun S(i: Int) = sides[i]
        fun A(i: Int) = sides[i].uppercaseChar()
        val knownSides = (0..2).filter { !s[it].isNaN() }
        val knownAngles = (0..2).filter { !ang[it].isNaN() }

        var second: Pair<DoubleArray, DoubleArray>? = null
        when {
            knownSides.size == 3 -> {
                if (s[0] + s[1] <= s[2] || s[0] + s[2] <= s[1] || s[1] + s[2] <= s[0]) {
                    throw MathError("These sides can't make a triangle: the two shorter sides must add up to more than the longest")
                }
                for (i in 0..1) {
                    val j = (i + 1) % 3; val k = (i + 2) % 3
                    val cos = (s[j] * s[j] + s[k] * s[k] - s[i] * s[i]) / (2 * s[j] * s[k])
                    ang[i] = Math.toDegrees(Math.acos(cos))
                    steps += Step("Cosine rule for angle ${A(i)}", "\\cos ${A(i)} = \\frac{${S(j)}^2 + ${S(k)}^2 - ${S(i)}^2}{2${S(j)}${S(k)}} = " +
                        "\\frac{${d(s[j])}^2 + ${d(s[k])}^2 - ${d(s[i])}^2}{2 \\cdot ${d(s[j])} \\cdot ${d(s[k])}} = ${d(cos)},\\quad ${A(i)} = ${deg(ang[i])}")
                }
                ang[2] = 180 - ang[0] - ang[1]
                steps += Step("Angles add to 180°", "C = 180^{\\circ} - ${deg(ang[0])} - ${deg(ang[1])} = ${deg(ang[2])}")
            }
            knownSides.size == 2 && knownAngles.size == 1 && knownAngles[0] !in knownSides -> {
                // Two sides and the angle between them: cosine rule.
                val i = knownAngles[0]; val j = (i + 1) % 3; val k = (i + 2) % 3
                s[i] = Math.sqrt(s[j] * s[j] + s[k] * s[k] - 2 * s[j] * s[k] * Math.cos(rad(ang[i])))
                steps += Step("Cosine rule for side ${S(i)} (two sides and the angle between)",
                    "${S(i)}^2 = ${S(j)}^2 + ${S(k)}^2 - 2${S(j)}${S(k)}\\cos ${A(i)} = ${d(s[j])}^2 + ${d(s[k])}^2 - 2 \\cdot ${d(s[j])} \\cdot ${d(s[k])} \\cos ${deg(ang[i])} = ${d(s[i] * s[i])}" +
                        ",\\quad ${S(i)} = ${d(s[i])}")
                val cos = (s[i] * s[i] + s[k] * s[k] - s[j] * s[j]) / (2 * s[i] * s[k])
                ang[j] = Math.toDegrees(Math.acos(cos))
                steps += Step("Cosine rule for angle ${A(j)}", "\\cos ${A(j)} = \\frac{${S(i)}^2 + ${S(k)}^2 - ${S(j)}^2}{2${S(i)}${S(k)}} = ${d(cos)},\\quad ${A(j)} = ${deg(ang[j])}",
                    "The cosine rule (rather than the sine rule) avoids picking the wrong one of two possible angles.")
                ang[k] = 180 - ang[i] - ang[j]
                steps += Step("Angles add to 180°", "${A(k)} = 180^{\\circ} - ${deg(ang[i])} - ${deg(ang[j])} = ${deg(ang[k])}")
            }
            knownAngles.size >= 2 -> {
                if (knownAngles.size == 2) {
                    val m = (0..2).first { it !in knownAngles }
                    ang[m] = 180 - ang[knownAngles[0]] - ang[knownAngles[1]]
                    if (ang[m] <= 0) throw MathError("Those angles add up to 180° or more, so there's no triangle")
                    steps += Step("Angles add to 180°", "${A(m)} = 180^{\\circ} - ${deg(ang[knownAngles[0]])} - ${deg(ang[knownAngles[1]])} = ${deg(ang[m])}")
                }
                val g = knownSides[0]
                val ratio = s[g] / Math.sin(rad(ang[g]))
                steps += Step("Sine rule", "\\frac{a}{\\sin A} = \\frac{b}{\\sin B} = \\frac{c}{\\sin C} = \\frac{${d(s[g])}}{\\sin ${deg(ang[g])}} = ${d(ratio)}")
                for (i in 0..2) if (i != g) {
                    s[i] = ratio * Math.sin(rad(ang[i]))
                    steps += Step("Side ${S(i)}", "${S(i)} = ${d(ratio)} \\sin ${deg(ang[i])} = ${d(s[i])}")
                }
            }
            else -> {
                // Two sides and an angle not between them: sine rule, maybe two triangles.
                val i = knownAngles[0]
                val j = knownSides.first { it != i }
                val sinJ = s[j] * Math.sin(rad(ang[i])) / s[i]
                steps += Step("Sine rule for angle ${A(j)}", "\\frac{\\sin ${A(j)}}{${S(j)}} = \\frac{\\sin ${A(i)}}{${S(i)}} \\;\\Rightarrow\\; \\sin ${A(j)} = \\frac{${d(s[j])} \\sin ${deg(ang[i])}}{${d(s[i])}} = ${d(sinJ)}")
                if (sinJ > 1 + 1e-12) {
                    steps += Step("No triangle", "\\sin ${A(j)} = ${d(sinJ)} > 1", "Sine can't be more than 1: side ${S(i)} is too short to reach.")
                    return Solution("Solving a triangle", steps, "\\text{No triangle fits these measurements}")
                }
                val b1 = Math.toDegrees(Math.asin(sinJ.coerceAtMost(1.0)))
                val b2 = 180 - b1
                val k = (0..2).first { it != i && it != j }
                fun finish(bj: Double): Pair<DoubleArray, DoubleArray> {
                    val ss = s.copyOf(); val aa = ang.copyOf()
                    aa[j] = bj; aa[k] = 180 - aa[i] - bj
                    ss[k] = ss[i] / Math.sin(rad(aa[i])) * Math.sin(rad(aa[k]))
                    return ss to aa
                }
                val first = finish(b1)
                first.first.copyInto(s); first.second.copyInto(ang)
                steps += Step("Angles and the last side", "${A(j)} = ${deg(b1)},\\quad ${A(k)} = 180^{\\circ} - ${deg(ang[i])} - ${deg(b1)} = ${deg(ang[k])},\\quad " +
                    "${S(k)} = \\frac{${d(s[i])} \\sin ${deg(ang[k])}}{\\sin ${deg(ang[i])}} = ${d(s[k])}")
                if (Math.abs(b2 - b1) > 1e-9 && ang[i] + b2 < 180 - 1e-9) {
                    val two = finish(b2)
                    second = two
                    steps += Step("The ambiguous case: a second triangle", "${A(j)} = 180^{\\circ} - ${deg(b1)} = ${deg(b2)},\\quad ${A(k)} = ${deg(two.second[k])},\\quad ${S(k)} = ${d(two.first[k])}",
                        "sin is the same for an angle and 180° minus it, and both fit here, so two different triangles have these measurements.")
                }
            }
        }
        fun area(sd: DoubleArray, an: DoubleArray) = 0.5 * sd[0] * sd[1] * Math.sin(rad(an[2]))
        val ar = area(s, ang)
        steps += Step("Area", "\\text{Area} = \\tfrac{1}{2}ab\\sin C = \\tfrac{1}{2} \\cdot ${d(s[0])} \\cdot ${d(s[1])} \\cdot \\sin ${deg(ang[2])} = ${d(ar)}")
        fun summary(sd: DoubleArray, an: DoubleArray) = "a = ${d(sd[0])},\\ b = ${d(sd[1])},\\ c = ${d(sd[2])},\\ A = ${deg(an[0])},\\ B = ${deg(an[1])},\\ C = ${deg(an[2])}"
        val ans = summary(s, ang)
        val approx = "\\text{Area} = ${d(ar)},\\quad \\text{perimeter} = ${d(s.sum())}" +
            (second?.let { (s2, a2) -> "\\quad\\text{or: } ${summary(s2, a2)},\\ \\text{area} = ${d(area(s2, a2))}" } ?: "")
        return Solution(if (second != null) "Solving a triangle (two answers)" else "Solving a triangle", steps, ans, approx)
    }
}
