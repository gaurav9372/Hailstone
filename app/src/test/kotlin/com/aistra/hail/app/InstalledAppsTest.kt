package com.aistra.hail.app

import android.content.pm.ApplicationInfo
import com.aistra.hail.utils.HPackages
import org.junit.Assert.*
import org.junit.Test

class InstalledAppsTest {
    @Test fun removedAppsWithRetainedMetadataAreNotInstalled() {
        assertFalse(HPackages.isInstalled(null))
        assertFalse(HPackages.isInstalled(0))
        assertFalse(HPackages.isInstalled(ApplicationInfo.FLAG_SYSTEM))
        assertFalse(HPackages.isInstalled(ApplicationInfo.FLAG_STOPPED or ApplicationInfo.FLAG_SUSPENDED))
    }

    @Test fun freezingDoesNotRemoveAnInstalledAppFromTheList() {
        assertTrue(HPackages.isInstalled(ApplicationInfo.FLAG_INSTALLED))
        assertTrue(HPackages.isInstalled(ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_STOPPED))
        assertTrue(HPackages.isInstalled(ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SUSPENDED))
        assertTrue(HPackages.isInstalled(ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_SYSTEM))
    }

}
