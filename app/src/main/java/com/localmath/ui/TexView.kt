package com.localmath.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.viewinterop.AndroidView

/** One formula typeset with KaTeX (offline, from app assets), centred. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TexView(tex: String, modifier: Modifier = Modifier, fontSize: Int = 24) {
    val c = rememberMathColors()
    val html = remember(tex, c, fontSize) {
        val js = "\"" + tex.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<link rel="stylesheet" href="katex/katex.min.css"><script src="katex/katex.min.js"></script>
<style>html,body{margin:0;padding:0;background:transparent;color:${c.text};}
#m{font-size:${fontSize}px;text-align:center;overflow-x:auto;overflow-y:hidden;padding:0 8px;} .katex-display{margin:4px 0;}</style>
</head><body><div id="m"></div><script>
katex.render($js, document.getElementById('m'), { displayMode: true, throwOnError: false });
</script></body></html>"""
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
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

/**
 * Plain formula text with real subscripts: "V_out = V_in·R₂" shows "out" and "in" small and lowered.
 * Used in lists, where one WebView per row would be too heavy.
 */
fun prettyMath(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        if (s[i] == '_' && i + 1 < s.length && s[i + 1].isLetterOrDigit()) {
            var j = i + 1
            while (j < s.length && s[j].isLetterOrDigit()) j++
            withStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.72.em)) { append(s.substring(i + 1, j)) }
            i = j
        } else {
            append(s[i]); i++
        }
    }
}
