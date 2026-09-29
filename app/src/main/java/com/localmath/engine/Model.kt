package com.localmath.engine

/**
 * One explanation step.
 * [title] and [note] are plain text that may contain inline math as \( … \); [math] is display LaTeX.
 */
data class Step(val title: String, val math: String, val note: String? = null)

data class Solution(
    val kind: String,            // e.g. "Linear equation"
    val steps: List<Step>,
    val answer: String,          // LaTeX
    val approx: String? = null,  // LaTeX, shown under the answer
    val graph: Graph? = null,
    val diagram: VectorDiagram? = null   // vector problems: the head-to-tail picture
)

/** Thrown for anything the user typed that the engine can't handle; the message is shown in the UI. */
open class MathError(message: String) : Exception(message)
