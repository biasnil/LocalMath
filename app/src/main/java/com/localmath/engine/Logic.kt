package com.localmath.engine

/** A Karnaugh map for the solution page: 0/1 cells in Gray-code order, with the groups of the minimal form. */
data class KarnaughMap(
    val rowVars: String,
    val colVars: String,
    val rowLabels: List<String>,
    val colLabels: List<String>,
    val cells: List<List<Int>>,
    val groups: List<List<Pair<Int, Int>>>,
    val groupNames: List<String>
)

/**
 * Propositional logic and Boolean algebra: truth tables, tautology / contradiction, DNF / CNF, minimal forms
 * (Quine–McCluskey), Karnaugh maps, equivalence (≡) and arguments (⊨).
 */
object Logic {

    private enum class Op(val sym: String, val tex: String) {
        AND("∧", "\\land"), OR("∨", "\\lor"), IMP("→", "\\to"), IFF("↔", "\\leftrightarrow"), EQV("≡", "\\equiv"),
        XOR("⊕", "\\oplus"), NAND("↑", "\\uparrow"), NOR("↓", "\\downarrow")
    }

    private sealed class F {
        data class V(val c: Char) : F()
        data class K(val b: Boolean) : F()
        data class Not(val a: F) : F()
        data class B(val op: Op, val a: F, val b: F) : F()
    }

    // ================= detection =================

    private val WORDS = mapOf("and" to "∧", "or" to "∨", "not" to "¬", "xor" to "⊕", "implies" to "→", "iff" to "↔",
        "nand" to "↑", "nor" to "↓", "equiv" to "≡", "entails" to "⊨", "true" to "⊤", "false" to "⊥")

    fun accepts(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.contains("lim", ignoreCase = true)) return false
        if (t.any { it in "¬∧∨↔≡⊕⊨⊤⊥↑↓" }) return true
        if (t.contains('→')) return true
        val c = t.replace(Regex("\\s+"), "")
        if (Regex("^(m|M|table)\\(.*\\)$").matches(c)) return true
        if (listOf("->", "<->", "<=>", "=>", "&&", "||", "|=").any { c.contains(it) }) return true
        val words = Regex("[A-Za-z]+").findAll(t).map { it.value }.toList()
        val mathWords = setOf("sin", "cos", "tan", "ln", "log", "exp", "abs", "lim", "det", "dot")
        if (words.any { it.lowercase() in WORDS } && words.none { it.lowercase() in mathWords } &&
            words.all { it.lowercase() in WORDS || it.length <= 3 } && !t.contains('=') && t.none { it.isDigit() && it != '0' && it != '1' }) return true
        // Boolean algebra with primes: A'B + AB'
        // Boolean algebra with primes (A'B + AB') or editor overlines (conj(A)B + A conj(B)), capital letters only.
        if ((t.contains('\'') || t.contains("conj(")) && Regex("^[A-Z'+·*() 01]+$").matches(t.replace("conj(", "("))) return true
        return false
    }

    /** LaTeX for the editor, so logic problems load back from History. */
    fun toLatex(text: String): String {
        var t = text
        for ((w, s) in WORDS) t = t.replace(Regex("\\b$w\\b", RegexOption.IGNORE_CASE), s)
        val map = linkedMapOf("<->" to "↔", "<=>" to "↔", "->" to "→", "=>" to "→", "&&" to "∧", "||" to "∨", "|=" to "⊨",
            "¬" to "\\neg ", "∧" to "\\land ", "∨" to "\\lor ", "→" to "\\to ", "↔" to "\\leftrightarrow ", "≡" to "\\equiv ",
            "⊕" to "\\oplus ", "⊨" to "\\models ", "⊤" to "\\top ", "⊥" to "\\bot ", "↑" to "\\uparrow ", "↓" to "\\downarrow ",
            "table(" to "\\operatorname{table}(")
        for ((a, b) in map) t = t.replace(a, b)
        return t
    }

    // ================= tokens and parsing =================

    private sealed class T {
        data class Var(val c: Char) : T()
        data class Const(val b: Boolean) : T()
        data class Sym(val s: String) : T()
    }

    private var boolStyle = false

    private fun tokenize(src: String): List<T> {
        val out = mutableListOf<T>()
        var i = 0
        fun sym(s: String) { out += T.Sym(s) }
        while (i < src.length) {
            val c = src[i]
            fun at(s: String) = src.startsWith(s, i)
            when {
                c.isWhitespace() -> i++
                at("<->") || at("<=>") -> { sym("↔"); i += 3 }
                at("->") || at("=>") -> { sym("→"); i += 2 }
                at("&&") -> { sym("∧"); i += 2 }
                at("||") -> { sym("∨"); i += 2 }
                at("|=") -> { sym("⊨"); i += 2 }
                at("==") -> { sym("≡"); i += 2 }
                c.isLetter() -> {
                    val start = i
                    while (i < src.length && src[i].isLetter()) i++
                    val w = src.substring(start, i)
                    val op = WORDS[w.lowercase()]
                    when {
                        op == "⊤" -> out += T.Const(true)
                        op == "⊥" -> out += T.Const(false)
                        op != null -> sym(op)
                        else -> {
                            if (w.length > 1) boolStyle = true   // AB means A·B
                            for (ch in w) out += T.Var(ch)
                        }
                    }
                }
                c == '1' || c == '⊤' -> { if (c == '1') boolStyle = true; out += T.Const(true); i++ }
                c == '0' || c == '⊥' -> { if (c == '0') boolStyle = true; out += T.Const(false); i++ }
                c == '¬' || c == '~' || c == '!' -> { sym("¬"); i++ }
                c == '∧' || c == '&' -> { sym("∧"); i++ }
                c == '·' || c == '*' || c == '⋅' || c == '×' -> { boolStyle = true; sym("∧"); i++ }
                c == '∨' || c == '|' -> { sym("∨"); i++ }
                c == '+' -> { boolStyle = true; sym("∨"); i++ }
                c == '→' || c == '⇒' -> { sym("→"); i++ }
                c == '↔' || c == '⇔' -> { sym("↔"); i++ }
                c == '≡' || c == '=' -> { sym("≡"); i++ }
                c == '⊕' -> { sym("⊕"); i++ }
                c == '↑' -> { sym("↑"); i++ }
                c == '↓' -> { sym("↓"); i++ }
                c == '⊨' || c == '⊢' -> { sym("⊨"); i++ }
                c == '\'' || c == '′' -> { boolStyle = true; sym("'"); i++ }
                c == '(' || c == '[' -> { sym("("); i++ }
                c == ')' || c == ']' -> { sym(")"); i++ }
                c == ',' || c == ';' -> { sym(","); i++ }
                else -> throw MathError("Logic doesn't use '$c'. Use ¬ ∧ ∨ → ↔ ≡ ⊕ ⊨, or NOT AND OR IMPLIES IFF")
            }
        }
        return out
    }

    private class P(val t: List<T>) {
        var pos = 0
        fun peek() = t.getOrNull(pos)
        fun isSym(s: String) = (peek() as? T.Sym)?.s == s
        fun formula(): F = equiv()
        private fun equiv(): F { var a = iff(); while (isSym("≡")) { pos++; a = F.B(Op.EQV, a, iff()) }; return a }
        private fun iff(): F { var a = imp(); while (isSym("↔")) { pos++; a = F.B(Op.IFF, a, imp()) }; return a }
        private fun imp(): F { val a = or(); if (isSym("→")) { pos++; return F.B(Op.IMP, a, imp()) }; return a }
        private fun or(): F {
            var a = and()
            while (true) a = when {
                isSym("∨") -> { pos++; F.B(Op.OR, a, and()) }
                isSym("⊕") -> { pos++; F.B(Op.XOR, a, and()) }
                isSym("↓") -> { pos++; F.B(Op.NOR, a, and()) }
                else -> return a
            }
        }
        private fun startsAtom() = peek().let { it is T.Var || it is T.Const || (it is T.Sym && (it.s == "(" || it.s == "¬")) }
        private fun and(): F {
            var a = unary()
            while (true) a = when {
                isSym("∧") -> { pos++; F.B(Op.AND, a, unary()) }
                isSym("↑") -> { pos++; F.B(Op.NAND, a, unary()) }
                startsAtom() -> { boolStyle = true; F.B(Op.AND, a, unary()) }
                else -> return a
            }
        }
        private fun unary(): F = if (isSym("¬")) { pos++; F.Not(unary()) } else postfix()
        private fun postfix(): F { var a = atom(); while (isSym("'")) { pos++; a = F.Not(a) }; return a }
        private fun atom(): F = when (val tok = peek()) {
            null -> throw MathError("The formula ends too early")
            is T.Var -> { pos++; F.V(tok.c) }
            is T.Const -> { pos++; F.K(tok.b) }
            is T.Sym -> when (tok.s) {
                "(" -> { pos++; val f = formula(); if (!isSym(")")) throw MathError("Missing ')'"); pos++; f }
                else -> throw MathError("Unexpected '${tok.s}'")
            }
        }
    }

    // ================= evaluation and printing =================

    private fun vars(f: F, out: MutableSet<Char> = sortedSetOf()): MutableSet<Char> {
        when (f) { is F.V -> out += f.c; is F.Not -> vars(f.a, out); is F.B -> { vars(f.a, out); vars(f.b, out) }; else -> {} }
        return out
    }

    private fun eval(f: F, env: Map<Char, Boolean>): Boolean = when (f) {
        is F.V -> env.getValue(f.c)
        is F.K -> f.b
        is F.Not -> !eval(f.a, env)
        is F.B -> {
            val a = eval(f.a, env); val b = eval(f.b, env)
            when (f.op) {
                Op.AND -> a && b; Op.OR -> a || b; Op.IMP -> !a || b; Op.IFF, Op.EQV -> a == b
                Op.XOR -> a != b; Op.NAND -> !(a && b); Op.NOR -> !(a || b)
            }
        }
    }

    private fun prec(f: F) = when (f) {
        is F.B -> when (f.op) { Op.EQV -> 1; Op.IFF -> 2; Op.IMP -> 3; Op.OR, Op.XOR, Op.NOR -> 4; Op.AND, Op.NAND -> 5 }
        is F.Not -> 6
        else -> 7
    }

    private fun tex(f: F): String = when (f) {
        is F.V -> f.c.toString()
        is F.K -> if (boolStyle) (if (f.b) "1" else "0") else (if (f.b) "\\top" else "\\bot")
        is F.Not -> if (boolStyle) "\\overline{${tex(f.a)}}" else "\\neg " + wrap(f.a, 6)
        is F.B -> {
            val p = prec(f)
            if (boolStyle) {
                val left = wrap(f.a, p)
                val right = wrap(f.b, p + 1)
                when (f.op) {
                    Op.AND -> "$left$right"
                    Op.OR -> "$left + $right"
                    else -> "${child(f.a, f.op)} ${f.op.tex} ${child(f.b, f.op)}"
                }
            } else "${child(f.a, f.op)} ${f.op.tex} ${child(f.b, f.op)}"
        }
    }

    /** Logic style: a different operator inside always gets brackets, so nobody has to remember precedence. */
    private fun child(c: F, parent: Op): String =
        if (c is F.B && !(c.op == parent && (parent == Op.AND || parent == Op.OR))) "\\left(${tex(c)}\\right)" else tex(c)

    private fun wrap(f: F, min: Int) = if (prec(f) < min) "\\left(${tex(f)}\\right)" else tex(f)

    private fun sub(f: F, out: MutableList<F> = mutableListOf()): MutableList<F> {
        when (f) { is F.Not -> sub(f.a, out); is F.B -> { sub(f.a, out); sub(f.b, out) }; else -> {} }
        if ((f is F.Not || f is F.B) && f !in out) out += f
        return out
    }

    private fun tf(b: Boolean) = if (boolStyle) (if (b) "1" else "0") else (if (b) "T" else "F")

    // ================= solving =================

    fun solve(text: String): Solution {
        boolStyle = false
        val c = text.replace(Regex("\\s+"), "")
        Regex("^(m|M|table)\\((.*)\\)$").matchEntire(c)?.let { return fromTable(it.groupValues[1], it.groupValues[2]) }
        // The editor writes an overline (Boolean NOT) as conj( ).
        val tokens = tokenize(text.replace("conj(", "¬("))
        // Arguments:  premises ⊨ conclusion
        val ent = tokens.indexOfFirst { it is T.Sym && it.s == "⊨" }
        if (ent >= 0) {
            val prem = split(tokens.subList(0, ent)).map { parse(it) }
            val concl = parse(tokens.subList(ent + 1, tokens.size))
            return entails(prem, concl)
        }
        val parts = split(tokens)
        if (parts.size > 1) throw MathError("Separate premises with commas only before ⊨, like p → q, p ⊨ q")
        val f = parse(parts[0])
        if (f is F.B && f.op == Op.EQV) return equivalence(f.a, f.b)
        return analyse(f)
    }

    private fun split(t: List<T>): List<List<T>> {
        val out = mutableListOf(mutableListOf<T>())
        var depth = 0
        for (x in t) {
            if (x is T.Sym && x.s == "(") depth++
            if (x is T.Sym && x.s == ")") depth--
            if (x is T.Sym && x.s == "," && depth == 0) out += mutableListOf<T>() else out.last() += x
        }
        return out.filter { it.isNotEmpty() }
    }

    private fun parse(t: List<T>): F {
        if (t.isEmpty()) throw MathError("Something is missing next to ⊨ or a comma")
        val p = P(t)
        val f = p.formula()
        if (p.pos < t.size) throw MathError("Couldn't read the formula near '${(t[p.pos] as? T.Sym)?.s ?: "a letter"}' — check the brackets")
        return f
    }

    private fun checkVars(v: Set<Char>) { if (v.size > 6) throw MathError("Up to 6 letters (64 rows) — this has ${v.size}") }

    private fun row(i: Int, n: Int, vs: List<Char>) = vs.withIndex().associate { (j, c) -> c to ((i shr (n - 1 - j)) and 1 == 1) }

    /** Rows in display order: T first for logic, 0 first for Boolean algebra. */
    private fun order(n: Int) = if (boolStyle) (0 until (1 shl n)).toList() else ((1 shl n) - 1 downTo 0).toList()

    private fun table(vs: List<Char>, cols: List<Pair<String, (Map<Char, Boolean>) -> Boolean>>, bold: Set<Int>, mark: (Int) -> Boolean = { false }): String {
        val n = vs.size
        val spec = "c".repeat(n) + "|" + "c".repeat(cols.size)
        val head = (vs.map { it.toString() } + cols.map { it.first }).joinToString(" & ")
        val body = order(n).joinToString(" \\\\ ") { i ->
            val env = row(i, n, vs)
            val cells = vs.map { tf(env.getValue(it)) } + cols.mapIndexed { k, (_, fn) -> tf(fn(env)).let { if (k in bold) "\\mathbf{$it}" else it } }
            val line = cells.joinToString(" & ")
            if (mark(i)) cells.joinToString(" & ") { "\\textcolor{#e5483f}{$it}" } else line
        }
        return "\\begin{array}{$spec}$head \\\\ \\hline $body\\end{array}"
    }

    private fun analyse(f: F): Solution {
        val vs = vars(f).toList()
        checkVars(vs.toSet())
        val n = vs.size
        val steps = mutableListOf<Step>()
        steps += Step("The formula", tex(f), if (n == 0) "No letters, only constants." else "Letters: ${vs.joinToString(", ")} → ${1 shl n} rows.")
        val subs = sub(f).let { if (it.size > 7) listOf(f) else it }
        steps += Step("Truth table", table(vs, subs.map { s -> tex(s) to { e: Map<Char, Boolean> -> eval(s, e) } }, setOf(subs.lastIndex)),
            "Work out each column from the ones before it; the last column is the whole formula." +
                if (!boolStyle) " (T = true, F = false.)" else "")
        val minterms = (0 until (1 shl n)).filter { eval(f, row(it, n, vs)) }
        return summarise(steps, vs, minterms, tex(f), "Truth table")
    }

    /** Properties, normal forms, K-map, minimal forms. */
    private fun summarise(steps: MutableList<Step>, vs: List<Char>, minterms: List<Int>, fTex: String, kind: String): Solution {
        val n = vs.size
        val total = 1 shl n
        val k = minterms.size
        val prop = when (k) {
            total -> "\\text{Tautology}"
            0 -> "\\text{Contradiction}"
            else -> "\\text{Contingent}"
        }
        steps += Step("Properties", when (k) {
            total -> "\\text{Tautology: true in every row}"
            0 -> "\\text{Contradiction: false in every row (unsatisfiable)}"
            else -> "\\text{Contingent: true in $k of $total rows}"
        }, when (k) {
            total -> "It's always true, whatever the letters are."
            0 -> "No choice of true/false makes it true."
            else -> "It's satisfiable (some row is true) but not a tautology (some row is false)."
        })
        if (n == 0) return Solution(kind, steps, "$fTex \\equiv ${if (k == total) (if (boolStyle) "1" else "\\top") else (if (boolStyle) "0" else "\\bot")}", prop)
        val maxterms = (0 until total).filter { it !in minterms }
        steps += Step("Minterms and maxterms", "\\sum m(${minterms.joinToString(", ").ifEmpty { "\\;" }}),\\quad \\prod M(${maxterms.joinToString(", ").ifEmpty { "\\;" }})",
            "Row numbers read the letters ${vs.joinToString("")} as a binary number (true = 1). Minterms are the true rows, maxterms the false ones.")
        if (k in 1..8) steps += Step("Disjunctive normal form (DNF)", sop(minterms.map { it to 0 }, vs),
            "One term for each true row, joined with ${if (boolStyle) "+" else "∨"}.")
        if (maxterms.size in 1..8) steps += Step("Conjunctive normal form (CNF)", pos(maxterms.map { it to 0 }, vs),
            "One bracket for each false row: it is false exactly on that row.")

        val sopPIs = minimise(minterms, n, steps.takeIf { k in 1 until total }, vs, "sum of products")
        val posPIs = minimise(maxterms, n, null, vs, "product of sums")
        val minSop = sop(sopPIs, vs)
        val minPos = pos(posPIs, vs)
        val kmap = if (n in 2..4) kmap(vs, minterms, sopPIs) else null
        if (kmap != null && k in 1 until total) steps += Step("Karnaugh map", minSop,
            "Each ring on the map is one term: a block of 1, 2, 4 or 8 neighbouring 1s (the map wraps around its edges). Bigger rings mean shorter terms.")
        steps += Step("Minimal sum of products", minSop)
        steps += Step("Minimal product of sums", minPos, "The same idea on the 0s (false rows) gives the shortest AND of ORs.")
        val approx = "$prop" + (if (k in 1 until total) "\\ \\text{— true in $k of $total rows}" else "") + ",\\quad \\text{POS: } $minPos"
        return Solution(kind, steps, if (fTex == minSop) "$fTex \\text{ (already minimal)}" else "$fTex \\equiv $minSop", approx, kmap = kmap)
    }

    // ================= Quine–McCluskey =================

    /** Returns chosen prime implicants as (value, mask); mask bits are "don't care". */
    private fun minimise(terms: List<Int>, n: Int, steps: MutableList<Step>?, vs: List<Char>, what: String): List<Pair<Int, Int>> {
        val total = 1 shl n
        if (terms.isEmpty()) return emptyList()
        if (terms.size == total) return listOf(0 to total - 1)
        var current = terms.map { it to 0 }.toSet()
        val primes = mutableSetOf<Pair<Int, Int>>()
        while (current.isNotEmpty()) {
            val used = mutableSetOf<Pair<Int, Int>>()
            val next = mutableSetOf<Pair<Int, Int>>()
            val list = current.toList()
            for (i in list.indices) for (j in i + 1 until list.size) {
                val (a, ma) = list[i]; val (b, mb) = list[j]
                if (ma != mb) continue
                val diff = a xor b
                if (Integer.bitCount(diff) == 1) { next += (a and b) to (ma or diff); used += list[i]; used += list[j] }
            }
            primes += current - used
            current = next
        }
        fun covers(p: Pair<Int, Int>, m: Int) = (m and p.second.inv()) == (p.first and p.second.inv())
        val pis = primes.toList()
        val essential = terms.mapNotNull { m -> pis.filter { covers(it, m) }.singleOrNull() }.toSet()
        var left = terms.filter { m -> essential.none { covers(it, m) } }
        val chosen = essential.toMutableList()
        if (left.isNotEmpty()) {
            val cands = pis.filter { it !in essential && left.any { m -> covers(it, m) } }
            var best: List<Pair<Int, Int>>? = null
            if (cands.size <= 16) {
                fun lits(s: List<Pair<Int, Int>>) = s.sumOf { n - Integer.bitCount(it.second) }
                for (mask in 1 until (1 shl cands.size)) {
                    val pick = cands.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
                    if (left.all { m -> pick.any { covers(it, m) } }) {
                        val b = best
                        if (b == null || pick.size < b.size || (pick.size == b.size && lits(pick) < lits(b))) best = pick
                    }
                }
            } else {
                val g = mutableListOf<Pair<Int, Int>>()
                while (left.isNotEmpty()) {
                    val p = cands.maxByOrNull { c -> left.count { covers(c, it) } }!!
                    g += p; left = left.filter { !covers(p, it) }
                }
                best = g
            }
            chosen += best!!
        }
        steps?.add(Step("Prime implicants (Quine–McCluskey)", pis.joinToString(",\\quad ") { term(it, vs, true) },
            "Merge true rows that differ in one letter, again and again; what can't be merged further are the prime implicants. " +
                (if (essential.isNotEmpty()) "Essential (the only one covering some row): \\(" + essential.joinToString(",\\ ") { term(it, vs, true) } + "\\)." else "")))
        return chosen.sortedWith(compareBy({ Integer.bitCount(it.second) * -1 }, { it.first }))
    }

    /** A product term (for SOP) or, with [product] = false, a sum term (for POS) of an implicant. */
    private fun term(p: Pair<Int, Int>, vs: List<Char>, product: Boolean): String {
        val n = vs.size
        val lits = vs.indices.filter { (p.second shr (n - 1 - it)) and 1 == 0 }.map { j ->
            val one = (p.first shr (n - 1 - j)) and 1 == 1
            val positive = if (product) one else !one
            if (positive) vs[j].toString() else if (boolStyle) "\\overline{${vs[j]}}" else "\\neg ${vs[j]}"
        }
        if (lits.isEmpty()) return if (product) (if (boolStyle) "1" else "\\top") else (if (boolStyle) "0" else "\\bot")
        return if (product) (if (boolStyle) lits.joinToString("") else lits.joinToString(" \\land "))
            else lits.joinToString(if (boolStyle) " + " else " \\lor ")
    }

    private fun sop(ps: List<Pair<Int, Int>>, vs: List<Char>): String {
        if (ps.isEmpty()) return if (boolStyle) "0" else "\\bot"
        val t = ps.map { term(it, vs, true) }
        return if (t.size == 1) t[0] else t.joinToString(if (boolStyle) " + " else " \\lor ") { if (!boolStyle && it.contains("\\land")) "($it)" else it }
    }

    private fun pos(ps: List<Pair<Int, Int>>, vs: List<Char>): String {
        if (ps.isEmpty()) return if (boolStyle) "1" else "\\top"
        val t = ps.map { term(it, vs, false) }
        return if (t.size == 1) t[0] else t.joinToString(if (boolStyle) "" else " \\land ") {
            if (it.contains("+") || it.contains("\\lor")) "($it)" else it
        }
    }

    // ================= Karnaugh map =================

    private val GRAY = listOf(0, 1, 3, 2)

    private fun kmap(vs: List<Char>, minterms: List<Int>, groups: List<Pair<Int, Int>>): KarnaughMap {
        val n = vs.size
        val rb = n / 2                      // row bits: 1 for 2–3 letters, 2 for 4
        val cb = n - rb
        val rowCodes = if (rb == 1) listOf(0, 1) else GRAY
        val colCodes = if (cb == 1) listOf(0, 1) else GRAY
        fun label(code: Int, bits: Int) = (bits - 1 downTo 0).joinToString("") { ((code shr it) and 1).toString() }
        fun pos(m: Int): Pair<Int, Int> = rowCodes.indexOf(m shr cb) to colCodes.indexOf(m and ((1 shl cb) - 1))
        val cells = rowCodes.map { r -> colCodes.map { c -> if (((r shl cb) or c) in minterms) 1 else 0 } }
        val gs = groups.map { g -> (0 until (1 shl n)).filter { (it and g.second.inv()) == (g.first and g.second.inv()) }.map { pos(it) } }
        return KarnaughMap(vs.take(rb).joinToString(""), vs.drop(rb).joinToString(""),
            rowCodes.map { label(it, rb) }, colCodes.map { label(it, cb) }, cells, gs,
            groups.map { term(it, vs, true).replace("\\overline{", "").replace("}", "'").replace("\\neg ", "¬")
                .replace(" \\land ", " ∧ ").replace("\\top", "⊤").replace("\\bot", "⊥") })
    }

    // ================= equivalence and arguments =================

    private fun equivalence(a: F, b: F): Solution {
        val vs = (vars(a) + vars(b)).toSortedSet().toList()
        checkVars(vs.toSet())
        val n = vs.size
        val steps = mutableListOf<Step>()
        steps += Step("Compare the two formulas row by row", "${tex(a)} \\;\\overset{?}{\\equiv}\\; ${tex(b)}",
            "Two formulas are equivalent when they have the same truth value in every row.")
        val diff = (0 until (1 shl n)).filter { eval(a, row(it, n, vs)) != eval(b, row(it, n, vs)) }
        steps += Step("Truth table", table(vs, listOf(tex(a) to { e: Map<Char, Boolean> -> eval(a, e) }, tex(b) to { e: Map<Char, Boolean> -> eval(b, e) }),
            setOf(0, 1)) { it in diff }, if (diff.isEmpty()) "The two columns match in every row." else "Rows in red differ.")
        if (diff.isEmpty()) return Solution("Logical equivalence", steps, "${tex(a)} \\equiv ${tex(b)}", "\\text{Equivalent (same truth table)}")
        val r = row(diff.first(), n, vs)
        val where = vs.joinToString(",\\ ") { "$it = ${tf(r.getValue(it))}" }
        steps += Step("A row where they differ", where, "Here the left side is ${tf(eval(a, r))} but the right side is ${tf(eval(b, r))}.")
        return Solution("Logical equivalence", steps, "${tex(a)} \\not\\equiv ${tex(b)}", "\\text{Not equivalent: they differ when } $where")
    }

    private fun entails(prem: List<F>, concl: F): Solution {
        val vs = (prem.flatMap { vars(it) } + vars(concl)).toSortedSet().toList()
        checkVars(vs.toSet())
        val n = vs.size
        val steps = mutableListOf<Step>()
        val arg = (if (prem.isEmpty()) "" else prem.joinToString(",\\ ") { tex(it) } + " ") + "\\models " + tex(concl)
        steps += Step("The argument", arg, "It is valid if the conclusion is true in every row where all the premises are true.")
        val cols = prem.map { p -> tex(p) to { e: Map<Char, Boolean> -> eval(p, e) } } + (tex(concl) to { e: Map<Char, Boolean> -> eval(concl, e) })
        val bad = (0 until (1 shl n)).filter { i -> val e = row(i, n, vs); prem.all { eval(it, e) } && !eval(concl, e) }
        val allTrue = (0 until (1 shl n)).count { i -> val e = row(i, n, vs); prem.all { eval(it, e) } }
        steps += Step("Truth table", table(vs, cols, setOf(cols.lastIndex)) { it in bad },
            if (bad.isEmpty()) "Check each row where every premise is ${tf(true)}: the conclusion is ${tf(true)} there too." else "Rows in red: premises all true, conclusion false.")
        if (bad.isEmpty()) {
            val note = if (allTrue == 0 && prem.isNotEmpty()) "The premises can never all be true together, so the argument is valid automatically (vacuously)."
                else if (prem.isEmpty()) "No premises: the conclusion is a tautology." else "No counterexample exists."
            steps += Step("Conclusion", "\\text{Valid}", note)
            return Solution("Argument (entailment)", steps, "$arg \\quad \\text{(valid)}", if (prem.isEmpty()) "\\text{The conclusion is a tautology}" else null)
        }
        val r = row(bad.first(), n, vs)
        val where = vs.joinToString(",\\ ") { "$it = ${tf(r.getValue(it))}" }
        steps += Step("Counterexample", where, "Every premise is true here but the conclusion is false, so the conclusion doesn't follow.")
        return Solution("Argument (entailment)", steps, "${arg.replace("\\models", "\\not\\models")} \\quad \\text{(not valid)}", "\\text{Counterexample: } $where")
    }

    // ================= truth table → expression =================

    private fun fromTable(kind: String, inner: String): Solution {
        boolStyle = true
        val (valuesPart, namesPart) = inner.split(';', limit = 2).let { it[0] to it.getOrNull(1) }
        val given = namesPart?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map {
            if (it.length != 1 || !it[0].isLetter()) throw MathError("Letters after ; must be single letters, like m(1, 3; A, B)")
            it[0]
        }
        val steps = mutableListOf<Step>()
        val minterms: List<Int>
        val n: Int
        if (kind == "table") {
            val outs = valuesPart.replace(",", "").map {
                when (it) { '0', 'F', 'f' -> 0; '1', 'T', 't' -> 1; else -> throw MathError("table( ) takes 0s and 1s, like table(0, 1, 1, 0)") }
            }
            n = Integer.numberOfTrailingZeros(outs.size)
            if (outs.size < 2 || outs.size != 1 shl n || n > 6) throw MathError("table( ) needs 2, 4, 8, 16, 32 or 64 outputs (one per row, starting from all 0s)")
            minterms = outs.indices.filter { outs[it] == 1 }
        } else {
            val nums = valuesPart.split(',').filter { it.isNotBlank() }.map {
                it.trim().toIntOrNull()?.takeIf { v -> v >= 0 } ?: throw MathError("$kind( ) takes row numbers like $kind(1, 3, 5)")
            }
            val need = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, nums.maxOrNull() ?: 1)))
            n = given?.size ?: need
            if (n > 6) throw MathError("Up to 6 letters (row numbers 0 to 63)")
            if (nums.any { it >= 1 shl n }) throw MathError("With $n letters the row numbers go up to ${(1 shl n) - 1}")
            minterms = if (kind == "m") nums.distinct().sorted() else (0 until (1 shl n)).filter { it !in nums }
        }
        val vs = given ?: ('A' until 'A' + n).toList()
        if (vs.size != n) throw MathError("Give $n letters after ;")
        steps += Step("The truth table", table(vs, listOf("f" to { e: Map<Char, Boolean> ->
            val i = vs.foldIndexed(0) { j, acc, c -> acc or ((if (e.getValue(c)) 1 else 0) shl (n - 1 - j)) }; i in minterms }), setOf(0)),
            if (kind == "M") "Maxterms (the 0 rows) were given; every other row is 1." else "Rows are numbered from 0 (all letters 0) upwards.")
        return summarise(steps, vs, minterms, "f", "Truth table to expression")
    }
}
