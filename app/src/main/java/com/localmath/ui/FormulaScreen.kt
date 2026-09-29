package com.localmath.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localmath.engine.Formula
import com.localmath.engine.Formulas
import com.localmath.engine.MathError
import com.localmath.engine.Solution

private const val PREFS = "localmath"
private const val FAVORITES = "favorite_formulas"
private const val GROUP = "formula_group"

/**
 * Searchable list of formulas with one tab per group (Engineering, Finance & economics),
 * grouped by subject, with favourites on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormulaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var favorites by remember { mutableStateOf(prefs.getStringSet(FAVORITES, emptySet())!!.toSet()) }
    var query by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val groups = Formulas.GROUPS.keys.toList()
    var group by rememberSaveable {
        mutableStateOf(prefs.getString(GROUP, null)?.takeIf { it in Formulas.GROUPS } ?: groups.first())
    }

    fun toggle(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putStringSet(FAVORITES, favorites).apply()
    }

    BackHandler { if (openId != null) openId = null else onBack() }

    val open = openId?.let { Formulas.byId(it) }
    if (open != null) {
        FormulaSolveScreen(open, open.id in favorites, onToggleFavorite = { toggle(open.id) }, onBack = { openId = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Formulas") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = groups.indexOf(group).coerceAtLeast(0)) {
                for (g in groups) {
                    Tab(
                        selected = g == group,
                        onClick = {
                            group = g
                            prefs.edit().putString(GROUP, g).apply()
                        },
                        text = { Text(g, maxLines = 1) }
                    )
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                singleLine = true,
                placeholder = {
                    Text(if (group == Formulas.FINANCE) "Search: loan, interest, margin, GDP…" else "Search: resistor, capacitor, speed…")
                }
            )
            val categories = Formulas.GROUPS.getValue(group)
            val found = Formulas.search(query).filter { it.category in categories }
            val favs = found.filter { it.id in favorites }
            LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
                if (favs.isNotEmpty()) {
                    item { Header("★ Favourites") }
                    items(favs, key = { "fav-" + it.id }) { f -> FormulaRow(f, true, { openId = f.id }, { toggle(f.id) }) }
                }
                for (cat in categories) {
                    val inCat = found.filter { it.category == cat }
                    if (inCat.isEmpty()) continue
                    item(key = "h-$cat") { Header(cat) }
                    items(inCat, key = { it.id }) { f -> FormulaRow(f, f.id in favorites, { openId = f.id }, { toggle(f.id) }) }
                }
                if (found.isEmpty()) item { Text("No formulas match \"$query\"", Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FormulaRow(f: Formula, favorite: Boolean, onOpen: () -> Unit, onStar: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.name, fontWeight = FontWeight.SemiBold)
                Text(prettyMath(f.plain), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onStar) { Text(if (favorite) "★" else "☆", fontSize = 22.sp) }
        }
    }
}

/** Fill in the known values, pick the unknown, and solve. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormulaSolveScreen(f: Formula, favorite: Boolean, onToggleFavorite: () -> Unit, onBack: () -> Unit) {
    val values = remember(f.id) { mutableStateMapOf<Char, String>() }
    var unknown by remember(f.id) { mutableStateOf(f.vars.first().letter) }
    var result by remember(f.id) { mutableStateOf<Result<Solution>?>(null) }
    val scroll = rememberScrollState()
    var resultTop by remember { mutableIntStateOf(0) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

    // After solving, scroll so the answer is at the top of the screen.
    LaunchedEffect(result) {
        if (result != null) {
            delay(80)
            scroll.animateScrollTo(resultTop)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(f.name, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) } },
                actions = { TextButton(onClick = onToggleFavorite) { Text(if (favorite) "★" else "☆", fontSize = 22.sp) } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(scroll).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val tex = remember(f.id) { runCatching { Formulas.tex(f) }.getOrNull() }
            if (tex != null) TexView(tex, Modifier.fillMaxWidth().height(84.dp))
            else Text(prettyMath(f.plain), fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 8.dp))
            f.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Tap ◉ next to the one you want to find, and fill in the rest. " +
                if (Formulas.groupOf(f) == Formulas.FINANCE) "Rates are in percent (type 5 for 5%). Amounts can be typed as 250000, 250,000 or 250k."
                else "Prefixes work: 4.7k, 100n, 2.2M, 3.3µ (or 3.3u), 1e-3.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            for (fv in f.vars) {
                val isUnknown = fv.letter == unknown
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isUnknown, onClick = { unknown = fv.letter; result = null })
                    OutlinedTextField(
                        value = if (isUnknown) "" else values[fv.letter] ?: "",
                        onValueChange = { values[fv.letter] = it; result = null },
                        enabled = !isUnknown,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        label = { Text(prettyMath("${fv.label} — ${fv.meaning}" + if (fv.unit.isNotEmpty()) " (${fv.unit})" else "")) },
                        placeholder = { Text(if (isUnknown) "?" else fv.default ?: "") }
                    )
                }
            }

            Button(
                onClick = {
                    keyboard?.hide()
                    focus.clearFocus()
                    result = runCatching { Formulas.solve(f, values.toMap(), unknown) }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) { Text(prettyMath("Solve for ${f.vars.first { it.letter == unknown }.label}")) }

            result?.let { r ->
              Box(Modifier.fillMaxWidth().onGloballyPositioned { resultTop = it.positionInParent().y.toInt() }) {
                r.fold(
                    onSuccess = { MathView(it, Modifier.fillMaxWidth(), eng = false, fitContent = true) },
                    onFailure = { e ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(if (e is MathError) e.message ?: "Error" else "Unexpected error: ${e.message}",
                                Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                )
              }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
