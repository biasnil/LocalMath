package com.localmath.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a key does when pressed. */
sealed class KeyAction {
    /** [text] is typed in plain-text mode; [latex] is inserted in the tap-to-edit editor (#0/#? are boxes). */
    data class Insert(val text: String, val latex: String? = null) : KeyAction()
    object Backspace : KeyAction()
    object Clear : KeyAction()
    object Left : KeyAction()
    object Right : KeyAction()
    object Solve : KeyAction()
    /** A tap-to-edit editor command (MathLive, e.g. "addRowAfter"); [text] is typed instead in plain-text mode. */
    data class Command(val name: String, val text: String) : KeyAction()
    /** 0 = numbers, 1 = functions, 2 = calculus (lim, Σ, aₙ, y′), 3 = engineering, 4 = vectors and matrices, 5 = numbers & statistics, 6 = logic. */
    data class GoToPage(val page: Int) : KeyAction()
}

private enum class KeyStyle { Digit, Operator, Function, Action, Primary }

private data class KeySpec(
    val label: String,
    val action: KeyAction,
    val style: KeyStyle,
    val span: Int = 1,
    val longPress: KeyAction? = null
)

private fun digit(d: String) = KeySpec(d, KeyAction.Insert(d), KeyStyle.Digit)
private fun op(label: String, insert: String = label, latex: String? = null) = KeySpec(label, KeyAction.Insert(insert, latex), KeyStyle.Operator)
private fun fn(label: String, insert: String, latex: String? = null) = KeySpec(label, KeyAction.Insert(insert, latex), KeyStyle.Function)
private fun func(label: String, name: String) = fn(label, "$name(", "\\$name\\left(#0\\right)")
private fun prefix(label: String, power: Int, tex: String = label) =
    fn(label, "×10^($power)", "\\mathrm{$tex}")

private val BACKSPACE = KeySpec("⌫", KeyAction.Backspace, KeyStyle.Action, longPress = KeyAction.Clear)
private val FRACTION = op("÷", "÷", "\\frac{#@}{#?}")
// Plain "·": between two vectors it is the dot product (× is the cross product, on the [ ] page).
private val TIMES = op("×", "·", "\\cdot")
private val MINUS = op("−", "−", "-")
private val POWER = fn("□ⁿ", "^", "#@^{#?}")

// Keys insert display symbols (×, ÷, −, ², √, ∫); the parser understands them directly.
private val BASIC = listOf(
    listOf(fn("x", "x"), fn("y", "y"), fn("z", "z"), fn("□²", "²", "#@^{2}"), BACKSPACE),
    listOf(digit("7"), digit("8"), digit("9"), FRACTION, POWER),
    listOf(digit("4"), digit("5"), digit("6"), TIMES, op("(")),
    listOf(digit("1"), digit("2"), digit("3"), MINUS, op(")")),
    listOf(digit("0"), digit("."), op("="), op("+"), op(";")),
)

private val INTEGRAL = KeySpec("∫", KeyAction.Insert("∫(", "\\int #0\\,dx"), KeyStyle.Function,
    longPress = KeyAction.Insert("∫(", "\\int_{#?}^{#?}#?\\,dx"))

private val FUNCTIONS = listOf(
    listOf(func("sin", "sin"), func("cos", "cos"), func("tan", "tan"), func("ln", "ln"), BACKSPACE),
    listOf(func("sin⁻¹", "arcsin"), func("cos⁻¹", "arccos"), func("tan⁻¹", "arctan"), func("log", "log"), POWER),
    listOf(fn("√", "√(", "\\sqrt{#0}"), fn("eˣ", "e^(", "e^{#0}"), fn("e", "e"), fn("π", "π", "\\pi"), op("(")),
    listOf(fn("d/dx", "d/dx(", "\\frac{d}{dx}\\left(#0\\right)"), INTEGRAL, op(","), fn("|x|", "abs(", "\\left|#0\\right|"), op(")")),
    listOf(fn("x", "x"), fn("i", "i"), op("<"), op(">"), op("≤", "≤", "\\le"), op("≥", "≥", "\\ge")),
)

private val CALCULUS = listOf(
    listOf(fn("lim", "lim(", "\\lim_{x\\to#?}#0"), op("→", "→", "\\to"), fn("∞", "∞", "\\infty"), fn("Σ", "Σ(", "\\sum_{k=#?}^{#?}#0"), BACKSPACE),
    listOf(func("sinh", "sinh"), func("cosh", "cosh"), func("tanh", "tanh"), fn("d/dy", "d/dy(", "\\frac{d}{dy}\\left(#0\\right)"), op(",")),
    listOf(fn("aₙ", "a_n", "a_{n}"), fn("aₙ₋₁", "a_(n−1)", "a_{n-1}"), fn("n", "n"), fn("k", "k"), op("(")),
    listOf(fn("y", "y"), fn("y′", "y′", "y^{\\prime}"), fn("y″", "y″", "y^{\\prime\\prime}"), fn("x", "x"), op(")")),
    listOf(op("="), op("+"), MINUS, op(";"), POWER),
)

// Engineering: SI prefixes (upright letters in the editor, ×10ⁿ in plain text), ∥, ×10ⁿ, dB.
private val ENGINEERING = listOf(
    listOf(digit("7"), digit("8"), digit("9"), prefix("k", 3), prefix("M", 6), prefix("G", 9)),
    listOf(digit("4"), digit("5"), digit("6"), prefix("m", -3), prefix("µ", -6, "\\mu"), prefix("n", -9)),
    listOf(digit("1"), digit("2"), digit("3"), prefix("p", -12), op("∥", "∥", "\\parallel"), BACKSPACE),
    listOf(digit("0"), digit("."), fn("×10ⁿ", "×10^(", "\\times10^{#0}"), op("("), op(")"), op("=")),
    listOf(op("+"), MINUS, TIMES, FRACTION, fn("dB", "20log(", "20\\log_{10}\\left(#0\\right)"), fn("10ˣ", "10^(", "10^{#0}")),
)

private fun named(label: String, name: String, args: Int = 1) =
    fn(label, "$name(", "\\operatorname{$name}\\left(#0" + ",#?".repeat(args - 1) + "\\right)")

private const val MATRIX_2 = "\\begin{pmatrix}#0 & #? \\\\ #? & #?\\end{pmatrix}"
private const val MATRIX_3 = "\\begin{pmatrix}#0 & #? & #? \\\\ #? & #? & #? \\\\ #? & #? & #?\\end{pmatrix}"

// Vectors and matrices: (3, 4), 5∠30°, a·b, a×b, [[1, 2], [3, 4]], det, A⁻¹, Aᵀ, rref, eig.
private val VECTORS = listOf(
    listOf(fn("( , )", "(", "\\left(#0,#?\\right)"), op("∠", "∠", "\\angle "), op("°", "°", "^{\\circ}"),
        fn("|v|", "abs(", "\\left|#0\\right|"), op(","), BACKSPACE),
    listOf(op("a·b", "·", "\\cdot "), op("a×b", "×", "\\times "), named("angle", "angle", 2), named("unit", "unit"),
        named("proj", "proj", 2), named("solve", "solve", 2)),
    listOf(fn("[2×2]", "[[", MATRIX_2), fn("[3×3]", "[[", MATRIX_3),
        KeySpec("+row", KeyAction.Command("addRowAfter", "], ["), KeyStyle.Function),
        KeySpec("+col", KeyAction.Command("addColumnAfter", ", "), KeyStyle.Function), op("("), op(")")),
    listOf(fn("det", "det(", "\\det\\left(#0\\right)"), fn("A⁻¹", "^(-1)", "#@^{-1}"), fn("Aᵀ", "^T", "#@^{T}"),
        named("rref", "rref").copy(longPress = KeyAction.Insert("ref(", "\\operatorname{ref}\\left(#0\\right)")),
        named("rank", "rank").copy(longPress = KeyAction.Insert("trace(", "\\operatorname{trace}\\left(#0\\right)")),
        named("eig", "eig")),
    listOf(fn("a", "a"), fn("b", "b"), fn("A", "A"), fn("B", "B"), op("="), op(";")),
)

// Numbers, counting, statistics, triangles: 5!, nCr, 15% of 80, 12:18, gcd, stats( ), triangle( ), complete( ).
private val NUMBERS = listOf(
    listOf(fn("n!", "!", "!"), named("nCr", "nCr", 2), named("nPr", "nPr", 2), op("%", "%", "\\%"),
        KeySpec("of", KeyAction.Insert(" of ", "\\text{ of }"), KeyStyle.Function,
            longPress = KeyAction.Insert(" as % of ", "\\text{ as }\\%\\text{ of }")), BACKSPACE),
    listOf(named("gcd", "gcd", 2), named("lcm", "lcm", 2), named("factor", "factor"), named("stats", "stats"), op(":"), op(",")),
    listOf(digit("7"), digit("8"), digit("9"), fn("△", "triangle(", "\\operatorname{triangle}\\left(a=#0,b=#?,C=#?\\right)"),
        named("(x+p)²", "complete"), op("°", "°", "^{\\circ}")),
    listOf(digit("4"), digit("5"), digit("6"), op("("), op(")"), op("=")),
    listOf(digit("1"), digit("2"), digit("3"), digit("0"), digit("."), fn("x", "x")),
)

// Logic: ¬ ∧ ∨ → ↔ ≡ ⊕ ⊨, ⊤ ⊥, NAND ↑ / NOR ↓, m( ) minterms and table( ) outputs.
private val LOGIC = listOf(
    listOf(op("¬", "¬", "\\neg "), op("∧", "∧", "\\land "), op("∨", "∨", "\\lor "), op("→", "→", "\\to "),
        op("↔", "↔", "\\leftrightarrow "), BACKSPACE),
    listOf(op("≡", "≡", "\\equiv "), op("⊕", "⊕", "\\oplus "), op("⊨", "⊨", "\\models "), op(","),
        fn("⊤", "⊤", "\\top "), fn("⊥", "⊥", "\\bot ")),
    listOf(fn("p", "p"), fn("q", "q"), fn("r", "r"), fn("s", "s"), op("("), op(")")),
    listOf(fn("A", "A"), fn("B", "B"), fn("C", "C"), fn("D", "D"), op("↑", "↑", "\\uparrow "), op("↓", "↓", "\\downarrow ")),
    listOf(fn("m( )", "m(", "m\\left(#0\\right)"), fn("table", "table(", "\\operatorname{table}\\left(#0\\right)"),
        digit("0"), digit("1"), op("′", "'", "'"), op(";")),
)

@Composable
fun MathKeyboard(onKey: (KeyAction) -> Unit, modifier: Modifier = Modifier, solveLabel: String = "Solve") {
    val haptics = LocalHapticFeedback.current
    var page by rememberSaveable { mutableStateOf(0) }

    fun press(a: KeyAction) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (a is KeyAction.GoToPage) page = a.page else onKey(a)
    }

    val grid = when (page) { 1 -> FUNCTIONS; 2 -> CALCULUS; 3 -> ENGINEERING; 4 -> VECTORS; 5 -> NUMBERS; 6 -> LOGIC; else -> BASIC }
    // Bottom-row keys use span 2 (Solve 3) so eight keys fit with a wider Solve.
    fun pageKey(target: Int, label: String) =
        if (page == target) KeySpec("123", KeyAction.GoToPage(0), KeyStyle.Action, span = 2)
        else KeySpec(label, KeyAction.GoToPage(target), KeyStyle.Action, span = 2)
    val rows = grid + listOf(
        listOf(
            pageKey(1, "f(x)"),
            pageKey(2, "lim"),
            pageKey(3, "Eng"),
            pageKey(4, "[ ]"),
            pageKey(5, "n!"),
            pageKey(6, "∧∨"),
            KeySpec("◀", KeyAction.Left, KeyStyle.Action, span = 2),
            KeySpec("▶", KeyAction.Right, KeyStyle.Action, span = 2),
            KeySpec(solveLabel, KeyAction.Solve, KeyStyle.Primary, span = 3),
        )
    )

    Column(
        modifier = modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (key in row) {
                    Key(key, Modifier.weight(key.span.toFloat()), onPress = { press(key.action) },
                        onLongPress = key.longPress?.let { lp -> { press(lp) } })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(spec: KeySpec, modifier: Modifier, onPress: () -> Unit, onLongPress: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val (container: Color, content: Color) = when (spec.style) {
        KeyStyle.Digit -> scheme.surfaceVariant to scheme.onSurfaceVariant
        KeyStyle.Operator -> scheme.secondaryContainer to scheme.onSecondaryContainer
        KeyStyle.Function -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        KeyStyle.Action -> scheme.errorContainer to scheme.onErrorContainer
        KeyStyle.Primary -> scheme.primary to scheme.onPrimary
    }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .height(50.dp)
            .clip(shape)
            .combinedClickable(onClick = onPress, onLongClick = onLongPress),
        shape = shape,
        color = container,
        contentColor = content
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                spec.label,
                fontSize = if (spec.label.length > 3) 14.sp else 19.sp,
                fontWeight = if (spec.style == KeyStyle.Primary) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}
