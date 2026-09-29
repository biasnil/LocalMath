package com.localmath.ui

import android.widget.Toast
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.edit
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import com.localmath.data.NotebookEntry
import com.localmath.engine.Notebook
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.localmath.engine.LatexInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmath.data.AppDatabase
import com.localmath.data.record
import com.localmath.engine.MathError
import com.localmath.engine.Solution
import com.localmath.engine.Solver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EXAMPLES = listOf(
    "10000 ∥ 4700",
    "a = (3, 5); b = (4, 2); a + b",
    "det([[1, 2], [3, 4]])",
    "triangle(a = 5, b = 7, C = 60°)",
    "stats(2, 4, 4, 5, 7, 9)",
    "complete(x² + 6x + 5)",
    "p → q, p ⊨ q",
    "m(0, 2, 5, 7, 8, 10, 13, 15)",
    "2x + 3 = 7",
    "x² − 5x + 6 = 0",
    "x³ − 6x² + 11x − 6 = 0",
    "1/x + 1/(x − 1) = 1",
    "x² − 4 ≥ 0",
    "(x² − 1)/(x − 1)",
    "d/dx(x² sin x)",
    "∫(x e^x)",
    "∫(x², 0, 3)",
    "2x + y = 5; x − y = 1",
    "sin(π/6) + √8",
    "lim(sin x / x, 0)",
    "lim((1 + 1/x)^x, ∞)",
    "Σ(k², 1, n)",
    "Σ(1/k², 1, ∞)",
    "a_n = 2a_(n−1) + 1; a_1 = 1",
    "y′ = 2y; y(0) = 1",
    "y″ + y = 0",
    "d/dx(x³ sin x, 2)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SolverScreen() {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).history() }
    val historyFlow = remember(dao) { dao.all() }
    val history by historyFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    val prefs = remember { context.getSharedPreferences("localmath", android.content.Context.MODE_PRIVATE) }
    var visual by remember { mutableStateOf(prefs.getBoolean("visual_editor", true)) }   // tap-to-edit input
    var eng by remember { mutableStateOf(prefs.getBoolean("eng_answers", false)) }       // engineering notation
    var menuOpen by remember { mutableStateOf(false) }
    var notebookMode by remember { mutableStateOf(prefs.getBoolean("notebook_mode", false)) }
    var matrixMode by remember { mutableStateOf(prefs.getBoolean("matrix_mode", false)) }
    var matrixPanelOpen by rememberSaveable { mutableStateOf(true) }   // Matrix tab: boxes and buttons, or the answer
    var keysOpen by remember { mutableStateOf(prefs.getBoolean("keys_open", true)) }
    var confirmClear by remember { mutableStateOf(false) }
    val notebookDao = remember { AppDatabase.get(context).notebook() }
    val notebookFlow = remember(notebookDao) { notebookDao.all() }
    val notebook by notebookFlow.collectAsState(initial = emptyList())
    var showFormulas by rememberSaveable { mutableStateOf(false) }
    val editor = remember { EditorController() }
    DisposableEffect(editor) { onDispose { editor.destroy() } }

    var input by rememberSaveable(stateSaver = InputState.Saver) { mutableStateOf(InputState()) }
    var solvedText by rememberSaveable { mutableStateOf<String?>(null) }   // survives rotation
    var result by remember { mutableStateOf<Result<Solution>?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    var solveCount by remember { mutableIntStateOf(0) }   // lets the same problem be solved again

    // If the tap-to-edit editor can't start on this device, fall back to plain-text input.
    LaunchedEffect(editor.failure) {
        val why = editor.failure ?: return@LaunchedEffect
        if (visual) {
            visual = false
            prefs.edit { putBoolean("visual_editor", false) }
            Toast.makeText(context, "Tap-to-edit input couldn't start ($why). Switched to text input.", Toast.LENGTH_LONG).show()
        }
    }

    // After rotation the editor starts empty; put the problem back.
    LaunchedEffect(Unit) {
        if (visual && input.text.isNotBlank()) editor.set(runCatching { LatexInput.fromText(input.text) }.getOrDefault(""))
    }

    LaunchedEffect(solvedText, solveCount) {
        val text = solvedText ?: return@LaunchedEffect
        result = null
        val r = withContext(Dispatchers.Default) { runCatching { Solver.solve(text) } }
        result = r
        r.getOrNull()?.let { s -> scope.launch { dao.record(text.trim(), s.kind, s.answer) } }
    }

    fun solve(text: String) {
        matrixPanelOpen = false   // on the Matrix tab, show the answer rather than the boxes
        input = InputState.of(text)
        if (visual) editor.set(runCatching { LatexInput.fromText(text) }.getOrDefault(""))
        solvedText = text
    }

    fun clearInput() {
        input = InputState()
        if (visual) editor.set("")
    }

    /** Notebook: work out the line (using earlier answers), save it, and clear the input. */
    fun addToNotebook(text: String) {
        val answers = notebook.map { it.answer }
        val known = Notebook.known(answers)
        val objects = Notebook.objects(answers)   // vectors and matrices from earlier lines
        scope.launch {
            val entry = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                try {
                    val r = Notebook.evaluate(text, known, objects)
                    NotebookEntry(input = text, answer = r.solution.answer, approx = r.solution.approx, error = null, used = r.used, timestamp = now)
                } catch (e: MathError) {
                    NotebookEntry(input = text, answer = null, approx = null, error = e.message ?: "Error", used = null, timestamp = now)
                } catch (e: Exception) {
                    NotebookEntry(input = text, answer = null, approx = null, error = "Unexpected error: ${e.message}", used = null, timestamp = now)
                }
            }
            notebookDao.insert(entry)
        }
        clearInput()
    }

    /** Puts an earlier answer into the input (the "Ans" button, or tapping an answer). */
    fun insertAnswer(answer: String?) {
        val value = Notebook.ansOf(answer)
        if (value == null) {
            Toast.makeText(context, "That answer isn't a single value", Toast.LENGTH_SHORT).show()
            return
        }
        if (visual) editor.insert(value.second) else input = input.insert(value.first)
    }

    /** Solve what's in the editor: its LaTeX is converted to LocalMath text first. */
    fun solveCurrent() {
        if (notebookMode) {
            val text = if (visual) {
                if (editor.latex.isBlank()) return
                try { LatexInput.toText(editor.latex) } catch (e: MathError) {
                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show(); return
                }
            } else input.text
            if (text.isNotBlank()) addToNotebook(text)
            return
        }
        if (!visual) {
            if (input.text.isNotBlank()) solvedText = input.text
            return
        }
        if (editor.latex.isBlank()) return
        try {
            val text = LatexInput.toText(editor.latex)
            input = InputState.of(text)
            solvedText = text
            solveCount++
        } catch (e: MathError) {
            solvedText = null
            result = Result.failure(e)
        }
    }

    if (showFormulas) {
        FormulaScreen(onBack = { showFormulas = false })
        return
    }

    if (showHistory) {
        HistoryScreen(
            entries = history,
            onPick = { entry -> showHistory = false; solve(entry.input) },
            onDelete = { entry -> scope.launch { dao.delete(entry) } },
            onClearAll = { scope.launch { dao.clear() } },
            onBack = { showHistory = false }
        )
        return
    }

    fun onKey(action: KeyAction) {
        if (visual) {
            when (action) {
                is KeyAction.Insert -> editor.insert(action.latex ?: action.text)
                is KeyAction.Command -> editor.command(action.name)
                KeyAction.Backspace -> editor.command("deleteBackward")
                KeyAction.Clear -> { solvedText = null; result = null; editor.set("") }
                KeyAction.Left -> editor.command("moveToPreviousChar")
                KeyAction.Right -> editor.command("moveToNextChar")
                KeyAction.Solve -> solveCurrent()
                is KeyAction.GoToPage -> Unit
            }
            return
        }
        when (action) {
            is KeyAction.Insert -> input = input.insert(action.text)
            is KeyAction.Command -> input = input.insert(action.text)
            KeyAction.Backspace -> input = input.backspace()
            KeyAction.Clear -> { solvedText = null; result = null; input = input.clear() }
            KeyAction.Left -> input = input.left()
            KeyAction.Right -> input = input.right()
            KeyAction.Solve -> solveCurrent()
            is KeyAction.GoToPage -> Unit  // handled inside the keyboard
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("LocalMath") },
                actions = {
                    TextButton(onClick = { showFormulas = true }) { Text("Formulas") }
                    TextButton(onClick = { showHistory = true }) { Text("History") }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 20.sp) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text((if (visual) "✓ " else "    ") + "Tap-to-edit input") },
                                onClick = {
                                    // Carry the current problem across when switching modes.
                                    if (visual) {
                                        runCatching { LatexInput.toText(editor.latex) }.getOrNull()?.let { input = InputState.of(it) }
                                    } else {
                                        editor.set(runCatching { LatexInput.fromText(input.text) }.getOrDefault(""))
                                    }
                                    visual = !visual
                                    prefs.edit { putBoolean("visual_editor", visual) }
                                    menuOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text((if (eng) "✓ " else "    ") + "Engineering answers (k, M, m, µ)") },
                                onClick = {
                                    eng = !eng
                                    prefs.edit { putBoolean("eng_answers", eng) }
                                    menuOpen = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Normal = one problem with full steps; Notebook = running list of answers.
            // Matrix = fill in boxes and tap an operation.
            fun setMode(notebook: Boolean, matrix: Boolean) {
                notebookMode = notebook; matrixMode = matrix
                prefs.edit { putBoolean("notebook_mode", notebook); putBoolean("matrix_mode", matrix) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !notebookMode && !matrixMode, label = { Text("Normal") }, onClick = { setMode(false, false) })
                FilterChip(selected = notebookMode && !matrixMode, label = { Text("Notebook") }, onClick = { setMode(true, false) })
                FilterChip(selected = matrixMode, label = { Text("Matrix") }, onClick = { setMode(false, true) })
            }

            val resultArea: @Composable (Modifier) -> Unit = { mod ->
                Box(mod) {
                    val r = result
                    when {
                        r == null && solvedText != null -> Text(
                            "Solving…", Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        r == null -> if (!matrixMode) Examples(onPick = ::solve)
                        else -> r.fold(
                            onSuccess = { MathView(it, Modifier.fillMaxSize(), eng = eng) },
                            onFailure = { e ->
                                ErrorCard(
                                    if (e is MathError) e.message ?: "Error" else "Unexpected error: ${e.message}",
                                    Modifier.padding(12.dp)
                                )
                            }
                        )
                    }
                }
            }

            val inputArea: @Composable () -> Unit = {
                if (visual) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(editorHeight(editor.latex)),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) { MathEditor(editor, Modifier.fillMaxSize()) }
                } else {
                    InputDisplay(input, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                }
            }

            if (matrixMode) {
                if (matrixPanelOpen) {
                    MatrixPanel(
                        onSolve = { text -> solvedText = text; solveCount++; matrixPanelOpen = false },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                } else {
                    // The answer, with a bar to go back to the boxes (they keep what you typed).
                    TextButton(onClick = { matrixPanelOpen = true }, modifier = Modifier.padding(horizontal = 4.dp)) {
                        Text("▴ Edit matrices")
                    }
                    resultArea(Modifier.weight(1f).fillMaxWidth())
                }
            } else if (notebookMode) {
                NotebookView(
                    entries = notebook, eng = eng,
                    onPick = { id -> insertAnswer(notebook.firstOrNull { it.id == id }?.answer) },
                    onRemove = { id -> scope.launch { notebookDao.delete(id) } },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { insertAnswer(notebook.lastOrNull { it.answer != null }?.answer) },
                        enabled = notebook.any { it.answer != null }
                    ) { Text("Ans") }
                    Text("${notebook.size} line${if (notebook.size == 1) "" else "s"}", Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    TextButton(onClick = { confirmClear = true }, enabled = notebook.isNotEmpty()) { Text("Clear notebook") }
                }
                inputArea()
                Spacer(Modifier.height(6.dp))
            } else {
                inputArea()
                resultArea(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp))
            }

            // The Matrix tab types into its boxes with the phone's keyboard, so ours is only for Normal and Notebook.
            if (!matrixMode) {
                HorizontalDivider()
                val solveLabel = if (notebookMode) "Add" else "Solve"
                fun setKeys(open: Boolean) { keysOpen = open; prefs.edit { putBoolean("keys_open", open) } }
                if (keysOpen) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { setKeys(false) }, modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)) {
                            Text("▾ Hide keys", fontSize = 13.sp)
                        }
                    }
                    MathKeyboard(onKey = ::onKey, modifier = Modifier.fillMaxWidth(), solveLabel = solveLabel)
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { setKeys(true) }) { Text("▴ Keys") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = ::solveCurrent) { Text(solveLabel) }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear notebook?") },
            text = { Text("This deletes every line in the notebook.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { notebookDao.clear() } }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

/** Taller input box when it holds a matrix, so every row shows (2 rows: 126 dp, 3 rows: 156 dp, …). */
private fun editorHeight(latex: String): Dp {
    val rows = Regex("\\\\begin\\{[a-zA-Z]*matrix\\}(.*?)\\\\end\\{", RegexOption.DOT_MATCHES_ALL).findAll(latex)
        .maxOfOrNull { it.groupValues[1].split("\\\\").size } ?: 1
    return (96 + (rows - 1) * 30).coerceAtMost(250).dp
}

/** Shows the typed input with a visible cursor; scrolls sideways for long input. */
@Composable
private fun InputDisplay(state: InputState, modifier: Modifier) {
    val cursorColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.heightIn(min = 60.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.text.isEmpty()) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = cursorColor)) { append("│") }
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" Type an equation") }
                    },
                    fontSize = 22.sp
                )
            } else {
                Text(
                    buildAnnotatedString {
                        append(state.text.substring(0, state.cursor))
                        withStyle(SpanStyle(color = cursorColor, fontWeight = FontWeight.Light)) { append("│") }
                        append(state.text.substring(state.cursor))
                    },
                    fontSize = 24.sp, softWrap = false
                )
            }
        }
    }
}

@Composable
private fun Examples(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Try an example", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXAMPLES.forEach { ex -> AssistChip(onClick = { onPick(ex) }, label = { Text(ex) }) }
        }
        Text(
            "Tap the problem to move the cursor. Hold ⌫ to clear.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorCard(message: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}
