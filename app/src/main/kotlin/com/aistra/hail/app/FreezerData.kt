package com.aistra.hail.app

import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.R
import com.aistra.hail.utils.HFiles
import com.aistra.hail.utils.HPackages
import com.aistra.hail.utils.HUI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class FreezerList(
    val id: String,
    var name: String,
    val packages: MutableList<String> = mutableListOf()
)

object FreezerData {
    const val ID_ALL_FROZEN = "all_frozen"
    const val ID_NO_CUSTOM_LIST = "no_custom_list"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_PACKAGES = "packages"
    private val dir = "${app.filesDir.path}/v1"
    private val path = "$dir/freezer_lists.json"
    private var canSave = true

    val lists: MutableList<FreezerList> by lazy {
        mutableListOf<FreezerList>().apply {
            val source = HFiles.read(path) ?: return@apply
            var loadFailed = false
            val json = runCatching { JSONArray(source) }.getOrElse {
                loadFailed = true
                JSONArray()
            }
            for (index in 0 until json.length()) {
                runCatching {
                    val item = json.getJSONObject(index)
                    val packages = item.optJSONArray(KEY_PACKAGES) ?: JSONArray()
                    add(
                        FreezerList(
                            id = item.getString(KEY_ID),
                            name = item.getString(KEY_NAME),
                            packages = MutableList(packages.length()) { packages.getString(it) }
                        )
                    )
                }.onFailure {
                    loadFailed = true
                }
            }
            if (loadFailed) {
                canSave = runCatching {
                    File(path).copyTo(File("$path.corrupt-${System.currentTimeMillis()}"), overwrite = false)
                    true
                }.getOrDefault(false)
            }
        }
    }

    fun createList(name: String): FreezerList = FreezerList(
        id = System.currentTimeMillis().toString(),
        name = name
    ).also {
        lists.add(it)
        save()
    }

    val noCustomList: FreezerList
        get() = lists.firstOrNull { it.id == ID_NO_CUSTOM_LIST } ?: FreezerList(
            id = ID_NO_CUSTOM_LIST,
            name = app.getString(R.string.list_no_custom)
        ).also {
            lists.add(0, it)
            save()
        }

    val allFrozenList: FreezerList
        get() = FreezerList(
            id = ID_ALL_FROZEN,
            name = app.getString(R.string.list_all_frozen),
            packages = buildSet {
                lists.flatMapTo(this) { it.packages }
                HPackages.getInstalledApplications()
                    .map { it.packageName }
                    .filterTo(this) { AppManager.isAppFrozen(it) }
            }.toMutableList()
        )

    @Synchronized
    fun packageSnapshot(id: String): List<String> = findList(id)?.packages?.toList().orEmpty()

    fun findList(id: String): FreezerList? =
        if (id == ID_ALL_FROZEN) allFrozenList else lists.firstOrNull { it.id == id }

    fun replaceListPackages(id: String, packages: Collection<String>): Boolean {
        val list = findList(id) ?: return false
        if (id == ID_ALL_FROZEN) return false
        val snapshots = lists.associate { it.id to it.packages.toList() }
        val previousPackages = list.packages.toSet()
        list.packages.clear()
        list.packages.addAll(packages.distinct())
        if (id != ID_NO_CUSTOM_LIST) {
            val fallback = noCustomList
            fallback.packages.removeAll(list.packages.toSet())
            val assignedPackages = lists
                .filter { it.id != ID_NO_CUSTOM_LIST }
                .flatMapTo(mutableSetOf()) { it.packages }
            fallback.packages.addAll(
                previousPackages.filterNot { it in assignedPackages || it in fallback.packages }
            )
        }
        return save().also { saved ->
            if (!saved) restorePackages(snapshots)
        }
    }

    fun deleteList(id: String): Boolean {
        if (id == ID_ALL_FROZEN || id == ID_NO_CUSTOM_LIST) return false
        val index = lists.indexOfFirst { it.id == id }
        if (index < 0) return false
        val fallback = noCustomList
        val fallbackSnapshot = fallback.packages.toList()
        val removed = lists.removeAt(index)
        val assignedPackages = lists
            .filter { it.id != ID_NO_CUSTOM_LIST }
            .flatMapTo(mutableSetOf()) { it.packages }
        fallback.packages.addAll(
            removed.packages.filterNot { it in assignedPackages || it in fallback.packages }
        )
        return save().also { saved ->
            if (!saved) {
                fallback.packages.clear()
                fallback.packages.addAll(fallbackSnapshot)
                lists.add(index, removed)
            }
        }
    }

    private fun restorePackages(snapshots: Map<String, List<String>>) {
        lists.forEach { list ->
            snapshots[list.id]?.let { packages ->
                list.packages.clear()
                list.packages.addAll(packages)
            }
        }
    }

    fun save(): Boolean {
        if (!canSave) {
            HUI.showToast(R.string.msg_freezer_save_failed)
            return false
        }
        if (!HFiles.exists(dir)) HFiles.createDirectories(dir)
        return HFiles.writeAtomic(path, JSONArray().run {
            lists.forEach { list ->
                put(
                    JSONObject()
                        .put(KEY_ID, list.id)
                        .put(KEY_NAME, list.name)
                        .put(KEY_PACKAGES, JSONArray(list.packages))
                )
            }
            toString()
        }).also { saved ->
            if (!saved) HUI.showToast(R.string.msg_freezer_save_failed)
        }
    }
}
