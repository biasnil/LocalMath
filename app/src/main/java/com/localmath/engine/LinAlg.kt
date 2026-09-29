package com.localmath.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

/** A value in a vector / matrix problem. Vector entries are exact symbolic numbers; matrix entries are fractions. */
sealed class LVal {
    data class Scal(val s: S) : LVal()
    data class Vec(val c: List<S>) : LVal() { val n get() = c.size }
    data class Mat(val m: List<List<Rational>>) : LVal() {
        val rows get() = m.size
        val cols get() = m[0].size
        val square get() = rows == cols
    }
}

/** What the user typed in a vector / matrix problem. */
sealed class LN {
    data class Num(val r: Rational, val text: String) : LN()
    data class Name(val c: Char) : LN()
    data class Const(val c: Char) : LN()                       // π or e
    data class VecLit(val items: List<LN>) : LN()              // (3, 4)
    data class MatLit(val rows: List<List<LN>>) : LN()         // [[1, 2], [3, 4]]
    data class Polar(val r: LN, val angle: LN) : LN()          // 5∠30°
    data class Deg(val inner: LN) : LN()                       // 30°
    data class Neg(val a: LN) : LN()
    /** op: + − · (also * and a·b) × / and 'j' for implicit multiplication (2a, AB). */
    data class Bin(val op: Char, val a: LN, val b: LN) : LN()
    data class Pow(val a: LN, val e: LN) : LN()
    data class Call(val name: String, val args: List<LN>) : LN()
}

internal sealed class LStmt {
    data class Def(val name: Char, val value: LN) : LStmt()
    data class Task(val e: LN) : LStmt()
    data class Eq(val left: LN, val right: LN) : LStmt()
}

/**
 * Vectors and matrices. Recognises input like  a = (3, 5); b = 5∠30°; a + b  or  det([[1, 2], [3, 4]])
 * before the ordinary parser sees it, and solves it with steps (see [Vectors] and [Matrices]).
 */
object LinAlg {

    /** Function words, longest first so "rref" wins over "ref". */
    internal val LA_FUNCS = listOf(
        "transpose", "inverse", "eigen", "cross", "angle", "trace", "polar", "solve", "unit", "proj", "rref",
        "rank", "norm", "diag", "chol", "dot", "det", "inv", "ref", "eig", "mag", "lu"
    )
    private val SCALAR_FUNCS = listOf("arcsin", "arccos", "arctan", "sqrt", "sin", "cos", "tan", "exp", "log", "abs", "ln")
    private val WORDS = (LA_FUNCS + SCALAR_FUNCS + "pi").sortedByDescending { it.length }

    // ================= public API =================

    /** True if [text] is a vector / matrix problem. [known] = vector/matrix names defined on earlier notebook lines. */
    fun accepts(text: String, known: Set<Char> = emptySet()): Boolean {
        val tokens = try { tokenize(text) } catch (_: Exception) { return false }
        if (tokens.isEmpty()) return false
        if (tokens.any { it is Tok.Sym && it.c == '∠' }) return true
        if (tokens.any { it is Tok.Word && it.w in LA_FUNCS }) return true
        var depth = 0
        for (t in tokens) {
            if (t is Tok.Sym) when (t.c) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth >= 1) return true
            }
        }
        if (known.isEmpty() || tokens.none { it is Tok.Word && it.w.length == 1 && it.w[0] in known }) return false
        // With only earlier names to go on, "2x + 3 = 11" stays an ordinary equation; "a + b" and "c = a + b" are ours.
        val eq = tokens.indexOf(Tok.Sym('='))
        return eq < 0 || (eq == 1 && tokens[0] is Tok.Word && (tokens[0] as Tok.Word).w.length == 1)
    }

    /** Solves a vector / matrix problem. [known] holds values from earlier notebook lines. */
    fun solve(text: String, known: Map<Char, LVal> = emptyMap()): Solution = Vectors.solve(parse(text), known)

    /** Vector / matrix values defined in earlier notebook answers ("c = (7, 7)"), oldest first — later ones win. */
    fun known(answers: List<String?>): Map<Char, LVal> {
        val out = linkedMapOf<Char, LVal>()
        for (a in answers) if (a != null) out.putAll(valuesIn(a))
        return out
    }

    fun valuesIn(answer: String): Map<Char, LVal> {
        if (!answer.contains("matrix}")) return emptyMap()
        return try {
            val text = LatexInput.toText(answer)
            val out = linkedMapOf<Char, LVal>()
            for (st in parse(text)) if (st is LStmt.Def) {
                try { out[st.name] = Eval(emptyMap()).ev(st.value) } catch (_: MathError) {}
            }
            out
        } catch (_: Exception) { emptyMap() }
    }

    /** "Ans" for a vector / matrix answer: (plain text, LaTeX), or null. */
    fun ansOf(answer: String?): Pair<String, String>? {
        if (answer == null) return null
        val v = valuesIn(answer).values.lastOrNull() ?: return null
        val text = plain(v)
        return text to toLatex(text)
    }

    /** Plain text for a value, e.g. "(7, 7)" or "[[1, 2], [3, 4]]". */
    fun plain(v: LVal): String = when (v) {
        is LVal.Scal -> sText(v.s)
        is LVal.Vec -> v.c.joinToString(", ", "(", ")") { sText(it) }
        is LVal.Mat -> v.m.joinToString(", ", "[", "]") { r -> r.joinToString(", ", "[", "]") { it.toString() } }
    }

    private fun sText(s: S): String = when {
        s is S.Num -> s.v.toString()
        else -> try { LatexInput.toText(Sym.latex(s)) } catch (_: Exception) { Tex.decimal(LA.dbl(s)) }
    }

    /** LaTeX for the tap-to-edit editor (so history and examples load back into it). */
    fun toLatex(text: String): String = parse(text).joinToString(";\\;") { st ->
        when (st) {
            is LStmt.Def -> "${st.name}=" + Printer(true).print(st.value)
            is LStmt.Task -> Printer(true).print(st.e)
            is LStmt.Eq -> Printer(true).print(st.left) + "=" + Printer(true).print(st.right)
        }
    }

    /** Letters a problem reads (not the ones it defines), in order. */
    fun namesUsed(text: String): List<Char> {
        val stmts = try { parse(text) } catch (_: MathError) { return emptyList() }
        val defined = mutableSetOf<Char>()
        val out = linkedSetOf<Char>()
        fun walk(n: LN) {
            when (n) {
                is LN.Name -> if (n.c !in defined) out += n.c
                is LN.VecLit -> n.items.forEach { walk(it) }
                is LN.MatLit -> n.rows.flatten().forEach { walk(it) }
                is LN.Polar -> { walk(n.r); walk(n.angle) }
                is LN.Deg -> walk(n.inner)
                is LN.Neg -> walk(n.a)
                is LN.Bin -> { walk(n.a); walk(n.b) }
                is LN.Pow -> { walk(n.a); if (!(n.e is LN.Name && n.e.c == 'T')) walk(n.e) }
                is LN.Call -> n.args.forEach { walk(it) }
                else -> {}
            }
        }
        for (st in stmts) when (st) {
            is LStmt.Def -> { walk(st.value); defined += st.name }
            is LStmt.Task -> walk(st.e)
            is LStmt.Eq -> { walk(st.left); walk(st.right) }
        }
        return out.toList()
    }

    /** LaTeX for a value (vectors as columns, matrices in square brackets). */
    fun tex(v: LVal): String = LA.valTex(v)

    /** Display LaTeX of what was typed (vectors as columns, matrices in square brackets). */
    internal fun show(n: LN): String = Printer(false).print(n)

    // ================= tokens =================

    internal sealed class Tok {
        data class Num(val text: String) : Tok()
        data class Word(val w: String) : Tok()
        data class Sym(val c: Char) : Tok()
    }

    /** Signals input that belongs to the ordinary parser (lim, Σ, ∫, y′, inequalities …). */
    private class Foreign : Exception()

    internal fun tokenize(src: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        fun sym(c: Char) { out += Tok.Sym(c) }
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\n' -> { sym(';'); i++ }
                c.isWhitespace() -> i++
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val start = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    val t = src.substring(start, i)
                    if (t.count { it == '.' } > 1) throw MathError("Bad number \"$t\"")
                    out += Tok.Num(t)
                }
                src.startsWith("d/d", i) -> throw Foreign()
                c == 'π' -> { out += Tok.Word("π"); i++ }
                c.isLetter() && c != 'Σ' -> {
                    val start = i
                    while (i < src.length && src[i].isLetter() && src[i] != 'π' && src[i] != 'Σ') i++
                    split(src.substring(start, i), out)
                }
                c == '√' -> { out += Tok.Word("sqrt"); i++ }
                c == '+' -> { sym('+'); i++ }
                c == '-' || c == '−' -> { sym('-'); i++ }
                c == '·' || c == '⋅' || c == '*' || c == '∙' -> { sym('·'); i++ }
                c == '×' || c == '⨯' -> { sym('×'); i++ }
                c == '/' || c == '÷' -> { sym('/'); i++ }
                c == '^' -> { sym('^'); i++ }
                c == '∠' -> { sym('∠'); i++ }
                c == '°' -> { sym('°'); i++ }
                c == '(' || c == '[' -> { sym('('); i++ }
                c == ')' || c == ']' -> { sym(')'); i++ }
                c == ',' -> { sym(','); i++ }
                c == ';' -> { sym(';'); i++ }
                c == '=' -> { sym('='); i++ }
                c == '²' -> { sym('^'); out += Tok.Num("2"); i++ }
                c == '³' -> { sym('^'); out += Tok.Num("3"); i++ }
                c == 'ᵀ' -> { sym('^'); out += Tok.Word("T"); i++ }
                c == '⁻' && i + 1 < src.length && src[i + 1] == '¹' -> {
                    sym('^'); sym('('); sym('-'); out += Tok.Num("1"); sym(')'); i += 2
                }
                c == '|' -> {
                    // |v| is the length of v (and |A| the determinant).
                    val opening = out.isEmpty() || (out.last() as? Tok.Sym)?.c in listOf('(', ',', ';', '=', '+', '-', '·', '×', '/', '^', '∠')
                    if (opening) { out += Tok.Word("abs"); sym('(') } else sym(')')
                    i++
                }
                c in "∫Σ∑→∞'′″_<>≤≥∥‖" -> throw Foreign()
                else -> throw MathError("Unknown symbol '$c'")
            }
        }
        return out
    }

    /** "dot" -> dot, "ab" -> a, b, "lim" -> not ours. Letters keep their case (A ≠ a). */
    private fun split(word: String, out: MutableList<Tok>) {
        val lower = word.lowercase()
        if (lower.contains("lim")) throw Foreign()
        var j = 0
        while (j < word.length) {
            val w = WORDS.firstOrNull { lower.startsWith(it, j) && (word.length - j == it.length || it.length > 1) && it.length > 1 }
            when {
                w == "pi" -> { out += Tok.Word("π"); j += 2 }
                w != null -> { out += Tok.Word(w); j += w.length }
                else -> { out += Tok.Word(word[j].toString()); j++ }
            }
        }
    }

    // ================= parser =================

    internal fun parse(text: String): List<LStmt> {
        val tokens = try { tokenize(text) } catch (_: Foreign) {
            throw MathError("Vectors and matrices can't be mixed with lim, Σ, ∫, y′ or inequalities")
        }
        if (tokens.isEmpty()) throw MathError("Type a problem first")
        val parts = mutableListOf(mutableListOf<Tok>())
        var depth = 0
        for (t in tokens) {
            if (t is Tok.Sym && t.c == '(') depth++
            if (t is Tok.Sym && t.c == ')') depth--
            if (t is Tok.Sym && t.c == ';' && depth <= 0) parts += mutableListOf<Tok>() else parts.last() += t
        }
        val stmts = parts.filter { it.isNotEmpty() }.map { part ->
            var p = part
            while (p.size > 1 && p.last() == Tok.Sym('=')) p = p.dropLast(1).toMutableList()   // "a + b =" like a calculator
            // Missing closing brackets are added, like the rest of the app.
            val open = p.count { it == Tok.Sym('(') } - p.count { it == Tok.Sym(')') }
            if (open < 0) throw MathError("Unexpected ')' — check your brackets")
            Stream(p + List(open) { Tok.Sym(')') }).statement()
        }
        if (stmts.isEmpty()) throw MathError("Type a problem first")
        return stmts
    }

    private class Stream(val t: List<Tok>) {
        var pos = 0
        fun peek(k: Int = 0) = t.getOrNull(pos + k)
        fun isSym(c: Char, k: Int = 0) = (peek(k) as? Tok.Sym)?.c == c
        fun next(): Tok = t.getOrNull(pos++) ?: throw MathError("The problem ends too early")
        fun expect(c: Char, msg: String) { if (!isSym(c)) throw MathError(msg); pos++ }

        fun statement(): LStmt {
            val left = expr()
            if (peek() == null) return LStmt.Task(left)
            if (!isSym('=')) throw unexpected()
            pos++
            if (peek() == null) throw MathError("Nothing on the right side of '='")
            val right = expr()
            if (peek() != null) throw if (isSym('=')) MathError("Only one '=' per line (use ; between lines)") else unexpected()
            return if (left is LN.Name) LStmt.Def(left.c, right) else LStmt.Eq(left, right)
        }

        fun expr(): LN {
            var e = term()
            while (true) {
                e = when {
                    isSym('+') -> { pos++; LN.Bin('+', e, term()) }
                    isSym('-') -> { pos++; LN.Bin('-', e, term()) }
                    else -> return e
                }
            }
        }

        private fun startsFactor(): Boolean {
            val p = peek()
            return p is Tok.Num || p is Tok.Word || (p is Tok.Sym && p.c == '(')
        }

        fun term(): LN {
            var e = unary()
            while (true) {
                val p = peek()
                e = when {
                    p is Tok.Sym && (p.c == '·' || p.c == '×' || p.c == '/') -> { pos++; LN.Bin(p.c, e, unary()) }
                    startsFactor() -> LN.Bin('j', e, power())
                    else -> return e
                }
            }
        }

        fun unary(): LN = when {
            isSym('-') -> { pos++; LN.Neg(unary()) }
            isSym('+') -> { pos++; unary() }
            else -> power()
        }

        fun power(): LN {
            val base = postfix()
            if (isSym('^')) { pos++; return LN.Pow(base, unary()) }
            return base
        }

        fun postfix(): LN {
            var e = primary()
            while (isSym('°')) { pos++; e = LN.Deg(e) }
            if (isSym('∠')) {
                pos++
                val neg = isSym('-').also { if (it) pos++ }
                var a = primary()
                while (isSym('°')) { pos++; a = LN.Deg(a) }
                if (neg) a = LN.Neg(a)
                e = LN.Polar(e, a)
            }
            return e
        }

        fun primary(): LN = when (val tok = next()) {
            is Tok.Num -> LN.Num(Rational.parse(tok.text), tok.text)
            is Tok.Word -> word(tok.w)
            is Tok.Sym -> when (tok.c) {
                '(' -> bracket()
                ')' -> throw MathError("Unexpected ')'")
                ',' -> throw MathError("Unexpected ','")
                '∠' -> throw MathError("∠ needs a length before it, like 5∠30°")
                '°' -> throw MathError("° needs a number before it")
                else -> throw MathError("Unexpected '${tok.c}'")
            }
        }

        private fun bracket(): LN {
            if (isSym(')')) throw MathError("Empty brackets")
            val items = mutableListOf(expr())
            while (isSym(',')) { pos++; items += expr() }
            expect(')', "Missing ')'")
            if (items.size == 1) return items[0]
            if (items.all { it is LN.VecLit }) {
                val rows = items.map { (it as LN.VecLit).items }
                if (rows.map { it.size }.toSet().size != 1) throw MathError("Every row of a matrix needs the same number of entries")
                return LN.MatLit(rows)
            }
            if (items.any { it is LN.VecLit || it is LN.MatLit }) throw MathError("A vector's entries must be numbers")
            return LN.VecLit(items)
        }

        private fun word(w: String): LN {
            if (w == "π") return LN.Const('π')
            if (w.length == 1) return if (w == "e") LN.Const('e') else LN.Name(w[0])
            val args = mutableListOf<LN>()
            if (isSym('(')) {
                pos++
                if (isSym(')')) throw MathError("$w( ) needs something inside")
                args += expr()
                while (isSym(',')) { pos++; args += expr() }
                expect(')', "Missing ')' after $w(")
                // f((3, 4)) and f(3, 4) both mean f of the vector (3, 4), except for two-argument functions.
                if (args.size > 1 && w !in TWO_ARGS && args.none { it is LN.VecLit || it is LN.MatLit }) {
                    return LN.Call(w, listOf(LN.VecLit(args.toList())))
                }
            } else {
                if (peek() == null) throw MathError("$w needs something after it")
                args += power()
            }
            return LN.Call(w, args)
        }

        fun unexpected() = MathError("Couldn't read the problem near '${peek()?.let { if (it is Tok.Sym) it.c.toString() else if (it is Tok.Word) it.w else (it as Tok.Num).text }}'")
    }

    internal val TWO_ARGS = setOf("dot", "cross", "angle", "proj", "solve")

    // ================= printing what was typed =================

    /** [editor] = LaTeX the MathLive editor understands; otherwise display LaTeX. */
    private class Printer(val editor: Boolean) {
        fun print(n: LN): String = when (n) {
            is LN.Num -> if (n.r.isInteger || n.text.contains('.')) n.text else Tex.rational(n.r)
            is LN.Name -> n.c.toString()
            is LN.Const -> if (n.c == 'π') "\\pi" else "e"
            is LN.VecLit -> if (editor) "\\left(" + n.items.joinToString(",") { print(it) } + "\\right)"
                else "\\begin{pmatrix}" + n.items.joinToString(" \\\\ ") { print(it) } + "\\end{pmatrix}"
            is LN.MatLit -> (if (editor) "\\begin{pmatrix}" else "\\begin{bmatrix}") +
                n.rows.joinToString(" \\\\ ") { r -> r.joinToString(" & ") { print(it) } } +
                (if (editor) "\\end{pmatrix}" else "\\end{bmatrix}")
            is LN.Polar -> wrap(n.r, 4) + "\\angle " + wrap(n.angle, 4)
            is LN.Deg -> wrap(n.inner, 5) + "^{\\circ}"
            is LN.Neg -> "-" + wrap(n.a, 3)
            is LN.Bin -> when (n.op) {
                '+' -> print(n.a) + " + " + wrap(n.b, 1)
                '-' -> print(n.a) + " - " + wrap(n.b, 2)
                '/' -> "\\frac{${print(n.a)}}{${print(n.b)}}"
                '×' -> wrap(n.a, 2) + " \\times " + wrap(n.b, 3)
                '·' -> wrap(n.a, 2) + " \\cdot " + wrap(n.b, 3)
                else -> {
                    val r = wrap(n.b, 3)
                    wrap(n.a, 2) + (if (r.first().isDigit()) " \\cdot " else " ") + r
                }
            }
            is LN.Pow -> wrap(n.a, 5) + "^{" + print(n.e) + "}"
            is LN.Call -> call(n)
        }

        private fun call(n: LN.Call): String {
            val inside = n.args.joinToString(",\\, ") { print(it) }
            return when (n.name) {
                "abs", "mag", "norm" -> "\\left|$inside\\right|"
                "sqrt" -> "\\sqrt{$inside}"
                "exp" -> "e^{$inside}"
                "det" -> "\\det\\left($inside\\right)"
                "sin", "cos", "tan", "ln", "log", "arcsin", "arccos", "arctan" -> "\\${n.name}\\left($inside\\right)"
                "transpose" -> if (editor) "\\operatorname{transpose}\\left($inside\\right)" else wrap(n.args[0], 5) + "^{T}"
                "inv", "inverse" -> if (editor) "\\operatorname{${n.name}}\\left($inside\\right)" else wrap(n.args[0], 5) + "^{-1}"
                else -> "\\operatorname{${n.name}}\\left($inside\\right)"
            }
        }

        private fun prec(n: LN): Int = when (n) {
            is LN.Bin -> when (n.op) { '+', '-' -> 1; '/' -> 4; else -> 2 }
            is LN.Neg -> 3
            is LN.Polar -> 3
            is LN.Num -> if (n.r.sign < 0) 3 else 5
            is LN.Pow, is LN.Deg -> 4
            else -> 5
        }

        private fun wrap(n: LN, min: Int) = if (prec(n) < min) "\\left(${print(n)}\\right)" else print(n)
    }
}

/** Shared helpers for the vector and matrix solvers. */
internal object LA {

    fun dbl(s: S): Double = Sym.eval(s, emptyMap())

    /** A decimal as an exact fraction (12 significant figures), for angles with no exact sine/cosine. */
    fun approxS(d: Double): S {
        if (!d.isFinite()) throw MathError("The numbers got too large")
        val bd = BigDecimal(d).round(MathContext(12))
        val scaled = bd.stripTrailingZeros()
        val scale = scaled.scale()
        val r = if (scale <= 0) Rational.of(scaled.toBigInteger())
            else Rational.of(scaled.unscaledValue(), BigInteger.TEN.pow(scale))
        return S.Num(r)
    }

    /** LaTeX for a number; long fractions from decimal angles are shown as decimals. */
    fun sTex(s: S): String {
        if (s is S.Num && s.v.den.bitLength() > 24) return Tex.decimal(s.v.toDouble())
        return Sym.latex(s)
    }

    /** In brackets when negative or a sum: for "3 + (−2)" and "(−2)²". */
    fun par(s: S): String {
        val t = sTex(s)
        return if (s is S.Sum || t.startsWith("-")) "\\left($t\\right)" else t
    }

    /** In brackets before a power: negatives, sums, fractions and products, as in (−2)², (3/2)². */
    fun parPow(s: S): String {
        val t = sTex(s)
        return if (s is S.Num && s.v.isInteger && s.v.sign >= 0) t else if (s is S.Var || s is S.Const) t else "\\left($t\\right)"
    }

    fun parR(r: Rational): String = if (r.sign < 0) "\\left(${Tex.rational(r)}\\right)" else Tex.rational(r)

    fun vecTex(v: List<S>) = "\\begin{pmatrix}" + v.joinToString(" \\\\ ") { sTex(it) } + "\\end{pmatrix}"

    fun matTex(m: List<List<Rational>>) =
        "\\begin{bmatrix}" + m.joinToString(" \\\\ ") { r -> r.joinToString(" & ") { Tex.rational(it) } } + "\\end{bmatrix}"

    /** A matrix whose entries are already LaTeX (e.g. "1 + 5"). */
    fun gridTex(m: List<List<String>>) = "\\begin{bmatrix}" + m.joinToString(" \\\\ ") { it.joinToString(" & ") } + "\\end{bmatrix}"

    fun valTex(v: LVal): String = when (v) {
        is LVal.Scal -> sTex(v.s)
        is LVal.Vec -> vecTex(v.c)
        is LVal.Mat -> matTex(v.m)
    }

    /** Angle in degrees for display: "45^{\circ}" or "36.87^{\circ}" (with exact = false). */
    fun degrees(rad: Double): Pair<String, Boolean> {
        var d = Math.toDegrees(rad)
        if (Math.abs(d) < 1e-9) d = 0.0
        val r = Math.round(d)
        if (Math.abs(d - r) < 1e-7) return "$r^{\\circ}" to true
        val s = "%.2f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
        return "$s^{\\circ}" to false
    }

    fun isRational(s: S) = s is S.Num
    fun rat(s: S, what: String = "Matrix entries"): Rational =
        (s as? S.Num)?.v ?: throw MathError("$what must be exact numbers like 3, −1/2 or 0.25 (not √2 or π)")
}
