package com.localmath.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
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
fun MathView(solution: Solution, modifier: Modifier = Modifier) {
    val colors = rememberMathColors()
    val html = remember(solution, colors) { SolutionHtml.build(solution, colors) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowContentAccess = false
                settings.allowFileAccess = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = true
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
