package com.localmath.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
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

    var input by rememberSaveable(stateSaver = InputState.Saver) { mutableStateOf(InputState()) }
    var solvedText by rememberSaveable { mutableStateOf<String?>(null) }   // survives rotation
    var result by remember { mutableStateOf<Result<Solution>?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(solvedText) {
        val text = solvedText ?: return@LaunchedEffect
        result = null
        val r = withContext(Dispatchers.Default) { runCatching { Solver.solve(text) } }
        result = r
        r.getOrNull()?.let { s -> scope.launch { dao.record(text.trim(), s.kind, s.answer) } }
    }

    fun solve(text: String) {
        input = InputState.of(text)
        solvedText = text
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
        when (action) {
            is KeyAction.Insert -> input = input.insert(action.text)
            KeyAction.Backspace -> input = input.backspace()
            KeyAction.Clear -> { solvedText = null; result = null; input = input.clear() }
            KeyAction.Left -> input = input.left()
            KeyAction.Right -> input = input.right()
            KeyAction.Solve -> if (input.text.isNotBlank()) solvedText = input.text
            is KeyAction.GoToPage -> Unit  // handled inside the keyboard
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("LocalMath") },
                actions = { TextButton(onClick = { showHistory = true }) { Text("History") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            InputDisplay(input, Modifier.fillMaxWidth().padding(horizontal = 12.dp))

            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                val r = result
                when {
                    r == null && solvedText != null -> Text(
                        "Solving…", Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    r == null -> Examples(onPick = ::solve)
                    else -> r.fold(
                        onSuccess = { MathView(it, Modifier.fillMaxSize()) },
                        onFailure = { e ->
                            ErrorCard(
                                if (e is MathError) e.message ?: "Error" else "Unexpected error: ${e.message}",
                                Modifier.padding(12.dp)
                            )
                        }
                    )
                }
            }

            HorizontalDivider()
            MathKeyboard(onKey = ::onKey, modifier = Modifier.fillMaxWidth())
        }
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
            "Tips: f(x) opens functions, d/dx, ∫ and < > ≤ ≥. Definite integral: ∫(f, a, b). " +
                "Σ lim opens limits, sums, sequences and y′. Limits: lim(f, a), lim(f, 0+), lim(f, ∞). " +
                "Sums: Σ(f, 1, 10), Σ(f, 1, n), Σ(f, 1, ∞). Higher derivatives: d/dx(f, 2). " +
                "Starting values go after ;, e.g. y′ = 2y; y(0) = 1. Hold ⌫ to clear.",
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
