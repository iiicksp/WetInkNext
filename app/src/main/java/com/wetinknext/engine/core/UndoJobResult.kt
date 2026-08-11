package com.wetinknext.engine.core

import com.wetinknext.engine.undo.UndoEntry

/** Terminal outcome of one tile-compression task. */
sealed interface UndoJobResult {
    val epoch: Long
    val sequence: Long

    data class Ready(
        override val epoch: Long,
        override val sequence: Long,
        val entry: UndoEntry,
    ) : UndoJobResult

    data class Failed(
        override val epoch: Long,
        override val sequence: Long,
        val error: Throwable,
    ) : UndoJobResult
}
