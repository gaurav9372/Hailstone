package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class ModeMutationTrackerTest {
    @Test fun aSlowBatchDoesNotExpireItsOwnOwnershipProtection() {
        var now = 0L
        val tracker = ModeMutationTracker(10_000) { now }
        tracker.begin()
        tracker.mark(listOf("early", "late"))
        now = 120_000
        assertTrue(tracker.isInternalMutation("late"))
        tracker.end()
        now += 9_999
        assertTrue(tracker.isInternalMutation("late"))
        now += 2
        assertFalse(tracker.isInternalMutation("late"))
    }

    @Test fun oneCompletedOperationCannotEndAnotherOperationsProtection() {
        var now = 0L
        val tracker = ModeMutationTracker(10_000) { now }
        tracker.begin()
        tracker.begin()
        tracker.mark(listOf("app"))
        tracker.end()
        now = 100_000
        assertTrue(tracker.isInternalMutation("app"))
        tracker.end()
        now += 10_001
        assertFalse(tracker.isInternalMutation("app"))
    }

    @Test fun externalChangesOutsideAnOperationAreNotSuppressed() {
        val tracker = ModeMutationTracker(10_000) { 42L }
        assertFalse(tracker.isInternalMutation("external"))
        tracker.mark(listOf("ours"))
        assertFalse(tracker.isInternalMutation("external"))
    }
}
