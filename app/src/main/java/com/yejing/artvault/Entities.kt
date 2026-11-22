package com.yejing.artvault

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/* ────────────────────────────────────────────────────────────────
 * 实体层（v2-B「轻实体化」）。
 *
 * 规则只有一条：**稿件上存 id，名字只是实体的属性。**
 * 于是改名 = 改实体一个字段（稿件一个都不用动）、删除 = 摘引用、
 * "同一个画师写成两种字" 这类脏数据在 ensure() 里就被合并了。
 * 之前围绕「名字当 key」长出来的补偿机制（搬 key / vanishing 判断 / 别名表）
 * 在这个模型下全部不再需要。
 *
 * 两个实体仓库刻意长得不一样：
 * - 画师：每人一个文件 meta/artists/<id>.json（人少、字段多、以后要挂主页/头像）
 * - 标签：单文件 meta/tags.json（标签会到几百个，几百个小文件对 Syncthing 不友好）
 * ──────────────────────────────────────────────────────────────── */

data class ArtistEntity(
    val id: String,
    val name: String,
    /** 历史名字。改名自动追加；查名字时主名和别名都认 */
    val aliases: List<String> = emptyList(),
    val homepage: String? = null,
)

data class TagEntity(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
)

/**
 * 画师仓库。可变方法是 synchronized + 复制整表：B 阶段写入点还分散在
 * 建档 / 编辑 / 批量赋值（有的在 IO 线程），先保证不撞；C 阶段收进统一命令层后
 * 这些锁就没有存在意义了。
 */
class ArtistBook private constructor(
    private val dir: File?,
    private val readOnly: Boolean,
    private var list: List<ArtistEntity>,
) {
    val writable: Boolean get() = dir != null && !readOnly
    val all: List<ArtistEntity> get() = list

    fun byId(id: String?): ArtistEntity? = list.firstOrNull { it.id == id }

    /** 主名和别名都认（trim 后全等），和表单输入的匹配口径一致 */
    fun byName(name: String): ArtistEntity? {
        val k = name.trim()
        if (k.isEmpty()) return null
        return list.firstOrNull { e -> key(e.name) == k || e.aliases.any { key(it) == k } }
    }

    /** 名字 → id。没有就**当场建实体**：表单里随手的输入不该要求用户先去"建档再选" */
    @Synchronized
    fun ensure(name: String): String? {
        val k = name.trim()
        if (k.isEmpty()) return null
        byName(k)?.let { return it.id }
        val e = ArtistEntity(newId(), k)
        list = list + e
        return if (persist(e)) e.id else null
    }

    /** 改名：主名换掉，旧名进别名。稿件一个都不用动——它们只认 id */
    @Synchronized
    fun rename(id: String, newName: String): Boolean {
        val k = newName.trim()
        if (k.isEmpty()) return false
        val e = byId(id) ?: return false
        if (key(e.name) == k) return true
        // 新名字如果已经被别人（含别名）占用，那是两个身份撞了，不自动并
        val clash = byName(k)
        if (clash != null && clash.id != id) return false
        val next = e.copy(
            name = k,
            aliases = (e.aliases + e.name).map { it.trim() }.filter { key(it) != k }.distinct(),
        )
        list = list.map { if (it.id == id) next else it }
        return persist(next)
    }

    @Synchronized
    fun setHomepage(id: String, url: String?): Boolean {
        val e = byId(id) ?: return false
        val u = url?.trim().orEmpty()
        val next = if (u.isEmpty()) e.copy(homepage = null) else e.copy(homepage = u)
        list = list.map { if (it.id == id) next else it }
        return persist(next)
    }

    /** 实体没被任何稿件引用时才允许真删（防悬空引用）；调用方负责先查引用 */
    @Synchronized
    fun delete(id: String): Boolean {
        val e = byId(id) ?: return false
        // 删失败（文件删不掉 / 目录只读）必须把内存还原：不还原的话调用方查 `byId(id)`
        // 会得到 null，把失败当成"已经删了"，而且内存与盘从此不一致（重载后又回来）。
        val prev = list
        list = list.filterNot { it.id == id }
        val f = dir?.let { File(it, "${e.id}.json") }
        val before = if (f != null && OperationJournal.isRecording()) {
            runCatching { if (f.isFile) f.readText() else null }.getOrNull()
        } else {
            null
        }
        // 只有**真的没了**才登记 after=null，否则撤销会把已删掉的原文件写回去。
        // 别用 delete() 的返回值：文件本来就不存在时它返回 false，那是正常情况。
        f?.delete()
        val gone = f == null || !f.exists()
        if (!gone) {
            list = prev
            return false
        }
        f?.let { OperationJournal.record(it, before, null) }
        return true
    }

    private fun persist(e: ArtistEntity): Boolean {
        val d = dir ?: return false
        if (readOnly) return false
        val o = JSONObject()
            .put("id", e.id)
            .put("name", e.name)
            .put("aliases", JSONArray().also { a -> e.aliases.forEach { a.put(it) } })
        if (e.homepage != null) o.put("homepage", e.homepage)
        return writeJsonAtomic(File(d, "${e.id}.json"), o)
    }

    companion object {
        private fun key(s: String) = s.trim()

        fun load(root: File?, readOnly: Boolean = false): ArtistBook {
            val d = root?.let { File(it, "meta/artists") } ?: return ArtistBook(null, true, emptyList())
            val list = (d.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
                .mapNotNull { f ->
                    runCatching {
                        val o = JSONObject(f.readText())
                        val name = o.optString("name").trim()
                        if (name.isEmpty()) return@runCatching null
                        ArtistEntity(
                            id = o.optString("id").ifBlank { f.nameWithoutExtension },
                            name = name,
                            aliases = o.optJSONArray("aliases").strList(),
                            homepage = o.strOrNull("homepage"),
                        )
                    }.getOrNull()
                }
                .sortedBy { it.name }
            return ArtistBook(d, readOnly, list)
        }
    }
}

/** 标签仓库：单文件 meta/tags.json。几百个标签几百行 JSON，一次全量重写也就几 KB */
class TagBook private constructor(
    private val file: File?,
    private val readOnly: Boolean,
    private var list: List<TagEntity>,
) {
    val writable: Boolean get() = file != null && !readOnly
    val all: List<TagEntity> get() = list

    fun byId(id: String?): TagEntity? = list.firstOrNull { it.id == id }

    fun byName(name: String): TagEntity? {
        val k = name.trim()
        if (k.isEmpty()) return null
        return list.firstOrNull { e -> key(e.name) == k || e.aliases.any { key(it) == k } }
    }

    @Synchronized
    fun ensure(name: String): String? {
        val k = name.trim()
        if (k.isEmpty()) return null
        byName(k)?.let { return it.id }
        val e = TagEntity(newId(), k)
        list = list + e
        return if (persist()) e.id else null
    }

    @Synchronized
    fun rename(id: String, newName: String): Boolean {
        val k = newName.trim()
        if (k.isEmpty()) return false
        val e = byId(id) ?: return false
        if (key(e.name) == k) return true
        if (byName(k) != null) return false
        val next = e.copy(
            name = k,
            aliases = (e.aliases + e.name).map { it.trim() }.filter { key(it) != k }.distinct(),
        )
        list = list.map { if (it.id == id) next else it }
        return persist()
    }

    /** 实体没被任何稿件引用时才允许真删。标签是单文件，写失败要说出来，别只清内存 */
    @Synchronized
    fun delete(id: String): Boolean {
        byId(id) ?: return false
        // 同 ArtistBook.delete：写盘失败必须回滚内存，否则调用方的「还在不在」判断永远为假
        val prev = list
        list = list.filterNot { it.id == id }
        if (!persist()) {
            list = prev
            return false
        }
        return true
    }

    private fun persist(): Boolean {
        val f = file ?: return false
        if (readOnly) return false
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("aliases", JSONArray().also { a -> t.aliases.forEach { a.put(it) } })
            )
        }
        return writeJsonAtomic(f, JSONObject().put("version", 1).put("tags", arr))
    }

    companion object {
        private fun key(s: String) = s.trim()

        fun load(root: File?, readOnly: Boolean = false): TagBook {
            val f = root?.let { File(it, "meta/tags.json") } ?: return TagBook(null, true, emptyList())
            val list = runCatching {
                val arr = JSONObject(f.readText()).optJSONArray("tags")
                    ?: return@runCatching emptyList<TagEntity>()
                (0 until arr.length()).mapNotNull { i ->
                    val e = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = e.optString("name").trim()
                    if (name.isEmpty()) return@mapNotNull null
                    TagEntity(
                        id = e.optString("id").ifBlank { newId() },
                        name = name,
                        aliases = e.optJSONArray("aliases").strList(),
                    )
                }
            }.getOrDefault(emptyList())
            return TagBook(f, readOnly, list)
        }
    }
}

private fun JSONArray?.strList(): List<String> {
    val a = this ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
}

/**
 * 读侧 join：id → 名字。所有展示画师/标签名字的地方都走这里，
 * **解析不出时显示「档案缺失」，绝不显示成空**——空会让人以为「没填」，缺失才是事实。
 */
class VaultNames(
    private val artists: ArtistBook,
    private val tags: TagBook,
) {
    fun artist(id: String?): String {
        if (id.isNullOrBlank()) return ""
        return artists.byId(id)?.name ?: MISSING
    }

    /** 表单回填用：解析不出给 null（回退到空字符串），别把「档案缺失」写进输入框 */
    fun artistName(id: String?): String? = artists.byId(id)?.name

    /** 画师主页。详情页拿它决定「主页 ›」这枚附注显不显示 */
    fun artistHome(id: String?): String? =
        artists.byId(id)?.homepage?.takeIf { it.isNotBlank() }

    fun tagsOf(ids: List<String>): List<String> = ids.mapNotNull { tags.byId(it)?.name }

    fun tagsText(ids: List<String>): String = tagsOf(ids).joinToString(" ")

    /**
     * 搜索串缓存。
     *
     * key 用 [Art] 的**引用**（IdentityHashMap），不用 rev：
     * - 输入关键词时 Art 实例不变 → 全命中，每敲一个字只剩 1779 次 `contains`；
     * - 写盘后 `loadPatched` 会把变动的那几条换成新实例 → 只有它们 miss；
     * - 世界观/角色/画师/标签改名 → `MainActivity` 里 `remember(s0)` 重建 VaultNames → 缓存自动清空。
     * rev 只在部分写路径递增，漏一次就是「搜不到」，而实例替换一定等价于内容变了。
     */
    private val searchTexts = java.util.IdentityHashMap<Art, String>()

    /** 关键词搜索的匹配文本：标题 + 画师（主名和别名）+ 标签 + 平台 + 备注 */
    fun searchText(a: Art, tax: Taxonomy): String {
        searchTexts[a]?.let { return it }
        // 兜底：删/合并多轮后旧实例会留在表里，涨到一定量整体清一次
        if (searchTexts.size > 8192) searchTexts.clear()
        val sb = StringBuilder()
        sb.append(a.title).append(' ').append(a.platform).append(' ').append(a.note).append(' ')
        val e = artists.byId(a.artistId)
        if (e != null) {
            sb.append(e.name).append(' ')
            e.aliases.forEach { sb.append(it).append(' ') }
        }
        a.tagIds.forEach { tags.byId(it)?.let { t -> sb.append(t.name).append(' ') } }
        a.roleIds.forEach { tax.roleName(it)?.let { r -> sb.append(r).append(' ') } }
        return sb.toString().lowercase().also { searchTexts[a] = it }
    }

    companion object {
        const val MISSING = "档案缺失"
    }
}

/**
 * 打开画师主页。
 *
 * **只交一个 ACTION_VIEW 给系统，不猜 deep link。** 米画师没公开过 scheme、
 * 也没确认注册 App Links；浏览器打开实际体验已经够顺（网页版自己会引导跳 App）。
 */
object ArtistLink {
    private val HOST_LABEL = listOf(
        "mihuashi.com" to "米画师",
        "weibo.com" to "微博",
        "weibo.cn" to "微博",
        "bilibili.com" to "B站",
        "b23.tv" to "B站",
        "pixiv.net" to "Pixiv",
        "lofter.com" to "LOFTER",
        "twitter.com" to "X",
        "x.com" to "X",
    )

    private val URL_RE = Regex("""https?://[^\s，。、；：）)\]】"'<>]+""")

    /** 分享文案里挑出链接；顺手补 `https://`，校验不出域名返回 null */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val u = URL_RE.find(t)?.value ?: t
        val withScheme = if (u.startsWith("http://") || u.startsWith("https://")) u else "https://" + u.trimStart('/')
        val host = runCatching { Uri.parse(withScheme).host }.getOrNull().orEmpty()
        return withScheme.takeIf { host.contains('.') }
    }

    fun label(url: String): String {
        val host = runCatching { Uri.parse(url).host }.getOrNull().orEmpty().removePrefix("www.")
        return HOST_LABEL.firstOrNull { (d, _) -> host == d || host.endsWith(".$d") }?.second ?: "主页"
    }

    fun open(ctx: Context, url: String): Boolean = runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrDefault(false)
}
