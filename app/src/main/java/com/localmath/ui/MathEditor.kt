package com.localmath.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

private const val TAG = "LocalMathEditor"

/**
 * Talks to the MathLive editor running in a WebView (assets/editor/editor.html).
 * Keys call [insert] / [command]; the current LaTeX is kept in [latex] after every change.
 */
class EditorController {
    var latex by mutableStateOf("")
        internal set

    /** Set if the editor couldn't start (the app then switches to plain-text input). */
    var failure by mutableStateOf<String?>(null)
        internal set

    /**
     * One WebView for the whole screen. It is reused when the input box moves (Normal <-> Notebook),
     * so MathLive loads once and what you typed stays put.
     */
    private var web: WebView? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val main = Handler(Looper.getMainLooper())

    fun insert(latex: String) = js("LM.insert(${JSONObject.quote(latex)})")
    fun command(name: String) = js("LM.cmd(${JSONObject.quote(name)})")
    fun set(value: String) { latex = value; js("LM.set(${JSONObject.quote(value)})") }
    internal fun theme(code: String) { web?.evaluateJavascript(code, null) }

    private fun js(code: String) {
        val w = web
        if (w == null || !ready) { pending += code; return }
        w.evaluateJavascript(code, null)
    }

    internal fun onReady() {
        if (ready) return
        ready = true
        Log.d(TAG, "editor ready, sending ${pending.size} queued call(s)")
        val w = web ?: return
        pending.forEach { w.evaluateJavascript(it, null) }
        pending.clear()
    }

    internal fun onError(message: String) {
        Log.e(TAG, "editor error: $message")
        if (!ready && failure == null) failure = message
    }

    /** The shared WebView, created on first use; taken off its old parent if the box moved. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    internal fun obtain(context: Context, theme: String): WebView {
        web?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            setBackgroundColor(AndroidColor.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            addJavascriptInterface(EditorBridge(this@EditorController), "Android")
            // Our own keyboard types into the editor, so keep Android's keyboard hidden.
            setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
            }
            // Page errors show up in Logcat under the tag "LocalMathEditor".
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.d(TAG, "${m.messageLevel()}: ${m.message()} (line ${m.lineNumber()})")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(theme, null)
                    if (latex.isNotEmpty()) set(latex)
                }
            }
        }
        web = view
        // Lets you inspect the editor from desktop Chrome (chrome://inspect) while debugging.
        WebView.setWebContentsDebuggingEnabled(true)
        // If the page never says it's ready, fall back to text input.
        main.postDelayed({ if (!ready && web === view && failure == null) failure = "it didn't respond" }, 4000)
        val html = context.assets.open("editor/editor.html").bufferedReader().use { it.readText() }
        view.loadDataWithBaseURL("file:///android_asset/editor/", html, "text/html", "utf-8", null)
        return view
    }

    /** Frees the WebView when the screen goes away for good. */
    fun destroy() {
        main.removeCallbacksAndMessages(null)
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null
        ready = false
        pending.clear()
    }
}

/** Called from JavaScript. Must be a public class with public methods for WebView to see them. */
class EditorBridge(private val controller: EditorController) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun changed(value: String) { main.post { controller.latex = value } }

    @JavascriptInterface
    fun ready() { main.post { controller.onReady() } }

    @JavascriptInterface
    fun error(message: String) { main.post { controller.onError(message) } }
}

private fun css(c: Color, alpha: Float = 1f): String {
    val argb = c.toArgb()
    return "rgba(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}, $alpha)"
}

/** Tap-to-edit math input: fractions, roots and powers with boxes to fill in. */
@Composable
fun MathEditor(controller: EditorController, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val theme = "LM.theme(${JSONObject.quote(css(scheme.onSurface))}, ${JSONObject.quote(css(scheme.primary))}, " +
        "${JSONObject.quote(css(scheme.onSurfaceVariant, 0.8f))}, ${JSONObject.quote(css(scheme.primary, 0.25f))})"
    AndroidView(
        modifier = modifier,
        factory = { context -> controller.obtain(context, theme) },
        update = { controller.theme(theme) }
        // No onRelease: the WebView is kept and reused; SolverScreen calls destroy() when it leaves.
    )
}
