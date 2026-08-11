package com.wetinknext.engine.core

/** Pure epoch contract used to reject undo results from a previous document state. */
fun isUndoResultCurrent(
    resultEpoch: Long,
    currentEpoch: Long,
): Boolean = resultEpoch == currentEpoch
