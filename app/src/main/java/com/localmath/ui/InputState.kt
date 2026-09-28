package com.localmath.ui

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** What's typed in the input box, plus where the cursor sits (0..text.length). */
data class InputState(val text: String = "", val cursor: Int = 0) {

    fun insert(s: String) = InputState(
        text.substring(0, cursor) + s + text.substring(cursor),
        cursor + s.length
    )

    fun backspace() =
        if (cursor == 0) this
        else InputState(text.removeRange(cursor - 1, cursor), cursor - 1)

    fun left() = copy(cursor = (cursor - 1).coerceAtLeast(0))
    fun right() = copy(cursor = (cursor + 1).coerceAtMost(text.length))
    fun clear() = InputState()

    companion object {
        fun of(text: String) = InputState(text, text.length)

        /** Lets rememberSaveable keep the input across rotation / process death. */
        val Saver: Saver<InputState, Any> = listSaver(
            save = { listOf(it.text, it.cursor) },
            restore = { InputState(it[0] as String, it[1] as Int) }
        )
    }
}
