package com.yejing.artvault

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.io.File

/** 一个版本：带背景 / 透明底 / 线稿 …… */
data class Ver(
    /** 版本自己的身份。blob 只是内容指纹（同一张图可能出现两次），不能拿来当身份；
     * 撤销、拆版本、导入去重（C/D 阶段）都要认这个 id */
    val id: String,
    val blob: String,
    val label: String,
    val ext: String,
    val w: Int,
    val h: Int,
    val hasAlpha: Boolean,
    val isPrimary: Boolean,
)

/** 稿件类型：普通稿在 JSON 里不写 `kind` 键，解析缺省即为 [Artwork]。 */
enum class ArtKind(val wire: String?) {
    Artwork(null),
    Derived("derived"),
    Fill("fill"),
}

/** 一个槽位 */
data class Art(
    val id: String,
    val title: String,
    /** 自由备注（v2-B：搜索需要它） */
    val note: String = "",
    val receivedAt: String?,
    /** 日期是从图片文件时间推断出来的，不是人工确认过的 */
    val dateGuessed: Boolean = false,
    /** 星标（收藏）。JSON 里没有这个键 = false */
    val starred: Boolean = false,
    val updatedAt: String? = null,
    val priceTotal: Long?,
    val platform: String,
    /** 画师实体 id；null = 没填。**只认 id，名字看 [ArtistBook]** */
    val artistId: String? = null,
    /**
     * 自己画的（没有画师）。与「没填」是两件事：
     * - `artistId = null, selfMade = false` → 没填
     * - `artistId = null, selfMade = true`  → 自制
     * 不建一个叫「自制」的画师实体，否则它会混进画师页、花费榜、占比条。
     */
    val selfMade: Boolean = false,
    /** 世界观（设定归属，单值） */
    val worldId: String? = null,
    /** 归档文件夹 id（导出用，**与世界观解耦**，人工指定） */
    val folderId: String? = null,
    val roleIds: List<String> = emptyList(),
    /** 标签实体 id 列表。**只认 id，名字看 [TagBook]** */
    val tagIds: List<String> = emptyList(),
    val versions: List<Ver>,
    val kind: ArtKind = ArtKind.Artwork,
    /** 二次创作关联的原稿 id（单边存；`derived` 必填，`fill` 可选） */
    val sourceArtId: String? = null,
    /** 盘上 `kind` 键的原文。parseArt 已把未知值折成 Artwork，Doctor 靠它发现拼错 */
    val rawKind: String? = null,
) {
    val primary: Ver? get() = versions.firstOrNull { it.isPrimary } ?: versions.firstOrNull()
}

/**
 * 从一条稿件 JSON 解析出 [Art]。
 * 抽成顶层函数是为了让回收站能复用——那里的文件已经不在 `meta/artworks/` 里，
 * 但也得按同一套规则解析，否则回收站和库里对「什么算合法稿件」的判断会漂。
 */
/**
 * 读一个**可能为 null 的字符串**。
 *
 * 坑：Android 的 org.json 里 `optString("k")` 遇到 JSON `null` 返回的是**字符串 "null"**，
 * 不是 ""。直接用 `optString(...).takeIf { it.isNotBlank() }` 会把「没填画师」读成
 * `artistId = "null"` —— 一个指向不存在实体的假引用：详情页显示「档案缺失」，
 * 统计里多出一个假的画师行，而人眼根本看不出问题出在哪。所有可空字符串都必须走这里。
 */
internal fun JSONObject.strOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).trim().takeIf { it.isNotEmpty() }

internal fun parseArt(text: String): Art? {
    val o = JSONObject(text)
    val arr = o.optJSONArray("versions") ?: return null
    val vs = (0 until arr.length()).mapNotNull { i ->
        val v = arr.optJSONObject(i) ?: return@mapNotNull null
        Ver(
            id = v.strOrNull("id").orEmpty(),
            blob = v.strOrNull("blob").orEmpty(),
            label = v.strOrNull("label").orEmpty(),
            ext = v.strOrNull("ext").orEmpty(),
            w = v.optInt("w"),
            h = v.optInt("h"),
            hasAlpha = v.optBoolean("has_alpha"),
            isPrimary = v.optBoolean("is_primary"),
        )
    }.filter { it.blob.isNotBlank() }
    if (vs.isEmpty()) return null
    val rawKind = o.strOrNull("kind")
    return Art(
        id = o.optString("id"),
        title = o.strOrNull("title").orEmpty(),
        note = o.strOrNull("note").orEmpty(),
        receivedAt = o.strOrNull("received_at"),
        // PC 端写 received_inferred、App 写 received_at_guessed，两个都认
        dateGuessed = o.optBoolean("received_at_guessed") || o.optBoolean("received_inferred"),
        starred = o.optBoolean("starred"),
        updatedAt = o.strOrNull("updated_at"),
        priceTotal = if (o.isNull("price_total")) null else o.optLong("price_total"),
        platform = o.optString("platform"),
        artistId = o.strOrNull("artist_id"),
        selfMade = o.optBoolean("self_made"),
        // 旧 schema 的 world_ids 是数组——不是兼容层，只是防止 Syncthing 把老文件
        // 复活回来时把 App 带崩：读成单值，最多退化成「未归类」
        worldId = o.strOrNull("world_id")
            ?: o.optJSONArray("world_ids")?.optString(0)?.takeIf { it.isNotBlank() },
        folderId = o.strOrNull("folder_id"),
        roleIds = o.strList("character_ids"),
        tagIds = o.strList("tag_ids"),
        versions = vs,
        // 未知字符串一律当普通稿，不能静默变成「不统计」的隐形数据，由 Doctor 报
        kind = when (rawKind) {
            "derived" -> ArtKind.Derived
            "fill" -> ArtKind.Fill
            else -> ArtKind.Artwork
        },
        sourceArtId = o.strOrNull("source_art_id"),
        rawKind = rawKind,
    )
}

class Library(
    val artworksDir: File,
    val thumbsDir: File,
    val blobsDir: File?,
    val demo: Boolean,
) {
    /**
     * 读全部稿件：分片并行解析（上限 4），结果与串行一致。
     *
     * 只在「冷启动」和「外部改动（Syncthing 拉回一批）」时走这条路；
     * 自己写盘的刷新走 [loadPatched]，只重读变动的那几条。
     * 试过按 (mtime, size) 逐文件校验——在 Android 上 stat 和读文件一样贵，
     * 1782 个文件光 stat 就要 500ms，比并行全解析还慢。
     */
    suspend fun load(): List<Art> = coroutineScope {
        val files = artworksDir.listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray()
        if (files.isEmpty()) return@coroutineScope emptyList()
        val parts = minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors()))
        files.toList().chunked((files.size + parts - 1) / parts)
            .map { chunk ->
                async(Dispatchers.Default) {
                    chunk.mapNotNull { f -> runCatching { parseArt(f.readText()) }.getOrNull() }
                }
            }
            .awaitAll()
            .flatten()
            .sortedWith(compareByDescending<Art> { it.receivedAt ?: "" })
    }

    fun thumb(v: Ver): File = File(thumbsDir, "${v.blob}.w640.webp")

    /** 原图；演示模式没有原图，回落到缩略图 */
    fun blob(v: Ver): File? =
        blobsDir?.let { File(File(it, v.blob.take(2)), "${v.blob}.${v.ext}") }?.takeIf { it.isFile }

    fun image(v: Ver): File = blob(v) ?: thumb(v)
}

/**
 * 演示数据：首次启动把 assets/demo 解到 cache，之后当普通库目录读。
 * **目录结构与真库完全一致**（meta/artworks + meta/artists + meta/taxonomy.json + meta/tags.json + thumbs），
 * 只是在 load() 时按只读打开——这样演示态和真库走同一套代码，不会长出第二种行为。
 * 标记带版本号：assets 布局变了就自动重解一次。
 */
object Demo {
    private const val MARK = ".ok.v4"

    fun ensure(ctx: Context): File {
        val dir = File(ctx.cacheDir, "demo")
        val mark = File(dir, MARK)
        if (mark.exists()) return dir

        dir.deleteRecursively()
        copyAssetTree(ctx, "demo", dir)
        mark.createNewFile()
        return dir
    }

    private fun copyAssetTree(ctx: Context, src: String, dst: File) {
        val children = ctx.assets.list(src).orEmpty()
        if (children.isEmpty()) {
            // 空目录（如 meta/artists）在打包时会被 aapt 丢掉，list 对它也返回空——
            // 这时按文件打开会抛异常，两种情况都当「空目录」跳过
            runCatching {
                dst.parentFile?.mkdirs()
                ctx.assets.open(src).use { i ->
                    dst.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
                }
            }
        } else {
            dst.mkdirs()
            children.forEach { copyAssetTree(ctx, "$src/$it", File(dst, it)) }
        }
    }
}
