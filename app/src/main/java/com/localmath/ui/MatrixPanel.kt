package com.localmath.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A square grid of number boxes. Blank boxes count as 0. */
data class MatrixGrid(val size: Int = 3, val cells: List<String> = List(9) { "" }) {
    fun cell(r: Int, c: Int) = cells[r * size + c]
    fun set(r: Int, c: Int, v: String) = copy(cells = cells.toMutableList().also { it[r * size + c] = v })

    fun resized(n: Int): MatrixGrid {
        val m = n.coerceIn(1, 6)
        return MatrixGrid(m, List(m * m) { i -> val r = i / m; val c = i % m; if (r < size && c < size) cell(r, c) else "" })
    }

    /** "[[1, 2], [3, 4]]" for the solver. */
    fun text(): String = (0 until size).joinToString(", ", "[", "]") { r ->
        (0 until size).joinToString(", ", "[", "]") { c -> cell(r, c).trim().replace('−', '-').ifEmpty { "0" } }
    }

    fun clear() = MatrixGrid(size, List(size * size) { "" })

    /** (row, column), counting from 1, of the first box that isn't a number like 3, −2, 0.5 or 1/2. */
    fun firstBad(): Pair<Int, Int>? {
        val ok = Regex("^-?(\\d+\\.?\\d*|\\.\\d+)(/(\\d+\\.?\\d*|\\.\\d+))?$")
        for (i in cells.indices) {
            val t = cells[i].trim().replace('−', '-')
            if (t.isNotEmpty() && !ok.matches(t)) return (i / size + 1) to (i % size + 1)
        }
        return null
    }

    companion object {
        val Saver = listSaver<MatrixGrid, String>(
            save = { listOf(it.size.toString()) + it.cells },
            restore = { MatrixGrid(it[0].toInt(), it.drop(1)) }
        )
    }
}

/**
 * Matrix calculator: fill in A (and B), then tap an operation. It builds a normal LocalMath problem such as
 * "A = [[1, 2], [3, 4]]; det(A)" and hands it to [onSolve], so the answer comes with the usual steps.
 */
@Composable
fun MatrixPanel(onSolve: (String) -> Unit, modifier: Modifier = Modifier) {
    var a by rememberSaveable(stateSaver = MatrixGrid.Saver) { mutableStateOf(MatrixGrid()) }
    var b by rememberSaveable(stateSaver = MatrixGrid.Saver) { mutableStateOf(MatrixGrid()) }
    var which by rememberSaveable { mutableIntStateOf(0) }   // 0 = A, 1 = B
    var k by rememberSaveable { mutableStateOf("2") }
    var power by rememberSaveable { mutableStateOf("2") }
    var expr by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }

    val letter = if (which == 0) "A" else "B"
    val grid = if (which == 0) a else b
    fun update(g: MatrixGrid) { if (which == 0) a = g else b = g }
    fun def(l: String) = "$l = " + (if (l == "A") a else b).text()
    /** Checks the boxes of the matrices used, then solves. */
    fun go(letters: List<String>, question: String) {
        for (l in letters) {
            val bad = (if (l == "A") a else b).firstBad()
            if (bad != null) {
                problem = "Matrix $l, row ${bad.first}, column ${bad.second}: use a number like 3, −2, 0.5 or 1/2 (empty = 0)"
                which = if (l == "A") 0 else 1
                return
            }
        }
        problem = null
        onSolve(letters.joinToString("") { "${def(it)}; " } + question)
    }
    fun one(question: String) = go(listOf(letter), question)
    fun both(question: String) = go(listOf("A", "B"), question)

    Column(
        // imePadding: the app draws edge to edge, so make room for the phone's keyboard ourselves.
        modifier.imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Which matrix, and swap.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = which == 0, onClick = { which = 0 }, label = { Text("A  ${a.size}×${a.size}", maxLines = 1) })
            FilterChip(selected = which == 1, onClick = { which = 1 }, label = { Text("B  ${b.size}×${b.size}", maxLines = 1) })
            Box(Modifier.weight(1f))
            TextButton(onClick = { val t = a; a = b; b = t }) { Text("⇄ Swap", maxLines = 1) }
        }

        problem?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

        // The grid.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in 0 until grid.size) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in 0 until grid.size) {
                        Cell(grid.cell(r, c), { update(grid.set(r, c, it)) }, Modifier.weight(1f), small = grid.size >= 5)
                    }
                }
            }
        }

        // Size and clear.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { update(grid.clear()) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Clear") }
            Box(Modifier.weight(1f))
            OutlinedButton(onClick = { update(grid.resized(grid.size - 1)) }, enabled = grid.size > 1,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("−", fontSize = 20.sp) }
            Text("${grid.size} × ${grid.size}", fontSize = 15.sp)
            OutlinedButton(onClick = { update(grid.resized(grid.size + 1)) }, enabled = grid.size < 6,
                contentPadding = PaddingValues(0.dp), modifier = Modifier.width(48.dp)) { Text("+", fontSize = 20.sp) }
        }

        // Operations on the selected matrix.
        OpRow({ Op("Determinant", it) { one("det($letter)") } }, { Op("Inverse", it) { one("$letter^-1") } })
        OpRow({ Op("Transpose", it) { one("$letter^T") } }, { Op("Rank", it) { one("rank($letter)") } })
        OpRow({ m ->
            Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Op("Multiply by", Modifier.weight(1f)) { one("(${k.ifBlank { "2" }}) $letter") }
                Cell(k, { k = it }, Modifier.width(48.dp))
            }
        }, { Op("Row echelon form", it) { one("ref($letter)") } })
        OpRow({ Op("Diagonal matrix", it) { one("diag($letter)") } }, { m ->
            Row(m, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Op("To the power of", Modifier.weight(1f)) { one("$letter^(${power.ifBlank { "2" }})") }
                Cell(power, { power = it }, Modifier.width(48.dp))
            }
        })
        OpRow({ Op("LU decomposition", it) { one("lu($letter)") } }, { Op("Cholesky decomposition", it) { one("chol($letter)") } })

        // Both matrices.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Op("A × B", Modifier.weight(1f)) { both("A B") }
            Op("A + B", Modifier.weight(1f)) { both("A + B") }
            Op("A − B", Modifier.weight(1f)) { both("A - B") }
        }

        // Any expression, like 2A + 3B.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = expr,
                onValueChange = { expr = it.take(60) },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (expr.isEmpty()) Text("2A + 3B", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
                        inner()
                    }
                }
            )
            Button(onClick = {
                val e = expr.ifBlank { "2A + 3B" }.replace('×', '·').replace('−', '-')
                go(listOf("A", "B").filter { e.contains(it) }, e)
            }) { Text("=", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun OpRow(left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        left(Modifier.weight(1f))
        right(Modifier.weight(1f))
    }
}

@Composable
private fun Op(label: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    ) { Text(label, fontSize = 12.sp, lineHeight = 13.sp, maxLines = 2, textAlign = TextAlign.Center) }
}

/** One number box: digits, minus, decimal point and / for fractions. */
@Composable
private fun Cell(value: String, onChange: (String) -> Unit, modifier: Modifier, small: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it in "-−./" }.take(12)) },
        singleLine = true,
        textStyle = TextStyle(color = scheme.onSurface, fontSize = if (small) 14.sp else 17.sp, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
        modifier = modifier.height(44.dp).clip(shape).background(scheme.surfaceContainerHigh).border(1.dp, scheme.outlineVariant, shape),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) { inner() }
        }
    )
}
