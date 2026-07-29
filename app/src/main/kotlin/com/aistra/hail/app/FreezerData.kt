package com.aistra.hail.app

import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HFiles
import org.json.JSONArray
import org.json.JSONObject

data class FreezerList(
    val id: String,
    var name: String,
    val packages: MutableList<String> = mutableListOf()
)

object FreezerData {
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_PACKAGES = "packages"
    private val dir = "${app.filesDir.path}/v1"
    private val path = "$dir/freezer_lists.json"

    val lists: MutableList<FreezerList> by lazy {
        mutableListOf<FreezerList>().apply {
            runCatching {
                val json = JSONArray(HFiles.read(path))
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    val packages = item.optJSONArray(KEY_PACKAGES) ?: JSONArray()
                    add(
                        FreezerList(
                            id = item.getString(KEY_ID),
                            name = item.getString(KEY_NAME),
                            packages = MutableList(packages.length()) { packages.getString(it) }
                        )
                    )
                }
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

    fun findList(id: String): FreezerList? = lists.firstOrNull { it.id == id }

    fun deleteList(id: String) {
        lists.removeAll { it.id == id }
        save()
    }

    fun save() {
        if (!HFiles.exists(dir)) HFiles.createDirectories(dir)
        HFiles.write(path, JSONArray().run {
            lists.forEach { list ->
                put(
                    JSONObject()
                        .put(KEY_ID, list.id)
                        .put(KEY_NAME, list.name)
                        .put(KEY_PACKAGES, JSONArray(list.packages))
                )
            }
            toString()
        })
    }
}
