package com.localmath.ui

import com.localmath.engine.DStyle
import com.localmath.engine.Eng
import com.localmath.engine.VectorDiagram

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
        val diagram = s.diagram?.let { diagramHtml(it, c) } ?: ""

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
  svg.vec { width: 100%; height: auto; display: block; }
  svg.vec text { font-family: KaTeX_Math, "Times New Roman", serif; font-style: italic; }
  svg.vec text.tick { font-family: sans-serif; font-style: normal; }
</style>
</head><body>
$answer
$diagram
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

    // ================= vector diagrams (inline SVG, no script needed) =================

    private const val BLUE = "#3478e5"
    private const val RED = "#e5483f"

    private fun f(d: Double) = String.format(java.util.Locale.US, "%.1f", d)

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** "c_x = a_x + b_x" -> text with lowered, smaller subscripts. */
    private fun rich(t: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            if (t[i] == '_' && i + 1 < t.length) {
                val sub: String
                if (t[i + 1] == '{') {
                    val end = t.indexOf('}', i + 2).let { if (it < 0) t.length else it }
                    sub = t.substring(i + 2, end); i = end + 1
                } else { sub = t[i + 1].toString(); i += 2 }
                sb.append("<tspan dy=\"4\" font-size=\"10\">").append(xml(sub)).append("</tspan><tspan dy=\"-4\">\u200B</tspan>")
            } else {
                val next = t.indexOf('_', i).let { if (it < 0) t.length else it }
                sb.append(xml(t.substring(i, next))); i = next
            }
        }
        return sb.toString()
    }

    private fun niceStep(span: Double): Double {
        val raw = span / 6
        val p = Math.pow(10.0, Math.floor(Math.log10(raw)))
        return listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
    }

    /** The head-to-tail picture: given vectors blue, moved ones dark, the resultant red, dashed components. */
    fun diagramHtml(d: VectorDiagram, c: MathColors): String {
        fun color(st: DStyle) = when (st) { DStyle.INPUT -> BLUE; DStyle.RESULT -> RED; DStyle.MOVED -> c.text; DStyle.GUIDE -> c.muted }
        val xs = mutableListOf(0.0)
        val ys = mutableListOf(0.0)
        d.arrows.forEach { xs += listOf(it.x0, it.x1); ys += listOf(it.y0, it.y1) }
        d.dashes.forEach { xs += listOf(it.x0, it.x1); ys += listOf(it.y0, it.y1) }
        val ok = (xs + ys).all { it.isFinite() }
        if (!ok) return ""
        var x0 = xs.min(); var x1 = xs.max(); var y0 = ys.min(); var y1 = ys.max()
        if (x1 - x0 < 1e-9) { x0 -= 1; x1 += 1 }
        if (y1 - y0 < 1e-9) { y0 -= 1; y1 += 1 }
        val padX = (x1 - x0) * 0.08; val padY = (y1 - y0) * 0.08
        x0 -= padX; x1 += padX; y0 -= padY; y1 += padY
        val w = 360.0
        val m = 34.0
        val maxH = 330.0
        val scale = Math.min((w - 2 * m) / (x1 - x0), (maxH - 2 * m) / (y1 - y0))
        val h = Math.max(170.0, (y1 - y0) * scale + 2 * m)
        val offX = (w - (x1 - x0) * scale) / 2
        val offY = (h - (y1 - y0) * scale) / 2
        fun sx(x: Double) = offX + (x - x0) * scale
        fun sy(y: Double) = h - offY - (y - y0) * scale

        val out = StringBuilder()
        out.append("<svg class=\"vec\" viewBox=\"0 0 ${f(w)} ${f(h)}\" xmlns=\"http://www.w3.org/2000/svg\"><defs>")
        for (st in DStyle.values()) {
            out.append("<marker id=\"ah${st.ordinal}\" viewBox=\"0 0 10 10\" refX=\"9\" refY=\"5\" markerUnits=\"userSpaceOnUse\" " +
                "markerWidth=\"11\" markerHeight=\"11\" orient=\"auto\"><path d=\"M0,0 L10,5 L0,10 z\" fill=\"${color(st)}\"/></marker>")
        }
        out.append("</defs>")
        // Grid and axes.
        val step = niceStep(Math.max(x1 - x0, y1 - y0))
        // Tick numbers would collide with the component labels, so they only appear on unlabelled pictures.
        val showTicks = d.labels.isEmpty()
        val grid = c.outline + "55"
        var gx = Math.ceil(x0 / step) * step
        while (gx <= x1) {
            out.append("<line x1=\"${f(sx(gx))}\" y1=\"${f(sy(y0))}\" x2=\"${f(sx(gx))}\" y2=\"${f(sy(y1))}\" stroke=\"$grid\" stroke-width=\"1\"/>")
            if (showTicks && Math.abs(gx) > step / 2) out.append("<text class=\"tick\" x=\"${f(sx(gx))}\" y=\"${f(Math.min(h - 4, sy(0.0) + 13))}\" font-size=\"9\" fill=\"${c.muted}\" text-anchor=\"middle\">${com.localmath.engine.Graphs.short(gx)}</text>")
            gx += step
        }
        var gy = Math.ceil(y0 / step) * step
        while (gy <= y1) {
            out.append("<line x1=\"${f(sx(x0))}\" y1=\"${f(sy(gy))}\" x2=\"${f(sx(x1))}\" y2=\"${f(sy(gy))}\" stroke=\"$grid\" stroke-width=\"1\"/>")
            if (showTicks && Math.abs(gy) > step / 2) out.append("<text class=\"tick\" x=\"${f(Math.max(4.0, sx(0.0) - 4))}\" y=\"${f(sy(gy) + 3)}\" font-size=\"9\" fill=\"${c.muted}\" text-anchor=\"end\">${com.localmath.engine.Graphs.short(gy)}</text>")
            gy += step
        }
        out.append("<line x1=\"${f(sx(x0))}\" y1=\"${f(sy(0.0))}\" x2=\"${f(sx(x1))}\" y2=\"${f(sy(0.0))}\" stroke=\"${c.muted}\" stroke-width=\"1.3\"/>")
        out.append("<line x1=\"${f(sx(0.0))}\" y1=\"${f(sy(y0))}\" x2=\"${f(sx(0.0))}\" y2=\"${f(sy(y1))}\" stroke=\"${c.muted}\" stroke-width=\"1.3\"/>")
        out.append("<text x=\"${f(sx(x1) - 2)}\" y=\"${f(sy(0.0) - 5)}\" font-size=\"12\" fill=\"${c.muted}\" text-anchor=\"end\">x</text>")
        out.append("<text x=\"${f(sx(0.0) + 5)}\" y=\"${f(sy(y1) + 11)}\" font-size=\"12\" fill=\"${c.muted}\">y</text>")
        // Dashed component lines.
        for (sg in d.dashes) {
            out.append("<line x1=\"${f(sx(sg.x0))}\" y1=\"${f(sy(sg.y0))}\" x2=\"${f(sx(sg.x1))}\" y2=\"${f(sy(sg.y1))}\" " +
                "stroke=\"${color(sg.style)}\" stroke-width=\"1.4\" stroke-dasharray=\"5 4\"/>")
        }
        // Angle marks.
        for (a in d.arcs) {
            val r = 24.0
            val cx = sx(0.0); val cy = sy(0.0)
            val p0x = cx + r * Math.cos(a.from); val p0y = cy - r * Math.sin(a.from)
            val p1x = cx + r * Math.cos(a.to); val p1y = cy - r * Math.sin(a.to)
            val large = if (a.to - a.from > Math.PI) 1 else 0
            out.append("<path d=\"M${f(p0x)},${f(p0y)} A${f(r)},${f(r)} 0 $large 0 ${f(p1x)},${f(p1y)}\" fill=\"none\" stroke=\"${c.muted}\" stroke-width=\"1.3\"/>")
            val mid = (a.from + a.to) / 2
            out.append("<text x=\"${f(cx + (r + 11) * Math.cos(mid))}\" y=\"${f(cy - (r + 11) * Math.sin(mid) + 4)}\" font-size=\"13\" fill=\"${c.muted}\" text-anchor=\"middle\">${xml(a.label)}</text>")
        }
        // Boxes of text already placed, so later labels can move out of the way.
        val placed = mutableListOf<DoubleArray>()
        fun box(tx: Double, ty: Double, tw: Double, anchor: String) = when (anchor) {
            "start" -> doubleArrayOf(tx, ty - 11, tx + tw, ty + 4)
            "end" -> doubleArrayOf(tx - tw, ty - 11, tx, ty + 4)
            else -> doubleArrayOf(tx - tw / 2, ty - 11, tx + tw / 2, ty + 4)
        }
        fun hits(b: DoubleArray) = placed.any { o -> b[0] < o[2] && b[2] > o[0] && b[1] < o[3] && b[3] > o[1] }
        // Arrows (resultant last so it sits on top), each named at its middle with a bar over the name.
        for (ar in d.arrows.sortedBy { if (it.style == DStyle.RESULT) 1 else 0 }) {
            val ax = sx(ar.x0); val ay = sy(ar.y0); val bx = sx(ar.x1); val by = sy(ar.y1)
            val len = Math.hypot(bx - ax, by - ay)
            if (len < 1.0) continue
            val col = color(ar.style)
            val sw = if (ar.style == DStyle.RESULT) 2.8 else 2.4
            out.append("<line x1=\"${f(ax)}\" y1=\"${f(ay)}\" x2=\"${f(bx)}\" y2=\"${f(by)}\" stroke=\"$col\" stroke-width=\"$sw\" " +
                "stroke-linecap=\"round\" marker-end=\"url(#ah${ar.style.ordinal})\"/>")
            val name = ar.name ?: continue
            val side = if (ar.style == DStyle.RESULT) -1.0 else 1.0
            val nx = -(by - ay) / len * side
            val ny = (bx - ax) / len * side
            // Screen normal points left of the arrow; flip so labels sit above-left for inputs, below-right for the resultant.
            val tx = (ax + bx) / 2 + nx * -12
            val ty = (ay + by) / 2 + ny * -12 + 4
            placed += box(tx, ty, name.length * 7.5, "middle")
            out.append("<text x=\"${f(tx)}\" y=\"${f(ty)}\" font-size=\"14\" fill=\"$col\" text-anchor=\"middle\" text-decoration=\"overline\">${rich(name)}</text>")
        }
        // Component labels.
        for (lb in d.labels) {
            // Rough text width so labels near an edge are flipped or slid back inside.
            val plain = lb.text.replace(Regex("_\\{([^}]*)\\}|_(.)")) { mt -> mt.groupValues[1] + mt.groupValues[2] }
            val subs = lb.text.count { it == '_' }
            val tw = (plain.length - subs) * 6.6 + subs * 5.0
            fun place(align0: Int, dx: Double, dy: Double): Triple<Double, Double, Int> {
                var align = align0
                var tx = sx(lb.x) + dx
                val ty = sy(lb.y) + dy
                if (align == 1 && tx + tw > w - 2) { align = -1; tx = sx(lb.x) - dx }
                if (align == -1 && tx - tw < 2) { align = 1; tx = sx(lb.x) - dx }
                tx = when (align) {
                    1 -> tx.coerceIn(2.0, Math.max(2.0, w - 2 - tw))
                    -1 -> tx.coerceIn(Math.min(w - 2, tw + 2), w - 2.0)
                    else -> tx.coerceIn(tw / 2 + 2, Math.max(tw / 2 + 2, w - tw / 2 - 2))
                }
                return Triple(tx, ty.coerceIn(12.0, h - 4), align)
            }
            fun anchorOf(a: Int) = when (a) { -1 -> "end"; 1 -> "start"; else -> "middle" }
            // Try the chosen spot, the mirror image across the axis / line, then small nudges.
            val mirrorDy = if (lb.dy > 0) -8.0 else if (lb.dy < 0) 16.0 else 0.0
            val tries = listOf(
                place(lb.align, lb.dx, lb.dy),
                place(-lb.align, -lb.dx, if (lb.align == 0) mirrorDy else lb.dy),
                place(lb.align, lb.dx, lb.dy + 14), place(lb.align, lb.dx, lb.dy - 14),
                place(lb.align, lb.dx, lb.dy + 28), place(lb.align, lb.dx, lb.dy - 28)
            )
            val (tx, ty, align) = tries.firstOrNull { (x, y, a) -> !hits(box(x, y, tw, anchorOf(a))) } ?: tries[0]
            val anchor = anchorOf(align)
            placed += box(tx, ty, tw, anchor)
            out.append("<text x=\"${f(tx)}\" y=\"${f(ty)}\" font-size=\"12.5\" fill=\"${color(lb.style)}\" text-anchor=\"$anchor\">${rich(lb.text)}</text>")
        }
        out.append("</svg>")
        val legend = d.legend.joinToString("") { (st, text) ->
            "<div><span class=\"sw\" style=\"background:${color(st)}\"></span>${esc(text)}</div>"
        }
        return "<div class=\"card\">$out<div class=\"legend\">$legend</div></div>"
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
