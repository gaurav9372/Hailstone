package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class RestoreTransactionTest {
    @Test fun firstSectionFailureRestoresPartiallyMutatedState() {
        val state = mutableListOf("original")
        val transaction = RestoreTransaction()
        runCatching {
            transaction.apply(rollback = { state.clear(); state.add("original") }) {
                state.clear()
                state.add("partial import")
                error("disk full")
            }
        }.onFailure { transaction.rollback() }
        assertEquals(listOf("original"), state)
    }

    @Test fun settingsFailureAfterCommitStillRestoresEveryPreviousSection() {
        var lists = "old lists"
        var settings = "old settings"
        val transaction = RestoreTransaction()
        runCatching {
            transaction.apply(rollback = { lists = "old lists" }) { lists = "imported lists" }
            transaction.apply(rollback = { settings = "old settings" }) {
                settings = "imported settings"
                error("service start failed after committing preferences")
            }
        }.onFailure { transaction.rollback() }
        assertEquals("old lists", lists)
        assertEquals("old settings", settings)
    }

    @Test fun aRollbackFailureDoesNotPreventOtherSectionsFromRecovering() {
        var recovered = false
        val transaction = RestoreTransaction()
        transaction.apply(rollback = { recovered = true }) { }
        transaction.apply(rollback = { error("storage still unavailable") }) { }
        transaction.rollback()
        assertTrue(recovered)
    }

    @Test fun sectionsAreRolledBackInReverseOrder() {
        val restored = mutableListOf<String>()
        val transaction = RestoreTransaction()
        transaction.apply(rollback = { restored.add("lists") }) { }
        transaction.apply(rollback = { restored.add("settings") }) { }
        transaction.rollback()
        transaction.rollback()
        assertEquals(listOf("settings", "lists"), restored)
    }
}
