package com.wetinknext.engine.undo

/**
 * Pure memory-bounded history. GPU reads and texture restores deliberately live
 * in EngineRenderer, keeping this class deterministic and unit-testable.
 */
class UndoManager(
    private val maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    private val undoStack = ArrayDeque<UndoEntry>()
    private val redoStack = ArrayDeque<UndoEntry>()
    private var undoMemoryBytes = 0L
    private var redoMemoryBytes = 0L

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val memoryBytes: Long get() = undoMemoryBytes + redoMemoryBytes
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    /** Records a new operation and invalidates the alternate redo history. */
    fun push(entry: UndoEntry) {
        undoStack.addLast(entry)
        undoMemoryBytes += entry.memorySize
        clearRedo()
        trimToLimits()
    }

    /** Returns the candidate without changing history; restore it before committing. */
    fun peekUndo(): UndoEntry? = undoStack.lastOrNull()

    /** Moves the exact current undo candidate to redo after a successful restore. */
    fun commitUndo(entry: UndoEntry): Boolean {
        if (undoStack.lastOrNull() !== entry) return false
        undoStack.removeLast()
        undoMemoryBytes -= entry.memorySize
        redoStack.addLast(entry)
        redoMemoryBytes += entry.memorySize
        trimToLimits()
        return true
    }

    /** Discards a no-longer-restorable undo record without moving it to redo. */
    fun dropUndo(entry: UndoEntry): Boolean {
        if (undoStack.lastOrNull() !== entry) return false
        undoStack.removeLast()
        undoMemoryBytes -= entry.memorySize
        entry.dispose()
        return true
    }

    /** Returns the candidate without changing history; restore it before committing. */
    fun peekRedo(): UndoEntry? = redoStack.lastOrNull()

    /** Moves the exact current redo candidate to undo after a successful restore. */
    fun commitRedo(entry: UndoEntry): Boolean {
        if (redoStack.lastOrNull() !== entry) return false
        redoStack.removeLast()
        redoMemoryBytes -= entry.memorySize
        undoStack.addLast(entry)
        undoMemoryBytes += entry.memorySize
        trimToLimits()
        return true
    }

    /** Discards a no-longer-restorable redo record without moving it to undo. */
    fun dropRedo(entry: UndoEntry): Boolean {
        if (redoStack.lastOrNull() !== entry) return false
        redoStack.removeLast()
        redoMemoryBytes -= entry.memorySize
        entry.dispose()
        return true
    }

    fun clearRedo() {
        while (redoStack.isNotEmpty()) {
            val entry = redoStack.removeLast()
            redoMemoryBytes -= entry.memorySize
            entry.dispose()
        }
        redoMemoryBytes = 0L
    }

    fun clear() {
        while (undoStack.isNotEmpty()) undoStack.removeLast().dispose()
        undoMemoryBytes = 0L
        clearRedo()
    }

    /** Removes history that can no longer be restored after deleting a layer. */
    fun removeEntriesForLayer(layerId: Long) {
        removeEntriesForLayer(undoStack, layerId) { entry -> undoMemoryBytes -= entry.memorySize }
        removeEntriesForLayer(redoStack, layerId) { entry -> redoMemoryBytes -= entry.memorySize }
        undoMemoryBytes = undoMemoryBytes.coerceAtLeast(0L)
        redoMemoryBytes = redoMemoryBytes.coerceAtLeast(0L)
    }

    private fun removeEntriesForLayer(
        stack: ArrayDeque<UndoEntry>,
        layerId: Long,
        subtractMemory: (UndoEntry) -> Unit,
    ) {
        if (stack.isEmpty()) return
        val retained = ArrayDeque<UndoEntry>(stack.size)
        while (stack.isNotEmpty()) {
            val entry = stack.removeFirst()
            if (entry.layerId == layerId) {
                subtractMemory(entry)
                entry.dispose()
            } else {
                retained.addLast(entry)
            }
        }
        stack.addAll(retained)
    }

    private fun trimToLimits() {
        while (undoStack.size > maxSteps) evictOldestUndo()
        while (redoStack.size > maxSteps) evictOldestRedo()
        while (memoryBytes > maxMemoryBytes && undoStack.size + redoStack.size > 1) {
            when {
                redoStack.size > 1 && redoMemoryBytes >= undoMemoryBytes -> evictOldestRedo()
                undoStack.size > 1 -> evictOldestUndo()
                redoStack.size > 1 -> evictOldestRedo()
                else -> return
            }
        }
    }

    private fun evictOldestUndo() {
        val entry = undoStack.removeFirstOrNull() ?: return
        undoMemoryBytes -= entry.memorySize
        entry.dispose()
    }

    private fun evictOldestRedo() {
        val entry = redoStack.removeFirstOrNull() ?: return
        redoMemoryBytes -= entry.memorySize
        entry.dispose()
    }

    companion object {
        const val DEFAULT_MAX_MEMORY_BYTES = 96L * 1024L * 1024L
        const val DEFAULT_MAX_STEPS = 50
    }
}
