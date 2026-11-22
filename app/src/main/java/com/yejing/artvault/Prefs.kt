package com.yejing.artvault

import android.content.Context

/** 设备本地偏好：库路径是每台设备自己的，不能写进随库同步的 settings.json */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("artvault", Context.MODE_PRIVATE)

    var vaultPath: String?
        get() = sp.getString("vault_path", null)
        set(v) = sp.edit().putString("vault_path", v).apply()

    /** 网格列数：2 = 大图，3 = 密排。默认 3。 */
    var gridColumns: Int
        get() = sp.getInt("grid_columns", 3).coerceIn(2, 3)
        set(v) = sp.edit().putInt("grid_columns", v.coerceIn(2, 3)).apply()

    /** 演示模式是否已经被用户主动跳过 */
    var seenPicker: Boolean
        get() = sp.getBoolean("seen_picker", false)
        set(v) = sp.edit().putBoolean("seen_picker", v).apply()

    /** 最近一次整理完毕（编辑后不再命中任何待整理缺口）的稿件 id */
    var lastCleanedId: String?
        get() = sp.getString("last_cleaned_id", null)
        set(v) = sp.edit().putString("last_cleaned_id", v).apply()

    /** 附属角色（不计入人均分摊）在角色页显示的自定义标签文字；默认「附属」 */
    var petLabel: String
        get() = sp.getString("pet_label", null)?.takeIf { it.isNotBlank() } ?: "附属"
        set(v) = sp.edit().putString("pet_label", v.trim().ifBlank { "附属" }).apply()

    /**
     * 筛选预设，JSON 数组字符串。设备本地，不进 meta/。
     * 结构见 FilterPresets.kt。
     */
    var filterPresets: String
        get() = sp.getString("filter_presets", "[]") ?: "[]"
        set(v) = sp.edit().putString("filter_presets", v).apply()

    /**
     * 相册镜像的文件名带哪些字段（存 enum 名，顺序由 Mirror.NameField 决定）。
     * 默认四项全要；全空会让文件名退化成「未命名」，所以在 setter 里兜住。
     */
    var mirrorNameFields: Set<String>
        get() = sp.getStringSet("mirror_name_fields", null)?.toSet()
            ?: Mirror.ALL_FIELDS.map { it.name }.toSet()
        set(v) = sp.edit()
            .putStringSet(
                "mirror_name_fields",
                v.ifEmpty { setOf(Mirror.NameField.DATE.name) },
            )
            .apply()
}
