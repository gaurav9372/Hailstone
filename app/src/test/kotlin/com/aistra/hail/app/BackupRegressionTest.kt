package com.aistra.hail.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupRegressionTest {
    private fun list(id: String = "one") = JSONObject()
        .put("id", id).put("name", "My apps")
        .put("packages", JSONArray().put("example.app"))

    private fun backup(lists: JSONArray) = JSONObject().put("backup_version", 1)
        .put("data", JSONObject().put("lists", JSONObject().put("freezer_lists", lists)))
        .toString()

    @Test fun validListsRoundTripWithoutAnAndroidApplication() {
        val parsed = BackupManager.parseBackup(backup(JSONArray().put(list())))
        assertNotNull(parsed)
        assertEquals(listOf("example.app"), parsed!!.lists!!.single().packages)
    }

    @Test fun malformedRecordRejectsTheWholeBackup() {
        assertNull(BackupManager.parseBackup(backup(JSONArray().put(list()).put("damaged"))))
    }

    @Test fun missingPackagesCannotBecomeAnEmptyReplacementList() {
        val damaged = list().apply { remove("packages") }
        assertNull(BackupManager.parseBackup(backup(JSONArray().put(list()).put(damaged))))
    }

    @Test fun wrongPackageTypesAndDuplicateIdsAreRejected() {
        val damaged = list("two").put("packages", JSONArray().put(42))
        assertNull(BackupManager.parseBackup(backup(JSONArray().put(list()).put(damaged))))
        assertNull(BackupManager.parseBackup(backup(JSONArray().put(list()).put(list()))))
    }

    @Test fun malformedModeRejectsEvenWhenAnotherSectionIsValid() {
        val root = JSONObject(backup(JSONArray().put(list())))
        root.getJSONObject("data").put("modes", JSONArray()
            .put(JSONObject().put("id", "m").put("name", "Mode")
                .put("excluded_packages", JSONArray()))
            .put(JSONObject().put("id", "bad").put("excluded_packages", JSONArray())))
        assertNull(BackupManager.parseBackup(root.toString()))
    }

    @Test fun incorrectSectionTypesAndFutureVersionsAreRejected() {
        assertNull(BackupManager.parseBackup("""{"data":{"lists":"broken"}}"""))
        assertNull(BackupManager.parseBackup("""{"data":{"modes":{}}}"""))
        assertNull(BackupManager.parseBackup("""{"backup_version":999,"data":{"settings":{}}}"""))
    }

    @Test fun importedTypeHintsCannotChangeStoredPreferenceTypes() {
        val root = JSONObject().put("data", JSONObject().put("settings", JSONObject()
            .put("values", JSONObject().put("app_theme", "theme_dark")
                .put("auto_freeze_after_lock", "true").put("home_font_size_f", 14))
            .put("types", JSONObject().put("app_theme", "int")
                .put("auto_freeze_after_lock", "string").put("home_font_size_f", "int"))))
        val settings = BackupManager.parseBackup(root.toString())!!.settings!!
        assertEquals("theme_dark", settings["app_theme"])
        assertEquals(true, settings["auto_freeze_after_lock"])
        assertEquals(14f, settings["home_font_size_f"])
    }

    @Test fun invalidSettingsRejectBeforeAnyRestoreMutation() {
        for ((key, value) in listOf(
            "app_theme" to 7, "tile_action" to "unknown", "biometric_login" to 1,
            "auto_freeze_delay_f" to -1, "home_font_size_f" to "NaN",
            "working_mode" to "shizuku_invalid"
        )) {
            val root = JSONObject().put("settings", JSONObject().put(key, value))
            assertNull("Should reject $key", BackupManager.parseBackup(root.toString()))
        }
    }

    @Test fun unrelatedPreferenceStateIsIgnored() {
        val normalized = BackupSettings.normalize(mapOf("unknown_key" to listOf("arbitrary")))
        assertTrue(normalized.isEmpty())
    }
}
