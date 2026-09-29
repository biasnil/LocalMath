package com.localmath.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.localmath.data.NotebookEntry
import com.localmath.engine.Eng
import com.localmath.engine.LatexInput

/** Builds the notebook page: each line shows the problem and its answer (no steps). Plain Kotlin, testable. */
object NotebookHtml {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun build(entries: List<NotebookEntry>, c: MathColors, eng: Boolean): String {
        val rows = if (entries.isEmpty()) {
            "<div class=\"empty\">Your notebook is empty.<br>Type a problem below and press <b>Add</b>.<br><br>" +
                "Later lines can use earlier answers: after <i>2x + 3 = 11</i>, <i>y = 3x + 2</i> uses x = 4.<br>" +
                "Tap an answer (or <b>Ans</b>) to put it into the next line.</div>"
        } else entries.mapIndexed { i, e ->
            val input = runCatching { LatexInput.fromText(e.input) }.getOrNull()
            val inputHtml = if (input != null) "\\(${esc(input)}\\)" else esc(e.input)
            val result = when {
                e.error != null -> "<div class=\"err\">${esc(e.error)}</div>"
                else -> {
                    val approx = e.approx?.let { if (eng) Eng.decorate(it) else it }
                        ?: if (eng) Eng.decorate(e.answer ?: "").takeIf { it != e.answer }?.let { "= " + it.substringAfter("\\left(= ").removeSuffix("\\right)") } else null
                    "<div class=\"ans\" onclick=\"Android.pick('${e.id}')\">\\(${esc(e.answer ?: "")}\\)" +
                        (approx?.let { "<span class=\"approx\">&nbsp;&nbsp;\\(${esc(it)}\\)</span>" } ?: "") + "</div>"
                }
            }
            val used = e.used?.let { "<div class=\"used\">using \\(${esc(it)}\\)</div>" } ?: ""
            "<div class=\"row${if (e.error != null) " bad" else ""}\">" +
                "<div class=\"head\"><span class=\"n\">${i + 1}</span><span class=\"in\">$inputHtml</span>" +
                "<span class=\"x\" onclick=\"Android.remove('${e.id}')\">&#10005;</span></div>$used$result</div>"
        }.joinToString("\n")

        return """<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<link rel="stylesheet" href="katex/katex.min.css">
<script src="katex/katex.min.js"></script>
<script src="katex/contrib/auto-render.min.js"></script>
<style>
  html, body { margin: 0; padding: 0; background: transparent; }
  body { color: ${c.text}; font-family: sans-serif; font-size: 16px; padding: 4px 12px 12px; }
  .row { background: ${c.card}; border-radius: 14px; padding: 8px 12px; margin-bottom: 8px; }
  .row.bad { border-left: 4px solid ${c.accent3}; }
  .head { display: flex; align-items: center; gap: 8px; }
  .n { color: ${c.muted}; font-size: 12px; min-width: 16px; }
  .in { flex: 1; overflow-x: auto; overflow-y: hidden; white-space: nowrap; }
  .x { color: ${c.muted}; font-size: 14px; padding: 4px 6px; }
  .ans { margin: 4px 0 0 24px; padding: 6px 10px; border-radius: 10px; background: ${c.answerBg}; color: ${c.answerText};
         display: inline-block; max-width: calc(100% - 44px); overflow-x: auto; overflow-y: hidden; white-space: nowrap; }
  .ans .katex { font-size: 1.15em; }
  .approx { opacity: 0.85; font-size: 14px; }
  .used { margin-left: 24px; color: ${c.muted}; font-size: 12px; }
  .err { margin: 4px 0 0 24px; color: ${c.accent3}; font-size: 14px; }
  .empty { color: ${c.muted}; text-align: center; margin-top: 32px; line-height: 1.6; }
</style>
</head><body>
$rows
<script>
  renderMathInElement(document.body, {
    delimiters: [{ left: "\\(", right: "\\)", display: false }],
    throwOnError: false
  });
  window.scrollTo(0, document.body.scrollHeight);
</script>
</body></html>"""
    }
}

private class NotebookBridge(private val onPick: () -> (Long) -> Unit, private val onRemove: () -> (Long) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @JavascriptInterface fun pick(id: String) { id.toLongOrNull()?.let { main.post { onPick()(it) } } }
    @JavascriptInterface fun remove(id: String) { id.toLongOrNull()?.let { main.post { onRemove()(it) } } }
}

/** The notebook as one WebView (fast even with many lines). Tapping an answer calls [onPick]; ✕ calls [onRemove]. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun NotebookView(
    entries: List<NotebookEntry>,
    eng: Boolean,
    onPick: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = rememberMathColors()
    val html = remember(entries, colors, eng) { NotebookHtml.build(entries, colors, eng) }
    val pick = rememberUpdatedState(onPick)
    val remove = rememberUpdatedState(onRemove)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                addJavascriptInterface(NotebookBridge({ pick.value }, { remove.value }), "Android")
            }
        },
        update = { web ->
            if (web.tag != html) {
                web.tag = html
                web.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            }
        }
    )
}
