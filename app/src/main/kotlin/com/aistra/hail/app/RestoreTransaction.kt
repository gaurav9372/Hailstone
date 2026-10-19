package com.aistra.hail.app

/** Roll back attempted sections too: a failed apply may already have changed memory or disk. */
internal class RestoreTransaction {
    private val rollbacks = mutableListOf<() -> Unit>()

    fun apply(rollback: () -> Unit, change: () -> Unit) {
        rollbacks.add(rollback)
        change()
    }

    fun rollback() {
        rollbacks.asReversed().forEach { undo -> runCatching(undo) }
        rollbacks.clear()
    }
}
