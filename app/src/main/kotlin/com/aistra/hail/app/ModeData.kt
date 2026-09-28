package com.aistra.hail.app

import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.utils.HFiles
import com.aistra.hail.utils.HUI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class AppMode(
    val id: String,
    var name: String,
    val excludedPackages: MutableList<String> = mutableListOf(),
    val frozenByMode: MutableList<String> = mutableListOf(),
    val fallbackSuspendedPackages: MutableList<String> = mutableListOf()
)

object ModeData {
    private const val KEY_MODES = "modes"
    private const val KEY_ACTIVE_MODE = "active_mode"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_EXCLUDED_PACKAGES = "excluded_packages"
    private const val KEY_FROZEN_BY_MODE = "frozen_by_mode"
    private const val KEY_FALLBACK_SUSPENDED_PACKAGES = "fallback_suspended_packages"
    private const val KEY_ACTIVE_WORKING_MODE = "active_working_mode"
    private const val KEY_TRANSACTION_STATE = "transaction_state"
    const val STATE_IDLE = "idle"
    const val STATE_ENABLING = "enabling"
    const val STATE_ACTIVE = "active"
    const val STATE_DISABLING = "disabling"
    private val dir = "${app.filesDir.path}/v1"
    private val path = "$dir/modes.json"
    private val backupPath = "$dir/modes.json.bak"
    private var canSave = true
    private var activeModeId: String? = null
    private var activeWorkingMode: String? = null
    private var transactionState: String = STATE_IDLE

    val modes: MutableList<AppMode> by lazy {
        mutableListOf<AppMode>().apply {
            val source = loadSource() ?: return@apply
            var loadFailed = false
            val root = runCatching { JSONObject(source) }.getOrElse {
                loadFailed = true
                JSONObject()
            }
            activeModeId = root.optString(KEY_ACTIVE_MODE).takeIf { it.isNotBlank() }
            activeWorkingMode = root.optString(KEY_ACTIVE_WORKING_MODE)
                .takeIf { it.isNotBlank() && it != "null" }
            transactionState = root.optString(KEY_TRANSACTION_STATE, STATE_IDLE)
            val json = root.optJSONArray(KEY_MODES) ?: JSONArray()
            for (index in 0 until json.length()) {
                runCatching {
                    val item = json.getJSONObject(index)
                    val excluded = item.optJSONArray(KEY_EXCLUDED_PACKAGES) ?: JSONArray()
                    val frozen = item.optJSONArray(KEY_FROZEN_BY_MODE) ?: JSONArray()
                    val fallbackSuspended =
                        item.optJSONArray(KEY_FALLBACK_SUSPENDED_PACKAGES) ?: JSONArray()
                    add(
                        AppMode(
                            id = item.getString(KEY_ID),
                            name = item.getString(KEY_NAME),
                            excludedPackages = MutableList(excluded.length()) { excluded.getString(it) },
                            frozenByMode = MutableList(frozen.length()) { frozen.getString(it) },
                            fallbackSuspendedPackages = MutableList(fallbackSuspended.length()) {
                                fallbackSuspended.getString(it)
                            }
                        )
                    )
                }.onFailure { loadFailed = true }
            }
            if (activeModeId !in map { it.id }) activeModeId = null
            if (activeModeId == null) {
                activeWorkingMode = null
                transactionState = STATE_IDLE
            } else if (activeWorkingMode == null) {
                // Backward-compatible migration for modes created before mechanism tracking.
                activeWorkingMode = HailData.workingMode
                transactionState = STATE_ACTIVE
            }
            if (loadFailed) {
                runCatching {
                    File(path).copyTo(File("$path.corrupt-${System.currentTimeMillis()}"), overwrite = false)
                }
                // Never overwrite a syntactically valid file after dropping a malformed
                // entry. Preserve it for explicit recovery instead of silently losing modes.
                canSave = false
            }
        }
    }

    @Synchronized fun activeMode(): AppMode? {
        modes
        return activeModeId?.let(::findMode)
    }

    @Synchronized fun activeWorkingMode(): String? {
        modes
        return activeWorkingMode
    }

    @Synchronized fun transactionState(): String {
        modes
        return transactionState
    }

    @Synchronized fun isActive(id: String): Boolean {
        modes
        return activeModeId == id
    }

    @Synchronized fun shouldRemainFrozen(packageName: String): Boolean {
        val mode = activeMode() ?: return false
        return transactionState != STATE_DISABLING && packageName !in mode.excludedPackages
    }

    @Synchronized fun findMode(id: String): AppMode? = modes.firstOrNull { it.id == id }

    @Synchronized
    fun isOwnedByActiveMode(packageName: String): Boolean =
        activeMode()?.frozenByMode?.contains(packageName) == true

    @Synchronized
    fun modesSnapshot(): List<AppMode> = modes.map { mode ->
        mode.copy(
            excludedPackages = mode.excludedPackages.toMutableList(),
            frozenByMode = mode.frozenByMode.toMutableList(),
            fallbackSuspendedPackages = mode.fallbackSuspendedPackages.toMutableList()
        )
    }

    @Synchronized fun createMode(name: String): AppMode = AppMode(
        id = System.currentTimeMillis().toString(),
        name = name
    ).also {
        modes.add(it)
        save()
    }

    @Synchronized
    fun renameMode(id: String, name: String): Boolean {
        val mode = findMode(id) ?: return false
        val previous = mode.name
        mode.name = name
        return save().also { saved -> if (!saved) mode.name = previous }
    }

    @Synchronized fun replaceExcludedPackages(id: String, packages: Collection<String>): Boolean {
        val mode = findMode(id) ?: return false
        val previous = mode.excludedPackages.toList()
        mode.excludedPackages.clear()
        mode.excludedPackages.addAll(packages.distinct())
        return save().also { saved ->
            if (!saved) {
                mode.excludedPackages.clear()
                mode.excludedPackages.addAll(previous)
            }
        }
    }

    @Synchronized fun setActive(
        id: String?,
        frozenByMode: Collection<String> = emptyList(),
        fallbackSuspendedPackages: Collection<String> = emptyList()
    ): Boolean {
        val previousId = activeModeId
        val previousWorkingMode = activeWorkingMode
        val previousState = transactionState
        val snapshots = modes.associate { it.id to it.frozenByMode.toList() }
        val fallbackSnapshots = modes.associate { it.id to it.fallbackSuspendedPackages.toList() }
        modes.forEach {
            it.frozenByMode.clear()
            it.fallbackSuspendedPackages.clear()
        }
        activeModeId = id
        activeWorkingMode = id?.let { HailData.workingMode }
        transactionState = if (id == null) STATE_IDLE else STATE_ACTIVE
        id?.let(::findMode)?.frozenByMode?.addAll(frozenByMode.distinct())
        id?.let(::findMode)?.fallbackSuspendedPackages?.addAll(
            fallbackSuspendedPackages.distinct().filter { it in frozenByMode }
        )
        return save().also { saved ->
            if (!saved) {
                activeModeId = previousId
                activeWorkingMode = previousWorkingMode
                transactionState = previousState
                modes.forEach { mode ->
                    mode.frozenByMode.clear()
                    mode.frozenByMode.addAll(snapshots[mode.id].orEmpty())
                    mode.fallbackSuspendedPackages.clear()
                    mode.fallbackSuspendedPackages.addAll(fallbackSnapshots[mode.id].orEmpty())
                }
            }
        }
    }

    @Synchronized fun beginActivation(id: String, workingMode: String, candidatePackages: Collection<String>): Boolean =
        updateTransaction(id, workingMode, STATE_ENABLING, candidatePackages)

    @Synchronized fun finishActivation(
        id: String,
        workingMode: String,
        ownedPackages: Collection<String>,
        fallbackSuspendedPackages: Collection<String> = emptyList()
    ): Boolean = updateTransaction(
        id,
        workingMode,
        STATE_ACTIVE,
        ownedPackages,
        fallbackSuspendedPackages
    )

    @Synchronized fun beginDisabling(): Boolean {
        modes
        val id = activeModeId ?: return true
        return updateTransaction(
            id,
            activeWorkingMode ?: HailData.workingMode,
            STATE_DISABLING,
            findMode(id)?.frozenByMode.orEmpty(),
            findMode(id)?.fallbackSuspendedPackages.orEmpty()
        )
    }

    @Synchronized fun clearActivation(): Boolean = updateTransaction(null, null, STATE_IDLE, emptyList())

    @Synchronized
    fun releaseOwnership(packageName: String) {
        val mode = activeMode() ?: return
        if (mode.frozenByMode.remove(packageName)) {
            mode.fallbackSuspendedPackages.remove(packageName)
            save()
        }
    }

    @Synchronized private fun updateTransaction(
        id: String?,
        workingMode: String?,
        state: String,
        ownedPackages: Collection<String>,
        fallbackSuspendedPackages: Collection<String> = emptyList()
    ): Boolean {
        val previousId = activeModeId
        val previousWorkingMode = activeWorkingMode
        val previousState = transactionState
        val snapshots = modes.associate { it.id to it.frozenByMode.toList() }
        val fallbackSnapshots = modes.associate { it.id to it.fallbackSuspendedPackages.toList() }
        modes.forEach {
            it.frozenByMode.clear()
            it.fallbackSuspendedPackages.clear()
        }
        activeModeId = id
        activeWorkingMode = workingMode
        transactionState = state
        id?.let(::findMode)?.frozenByMode?.addAll(ownedPackages.distinct())
        id?.let(::findMode)?.fallbackSuspendedPackages?.addAll(
            fallbackSuspendedPackages.distinct().filter { it in ownedPackages }
        )
        return save().also { saved ->
            if (!saved) {
                activeModeId = previousId
                activeWorkingMode = previousWorkingMode
                transactionState = previousState
                modes.forEach { mode ->
                    mode.frozenByMode.clear()
                    mode.frozenByMode.addAll(snapshots[mode.id].orEmpty())
                    mode.fallbackSuspendedPackages.clear()
                    mode.fallbackSuspendedPackages.addAll(fallbackSnapshots[mode.id].orEmpty())
                }
            }
        }
    }

    @Synchronized fun deleteMode(id: String): Boolean {
        if (isActive(id)) return false
        val index = modes.indexOfFirst { it.id == id }
        if (index < 0) return false
        val removed = modes.removeAt(index)
        return save().also { saved -> if (!saved) modes.add(index, removed) }
    }

    @Synchronized fun save(): Boolean {
        if (!canSave) {
            HUI.showToast(R.string.msg_modes_save_failed)
            return false
        }
        if (!HFiles.exists(dir)) HFiles.createDirectories(dir)
        val root = JSONObject()
            .put(KEY_ACTIVE_MODE, activeModeId ?: JSONObject.NULL)
            .put(KEY_ACTIVE_WORKING_MODE, activeWorkingMode ?: JSONObject.NULL)
            .put(KEY_TRANSACTION_STATE, transactionState)
            .put(KEY_MODES, JSONArray().apply {
                modes.forEach { mode ->
                    put(
                        JSONObject()
                            .put(KEY_ID, mode.id)
                            .put(KEY_NAME, mode.name)
                            .put(KEY_EXCLUDED_PACKAGES, JSONArray(mode.excludedPackages))
                            .put(KEY_FROZEN_BY_MODE, JSONArray(mode.frozenByMode))
                            .put(
                                KEY_FALLBACK_SUSPENDED_PACKAGES,
                                JSONArray(mode.fallbackSuspendedPackages)
                            )
                    )
                }
            })
        val existing = HFiles.read(path)
        if (existing != null && runCatching { JSONObject(existing) }.isSuccess) {
            HFiles.writeAtomic(backupPath, existing)
        }
        return HFiles.writeAtomic(path, root.toString()).also { saved ->
            if (!saved) HUI.showToast(R.string.msg_modes_save_failed)
        }
    }

    private fun loadSource(): String? {
        val primary = HFiles.read(path)
        if (primary != null && runCatching { JSONObject(primary) }.isSuccess) return primary
        val backup = HFiles.read(backupPath)
        if (backup != null && runCatching { JSONObject(backup) }.isSuccess) return backup
        return primary
    }
}
