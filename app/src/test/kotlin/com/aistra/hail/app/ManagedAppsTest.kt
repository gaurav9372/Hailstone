package com.aistra.hail.app

import org.junit.Assert.*
import org.junit.Test

class ManagedAppsTest {
    @Test fun appsSelectedInFreezerAreAutomationTargetsOnFreshInstalls() {
        val targets = HailData.managedApps(listOf("example.one", "example.two"), emptyList())
        assertEquals(listOf("example.one", "example.two"), targets.map { it.packageName })
    }

    @Test fun legacySelectionsAndWhitelistMetadataSurviveTheUnion() {
        val legacy = AppInfo("example.legacy", pinned = true, whitelisted = true,
            tagIdList = mutableListOf(2, 3))
        val targets = HailData.managedApps(listOf("example.new", "example.legacy", "example.new"), listOf(legacy))
        assertEquals(2, targets.size)
        assertSame(legacy, targets.single { it.packageName == "example.legacy" })
        assertEquals(listOf(2, 3), targets.single { it.packageName == "example.legacy" }.tagIdList)
        assertTrue(targets.single { it.packageName == "example.legacy" }.whitelisted)
    }

    @Test fun emptySelectionDoesNotDiscoverAndManageUnselectedApps() {
        assertTrue(HailData.managedApps(emptyList(), emptyList()).isEmpty())
    }
}
