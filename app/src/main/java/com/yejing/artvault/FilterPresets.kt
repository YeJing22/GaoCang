package com.yejing.artvault

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条本地筛选预设。存在 SharedPreferences（[Prefs.filterPresets]），不进 meta/。
 * 刻意不存 [Sort]：排序是浏览习惯，不应被预设覆盖。
 */
data class FilterPreset(val name: String, val filter: Filter)

object FilterPresets {
    const val MAX = 8

    fun load(prefs: Prefs): List<FilterPreset> = decode(prefs.filterPresets)

    fun save(prefs: Prefs, list: List<FilterPreset>) {
        prefs.filterPresets = encode(list.take(MAX))
    }

    internal fun decode(raw: String): List<FilterPreset> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<FilterPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.strOrNull("name") ?: continue
            val f = parseFilter(o) ?: continue
            out += FilterPreset(name, f)
        }
        return out
    }

    internal fun encode(list: List<FilterPreset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            val f = p.filter
            val o = JSONObject()
            o.put("name", p.name)
            o.put("worldId", f.worldId ?: JSONObject.NULL)
            o.put("folderId", f.folderId ?: JSONObject.NULL)
            o.put("roleIds", JSONArray().apply { f.roleIds.forEach { put(it) } })
            o.put("roleMode", f.roleMode.name)
            o.put("tags", JSONArray().apply { f.tags.forEach { put(it) } })
            o.put("gaps", JSONArray().apply { f.gaps.forEach { put(it.name) } })
            o.put("query", f.query)
            o.put("priceMin", f.priceMin ?: JSONObject.NULL)
            o.put("priceMax", f.priceMax ?: JSONObject.NULL)
            o.put("dateFrom", f.dateFrom ?: JSONObject.NULL)
            o.put("dateTo", f.dateTo ?: JSONObject.NULL)
            o.put("starredOnly", f.starredOnly)
            o.put("kind", f.kind.name)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun parseFilter(o: JSONObject): Filter? = runCatching {
        // folderId 允许空串（UNSORTED_FOLDER = "" 表示「未分类」），不能走 strOrNull
        val folderId = if (o.isNull("folderId")) null else o.optString("folderId")
        val roleMode = o.strOrNull("roleMode")
            ?.let { runCatching { RoleMode.valueOf(it) }.getOrNull() }
            ?: RoleMode.And
        val gaps = o.optJSONArray("gaps").toStringList()
            .mapNotNull { runCatching { Gap.valueOf(it) }.getOrNull() }
            .toSet()
        val kind = o.strOrNull("kind")
            ?.let { runCatching { KindFilter.valueOf(it) }.getOrNull() }
            ?: KindFilter.Artwork
        Filter(
            worldId = o.strOrNull("worldId"),
            folderId = folderId,
            roleIds = o.optJSONArray("roleIds").toStringList().toSet(),
            roleMode = roleMode,
            tags = o.optJSONArray("tags").toStringList().toSet(),
            gaps = gaps,
            query = o.strOrNull("query").orEmpty(),
            priceMin = if (o.isNull("priceMin")) null else o.optLong("priceMin"),
            priceMax = if (o.isNull("priceMax")) null else o.optLong("priceMax"),
            dateFrom = o.strOrNull("dateFrom"),
            dateTo = o.strOrNull("dateTo"),
            starredOnly = o.optBoolean("starredOnly"),
            kind = kind,
        )
    }.getOrNull()

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            if (isNull(i)) null else optString(i).trim().takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 清理已被删除的实体引用，防止点预设后得到「永远 0 件」。
     * 只清理内存对象，不改写盘上预设。
     */
    fun sanitize(f: Filter, tax: Taxonomy, tags: TagBook, folders: FolderBook): Filter {
        val validWorlds = tax.worlds.mapTo(HashSet()) { it.id }
        val validRoles = tax.roles.mapTo(HashSet()) { it.id }
        val validTags = tags.all.mapTo(HashSet()) { it.name }
        val validFolders = folders.all.mapTo(HashSet()) { it.id }
        return f.copy(
            worldId = f.worldId?.takeIf { it in validWorlds },
            folderId = f.folderId?.takeIf { it == UNSORTED_FOLDER || it in validFolders },
            roleIds = f.roleIds.filterTo(LinkedHashSet()) { it in validRoles },
            tags = f.tags.filterTo(LinkedHashSet()) { it in validTags },
        )
    }

    /** 存预设时的默认名：按优先级最多取两段，用「 · 」拼接，总长截到 14 字。 */
    fun autoName(f: Filter, tax: Taxonomy, tags: TagBook, folders: FolderBook): String {
        val parts = ArrayList<String>(4)
        f.worldId?.let { id -> tax.worlds.firstOrNull { it.id == id }?.name }?.takeIf { it.isNotBlank() }?.let { parts += it }
        f.folderId?.let { id ->
            if (id == UNSORTED_FOLDER) "未分类" else folders.byId(id)?.name
        }?.takeIf { it.isNotBlank() }?.let { parts += it }
        f.tags.firstOrNull()?.takeIf { it.isNotBlank() }?.let { parts += it }
        f.roleIds.firstOrNull()?.let { id -> tax.roles.firstOrNull { it.id == id }?.name }?.takeIf { it.isNotBlank() }?.let { parts += it }
        f.query.trim().take(10).takeIf { it.isNotEmpty() }?.let { parts += it }
        if (parts.isEmpty() && f.starredOnly) parts += "星标"
        if (parts.isEmpty() && f.gaps.size == 1) parts += f.gaps.first().label
        if (parts.isEmpty() && f.kind != KindFilter.Artwork) parts += f.kind.label
        val joined = parts.distinct().take(2).joinToString(" · ")
        return (if (joined.isNotBlank()) joined else "筛选 ${f.condCount} 项").take(14)
    }
}
