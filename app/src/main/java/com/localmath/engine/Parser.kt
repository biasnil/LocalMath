package com.localmath.engine

/** Abstract syntax tree, exactly as the user typed it (before any simplification). */
sealed class Expr {
    data class Num(val value: Rational, val text: String? = null) : Expr()  // text = as typed, e.g. "0.5"
    data class Var(val name: Char) : Expr()
    data class Const(val name: Char) : Expr()                               // 'e' or 'π'
    data class Func(val name: String, val arg: Expr) : Expr()               // sin cos tan arcsin … ln log sqrt exp abs
    data class Neg(val inner: Expr) : Expr()
    data class Add(val left: Expr, val right: Expr) : Expr()
    data class Sub(val left: Expr, val right: Expr) : Expr()
    data class Mul(val left: Expr, val right: Expr) : Expr()
    data class Div(val left: Expr, val right: Expr) : Expr()
    data class Pow(val base: Expr, val exponent: Expr) : Expr()
    data class Derivative(val body: Expr, val variable: Char, val order: Int = 1) : Expr()
    data class Integral(val body: Expr, val variable: Char?, val lower: Expr?, val upper: Expr?) : Expr()

    /** Calculus-2 notation. Grouped so code that doesn't handle them can reject them in one branch. */
    sealed class Special : Expr()
    object Inf : Special() { override fun toString() = "∞" }
    /** side: 0 = both sides, +1 = from the right (a+), −1 = from the left (a−). */
    data class Limit(val body: Expr, val variable: Char?, val to: Expr, val side: Int) : Special()
    data class Sum(val body: Expr, val variable: Char?, val from: Expr, val to: Expr) : Special()
    /** a_n, a_(n−1), a_1 */
    data class Seq(val name: Char, val index: Expr) : Special()
    /** y', y'' (order = number of primes) */
    data class Prime(val name: Char, val order: Int) : Special()
    /** y(0), y'(0) — only inside differential-equation input */
    data class Call(val name: Char, val order: Int, val arg: Expr) : Special()

    private fun children(): List<Expr> = when (this) {
        is Num, is Var, is Const, is Inf, is Prime -> emptyList()
        is Limit -> listOf(body, to)
        is Sum -> listOf(body, from, to)
        is Seq -> listOf(index)
        is Call -> listOf(arg)
        is Func -> listOf(arg)
        is Neg -> listOf(inner)
        is Add -> listOf(left, right)
        is Sub -> listOf(left, right)
        is Mul -> listOf(left, right)
        is Div -> listOf(left, right)
        is Pow -> listOf(base, exponent)
        is Derivative -> listOf(body)
        is Integral -> listOfNotNull(body, lower, upper)
    }

    fun variables(): Set<Char> = when (this) {
        is Var -> setOf(name)
        is Derivative -> body.variables() + variable
        is Limit -> body.variables() - (variable ?: ' ') + to.variables()
        is Sum -> body.variables() - (variable ?: ' ') + from.variables() + to.variables()
        else -> children().flatMap { it.variables() }.toSet()
    }

    /** True if this uses sin, ln, e, π, √, d/dx, ∫ — anything a plain polynomial can't hold. */
    fun hasFunctions(): Boolean = this is Const || this is Func || this is Derivative || this is Integral ||
        children().any { it.hasFunctions() }

    fun hasCalculus(): Boolean = this is Derivative || this is Integral || this is Limit || this is Sum ||
        children().any { it.hasCalculus() }

    fun has(test: (Expr) -> Boolean): Boolean = test(this) || children().any { it.has(test) }
}

sealed class Input {
    data class Expression(val expr: Expr) : Input()
    data class Equation(val left: Expr, val right: Expr) : Input()
    data class System(val equations: List<Equation>) : Input()
    /** [op] is one of "<", ">", "≤", "≥". */
    data class Inequality(val left: Expr, val op: String, val right: Expr) : Input()
    /** left op1 middle op2 right, e.g. 1 < x ≤ 3. Both signs point the same way. */
    data class Between(val left: Expr, val op1: String, val middle: Expr, val op2: String, val right: Expr) : Input() {
        val lower get() = Inequality(left, op1, middle)
        val upper get() = Inequality(middle, op2, right)
    }
}

private sealed class Token {
    data class Num(val text: String) : Token()
    data class Var(val name: Char) : Token()
    data class Const(val name: Char) : Token()
    data class Func(val name: String) : Token()
    data class Deriv(val variable: Char) : Token()
    data class Op(val symbol: Char) : Token()
    data class Rel(val op: String) : Token() { override fun toString() = op }
    object Integral : Token() { override fun toString() = "∫" }
    object Lim : Token() { override fun toString() = "lim" }
    object Sigma : Token() { override fun toString() = "Σ" }
    object Inf : Token() { override fun toString() = "∞" }
    object Arrow : Token() { override fun toString() = "→" }
    object Underscore : Token() { override fun toString() = "_" }
    object Prime : Token() { override fun toString() = "'" }
    object LParen : Token() { override fun toString() = "(" }
    object RParen : Token() { override fun toString() = ")" }
    object Equals : Token() { override fun toString() = "=" }
    object Comma : Token() { override fun toString() = "," }
    object Semicolon : Token() { override fun toString() = ";" }
}

object Parser {

    /** Longest names first so "exp" wins over "e" and "sqrt" over "s". */
    private val FUNCTION_NAMES = listOf(
        "arsinh", "arcosh", "artanh", "arcsin", "arccos", "arctan",
        "sinh", "cosh", "tanh", "sqrt", "conj", "real", "imag", "sin", "cos", "tan", "exp", "log", "abs", "arg", "ln"
    )

    /**
     * [imaginaryUnit]: the letter i means √−1 (true everywhere except the formula library,
     * where i is an ordinary letter such as V_in).
     */
    fun parse(source: String, imaginaryUnit: Boolean = true): Input {
        var tokens = tokenize(source, imaginaryUnit)
        if (tokens.isEmpty()) throw MathError("Type an equation or expression first")
        // "5 * 6 =" means "work out 5 * 6", like a calculator.
        while (tokens.size > 1 && tokens.last() == Token.Equals) tokens = tokens.dropLast(1)

        // Split on ';' (or new lines) for systems of equations.
        val parts = mutableListOf(mutableListOf<Token>())
        for (t in tokens) if (t == Token.Semicolon) parts += mutableListOf<Token>() else parts.last() += t
        val nonEmpty = parts.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) throw MathError("Type an equation or expression first")

        // y(0) means "y at 0" only in differential equations; elsewhere y(…) is y times (…).
        val odeMode = tokens.any { it == Token.Prime }
        if (nonEmpty.size == 1) return TokenStream(autoClose(nonEmpty[0]), odeMode).parseInput()

        val equations = nonEmpty.mapIndexed { i, part ->
            when (val input = TokenStream(autoClose(part), odeMode).parseInput()) {
                is Input.Equation -> input
                is Input.Inequality, is Input.Between -> throw MathError("Systems of inequalities aren't supported yet")
                else -> throw MathError("Part ${i + 1} of the system needs an '=' sign")
            }
        }
        return Input.System(equations)
    }

    /** Lets people skip closing brackets at the end, like most calculators: "sin(x" means "sin(x)". */
    private fun autoClose(tokens: List<Token>): List<Token> {
        var depth = 0
        for (t in tokens) {
            if (t == Token.LParen) depth++
            if (t == Token.RParen) depth--
            if (depth < 0) throw MathError("Unexpected ')' — check your brackets")
        }
        return tokens + List(depth) { Token.RParen }
    }

    private fun tokenize(src: String, imaginaryUnit: Boolean): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() && c != '\n' -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    val text = src.substring(start, i)
                    if (text.count { it == '.' } > 1 || text == ".") throw MathError("Bad number \"$text\"")
                    out += Token.Num(text)
                }
                src.startsWith("d/d", i) && i + 3 < src.length && src[i + 3].isLetter() -> {
                    out += Token.Deriv(src[i + 3].lowercaseChar()); i += 4
                }
                // dy/dx  ->  y'
                c == 'd' && i + 4 < src.length && src[i + 1].isLetter() && src[i + 2] == '/' && src[i + 3] == 'd' &&
                    src[i + 4].isLetter() && (i + 5 >= src.length || !src[i + 5].isLetter()) -> {
                    out += Token.Var(src[i + 1].lowercaseChar()); out += Token.Prime; i += 5
                }
                src.startsWith("lim", i) && (i + 3 >= src.length || !src[i + 3].isLetter()) -> {
                    out += Token.Lim; i += 3
                }
                c == 'π' -> { out += Token.Const('π'); i++ }
                c.isLetter() && c != 'Σ' -> {
                    val start = i
                    while (i < src.length && src[i].isLetter() && src[i] != 'π' && src[i] != 'Σ') i++
                    splitWord(src.substring(start, i), out, imaginaryUnit)
                }
                c == '√' -> { out += Token.Func("sqrt"); i++ }
                c == '∫' -> { out += Token.Integral; i++ }
                c == 'Σ' || c == '∑' -> { out += Token.Sigma; i++ }
                c == '∞' -> { out += Token.Inf; i++ }
                c == '→' -> { out += Token.Arrow; i++ }
                c == '_' -> { out += Token.Underscore; i++ }
                c == '\'' || c == '′' -> { out += Token.Prime; i++ }
                c == '″' -> { out += Token.Prime; out += Token.Prime; i++ }
                c == '+' -> { out += Token.Op('+'); i++ }
                c == '-' || c == '−' -> { out += Token.Op('-'); i++ }
                c == '*' || c == '×' || c == '·' -> { out += Token.Op('*'); i++ }
                c == '/' || c == '÷' -> { out += Token.Op('/'); i++ }
                c == '^' -> { out += Token.Op('^'); i++ }
                c == '∥' || c == '‖' -> { out += Token.Op('∥'); i++ }
                c == '²' -> { out += Token.Op('^'); out += Token.Num("2"); i++ }
                c == '³' -> { out += Token.Op('^'); out += Token.Num("3"); i++ }
                c == '(' || c == '[' -> { out += Token.LParen; i++ }
                c == ')' || c == ']' -> { out += Token.RParen; i++ }
                c == '<' || c == '>' -> {
                    val orEqual = i + 1 < src.length && src[i + 1] == '='
                    out += Token.Rel(if (c == '<') (if (orEqual) "≤" else "<") else (if (orEqual) "≥" else ">"))
                    i += if (orEqual) 2 else 1
                }
                c == '≤' || c == '≥' -> { out += Token.Rel(c.toString()); i++ }
                c == '=' -> { out += Token.Equals; i++ }
                c == ',' -> { out += Token.Comma; i++ }
                c == ';' || c == '\n' -> { out += Token.Semicolon; i++ }
                else -> throw MathError("Unknown symbol '$c'")
            }
        }
        return out
    }

    /** "xsinx" -> x, sin, x.  "pi" -> π.  "e" -> Euler's number, "i" -> √−1. Other letters are variables. */
    /** Function names are matched in any case (Sin = sin); single letters keep their case (R ≠ r). */
    private fun splitWord(word: String, out: MutableList<Token>, imaginaryUnit: Boolean) {
        val lower = word.lowercase()
        var j = 0
        while (j < word.length) {
            val name = FUNCTION_NAMES.firstOrNull { lower.startsWith(it, j) && (it.length > 1) && !(word.substring(j, j + it.length).any { c -> c.isUpperCase() } && word.substring(j, j + it.length) != it.uppercase() && word[j].isLowerCase()) }
            when {
                name != null -> { out += Token.Func(name); j += name.length }
                lower.startsWith("pi", j) && word.startsWith("pi", j) -> { out += Token.Const('π'); j += 2 }
                word[j] == 'e' -> { out += Token.Const('e'); j++ }
                word[j] == 'i' && imaginaryUnit -> { out += Token.Const('i'); j++ }
                else -> { out += Token.Var(word[j]); j++ }
            }
        }
    }

    private class TokenStream(val tokens: List<Token>, val odeMode: Boolean = false) {
        var pos = 0
        fun peek(offset: Int = 0): Token? = tokens.getOrNull(pos + offset)
        fun next(): Token = tokens.getOrNull(pos++) ?: throw MathError("Expression ends too early")
        fun expect(t: Token, message: String) { if (peek() != t) throw MathError(message); next() }

        fun parseInput(): Input {
            val left = parseExpr()
            if (peek() == null) return Input.Expression(left)
            val rel = peek()
            if (rel is Token.Rel) {
                next()
                if (peek() == null) throw MathError("Nothing on the right side of '${rel.op}'")
                val right = parseExpr()
                val rel2 = peek()
                if (rel2 is Token.Rel) {
                    // 1 < x < 3, 5 ≥ 2x + 1 > −3
                    next()
                    if (peek() == null) throw MathError("Nothing on the right side of '${rel2.op}'")
                    val third = parseExpr()
                    if (peek() is Token.Rel) throw MathError("Use at most two inequality signs, like 1 < x < 3")
                    if (peek() != null) throw unexpected()
                    val up = rel.op == "<" || rel.op == "≤"
                    if (up != (rel2.op == "<" || rel2.op == "≤")) {
                        throw MathError("Both signs must point the same way, like 1 < x < 3 or 3 > x > 1")
                    }
                    return Input.Between(left, rel.op, right, rel2.op, third)
                }
                if (peek() != null) throw unexpected()
                return Input.Inequality(left, rel.op, right)
            }
            if (peek() != Token.Equals) throw unexpected()
            next()
            if (peek() == null) throw MathError("Nothing on the right side of '='")
            val right = parseExpr()
            if (peek() != null) {
                if (peek() is Token.Rel) throw MathError("Use either '=' or an inequality sign, not both")
                throw if (peek() == Token.Equals) MathError("Only one '=' per equation (use ; between equations)") else unexpected()
            }
            return Input.Equation(left, right)
        }

        fun parseExpr(): Expr {
            var e = parseParallel()
            while (true) {
                val t = peek()
                e = when {
                    t is Token.Op && t.symbol == '+' -> { next(); Expr.Add(e, parseParallel()) }
                    t is Token.Op && t.symbol == '-' -> { next(); Expr.Sub(e, parseParallel()) }
                    else -> return e
                }
            }
        }

        /** a ∥ b = ab/(a + b) (resistors in parallel). Binds tighter than +, looser than ×. */
        private fun parseParallel(): Expr {
            var e = parseTerm()
            while (true) {
                val t = peek()
                if (t is Token.Op && t.symbol == '∥') {
                    next()
                    val r = parseTerm()
                    e = Expr.Div(Expr.Mul(e, r), Expr.Add(e, r))
                } else return e
            }
        }

        private fun startsFactor(t: Token?) = t is Token.Var || t is Token.Num || t is Token.Const ||
            t is Token.Func || t is Token.Deriv || t == Token.LParen || t == Token.Integral ||
            t == Token.Lim || t == Token.Sigma || t == Token.Inf

        fun parseTerm(): Expr {
            var e = parseUnary()
            while (true) {
                val t = peek()
                e = when {
                    t is Token.Op && t.symbol == '*' -> { next(); Expr.Mul(e, parseUnary()) }
                    t is Token.Op && t.symbol == '/' -> { next(); Expr.Div(e, parseUnary()) }
                    startsFactor(t) -> Expr.Mul(e, parsePower())   // implicit: 2x, 3(x+1), 2sin x
                    else -> return e
                }
            }
        }

        fun parseUnary(): Expr {
            val t = peek()
            if (t is Token.Op && t.symbol == '-') { next(); return Expr.Neg(parseUnary()) }
            if (t is Token.Op && t.symbol == '+') { next(); return parseUnary() }
            return parsePower()
        }

        fun parsePower(): Expr {
            val base = parsePrimary()
            val t = peek()
            if (t is Token.Op && t.symbol == '^') { next(); return Expr.Pow(base, parseUnary()) }
            return base
        }

        /** Function argument: "(…)" or, without brackets, a single power like "x" or "x^2". */
        private fun parseArgument(): Expr = if (peek() == Token.LParen) parsePrimary() else parsePower()

        fun parsePrimary(): Expr = when (val t = next()) {
            is Token.Num -> Expr.Num(Rational.parse(t.text), t.text)
            is Token.Var -> variable(t.name)
            Token.Inf -> Expr.Inf
            Token.Lim -> parseLimit()
            Token.Sigma -> parseSum()
            Token.Arrow -> throw MathError("'→' goes inside lim( … , x → a)")
            Token.Underscore -> throw MathError("'_' goes after a letter, like a_n")
            Token.Prime -> throw MathError("' goes after a letter, like y'")
            is Token.Const -> Expr.Const(t.name)
            is Token.Func -> {
                if (peek() == null || peek() == Token.RParen) throw MathError("${t.name} needs something after it")
                Expr.Func(t.name, parseArgument())
            }
            is Token.Deriv -> {
                if (peek() == null || peek() == Token.RParen) throw MathError("d/d${t.variable} needs something to differentiate")
                if (peek() == Token.LParen) {
                    next()
                    if (peek() == Token.RParen) throw MathError("Empty brackets")
                    val body = parseExpr()
                    var order = 1
                    if (peek() == Token.Comma) {
                        next()
                        val n = next()
                        order = (n as? Token.Num)?.text?.toIntOrNull()
                            ?: throw MathError("Write higher derivatives as d/dx(f, 2)")
                        if (order < 1 || order > 10) throw MathError("Derivative order must be 1 to 10")
                    }
                    expect(Token.RParen, "Missing ')' after d/d${t.variable}(…")
                    Expr.Derivative(body, t.variable, order)
                } else Expr.Derivative(parsePower(), t.variable)
            }
            Token.Integral -> parseIntegral()
            Token.LParen -> {
                if (peek() == Token.RParen) throw MathError("Empty brackets")
                val inner = parseExpr()
                if (peek() == Token.Comma) throw MathError("Commas are only used inside ∫( … , a, b)")
                expect(Token.RParen, "Missing ')'")
                inner
            }
            Token.RParen -> throw MathError("Unexpected ')'")
            Token.Equals -> throw MathError("Unexpected '='")
            Token.Comma -> throw MathError("Unexpected ','")
            Token.Semicolon -> throw MathError("Unexpected ';'")
            is Token.Rel -> throw MathError("Unexpected '${t.op}'")
            is Token.Op -> throw MathError("Unexpected '${t.symbol}'")
        }

        /** A letter, possibly followed by a subscript (a_n), primes (y'') or, in ODE input, a call (y(0)). */
        private fun variable(name: Char): Expr {
            if (peek() == Token.Underscore) {
                next()
                val index = when (val t = peek()) {
                    Token.LParen -> parsePrimary()
                    is Token.Num -> { next(); Expr.Num(Rational.parse(t.text), t.text) }
                    is Token.Var -> { next(); Expr.Var(t.name) }
                    else -> throw MathError("Write subscripts like a_n, a_1 or a_(n-1)")
                }
                return Expr.Seq(name, index)
            }
            var order = 0
            while (peek() == Token.Prime) { next(); order++ }
            if (odeMode && peek() == Token.LParen && (order > 0 || name == 'y')) {
                next()
                val arg = parseExpr()
                expect(Token.RParen, "Missing ')'")
                // y(0) is "y at 0"; y(1 − y) is still y times (1 − y).
                if (arg.variables().isEmpty()) return Expr.Call(name, order, arg)
                val head: Expr = if (order > 0) Expr.Prime(name, order) else Expr.Var(name)
                return Expr.Mul(head, arg)
            }
            return if (order > 0) Expr.Prime(name, order) else Expr.Var(name)
        }

        /** Index of the ')' that closes the bracket opened just before [pos]. */
        private fun closingIndex(): Int {
            var depth = 1
            var j = pos
            while (j < tokens.size) {
                if (tokens[j] == Token.LParen) depth++
                if (tokens[j] == Token.RParen) { depth--; if (depth == 0) return j }
                j++
            }
            throw MathError("Missing ')'")
        }

        /** lim(f, a)   lim(f, x → a)   lim(f, a+)   lim(f, x → ∞) */
        private fun parseLimit(): Expr {
            expect(Token.LParen, "Write limits as lim(f, a) or lim(f, x → a)")
            val body = parseExpr()
            expect(Token.Comma, "A limit needs a value to approach: lim(f, a)")
            var variable: Char? = null
            val t0 = peek()
            if (t0 is Token.Var && peek(1) == Token.Arrow) { next(); next(); variable = t0.name }
            val close = closingIndex()
            var side = 0
            var end = close
            val last = tokens.getOrNull(close - 1)
            val before = tokens.getOrNull(close - 2)
            if (last is Token.Op && (last.symbol == '+' || last.symbol == '-') && before != null &&
                before != Token.Comma && before != Token.Arrow && !(before is Token.Op)) {
                side = if (last.symbol == '+') 1 else -1
                end = close - 1
            }
            if (end <= pos) throw MathError("A limit needs a value to approach: lim(f, a)")
            val to = TokenStream(tokens.subList(pos, end)).parseWhole()
            pos = close + 1
            return Expr.Limit(body, variable, to, side)
        }

        /** Σ(f, a, b)   Σ(f, k, a, b) */
        private fun parseSum(): Expr {
            expect(Token.LParen, "Write sums as Σ(f, a, b) — e.g. Σ(k², 1, 10)")
            val body = parseExpr()
            val args = mutableListOf<Expr>()
            while (peek() == Token.Comma) { next(); args += parseExpr() }
            expect(Token.RParen, "Missing ')' after the sum")
            return when (args.size) {
                2 -> Expr.Sum(body, null, args[0], args[1])
                3 -> {
                    val index = args[0]
                    // Σ(i², i, 1, 10): here i is the counter, not √−1.
                    if (index is Expr.Const && index.name == 'i') {
                        val b = body.mapNodes { if (it is Expr.Const && it.name == 'i') Expr.Var('i') else null }
                        return Expr.Sum(b, 'i', args[1], args[2])
                    }
                    val v = index as? Expr.Var ?: throw MathError("In Σ(f, k, a, b) the second item must be the letter you sum over")
                    Expr.Sum(body, v.name, args[1], args[2])
                }
                else -> throw MathError("Write sums as Σ(f, a, b) or Σ(f, k, a, b)")
            }
        }

        /** Parses all tokens as one expression. */
        fun parseWhole(): Expr {
            val e = parseExpr()
            if (peek() != null) throw unexpected()
            return e
        }

        /** ∫(f)   ∫(f)dx   ∫(f, a, b)   ∫(f, a, b)dx */
        private fun parseIntegral(): Expr {
            expect(Token.LParen, "Write integrals as ∫(…) — e.g. ∫(x^2) or ∫(x^2, 0, 3)")
            if (peek() == Token.RParen) throw MathError("∫ needs something to integrate")
            val body = parseExpr()
            var lower: Expr? = null
            var upper: Expr? = null
            if (peek() == Token.Comma) {
                next(); lower = parseExpr()
                expect(Token.Comma, "A definite integral needs two limits: ∫(f, a, b)")
                upper = parseExpr()
            }
            expect(Token.RParen, "Missing ')' after the integral")
            var variable: Char? = null
            val d = peek()
            val v = peek(1)
            if (d is Token.Var && d.name == 'd' && v is Token.Var) { next(); next(); variable = v.name }
            return Expr.Integral(body, variable, lower, upper)
        }

        fun unexpected(): MathError = when (val t = peek()) {
            Token.RParen -> MathError("Unexpected ')' — check your brackets")
            Token.Comma -> MathError("Unexpected ',' — commas only go inside ∫( … , a, b)")
            else -> MathError("Couldn't read the input near '$t'")
        }
    }
}
