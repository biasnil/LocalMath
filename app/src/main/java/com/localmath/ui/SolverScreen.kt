package com.localmath.ui

import android.widget.Toast
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.edit
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
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
        val known = Notebook.known(notebook.map { it.answer })
        scope.launch {
            val entry = withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                try {
                    val r = Notebook.evaluate(text, known)
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !notebookMode, label = { Text("Normal") }, onClick = {
                    notebookMode = false; prefs.edit { putBoolean("notebook_mode", false) }
                })
                FilterChip(selected = notebookMode, label = { Text("Notebook") }, onClick = {
                    notebookMode = true; prefs.edit { putBoolean("notebook_mode", true) }
                })
            }

            val inputArea: @Composable () -> Unit = {
                if (visual) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(96.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) { MathEditor(editor, Modifier.fillMaxSize()) }
                } else {
                    InputDisplay(input, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                }
            }

            if (notebookMode) {
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
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                    val r = result
                    when {
                        r == null && solvedText != null -> Text(
                            "Solving…", Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        r == null -> Examples(onPick = ::solve)
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

            HorizontalDivider()
            MathKeyboard(onKey = ::onKey, modifier = Modifier.fillMaxWidth(), solveLabel = if (notebookMode) "Add" else "Solve")
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
            "Tap inside the problem to move the cursor. ÷ makes a fraction. " +
                "Eng: k, M, µ, ∥ and dB. Σ lim: limits, sums and y′. Hold ⌫ to clear.",
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
