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
    data class Insert(val text: String) : KeyAction()
    object Backspace : KeyAction()
    object Clear : KeyAction()
    object Left : KeyAction()
    object Right : KeyAction()
    object Solve : KeyAction()
    /** 0 = numbers, 1 = functions, 2 = calculus (lim, Σ, aₙ, y′). */
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
private fun op(label: String, insert: String = label) = KeySpec(label, KeyAction.Insert(insert), KeyStyle.Operator)
private fun fn(label: String, insert: String) = KeySpec(label, KeyAction.Insert(insert), KeyStyle.Function)

private val BACKSPACE = KeySpec("⌫", KeyAction.Backspace, KeyStyle.Action, longPress = KeyAction.Clear)

// Keys insert display symbols (×, ÷, −, ², √, ∫); the parser understands them directly.
private val BASIC = listOf(
    listOf(fn("x", "x"), fn("y", "y"), fn("z", "z"), fn("□²", "²"), BACKSPACE),
    listOf(digit("7"), digit("8"), digit("9"), op("÷"), fn("□ⁿ", "^")),
    listOf(digit("4"), digit("5"), digit("6"), op("×"), op("(")),
    listOf(digit("1"), digit("2"), digit("3"), op("−"), op(")")),
    listOf(digit("0"), digit("."), op("="), op("+"), op(";")),
)

private val FUNCTIONS = listOf(
    listOf(fn("sin", "sin("), fn("cos", "cos("), fn("tan", "tan("), fn("ln", "ln("), BACKSPACE),
    listOf(fn("sin⁻¹", "arcsin("), fn("cos⁻¹", "arccos("), fn("tan⁻¹", "arctan("), fn("log", "log("), fn("□ⁿ", "^")),
    listOf(fn("√", "√("), fn("eˣ", "e^("), fn("e", "e"), fn("π", "π"), op("(")),
    listOf(fn("d/dx", "d/dx("), fn("∫", "∫("), op(","), fn("|x|", "abs("), op(")")),
    listOf(fn("x", "x"), op("<"), op(">"), op("≤"), op("≥")),
)

private val CALCULUS = listOf(
    listOf(fn("lim", "lim("), op("→"), fn("∞", "∞"), fn("Σ", "Σ("), BACKSPACE),
    listOf(fn("sinh", "sinh("), fn("cosh", "cosh("), fn("tanh", "tanh("), fn("d/dy", "d/dy("), op(",")),
    listOf(fn("aₙ", "a_n"), fn("aₙ₋₁", "a_(n−1)"), fn("n", "n"), fn("k", "k"), op("(")),
    listOf(fn("y", "y"), fn("y′", "y′"), fn("y″", "y″"), fn("x", "x"), op(")")),
    listOf(op("="), op("+"), op("−"), op(";"), fn("□ⁿ", "^")),
)

@Composable
fun MathKeyboard(onKey: (KeyAction) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var page by rememberSaveable { mutableStateOf(0) }

    fun press(a: KeyAction) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (a is KeyAction.GoToPage) page = a.page else onKey(a)
    }

    val grid = when (page) { 1 -> FUNCTIONS; 2 -> CALCULUS; else -> BASIC }
    val rows = grid + listOf(
        listOf(
            if (page == 1) KeySpec("123", KeyAction.GoToPage(0), KeyStyle.Action)
            else KeySpec("f(x)", KeyAction.GoToPage(1), KeyStyle.Action),
            if (page == 2) KeySpec("123", KeyAction.GoToPage(0), KeyStyle.Action)
            else KeySpec("Σ lim", KeyAction.GoToPage(2), KeyStyle.Action),
            KeySpec("◀", KeyAction.Left, KeyStyle.Action),
            KeySpec("▶", KeyAction.Right, KeyStyle.Action),
            KeySpec("Solve", KeyAction.Solve, KeyStyle.Primary),
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
                fontSize = if (spec.label.length > 3) 15.sp else 19.sp,
                fontWeight = if (spec.style == KeyStyle.Primary) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}
