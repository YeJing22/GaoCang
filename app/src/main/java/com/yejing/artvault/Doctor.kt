package com.yejing.artvault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import org.json.JSONObject
import org.json.JSONArray
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ────────────────────────────────────────────────────────────────
 * 一致性自检（「体检」）。
 *
 * 为什么要它：结构改成 id 引用之后，坏数据的样子变了——不再是「画师名怪怪的」，
 * 而是「某条引用指向一个不存在的 id」，那种东西在界面上只是**少显示一个标签**，人眼看不出。
 * 这里把 B5 验收里「自动检查」那部分做成随手可跑的入口：
 * 每槽恰一主图 / 版本 id 唯一 / 引用可解析 / 名字不撞 / blob 在位 / 价格日期没坏。
 *
 * **只报告，不修复。** 修是 C 阶段命令层的事（要能撤销、要能解释改了什么）。
 * ──────────────────────────────────────────────────────────────── */

data class HealthIssue(val kind: String, val detail: String)

data class HealthReport(
    val rootPath: String,
    val artCount: Int,
    val blobCount: Int,
    val issues: List<HealthIssue>,
) {
    val ok: Boolean get() = issues.isEmpty()

    /** 有指向不存在实体的引用（可以安全清掉，不影响图片和稿件本身） */
    val hasDangling: Boolean get() = issues.any { it.kind.endsWith("引用悬空") }

    /** 纯文本报告，方便复制出来贴给我 */
    fun text(): String = buildString {
        appendLine("稿仓 · 一致性检查")
        appendLine("库：$rootPath")
        appendLine("$artCount 件稿件 · $blobCount 个 blob")
        if (ok) {
            appendLine("结果：全部通过")
        } else {
            val grouped = issues.groupBy { it.kind }
            appendLine("结果：${issues.size} 个问题，分 ${grouped.size} 类")
            grouped.forEach { (kind, list) ->
                appendLine()
                appendLine("【$kind】${list.size} 条")
                list.forEach { appendLine("  - ${it.detail}") }
            }
        }
    }
}

/**
 * 跑一遍全库检查。
 * 每类最多记 [CAP] 条：坏数据的数量本身不重要，现象和样例才重要。
 */
suspend fun inspectVault(
    root: File,
    arts: List<Art>,
    artists: ArtistBook,
    tags: TagBook,
    folders: FolderBook,
    tax: Taxonomy,
): HealthReport = withContext(Dispatchers.IO) {
    val CAP = 20
    val issues = ArrayList<HealthIssue>()
    fun add(kind: String, detail: String) {
        if (issues.count { it.kind == kind } < CAP) issues += HealthIssue(kind, detail)
    }

    val artworkDir = File(root, "meta/artworks")
    val jsonCount = artworkDir.listFiles { f -> f.isFile && f.extension == "json" }?.size ?: 0
    if (jsonCount != arts.size) {
        add("稿件文件数与解析数不一致", "目录 $jsonCount 个 .json，实际解析出 ${arts.size} 件（有槽没有版本，或 JSON 坏了）")
    }
    val blobDir = File(root, "blobs")
    val thumbDir = File(root, "thumbs")

    // blob 总数（跳过 .nomedia 与导入临时目录）
    val blobCount = runCatching {
        blobDir.walkTopDown()
            .onEnter { it.name != ".staging" }
            .count { it.isFile && it.name != ".nomedia" }
    }.getOrDefault(0)

    // 实体名字池：主名和别名都算，任何两个实体撞同一个词就是「同一个人被拆成两条」
    val artistNames = HashMap<String, String>()
    artists.all.forEach { e ->
        (listOf(e.name) + e.aliases).forEach { n ->
            val k = n.trim()
            if (k.isEmpty()) return@forEach
            val prev = artistNames.put(k, e.id)
            if (prev != null && prev != e.id) add("画师名字重复", "「$k」同时属于 ${prev} 和 ${e.id}")
        }
    }
    val tagNames = HashMap<String, String>()
    tags.all.forEach { e ->
        (listOf(e.name) + e.aliases).forEach { n ->
            val k = n.trim()
            if (k.isEmpty()) return@forEach
            val prev = tagNames.put(k, e.id)
            if (prev != null && prev != e.id) add("标签名字重复", "「$k」同时属于 ${prev} 和 ${e.id}")
        }
    }

    val validSystemFolders = setOf(ArtKind.Derived.wire, ArtKind.Fill.wire)
    folders.all.forEach { f ->
        val sys = f.system
        if (sys != null && sys !in validSystemFolders) {
            add("系统归档标记未知", "归档「${f.name}」(${f.id}) → system=$sys")
        }
    }

    val artIds = arts.mapTo(HashSet()) { it.id }

    arts.forEach { a ->
        // 定位用「标题 + 日期 + id 前 8 位」：只给 id 时人根本对不上是哪一件
        val label = buildString {
            append("「").append(a.title.takeIf { it.isNotBlank() }?.take(14) ?: "未命名").append("」")
            a.receivedAt?.let { append(" ").append(Dates.datePart(it)) }
            append(" · ${a.id.take(8)}")
        }

        // 文件名应当就是 id：不一致说明被外部工具改过名，以后按 id 恢复会找不到文件
        val file = File(artworkDir, "${a.id}.json")
        if (!file.isFile) add("文件名与 id 不一致", "稿件 $label 找不到 ${a.id}.json")

        // kind 拼写与原稿引用
        if (a.rawKind != null && a.kind == ArtKind.Artwork) {
            add("未知 kind", "稿件 $label → kind=${a.rawKind}")
        }
        // 制品/制图的归档必须是对应的系统归档（否则会从「制品」展柜里消失、导出目录也错）
        if (a.kind != ArtKind.Artwork) {
            val sys = folders.systemFolder(a.kind)?.id
            if (sys != null && a.folderId != sys) {
                add("归档与类型不符", "稿件 $label → kind=${a.kind.wire} 但 folder_id=${a.folderId ?: "null"}")
            }
            if (a.priceTotal != null) add("二次创作带价格", "稿件 $label → price_total=${a.priceTotal}")
        }
        if (a.kind == ArtKind.Fill && a.sourceArtId != null) {
            add("制图带原稿", "稿件 $label → source_art_id=${a.sourceArtId}")
        }
        // 命名必须正好是「原稿缺失」（不能以「引用悬空」结尾，避免触发自动清理悬空引用）
        a.sourceArtId?.let { if (it !in artIds) add("原稿缺失", "稿件 $label → source_art_id=$it 不存在") }

        // 主图：必须恰好一个
        val primaries = a.versions.count { it.isPrimary }
        if (primaries == 0) add("没有主图", "稿件 $label 的 ${a.versions.size} 个版本都没有 is_primary")
        if (primaries > 1) add("多个主图", "稿件 $label 有 $primaries 个 is_primary")

        // 版本 id：不能空、不能同槽重复（blob 可以重复，id 不行）
        val vIds = a.versions.map { it.id }
        if (vIds.any { it.isBlank() }) add("版本缺少 id", "稿件 $label 有版本没有 id")
        val dupV = vIds.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filterValues { it > 1 }
        dupV.keys.forEach { add("版本 id 重复", "稿件 $label 的版本 $it 出现 ${dupV[it]} 次") }

        // 引用必须可解析。解析不出在界面上只是「少显示」，所以必须在这里抓
        // 制品/制图不使用画师（制品的世界观与角色也继承自原稿），跳过不适用的引用检查
        if (a.kind == ArtKind.Artwork) {
            a.artistId?.let { if (artists.byId(it) == null) add("画师引用悬空", "稿件 $label → artist_id=$it 不存在") }
        }
        if (a.kind != ArtKind.Derived) {
            a.worldId?.let { if (tax.worlds.none { w -> w.id == it }) add("世界观引用悬空", "稿件 $label → world_id=$it 不存在") }
            a.roleIds.forEach { id ->
                if (tax.roles.none { r -> r.id == id }) add("角色引用悬空", "稿件 $label → character_id=$id 不存在")
            }
        }
        a.tagIds.forEach { id ->
            if (tags.byId(id) == null) add("标签引用悬空", "稿件 $label → tag_id=$id 不存在")
        }
        a.folderId?.let { id ->
            if (folders.byId(id) == null) add("归档引用悬空", "稿件 $label → folder_id=$id 不存在")
        }

        // 图片与缩略图必须在位。blob 缺了就是真的丢了图，比引用悬空严重得多
        a.versions.forEach { v ->
            if (v.blob.isBlank()) {
                add("版本缺少 blob", "稿件 $label 版本「${v.label}」没有 blob")
                return@forEach
            }
            val b = File(File(blobDir, v.blob.take(2)), "${v.blob}.${v.ext}")
            if (!b.isFile) add("原图缺失", "稿件 $label 版本「${v.label}」→ blobs/${v.blob.take(2)}/${v.blob}.${v.ext}")
            if (!File(thumbDir, "${v.blob}.w640.webp").isFile) {
                add("缩略图缺失", "稿件 $label 版本「${v.label}」→ thumbs/${v.blob}.w640.webp")
            }
        }

        // 数值与日期
        a.priceTotal?.let { if (it < 0) add("价格为负", "稿件 $label → $it") }
        if (a.receivedAt != null && Dates.parse(a.receivedAt) == null) {
            add("日期解析失败", "稿件 $label → 「${a.receivedAt}」")
        }
    }

    HealthReport(
        rootPath = root.absolutePath,
        artCount = arts.size,
        blobCount = blobCount,
        issues = issues,
    )
}

/**
 * 把指向不存在实体的引用清空（world_id / artist_id / folder_id 置 null，character_ids / tag_ids 摘掉）。
 *
 * 这是体检里唯一会自动改数据的动作，因为它**不丢信息**：那个 id 本来就没有对应实体，
 * 留着只会在界面上显示成「档案缺失」、让筛选和统计对不上。图片、标题、价格、备注一概不动。
 *
 * 为什么现在做而不是等 C 阶段：这类脏数据是**删实体时留下的**（比如删世界观没清引用），
 * 手工改 JSON 才能修，用户没法自己解决。
 */
suspend fun purgeDanglingRefs(
    root: File,
    arts: List<Art>,
    artists: ArtistBook,
    tags: TagBook,
    folders: FolderBook,
    tax: Taxonomy,
): Int = withContext(Dispatchers.IO) {
    val worldIds = tax.worlds.mapTo(HashSet()) { it.id }
    val roleIds = tax.roles.mapTo(HashSet()) { it.id }
    val artistIds = artists.all.mapTo(HashSet()) { it.id }
    val tagIds = tags.all.mapTo(HashSet()) { it.id }
    val folderIds = folders.all.mapTo(HashSet()) { it.id }
    val dir = File(root, "meta/artworks")
    var n = 0

    arts.forEach { a ->
        val f = File(dir, "${a.id}.json")
        if (!f.isFile) return@forEach
        runCatching {
            val o = JSONObject(f.readText())
            var changed = false

            fun single(key: String, valid: Set<String>) {
                val v = o.strOrNull(key) ?: return
                if (v !in valid) {
                    o.put(key, JSONObject.NULL)
                    changed = true
                }
            }
            single("world_id", worldIds)
            single("artist_id", artistIds)
            single("folder_id", folderIds)

            listOf("character_ids" to roleIds, "tag_ids" to tagIds).forEach { (key, valid) ->
                val arr = o.optJSONArray(key) ?: return@forEach
                val next = (0 until arr.length()).mapNotNull { arr.optString(it) }.filter { it in valid }
                if (next.size != arr.length()) {
                    o.put(key, JSONArray().also { out -> next.forEach { out.put(it) } })
                    changed = true
                }
            }

            if (changed && ArtworkCommands.commit(root, a.id, o)) n++
        }
    }
    n
}

/**
 * 体检结果弹窗。打开即扫（IO 线程），可以一键复制整份报告。
 * 只报告，不提供「自动修复」——修要能撤销、要能说清改了什么，那是 C 阶段的事。
 */
@Composable
fun DoctorDialog(
    root: File,
    arts: List<Art>,
    artists: ArtistBook,
    tags: TagBook,
    folders: FolderBook,
    tax: Taxonomy,
    onClose: () -> Unit,
    /** 清完悬空引用要让外面的库重新加载（界面上那些「档案缺失」才会消失） */
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<HealthReport?>(null) }
    var running by remember { mutableStateOf(true) }
    var confirmPurge by remember { mutableStateOf(false) }
    var purging by remember { mutableStateOf(false) }
    val clip = LocalClipboardManager.current

    LaunchedEffect(root) {
        report = inspectVault(root, arts, artists, tags, folders, tax)
        running = false
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("一致性检查", style = T.titleSm, color = Paper) },
        text = {
            Column {
                val r = report
                when {
                    running || r == null -> Text("正在扫库…", style = T.bodySm, color = Muted)

                    r.ok -> {
                        Text("全部通过", style = T.body, color = Mint, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "${r.artCount} 件稿件 · ${r.blobCount} 个 blob\n" +
                                "主图、版本 id、引用、名字、原图与缩略图都没问题。",
                            style = T.micro,
                            color = Muted,
                        )
                    }

                    else -> {
                        val grouped = r.issues.groupBy { it.kind }
                        Text(
                            "${r.issues.size} 个问题 · ${grouped.size} 类（每类最多列 20 条）",
                            style = T.label,
                            color = Warn,
                        )
                        Spacer(Modifier.height(Space.s))
                        LazyColumn(
                            Modifier.heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(Space.s),
                        ) {
                            grouped.forEach { (kind, list) ->
                                item {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(CardBg)
                                            .padding(10.dp),
                                    ) {
                                        Text("$kind · ${list.size}", style = T.label, color = Paper)
                                        Spacer(Modifier.height(Space.xxs))
                                        list.forEach { i ->
                                            Text(
                                                "· ${i.detail}",
                                                style = T.micro,
                                                color = Muted,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val r = report
            if (r != null && !r.ok) {
                Row {
                    if (r.hasDangling) {
                        TextButton(
                            onClick = { confirmPurge = true },
                            enabled = !purging,
                        ) {
                            Text(
                                if (purging) "清理中…" else "清理悬空引用",
                                style = T.label,
                                color = Warn,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    TextButton(onClick = { clip.setText(AnnotatedString(r.text())) }) {
                        Text("复制报告", style = T.label, color = Accent, fontWeight = FontWeight.Medium)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("关闭", style = T.label, color = Muted) }
        },
    )

    if (confirmPurge) {
        AlertDialog(
            onDismissRequest = { confirmPurge = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("清理悬空引用？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "会把指向不存在实体的 id 清空：世界观 / 画师 / 归档置为「未填」，角色 / 标签从列表里摘掉。\n" +
                        "图片、标题、价格、备注都不动，稿件本身也不会删。\n\n" +
                        "清完之后这些稿件会变成「未归类 / 未填画师」，你可以再手动补上。",
                    style = T.bodySm,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPurge = false
                    purging = true
                    scope.launch {
                        val n = purgeDanglingRefs(root, arts, artists, tags, folders, tax)
                        purging = false
                        toast(ctx, if (n > 0) "已清理 $n 件的悬空引用" else "没有可清理的")
                        if (n > 0) onChanged()
                        report = inspectVault(root, arts, artists, tags, folders, tax)
                    }
                }) { Text("清理", style = T.label, color = Warn, fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                TextButton(onClick = { confirmPurge = false }) {
                    Text("取消", style = T.label, color = Muted)
                }
            },
        )
    }
}
