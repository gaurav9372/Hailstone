package com.aistra.hail.app

/** The app's schema, never the imported type hints, determines preference storage types. */
internal object BackupSettings {
    private val booleanKeys = setOf(
        HailData.BIOMETRIC_LOGIN, HailData.FILTER_USER_APPS, HailData.FILTER_SYSTEM_APPS,
        HailData.FILTER_FROZEN_APPS, HailData.FILTER_UNFROZEN_APPS, HailData.GRAYSCALE_ICON,
        HailData.COMPACT_ICON, HailData.SYNTHESIZE_ADAPTIVE_ICONS, HailData.FUZZY_SEARCH,
        HailData.NINE_KEY_SEARCH, HailData.AUTO_FREEZE_AFTER_LOCK, HailData.SKIP_WHILE_CHARGING,
        HailData.SKIP_FOREGROUND_APP, HailData.SKIP_NOTIFYING_APP, "freezer_list_view"
    )
    private val stringChoices = mapOf(
        HailData.WORKING_MODE to HailData.WORKING_MODE_VALUES,
        HailData.APP_THEME to HailData.APP_THEME_VALUES,
        HailData.TILE_ACTION to HailData.TILE_ACTION_VALUES,
        HailData.DYNAMIC_SHORTCUT_ACTION to HailData.DYNAMIC_SHORTCUT_ACTIONS,
        "sort_by" to listOf(HailData.SORT_NAME, HailData.SORT_INSTALL, HailData.SORT_UPDATE)
    )

    fun normalize(settings: Map<String, Any?>): Map<String, Any> = buildMap {
        settings.forEach { (key, value) ->
            if (value == null) return@forEach
            when {
                key in booleanKeys -> put(key, when (value) {
                    is Boolean -> value
                    is String -> requireNotNull(value.toBooleanStrictOrNull()) { "Invalid boolean: $key" }
                    else -> error("Invalid boolean: $key")
                })
                key in stringChoices -> {
                    require(value is String && value in stringChoices.getValue(key)) { "Invalid choice: $key" }
                    put(key, value)
                }
                key == HailData.ICON_PACK -> {
                    require(value is String && value.isNotBlank()) { "Invalid icon pack" }
                    put(key, value)
                }
                key == HailData.HOME_FONT_SIZE || key == HailData.AUTO_FREEZE_DELAY -> {
                    val number = when (value) {
                        is Number -> value.toFloat()
                        is String -> value.toFloatOrNull()
                        else -> null
                    }
                    val range = if (key == HailData.HOME_FONT_SIZE) 11f..16f else 0f..30f
                    require(number != null && number.isFinite() && number in range) { "Invalid slider: $key" }
                    put(key, number)
                }
                // Ignore unrelated preference-library state and keys from future versions.
            }
        }
    }
}
