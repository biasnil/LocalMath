package com.localmath.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.localmath.engine.Solution

private fun hex(c: Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())

@Composable
fun rememberMathColors(): MathColors {
    val c = MaterialTheme.colorScheme
    return remember(c) {
        MathColors(
            text = hex(c.onSurface), muted = hex(c.onSurfaceVariant), card = hex(c.surfaceContainer),
            title = hex(c.primary), answerBg = hex(c.primaryContainer), answerText = hex(c.onPrimaryContainer),
            outline = hex(c.outlineVariant), accent2 = hex(c.tertiary), accent3 = hex(c.error)
        )
    }
}

/**
 * Renders a [Solution] (answer, graph and steps) with KaTeX and a small canvas plotter,
 * entirely from app assets. The app has no INTERNET permission, so nothing here can reach the network.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(solution: Solution, modifier: Modifier = Modifier, eng: Boolean = false, fitContent: Boolean = false) {
    val colors = rememberMathColors()
    val html = remember(solution, colors, eng) { SolutionHtml.build(solution, colors, eng) }
    // fitContent: grow to the page's full height (for use inside a screen that already scrolls).
    var contentHeight by remember(html) { mutableIntStateOf(0) }
    val onMeasured = rememberUpdatedState<(Int) -> Unit> { h -> if (h > 0) contentHeight = h }
    val sized = if (!fitContent) modifier else modifier.height(if (contentHeight > 0) contentHeight.dp else 420.dp)
    AndroidView(
        modifier = sized,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = !fitContent
                if (fitContent) {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            // CSS pixels are dp here (width=device-width). Measure again after fonts settle.
                            val measure = Runnable {
                                view.evaluateJavascript("Math.ceil(document.body.getBoundingClientRect().height)") { v ->
                                    v?.trim('"')?.toFloatOrNull()?.let { onMeasured.value(it.toInt() + 12) }
                                }
                            }
                            measure.run()
                            view.postDelayed(measure, 350)
                            view.postDelayed(measure, 1000)
                        }
                    }
                }
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
