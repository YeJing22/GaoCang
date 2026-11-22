package com.yejing.artvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ────────────────────────────────────────────────────────────────
 * 归档文件夹（`meta/folders.json`）。
 *
 * 为什么和世界观分开：**世界观是设定归属，归档是"这张图放进哪个文件夹"**。
 * 系统相册只认一级子目录，所以导出必须有一个**单值**的归档位；而角色常常是复数、
 * 还跨世界观（联动图），拿角色或世界观去推文件夹一定推错——只有人工指定才稳。
 * 所以这里是独立实体，稿件上单独一个 `folder_id`，与世界观的筛选互不干扰。
 * ──────────────────────────────────────────────────────────────── */

data class FolderEntity(
    val id: String,
    val name: String,
    /** 系统归档：`"derived"` / `"fill"`；null = 用户自己建的普通归档 */
    val system: String? = null,
)

class FolderBook private constructor(
    private val file: File?,
    private val readOnly: Boolean,
    private var list: List<FolderEntity>,
) {
    val writable: Boolean get() = file != null && !readOnly
    val all: List<FolderEntity> get() = list

    fun byId(id: String?): FolderEntity? = list.firstOrNull { it.id == id }
    fun byName(name: String): FolderEntity? =
        list.firstOrNull { it.name.trim() == name.trim() }

    /** 按稿件类型查找对应的系统归档；普通稿返回 null */
    fun systemFolder(kind: ArtKind): FolderEntity? =
        kind.wire?.let { w -> list.firstOrNull { it.system == w } }

    /**
     * 补齐系统归档（缺就建）。
     *
     * 顺序：**先按 `system` 键找，再按名字兜一次，最后才新建**。
     * 锁在伴生对象上：`MainActivity` 冷启动与 `loadPatched` 每次都新建实例，
     * 实例级锁挡不住两个协程并发 ensure。两条都在且都带 `system` 时直接返回 false 不写盘（幂等）。
     * 演示库（`file == null` 或 `readOnly`）直接返回 false，不写盘。
     */
    fun ensureSystem(): Boolean = synchronized(FolderBook) {
        if (!writable) return@synchronized false
        val defs = listOf(
            ArtKind.Derived.wire!! to "制品",
            ArtKind.Fill.wire!! to "制图",
        )
        var changed = false
        var cur = list
        for ((sysKey, defaultName) in defs) {
            if (cur.any { it.system == sysKey }) continue
            val idx = cur.indexOfFirst { it.name.trim() == defaultName }
            if (idx >= 0) {
                val old = cur[idx]
                cur = cur.toMutableList().also { it[idx] = old.copy(system = sysKey) }
                changed = true
            } else {
                cur = cur + FolderEntity(newId(), defaultName, system = sysKey)
                changed = true
            }
        }
        if (!changed) return@synchronized false
        list = cur
        persist()
    }

    /** 名字 → id；没有就当场建（和标签一样的内联创建） */
    @Synchronized
    fun ensure(name: String): String? {
        val k = name.trim()
        if (k.isEmpty()) return null
        byName(k)?.let { return it.id }
        val e = FolderEntity(newId(), k)
        list = list + e
        return if (persist()) e.id else null
    }

    @Synchronized
    fun rename(id: String, newName: String): Boolean {
        val k = newName.trim()
        if (k.isEmpty()) return false
        val e = byId(id) ?: return false
        if (e.system != null) return false
        if (e.name == k) return true
        if (byName(k) != null) return false
        list = list.map { if (it.id == id) e.copy(name = k) else it }
        return persist()
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val e = byId(id) ?: return false
        if (e.system != null) return false
        list = list.filterNot { it.id == id }
        return persist()
    }

    /** 按给定 id 顺序重排用户文件夹；系统归档与未列出的项保持原相对顺序不丢失 */
    @Synchronized
    fun reorder(orderedIds: List<String>): Boolean {
        val user = list.filter { it.system == null }
        val sys = list.filter { it.system != null }
        val byId = user.associateBy { it.id }
        val seen = HashSet<String>()
        val nextUser = ArrayList<FolderEntity>(user.size)
        orderedIds.forEach { id ->
            val e = byId[id]
            if (e != null && seen.add(id)) nextUser += e
        }
        user.forEach { if (seen.add(it.id)) nextUser += it }
        list = nextUser + sys
        return persist()
    }

    private fun persist(): Boolean {
        val f = file ?: return false
        if (readOnly) return false
        val arr = JSONArray()
        list.forEach {
            val obj = JSONObject().put("id", it.id).put("name", it.name)
            if (it.system != null) obj.put("system", it.system)
            arr.put(obj)
        }
        return writeJsonAtomic(f, JSONObject().put("version", 1).put("folders", arr))
    }

    companion object {
        fun load(root: File?, readOnly: Boolean = false): FolderBook {
            val f = root?.let { File(it, "meta/folders.json") } ?: return FolderBook(null, true, emptyList())
            val list = runCatching {
                val arr = JSONObject(f.readText()).optJSONArray("folders") ?: return@runCatching emptyList()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = o.strOrNull("name") ?: return@mapNotNull null
                    FolderEntity(o.strOrNull("id") ?: newId(), name, system = o.strOrNull("system"))
                }
            }.getOrDefault(emptyList())
            return FolderBook(f, readOnly, list)
        }
    }
}
