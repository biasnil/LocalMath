package com.localmath.engine

/**
 * Converts the LaTeX produced by the tap-to-edit math editor (MathLive) into LocalMath's text syntax,
 * so the same parser and solver handle both input modes. Also goes the other way (text -> LaTeX)
 * so history entries can be loaded back into the editor.
 */
object LatexInput {

    private sealed class T {
        data class Cmd(val name: String) : T()
        data class Ch(val c: String) : T()        // a character, or a whole number like 3.14
        object Open : T()
        object Close : T()
        object Sup : T()
        object Sub : T()
    }

    private enum class K { ATOM, ADD, REL, SEP, BIG }

    private class Item(var text: String, val kind: K, val big: Big? = null)

    private sealed class Big {
        data class Lim(val v: String, val to: String) : Big()
        data class Sum(val k: String, val from: String, val to: String) : Big()
        data class Int(val lower: String?, val upper: String?) : Big()
        data class Deriv(val v: Char, val order: kotlin.Int) : Big()
    }

    private val FUNCS = setOf("sin", "cos", "tan", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
        "arsinh", "arcosh", "artanh", "ln", "log", "exp", "sqrt", "abs", "lg", "conj", "arg", "real", "imag") + LinAlg.LA_FUNCS + Extras.FUNCS

    /** Matrix environments from the editor's grid (vmatrix = determinant bars). */
    private val MATRIX_ENVS = setOf("pmatrix", "bmatrix", "matrix", "Bmatrix", "vmatrix", "smallmatrix")

    /** SI prefixes (inserted as \mathrm{k}) and constants (inserted as \mathbf{c}). */
    private val PREFIX = mapOf("T" to 12, "G" to 9, "M" to 6, "k" to 3, "m" to -3, "\\mu" to -6, "µ" to -6, "μ" to -6, "u" to -6, "n" to -9, "p" to -12)
    val CONSTANTS = linkedMapOf(
        "c" to "299792458",                    // speed of light, m/s
        "q" to "1.602176634×10^(-19)",         // elementary charge, C
        "g" to "9.80665",                      // gravity, m/s²
        "\\varepsilon_0" to "8.8541878128×10^(-12)",
        "\\mu_0" to "1.25663706212×10^(-6)",
        "k_B" to "1.380649×10^(-23)",
        "h" to "6.62607015×10^(-34)"
    )

    fun toText(latex: String): String {
        if (latex.contains("\\placeholder")) throw MathError("Please fill in the empty boxes first")
        val tokens = tokenize(latex)
        if (tokens.isEmpty()) throw MathError("Enter a math problem first")
        val conv = Converter(tokens)
        val items = conv.seq(stopAtClose = false)
        return assemble(items).trim()
    }

    private fun tokenize(src: String): List<T> {
        val out = mutableListOf<T>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\\' -> {
                    if (i + 1 < src.length && src[i + 1].isLetter()) {
                        var j = i + 1
                        while (j < src.length && src[j].isLetter()) j++
                        out += T.Cmd(src.substring(i + 1, j)); i = j
                    } else if (i + 1 < src.length) { out += T.Cmd(src[i + 1].toString()); i += 2 }
                    else i++
                }
                c == '{' -> { out += T.Open; i++ }
                c == '}' -> { out += T.Close; i++ }
                c == '^' -> { out += T.Sup; i++ }
                c == '_' -> { out += T.Sub; i++ }
                c.isWhitespace() || c == '~' -> i++
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    var j = i
                    while (j < src.length && (src[j].isDigit() || src[j] == '.')) j++
                    out += T.Ch(src.substring(i, j)); i = j
                }
                else -> { out += T.Ch(c.toString()); i++ }
            }
        }
        return out
    }

    private class Converter(val t: List<T>) {
        var pos = 0
        var absOpen = false
        fun peek() = t.getOrNull(pos)
        fun next() = t.getOrNull(pos++) ?: throw MathError("The expression looks incomplete")

        /** Items until the matching '}' (or the end). */
        fun seq(stopAtClose: Boolean, stopAtRight: Boolean = false): MutableList<Item> {
            val items = mutableListOf<Item>()
            while (true) {
                val tok = peek() ?: break
                if (tok == T.Close && stopAtClose) break
                if (stopAtRight && tok is T.Cmd && (tok.name == "right" || tok.name == "mright")) break
                if (stopAtRight && tok is T.Ch && tok.c == ")" ) break
                one(items)
            }
            return items
        }

        /** A braced group or a single token, converted to text. */
        fun arg(): String {
            val tok = peek() ?: throw MathError("The expression looks incomplete")
            if (tok == T.Open) {
                next()
                val items = seq(stopAtClose = true)
                if (peek() == T.Close) next()
                return assemble(items)
            }
            val items = mutableListOf<Item>()
            one(items)
            return assemble(items)
        }

        /** Raw tokens of a braced group (for \mathrm{…} lookups). */
        fun rawGroup(): String {
            if (peek() != T.Open) return tokText(next())
            next()
            val sb = StringBuilder()
            var depth = 1
            while (true) {
                val tok = next()
                if (tok == T.Open) depth++
                if (tok == T.Close) { depth--; if (depth == 0) break }
                sb.append(tokText(tok))
            }
            return sb.toString()
        }

        fun tokText(tok: T): String = when (tok) {
            is T.Cmd -> "\\" + tok.name
            is T.Ch -> tok.c
            T.Open -> "{"
            T.Close -> "}"
            T.Sup -> "^"
            T.Sub -> "_"
        }

        private fun atom(items: MutableList<Item>, text: String) { items += Item(text, K.ATOM) }

        private fun appendToLast(items: MutableList<Item>, suffix: String) {
            val last = items.lastOrNull() ?: throw MathError("A power or subscript needs something before it")
            last.text = last.text + suffix
        }

        /** Reads optional _{…} and ^{…} after \lim, \sum, \int (in either order). */
        private fun limits(): Pair<String?, String?> {
            var lower: String? = null
            var upper: String? = null
            repeat(2) {
                when (peek()) {
                    T.Sub -> { next(); lower = arg() }
                    T.Sup -> { next(); upper = arg() }
                    else -> {}
                }
            }
            return lower to upper
        }

        private fun skipLimitsCmd() {
            while (peek().let { it is T.Cmd && (it.name == "limits" || it.name == "nolimits" || it.name == "displaystyle") }) next()
        }

        fun one(items: MutableList<Item>) {
            when (val tok = next()) {
                T.Open -> {
                    val inner = seq(stopAtClose = true)
                    if (peek() == T.Close) next()
                    val text = assemble(inner)
                    if (text.isNotBlank()) atom(items, "($text)")
                }
                T.Close -> {}
                T.Sup -> {
                    if (peek() is T.Cmd && (peek() as T.Cmd).name == "circ") { next(); val last = items.lastOrNull() ?: throw MathError("° needs a number before it"); last.text = "((${last.text})×π/180)"; return }
                    val save = pos
                    val raw = if (peek() == T.Open) rawGroup() else tokText(next())
                    val primes = Regex("^(\\\\prime|')+$").matchEntire(raw.replace("\\doubleprime", "\\prime\\prime"))
                    if (primes != null) {
                        val n = Regex("\\\\prime|'").findAll(raw.replace("\\doubleprime", "\\prime\\prime")).count()
                        appendToLast(items, "'".repeat(n)); return
                    }
                    if (raw == "\\circ") { val last = items.lastOrNull() ?: throw MathError("° needs a number before it"); last.text = "((${last.text})×π/180)"; return }
                    pos = save
                    appendToLast(items, "^(" + arg() + ")")
                }
                T.Sub -> appendToLast(items, "_(" + arg() + ")")
                is T.Ch -> when (val c = tok.c) {
                    "+" -> items += Item("+", K.ADD)
                    "-", "−" -> items += Item("-", K.ADD)
                    "=", "<", ">", "≤", "≥" -> items += Item(c, K.REL)
                    ",", ";" -> items += Item(c, K.SEP)
                    "'" -> appendToLast(items, "'")
                    "|" -> { atom(items, if (absOpen) ")" else "abs("); absOpen = !absOpen }
                    "[" -> atom(items, "(")
                    "]" -> atom(items, ")")
                    "*", "×" -> atom(items, "×")
                    "·", "⋅" -> atom(items, "·")
                    "/", "÷" -> atom(items, "÷")
                    else -> atom(items, c)
                }
                is T.Cmd -> command(tok.name, items)
            }
        }

        private fun command(name: String, items: MutableList<Item>) {
            when (name) {
                ",", ";", ":", "!", " ", "quad", "qquad", "space", "thinspace", "medspace", "thickspace", "displaystyle", "limits", "nolimits" -> {}
                "times", "ast" -> atom(items, "×")
                "cdot" -> atom(items, "·")
                "%" -> atom(items, "%")
                "binom" -> { val n = arg(); val r = arg(); atom(items, "nCr($n, $r)") }
                "angle", "measuredangle" -> atom(items, "∠")
                "begin" -> matrix(items)
                "end" -> { rawGroup() }
                "div" -> atom(items, "÷")
                "pi" -> atom(items, "π")
                "infty" -> atom(items, "∞")
                "to", "rightarrow", "longrightarrow", "mapsto" -> atom(items, "→")
                "le", "leq", "leqslant" -> items += Item("≤", K.REL)
                "ge", "geq", "geqslant" -> items += Item("≥", K.REL)
                "lt" -> items += Item("<", K.REL)
                "gt" -> items += Item(">", K.REL)
                "ne", "neq" -> throw MathError("≠ isn't supported; use = or an inequality")
                "exponentialE" -> atom(items, "e")
                "differentialD", "partial" -> atom(items, "d")
                "imaginaryI" -> atom(items, "i")
                "overline", "bar" -> atom(items, "conj(${arg()})")
                "prime" -> appendToLast(items, "'")
                "doubleprime" -> appendToLast(items, "''")
                "parallel", "Vert", "|" -> atom(items, "∥")
                "lbrack", "lparen" -> atom(items, "(")
                "rbrack", "rparen" -> atom(items, ")")
                "vert", "lvert", "rvert" -> { atom(items, if (absOpen) ")" else "abs("); absOpen = !absOpen }
                "placeholder" -> throw MathError("Please fill in the empty boxes first")
                "frac", "dfrac", "tfrac", "cfrac" -> fraction(items)
                "sqrt" -> {
                    var index: String? = null
                    if (peek() is T.Ch && (peek() as T.Ch).c == "[") {
                        next()
                        val sb = mutableListOf<Item>()
                        while (!(peek() is T.Ch && (peek() as T.Ch).c == "]")) one(sb)
                        next()
                        index = assemble(sb)
                    }
                    val body = arg()
                    atom(items, if (index == null) "√($body)" else "(($body)^(1/($index)))")
                }
                "left", "mleft", "bigl", "Bigl", "big", "Big" -> leftRight(items)
                "right", "mright" -> { next() }
                "operatorname", "mathrm", "text", "textrm", "mathit", "operatorname*" -> roman(name, items)
                "mathbf", "boldsymbol" -> {
                    val raw = rawGroup().replace(" ", "")
                    val value = CONSTANTS[raw] ?: CONSTANTS[raw.replace("{", "").replace("}", "")]
                        ?: throw MathError("Unknown constant $raw")
                    atom(items, "($value)")
                }
                "lim" -> {
                    skipLimitsCmd()
                    val (lower, _) = limits()
                    val sub = lower ?: throw MathError("Write the limit as lim with x → a underneath")
                    val parts = sub.split("→")
                    if (parts.size != 2) throw MathError("Write the limit as lim with x → a underneath")
                    var to = parts[1].trim()
                    to = to.replace(Regex("\\^\\(\\s*\\+\\s*\\)$"), "+").replace(Regex("\\^\\(\\s*-\\s*\\)$"), "-")
                    items += Item("lim", K.BIG, Big.Lim(parts[0].trim(), to))
                }
                "sum" -> {
                    skipLimitsCmd()
                    val (lower, upper) = limits()
                    val low = lower ?: throw MathError("Σ needs a start underneath, like k = 1")
                    val up = upper ?: throw MathError("Σ needs an end on top")
                    val eq = low.split("=")
                    if (eq.size != 2) throw MathError("Write the start of Σ as k = 1")
                    items += Item("Σ", K.BIG, Big.Sum(eq[0].trim(), eq[1].trim(), up.trim()))
                }
                "int" -> {
                    skipLimitsCmd()
                    val (lower, upper) = limits()
                    if ((lower == null) != (upper == null)) throw MathError("A definite integral needs both limits")
                    items += Item("∫", K.BIG, Big.Int(lower, upper))
                }
                "log" -> {
                    if (peek() == T.Sub) {
                        next()
                        val base = arg().trim()
                        if (base != "10") throw MathError("Only log base 10 (log) and ln are supported")
                    }
                    atom(items, "log")
                }
                in FUNCS -> atom(items, name)
                else -> throw MathError("LocalMath doesn't understand \\$name yet")
            }
        }

        /** \begin{pmatrix} a & b \\ c & d \end{pmatrix} -> [[a, b], [c, d]]; a single column -> (a, b). */
        private fun matrix(items: MutableList<Item>) {
            val env = rawGroup().replace(" ", "")
            if (env !in MATRIX_ENVS) throw MathError("LocalMath doesn't understand $env yet")
            val rows = mutableListOf(mutableListOf<String>())
            while (true) {
                val cell = mutableListOf<Item>()
                while (true) {
                    val tok = peek() ?: throw MathError("The matrix isn't finished")
                    if (tok is T.Ch && tok.c == "&") break
                    if (tok is T.Cmd && (tok.name == "\\" || tok.name == "cr" || tok.name == "end")) break
                    one(cell)
                }
                rows.last() += assemble(cell).trim()
                val sep = next()
                when {
                    sep is T.Ch -> {}
                    sep is T.Cmd && sep.name == "end" -> { rawGroup(); break }
                    else -> rows += mutableListOf<String>()
                }
            }
            val clean = rows.filter { r -> r.any { it.isNotBlank() } }
            if (clean.isEmpty()) throw MathError("The matrix is empty")
            if (clean.any { r -> r.any { it.isBlank() } }) throw MathError("Please fill in every box of the matrix")
            if (clean.map { it.size }.toSet().size != 1) throw MathError("Every row of a matrix needs the same number of entries")
            val text = if (clean.size >= 2 && clean.all { it.size == 1 }) clean.joinToString(", ", "(", ")") { it[0] }
                else clean.joinToString(", ", "[", "]") { r -> r.joinToString(", ", "[", "]") }
            atom(items, if (env == "vmatrix") "det($text)" else text)
        }

        private fun roman(cmd: String, items: MutableList<Item>) {
            val raw = rawGroup().replace(" ", "")
            if (cmd.startsWith("operatorname")) {
                if (raw in FUNCS) { atom(items, raw); return }
                if (raw == "Re") { atom(items, "real"); return }
                if (raw == "Im") { atom(items, "imag"); return }
                throw MathError("Unknown function $raw")
            }
            PREFIX[raw]?.let { atom(items, "(10^($it))"); return }
            when {
                raw == "d" -> atom(items, "d")
                raw == "e" -> atom(items, "e")
                raw in FUNCS -> atom(items, raw)
                raw.isEmpty() -> {}
                raw.all { it.isLetterOrDigit() } -> atom(items, raw)
                else -> throw MathError("LocalMath doesn't understand $raw yet")
            }
        }

        private fun leftRight(items: MutableList<Item>) {
            val open = tokText(next())
            val inner = seq(stopAtClose = false, stopAtRight = true)
            val closeTok = peek()
            if (closeTok is T.Cmd && (closeTok.name == "right" || closeTok.name == "mright")) { next(); if (peek() != null) next() }
            else if (closeTok is T.Ch && closeTok.c == ")") next()
            val body = assemble(inner)
            atom(items, when (open) {
                "|", "\\vert", "\\lvert" -> "abs($body)"
                "." -> body
                else -> "($body)"
            })
        }

        private fun fraction(items: MutableList<Item>) {
            val num = arg()
            val den = arg()
            val n = num.replace(" ", "")
            val d = den.replace(" ", "")
            val dn = Regex("^d(\\^\\((\\d+)\\))?$").matchEntire(n)
            val dd = Regex("^d([a-zA-Z])(\\^\\((\\d+)\\))?$").matchEntire(d)
            if (dn != null && dd != null) {
                val order = dn.groupValues[2].ifEmpty { "1" }.toInt()
                items += Item("d/d", K.BIG, Big.Deriv(dd.groupValues[1][0], order))
                return
            }
            // dy/dx as a fraction on its own
            val dyx = Regex("^d([a-zA-Z])$").matchEntire(n)
            if (dyx != null && dd != null && dd.groupValues[2].isEmpty()) { atom(items, dyx.groupValues[1] + "'"); return }
            atom(items, "(($num)/($den))")
        }
    }

    /** Joins items, wrapping lim / Σ / ∫ / d/dx around what follows them (up to the next + or −). */
    private fun assemble(items: List<Item>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < items.size) {
            val it = items[i]
            if (it.kind != K.BIG) {
                sb.append(it.text).append(' ')
                i++
                continue
            }
            val body = mutableListOf<Item>()
            var j = i + 1
            while (j < items.size) {
                val k = items[j].kind
                if ((k == K.ADD && body.isNotEmpty()) || k == K.REL || k == K.SEP) break
                body += items[j]; j++
            }
            sb.append(wrap(it.big!!, body)).append(' ')
            i = j
        }
        return sb.toString().trim()
    }

    private fun wrap(b: Big, body: MutableList<Item>): String = when (b) {
        is Big.Lim -> "lim(${assemble(body)}, ${b.v}→${b.to})"
        is Big.Sum -> "Σ(${assemble(body)}, ${b.k}, ${b.from}, ${b.to})"
        is Big.Deriv -> "d/d${b.v}(${assemble(body)}" + (if (b.order > 1) ", ${b.order}" else "") + ")"
        is Big.Int -> {
            // Trailing "d x" is the differential.
            var v: String? = null
            if (body.size >= 2 && body[body.size - 2].text == "d" && Regex("^[a-zA-Z]$").matches(body.last().text)) {
                v = body.last().text
                body.removeAt(body.size - 1); body.removeAt(body.size - 1)
            } else if (body.isNotEmpty() && Regex("^d[a-zA-Z]$").matches(body.last().text)) {
                v = body.last().text.substring(1); body.removeAt(body.size - 1)
            }
            val bounds = if (b.lower != null) ", ${b.lower}, ${b.upper}" else ""
            "∫(${assemble(body)}$bounds)" + (v?.let { "d$it" } ?: "")
        }
    }

    // ---------------- text -> LaTeX (to load history into the editor) ----------------

    fun fromText(text: String): String {
        if (Extras.accepts(text)) return Extras.toLatex(text)
        if (LinAlg.accepts(text)) return LinAlg.toLatex(text)
        return fromParsed(text)
    }

    private fun fromParsed(text: String): String = when (val input = Parser.parse(text)) {
        is Input.Expression -> Tex.expr(input.expr)
        is Input.Equation -> Tex.equation(input.left, input.right)
        is Input.System -> input.equations.joinToString(";\\;") { Tex.equation(it.left, it.right) }
        is Input.Inequality -> Tex.expr(input.left) + " " + relTex(input.op) + " " + Tex.expr(input.right)
        is Input.Between -> Tex.expr(input.left) + " " + relTex(input.op1) + " " + Tex.expr(input.middle) + " " +
            relTex(input.op2) + " " + Tex.expr(input.right)
    }

    private fun relTex(op: String): String = when (op) {
        "≤", "<=" -> "\\le"
        "≥", ">=" -> "\\ge"
        else -> op
    }
}
