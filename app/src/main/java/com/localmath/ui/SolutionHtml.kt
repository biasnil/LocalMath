package com.localmath.ui

import com.localmath.engine.Eng

import com.localmath.engine.Graph
import com.localmath.engine.Solution

/** Theme colors as "#RRGGBB" strings, so the rendered page matches light/dark mode. */
data class MathColors(
    val text: String, val muted: String, val card: String, val title: String,
    val answerBg: String, val answerText: String, val outline: String,
    val accent2: String, val accent3: String
)

/** Builds the page shown in MathView. Plain Kotlin (no Android types) so it can be tested off-device. */
object SolutionHtml {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A JavaScript number literal (Infinity/NaN spelled the JS way). */
    private fun num(d: Double): String = when {
        d.isNaN() -> "NaN"
        d == Double.POSITIVE_INFINITY -> "Infinity"
        d == Double.NEGATIVE_INFINITY -> "-Infinity"
        else -> d.toString()
    }

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    /** With [eng] on, numbers in the answer also get an engineering-notation form (e.g. 3.197 k). */
    fun build(solution: Solution, c: MathColors, eng: Boolean = false): String {
        val s = if (!eng) solution else solution.copy(
            approx = solution.approx?.let { Eng.decorate(it) }
                ?: Eng.decorate(solution.answer).takeIf { it != solution.answer }?.let { "= " + it.substringAfter("\\left(= ").removeSuffix("\\right)") }
        )
        val steps = s.steps.mapIndexed { i, step ->
            "<div class=\"card\"><div class=\"title\">Step ${i + 1} · ${esc(step.title)}</div>" +
                "<div class=\"math\">\\[${esc(step.math)}\\]</div>" +
                (step.note?.let { "<div class=\"note\">${esc(it)}</div>" } ?: "") + "</div>"
        }.joinToString("\n")

        val answer = "<div class=\"card answer\"><div class=\"kind\">${esc(s.kind)}</div>" +
            "<div class=\"math\">\\[${esc(s.answer)}\\]</div>" +
            (s.approx?.let { "<div class=\"approx\">\\(${esc(it)}\\)</div>" } ?: "") + "</div>"

        val graph = s.graph?.let { graphHtml(it, c) } ?: ""

        return """<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<link rel="stylesheet" href="katex/katex.min.css">
<script src="katex/katex.min.js"></script>
<script src="katex/contrib/auto-render.min.js"></script>
<script src="plot.js"></script>
<style>
  html, body { margin: 0; padding: 0; background: transparent; }
  body { color: ${c.text}; font-family: sans-serif; font-size: 16px; padding: 4px 12px 16px; }
  .card { background: ${c.card}; border-radius: 16px; padding: 12px 14px; margin-bottom: 10px; }
  .answer { background: ${c.answerBg}; color: ${c.answerText}; }
  .kind { font-size: 13px; font-weight: 600; opacity: 0.85; }
  .approx { font-size: 15px; opacity: 0.9; overflow-x: auto; }
  .title { color: ${c.title}; font-size: 13px; font-weight: 600; line-height: 1.5; }
  .note { color: ${c.muted}; font-size: 13px; margin-top: 4px; line-height: 1.6; }
  .katex-display { margin: 6px 0; overflow-x: auto; overflow-y: hidden; padding: 4px 0; }
  .answer .katex-display .katex { font-size: 1.35em; }
  .plotwrap { position: relative; }
  canvas.plot { width: 100%; height: 250px; display: block; touch-action: none; border-radius: 10px; }
  .zoom { position: absolute; right: 6px; top: 6px; display: flex; gap: 6px; }
  .zoom button { width: 34px; height: 34px; border-radius: 17px; border: 1px solid ${c.outline};
                 background: ${c.card}; color: ${c.text}; font-size: 18px; line-height: 1; padding: 0; }
  .legend { margin-top: 8px; font-size: 14px; line-height: 1.9; overflow-x: auto; }
  .sw { display: inline-block; width: 14px; height: 4px; border-radius: 2px; vertical-align: middle; margin-right: 6px; }
  .hint { color: ${c.muted}; font-size: 12px; margin-top: 2px; }
</style>
</head><body>
$answer
$graph
$steps
<script>
  renderMathInElement(document.body, {
    delimiters: [
      { left: "\\[", right: "\\]", display: true },
      { left: "\\(", right: "\\)", display: false }
    ],
    throwOnError: false
  });
</script>
</body></html>"""
    }

    private fun graphHtml(g: Graph, c: MathColors): String {
        val palette = listOf(c.title, c.accent3, c.accent2, c.muted)
        val curves = g.curves.mapIndexed { i, cv ->
            "{ f: function (x) { return ${cv.js}; }, color: ${jsString(palette[i % palette.size])}, vx: ${cv.verticalX?.let { num(it) } ?: "null"} }"
        }.joinToString(",\n    ")
        val points = g.points.joinToString(", ") { "{ x: ${num(it.x)}, y: ${num(it.y)}, label: ${jsString(it.label)} }" }
        val intervals = g.intervals.joinToString(", ") {
            "{ a: ${num(it.from)}, b: ${num(it.to)}, ca: ${it.closedFrom}, cb: ${it.closedTo} }"
        }
        val shade = if (g.shadeFrom != null && g.shadeTo != null) "[${num(g.shadeFrom)}, ${num(g.shadeTo)}]" else "null"
        val legend = g.curves.mapIndexed { i, cv ->
            "<div><span class=\"sw\" style=\"background:${palette[i % palette.size]}\"></span>\\(${esc(cv.label)}\\)</div>"
        }.joinToString("")
        return """
<div class="card">
  <div class="plotwrap">
    <canvas id="plot" class="plot"></canvas>
    <div class="zoom"><button id="zin">+</button><button id="zout">&minus;</button><button id="zreset">&#8634;</button></div>
  </div>
  <div class="legend">$legend</div>
  <div class="hint">Drag to move · pinch or +/− to zoom · double-tap to reset</div>
</div>
<script>
  plotGraph(document.getElementById("plot"), {
    curves: [
    $curves
    ],
    points: [$points],
    shade: $shade,
    intervals: [$intervals],
    x0: ${num(g.xMin)}, x1: ${num(g.xMax)}
  }, { text: ${jsString(c.text)}, muted: ${jsString(c.muted)}, grid: ${jsString(c.outline + "66")},
       axis: ${jsString(c.muted)}, band: ${jsString(c.accent2)}, bg: ${jsString(c.card)} },
     { zin: document.getElementById("zin"), zout: document.getElementById("zout"), reset: document.getElementById("zreset") });
</script>"""
    }
}
