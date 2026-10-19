package com.aistra.hail.app

import android.content.pm.PackageManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.utils.*
import com.rosan.dhizuku.api.Dhizuku
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupPayload(
    val version: Int = 1,
    val appVersion: String,
    val timestamp: Long,
    val lists: List<FreezerList>?,
    val checkedApps: List<AppInfo>?,
    val tags: List<Pair<String, Int>>?,
    val modes: List<AppMode>?,
    val settings: Map<String, Any?>?,
    val settingsTypes: Map<String, String>?
) {
    val listsCount: Int get() = lists?.size ?: 0
    val totalAppsInLists: Int get() = lists?.sumOf { it.packages.size } ?: 0
    val modesCount: Int get() = modes?.size ?: 0
    val settingsCount: Int get() = settings?.size ?: 0

    val hasLists: Boolean get() = lists != null && lists.isNotEmpty()
    val hasModes: Boolean get() = modes != null && modes.isNotEmpty()
    val hasSettings: Boolean get() = settings != null && settings.isNotEmpty()
}

data class LocalBackupItem(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val payload: BackupPayload?
)

object BackupManager {
    private const val BACKUP_VERSION = 1
    private const val KEY_BACKUP_VERSION = "backup_version"
    private const val KEY_APP_VERSION = "app_version"
    private const val KEY_TIMESTAMP = "timestamp"
    private const val KEY_DATA = "data"
    private const val KEY_LISTS = "lists"
    private const val KEY_FREEZER_LISTS = "freezer_lists"
    private const val KEY_CHECKED_APPS = "checked_apps"
    private const val KEY_TAGS = "tags"
    private const val KEY_MODES = "modes"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_VALUES = "values"
    private const val KEY_TYPES = "types"

    private val backupsDir: File
        get() = File(app.filesDir, "backups").apply { if (!exists()) mkdirs() }

    private val cacheDir: File
        get() = File(app.cacheDir, "backups").apply { if (!exists()) mkdirs() }

    fun createBackupJson(
        includeLists: Boolean,
        includeModes: Boolean,
        includeSettings: Boolean
    ): String {
        val root = JSONObject()
        root.put(KEY_BACKUP_VERSION, BACKUP_VERSION)
        root.put(KEY_APP_VERSION, HailData.VERSION)
        root.put(KEY_TIMESTAMP, System.currentTimeMillis())

        val dataObj = JSONObject()

        if (includeLists) {
            val listsObj = JSONObject()
            val freezerListsArray = JSONArray()
            FreezerData.lists.forEach { list ->
                freezerListsArray.put(
                    JSONObject()
                        .put("id", list.id)
                        .put("name", list.name)
                        .put("packages", JSONArray(list.packages.distinct()))
                )
            }
            listsObj.put(KEY_FREEZER_LISTS, freezerListsArray)

            val checkedAppsArray = JSONArray()
            HailData.checkedList.forEach { appInfo ->
                checkedAppsArray.put(
                    JSONObject()
                        .put("package", appInfo.packageName)
                        .put("pinned", appInfo.pinned)
                        .put("whitelisted", appInfo.whitelisted)
                        .put("tags", JSONArray(appInfo.tagIdList.distinct()))
                )
            }
            listsObj.put(KEY_CHECKED_APPS, checkedAppsArray)

            val tagsArray = JSONArray()
            HailData.tags.forEach { tag ->
                tagsArray.put(
                    JSONObject()
                        .put("tag", tag.first)
                        .put("id", tag.second)
                )
            }
            listsObj.put(KEY_TAGS, tagsArray)

            dataObj.put(KEY_LISTS, listsObj)
        }

        if (includeModes) {
            val modesObj = JSONObject()
            val modesArray = JSONArray()
            ModeData.modes.forEach { mode ->
                modesArray.put(
                    JSONObject()
                        .put("id", mode.id)
                        .put("name", mode.name)
                        .put("excluded_packages", JSONArray(mode.excludedPackages.distinct()))
                        .put("frozen_by_mode", JSONArray(mode.frozenByMode.distinct()))
                        .put("fallback_suspended_packages", JSONArray(mode.fallbackSuspendedPackages.distinct()))
                )
            }
            modesObj.put(KEY_MODES, modesArray)
            dataObj.put(KEY_MODES, modesObj)
        }

        if (includeSettings) {
            val settingsObj = JSONObject()
            val valuesObj = JSONObject()
            val typesObj = JSONObject()
            val sp = PreferenceManager.getDefaultSharedPreferences(app)

            for ((key, value) in sp.all) {
                when (value) {
                    is Boolean -> {
                        valuesObj.put(key, value)
                        typesObj.put(key, "boolean")
                    }
                    is Int -> {
                        valuesObj.put(key, value)
                        typesObj.put(key, "int")
                    }
                    is Long -> {
                        valuesObj.put(key, value)
                        typesObj.put(key, "long")
                    }
                    is Float -> {
                        valuesObj.put(key, value.toDouble())
                        typesObj.put(key, "float")
                    }
                    is String -> {
                        valuesObj.put(key, value)
                        typesObj.put(key, "string")
                    }
                    is Set<*> -> {
                        val arr = JSONArray()
                        value.forEach { if (it is String) arr.put(it) }
                        valuesObj.put(key, arr)
                        typesObj.put(key, "string_set")
                    }
                }
            }
            settingsObj.put(KEY_VALUES, valuesObj)
            settingsObj.put(KEY_TYPES, typesObj)
            dataObj.put(KEY_SETTINGS, settingsObj)
        }

        root.put(KEY_DATA, dataObj)
        return root.toString(2)
    }

    fun parseBackup(jsonString: String): BackupPayload? = runCatching {
        if (jsonString.length > 20 * 1024 * 1024) return null // Max 20MB payload to prevent OOM
        val root = JSONObject(jsonString)
        val version = root.optInt(KEY_BACKUP_VERSION, 1)
        require(version == BACKUP_VERSION) { "Unsupported backup version" }
        val appVersion = root.optString(KEY_APP_VERSION, "unknown")
        val timestamp = root.optLong(KEY_TIMESTAMP, System.currentTimeMillis())

        val dataObj = if (root.has(KEY_DATA)) root.getJSONObject(KEY_DATA) else root
        validateStructure(dataObj)

        var parsedLists: List<FreezerList>? = null
        var parsedCheckedApps: List<AppInfo>? = null
        var parsedTags: List<Pair<String, Int>>? = null

        val listsContainer = dataObj.opt(KEY_LISTS)
        if (listsContainer is JSONObject) {
            val freezerListsArray = listsContainer.optJSONArray(KEY_FREEZER_LISTS)
            if (freezerListsArray != null) {
                val list = mutableListOf<FreezerList>()
                for (i in 0 until freezerListsArray.length()) {
                    runCatching {
                        val item = freezerListsArray.getJSONObject(i)
                        val pkgs = item.optJSONArray("packages") ?: JSONArray()
                        val packageList = mutableListOf<String>()
                        for (p in 0 until pkgs.length()) {
                            pkgs.optString(p).takeIf { it.isNotBlank() }?.let { packageList.add(it) }
                        }
                        list.add(
                            FreezerList(
                                id = item.optString("id", System.currentTimeMillis().toString()),
                                name = item.optString("name", "List $i"),
                                packages = packageList.distinct().toMutableList()
                            )
                        )
                    }.getOrThrow()
                }
                parsedLists = list
            }

            val checkedArray = listsContainer.optJSONArray(KEY_CHECKED_APPS)
            if (checkedArray != null) {
                val apps = mutableListOf<AppInfo>()
                for (i in 0 until checkedArray.length()) {
                    runCatching {
                        val item = checkedArray.getJSONObject(i)
                        val tagsJson = item.optJSONArray("tags")
                        apps.add(
                            AppInfo(
                                packageName = item.getString("package"),
                                pinned = item.optBoolean("pinned"),
                                whitelisted = item.optBoolean("whitelisted"),
                                tagIdList = tagsJson?.let {
                                    MutableList(it.length()) { idx -> it.getInt(idx) }
                                } ?: mutableListOf()
                            )
                        )
                    }.getOrThrow()
                }
                parsedCheckedApps = apps
            }

            val tagsArray = listsContainer.optJSONArray(KEY_TAGS)
            if (tagsArray != null) {
                val tags = mutableListOf<Pair<String, Int>>()
                for (i in 0 until tagsArray.length()) {
                    runCatching {
                        val item = tagsArray.getJSONObject(i)
                        tags.add(item.getString("tag") to item.getInt("id"))
                    }.getOrThrow()
                }
                parsedTags = tags
            }
        } else if (listsContainer is JSONArray) {
            val list = mutableListOf<FreezerList>()
            for (i in 0 until listsContainer.length()) {
                runCatching {
                    val item = listsContainer.getJSONObject(i)
                    val pkgs = item.optJSONArray("packages") ?: JSONArray()
                    val packageList = mutableListOf<String>()
                    for (p in 0 until pkgs.length()) {
                        pkgs.optString(p).takeIf { it.isNotBlank() }?.let { packageList.add(it) }
                    }
                    list.add(
                        FreezerList(
                            id = item.optString("id", System.currentTimeMillis().toString()),
                            name = item.optString("name", "List $i"),
                            packages = packageList.distinct().toMutableList()
                        )
                    )
                }.getOrThrow()
            }
            parsedLists = list
        }

        var parsedModes: List<AppMode>? = null
        val modesContainer = dataObj.opt(KEY_MODES)
        val modesArray = when (modesContainer) {
            is JSONObject -> modesContainer.optJSONArray(KEY_MODES)
            is JSONArray -> modesContainer
            else -> null
        }
        if (modesArray != null) {
            val list = mutableListOf<AppMode>()
            for (i in 0 until modesArray.length()) {
                runCatching {
                    val item = modesArray.getJSONObject(i)
                    val excluded = item.optJSONArray("excluded_packages") ?: JSONArray()
                    val frozen = item.optJSONArray("frozen_by_mode") ?: JSONArray()
                    val fallback = item.optJSONArray("fallback_suspended_packages") ?: JSONArray()
                    list.add(
                        AppMode(
                            id = item.optString("id", System.currentTimeMillis().toString()),
                            name = item.optString("name", "Mode $i"),
                            excludedPackages = MutableList(excluded.length()) { excluded.getString(it) }.distinct().toMutableList(),
                            frozenByMode = MutableList(frozen.length()) { frozen.getString(it) }.distinct().toMutableList(),
                            fallbackSuspendedPackages = MutableList(fallback.length()) { fallback.getString(it) }.distinct().toMutableList()
                        )
                    )
                }.getOrThrow()
            }
            parsedModes = list
        }

        var parsedSettings: Map<String, Any?>? = null
        var parsedSettingsTypes: Map<String, String>? = null
        val settingsContainer = dataObj.opt(KEY_SETTINGS)
        if (settingsContainer is JSONObject) {
            val valuesObj = settingsContainer.optJSONObject(KEY_VALUES) ?: settingsContainer
            val typesObj = settingsContainer.optJSONObject(KEY_TYPES)
            val valuesMap = mutableMapOf<String, Any?>()
            val typesMap = mutableMapOf<String, String>()

            for (key in valuesObj.keys()) {
                if (key == KEY_TYPES || key == KEY_VALUES) continue
                val v = valuesObj.get(key)
                valuesMap[key] = if (v == JSONObject.NULL) null else v
            }
            if (typesObj != null) {
                for (key in typesObj.keys()) {
                    typesMap[key] = typesObj.getString(key)
                }
            }
            parsedSettings = BackupSettings.normalize(valuesMap)
            parsedSettingsTypes = typesMap
        }

        if (parsedLists == null && parsedModes == null && parsedSettings == null) {
            null
        } else {
            BackupPayload(
                version = version,
                appVersion = appVersion,
                timestamp = timestamp,
                lists = parsedLists,
                checkedApps = parsedCheckedApps,
                tags = parsedTags,
                modes = parsedModes,
                settings = parsedSettings,
                settingsTypes = parsedSettingsTypes
            )
        }
    }.getOrNull()

    /** Reject a damaged section as a whole; overwrite must never use a partial parse. */
    private fun validateStructure(data: JSONObject) {
        fun strings(item: JSONObject, key: String, required: Boolean = false) {
            if (!item.has(key) && !required) return
            val array = item.getJSONArray(key)
            for (index in 0 until array.length()) {
                val value = array.get(index)
                require(value is String && value.isNotBlank()) { "Invalid $key entry" }
            }
        }
        fun records(array: JSONArray, mode: Boolean) {
            val ids = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.get("id")
                require(id is String && id.isNotBlank() && ids.add(id)) { "Invalid or duplicate id" }
                require(item.get("name") is String && item.getString("name").isNotBlank())
                if (mode) {
                    strings(item, "excluded_packages", required = true)
                    strings(item, "frozen_by_mode")
                    strings(item, "fallback_suspended_packages")
                } else {
                    require(id != FreezerData.ID_ALL_FROZEN)
                    strings(item, "packages", required = true)
                }
            }
        }
        if (data.has(KEY_LISTS)) {
            when (val lists = data.get(KEY_LISTS)) {
                is JSONArray -> records(lists, mode = false)
                is JSONObject -> {
                    records(lists.getJSONArray(KEY_FREEZER_LISTS), mode = false)
                    if (lists.has(KEY_CHECKED_APPS)) {
                        val apps = lists.getJSONArray(KEY_CHECKED_APPS)
                        for (index in 0 until apps.length()) {
                            val item = apps.getJSONObject(index)
                            require(item.get("package") is String && item.getString("package").isNotBlank())
                            for (key in listOf("pinned", "whitelisted")) {
                                if (item.has(key)) require(item.get(key) is Boolean)
                            }
                            if (item.has("tags")) item.getJSONArray("tags").let { tags ->
                                for (tag in 0 until tags.length()) require(tags.get(tag) is Int)
                            }
                        }
                    }
                    if (lists.has(KEY_TAGS)) lists.getJSONArray(KEY_TAGS).let { tags ->
                        for (index in 0 until tags.length()) {
                            val item = tags.getJSONObject(index)
                            require(item.get("tag") is String && item.get("id") is Int)
                        }
                    }
                }
                else -> error("Invalid lists section")
            }
        }
        if (data.has(KEY_MODES)) {
            when (val modes = data.get(KEY_MODES)) {
                is JSONArray -> records(modes, mode = true)
                is JSONObject -> records(modes.getJSONArray(KEY_MODES), mode = true)
                else -> error("Invalid modes section")
            }
        }
        if (data.has(KEY_SETTINGS)) {
            val settings = data.getJSONObject(KEY_SETTINGS)
            if (settings.has(KEY_VALUES)) settings.getJSONObject(KEY_VALUES)
            if (settings.has(KEY_TYPES)) settings.getJSONObject(KEY_TYPES)
        }
    }

    @Synchronized
    fun restoreBackup(
        payload: BackupPayload,
        restoreLists: Boolean,
        restoreModes: Boolean,
        restoreSettings: Boolean,
        overwrite: Boolean
    ): Boolean {
        // Bug 3: Guard against restoring while a mode is active
        if ((restoreModes || (restoreLists && overwrite)) && ModeData.activeMode() != null) {
            HUI.showToast(R.string.msg_disable_mode_before_restore)
            return false
        }

        val validatedSettings = runCatching {
            if (restoreSettings) BackupSettings.normalize(payload.settings.orEmpty()) else emptyMap()
        }
            .getOrElse { return false }
        // Match the settings screen: changing mechanism requires disabling the active mode.
        if (ModeData.activeMode() != null &&
            validatedSettings[HailData.WORKING_MODE]?.let { it != HailData.workingMode } == true
        ) return false

        // Snapshot before the first mutation, including operations that can fail midway.
        val listsSnapshot = FreezerData.lists.map { it.copy(packages = it.packages.toMutableList()) }
        val modesSnapshot = ModeData.modesSnapshot()
        val appsSnapshot = HailData.checkedList.map {
            AppInfo(
                packageName = it.packageName,
                pinned = it.pinned,
                whitelisted = it.whitelisted,
                tagIdList = it.tagIdList.toMutableList()
            )
        }
        val tagsSnapshot = HailData.tags.toList()
        val prefsSnapshot = PreferenceManager.getDefaultSharedPreferences(app).all.toMap()

        val transaction = RestoreTransaction()

        return runCatching {
            if (restoreLists && payload.lists != null) {
                transaction.apply(rollback = { FreezerData.rollbackLists(listsSnapshot) }) {
                    check(FreezerData.restoreLists(payload.lists, overwrite)) { "Failed to restore freezer lists" }
                }
                if (payload.checkedApps != null) {
                    transaction.apply(rollback = { HailData.restoreApps(appsSnapshot, overwrite = true) }) {
                        HailData.restoreApps(payload.checkedApps, overwrite)
                    }
                }
                if (payload.tags != null) {
                    transaction.apply(rollback = { HailData.restoreTags(tagsSnapshot, overwrite = true) }) {
                        HailData.restoreTags(payload.tags, overwrite)
                    }
                }
            }

            if (restoreModes && payload.modes != null) {
                transaction.apply(rollback = { ModeData.rollbackModes(modesSnapshot) }) {
                    check(ModeData.restoreModes(payload.modes, overwrite)) { "Failed to restore modes" }
                }
            }

            if (restoreSettings && payload.settings != null) {
                transaction.apply(rollback = { rollbackSettings(prefsSnapshot) }) {
                    restoreSettings(validatedSettings)
                }
            }

            if (restoreLists) app.setAutoFreezeService()
            true
        }.getOrElse {
            transaction.rollback()
            runCatching { app.setAutoFreezeService() }
            false
        }
    }

    private fun rollbackSettings(prefsSnapshot: Map<String, *>) {
        val sp = PreferenceManager.getDefaultSharedPreferences(app)
        sp.edit(commit = true) {
            clear()
            for ((key, value) in prefsSnapshot) {
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        runCatching { app.setAppTheme(HailData.appTheme) }
        runCatching { app.setAutoFreezeService(HailData.autoFreezeAfterLock) }
        AppIconCache.clear()
        runCatching {
            HShortcuts.removeAllDynamicShortcuts()
            HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
        }
    }

    private fun restoreSettings(settings: Map<String, Any>) {
        val sp = PreferenceManager.getDefaultSharedPreferences(app)
        val editor = sp.edit()
        with(editor) {
            for ((key, value) in settings) {

                // Bug 1: Biometric Login Validation
                if (key == HailData.BIOMETRIC_LOGIN) {
                    val canBiometric = runCatching {
                        val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
                        BiometricManager.from(app).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
                    }.getOrDefault(false)

                    val wantsBiometric = when (value) {
                        is Boolean -> value
                        is String -> value.toBooleanStrictOrNull() ?: false
                        else -> false
                    }
                    putBoolean(key, wantsBiometric && canBiometric)
                    continue
                }

                // Bug 7: Working Mode Validation
                if (key == HailData.WORKING_MODE) {
                    val targetMode = value.toString()
                    val isValid = when {
                        targetMode == HailData.MODE_DEFAULT -> true
                        targetMode.startsWith(HailData.OWNER) -> HPolicy.isDeviceOwnerActive
                        targetMode.startsWith(HailData.SU) -> HShell.checkSU
                        targetMode.startsWith(HailData.SHIZUKU) -> runCatching {
                            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                        }.getOrDefault(false)
                        targetMode.startsWith(HailData.DHIZUKU) -> runCatching {
                            Dhizuku.init(app)
                            Dhizuku.isPermissionGranted()
                        }.getOrDefault(false)
                        targetMode.startsWith(HailData.ISLAND) -> runCatching {
                            if (targetMode == HailData.MODE_ISLAND_HIDE) HIsland.freezePermissionGranted()
                            else HIsland.suspendPermissionGranted()
                        }.getOrDefault(false)
                        targetMode.startsWith(HailData.PRIVAPP) -> HPackages.isPrivilegedApp(app.packageName)
                        else -> false
                    }
                    if (isValid) {
                        putString(key, targetMode)
                        if (targetMode.startsWith(HailData.DHIZUKU) && HTarget.O) {
                            runCatching { HDhizuku.setDelegatedScopes() }
                        }
                    } else {
                        putString(key, HailData.MODE_DEFAULT)
                    }
                    continue
                }

                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    else -> error("Unsupported preference: $key")
                }
            }
        }

        check(editor.commit()) { "Failed to persist settings" }

        if (settings.containsKey(HailData.APP_THEME)) {
            app.setAppTheme(HailData.appTheme)
        }
        if (settings.containsKey(HailData.AUTO_FREEZE_AFTER_LOCK)) {
            app.setAutoFreezeService(HailData.autoFreezeAfterLock)
        }
        if (settings.containsKey(HailData.ICON_PACK)) {
            AppIconCache.clear()
        }
        if (settings.containsKey(HailData.DYNAMIC_SHORTCUT_ACTION)) {
            HShortcuts.removeAllDynamicShortcuts()
            HShortcuts.addDynamicShortcutAction(HailData.dynamicShortcutAction)
        }
    }

    fun getLocalBackups(): List<LocalBackupItem> {
        val dir = backupsDir
        val files = dir.listFiles { file -> file.isFile && file.extension == "json" } ?: emptyArray()
        return files.sortedByDescending { it.lastModified() }.map { file ->
            val content = HFiles.read(file.absolutePath)
            val payload = content?.let { parseBackup(it) }
            LocalBackupItem(
                file = file,
                name = file.name,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                payload = payload
            )
        }
    }

    fun saveLocalBackup(jsonString: String): File {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(backupsDir, "Hailstone_Backup_$dateStr.json")
        HFiles.writeAtomic(file.absolutePath, jsonString)
        return file
    }

    fun deleteLocalBackup(file: File): Boolean {
        // Bug 10: Canonical path check to prevent path traversal
        val canonicalParent = backupsDir.canonicalFile
        val targetCanonical = file.canonicalFile
        if (targetCanonical.parentFile != canonicalParent) {
            return false
        }
        return file.delete()
    }

    fun getShareableBackupFile(jsonString: String, name: String? = null): File {
        val filename = name ?: "Hailstone_Backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json"
        val file = File(cacheDir, filename)
        HFiles.writeAtomic(file.absolutePath, jsonString)
        return file
    }

    fun generateBackupFileName(): String {
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "Hailstone_Backup_$dateStr.json"
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
        val pre = "KMGTPE"[exp - 1]
        return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
    }

    fun formatDate(timestamp: Long): String {
        return SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
