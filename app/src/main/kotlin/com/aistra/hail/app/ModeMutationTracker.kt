package com.aistra.hail.app

/** Keep ownership while our operation is running, then allow time for queued broadcasts. */
internal class ModeMutationTracker(private val graceMillis: Long, private val now: () -> Long) {
    private var operations = 0
    private val recent = mutableMapOf<String, Long>()

    @Synchronized fun begin() { operations++ }

    @Synchronized fun mark(packages: Collection<String>) {
        val expiry = now() + graceMillis
        packages.forEach { recent[it] = expiry }
    }

    @Synchronized fun end() {
        val expiry = now() + graceMillis
        recent.keys.toList().forEach { recent[it] = expiry }
        operations--
    }

    @Synchronized fun isInternalMutation(packageName: String): Boolean {
        if (operations > 0) return true
        val expiry = recent[packageName] ?: return false
        if (expiry >= now()) return true
        recent.remove(packageName)
        return false
    }
}
