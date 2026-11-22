package com.yejing.artvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GIcon
import com.yejing.artvault.ui.component.RoundIconButton
import kotlinx.coroutines.launch
import java.io.File


/**
 * 分类：**世界观·角色 / 归档** 两段（R5；画师与标签是第三段，下一批加）。
 *
 * ## 世界观·角色
 *
 * 一个世界一张大卡（[WorldCard]）：卡头是这个世界观本身，卡内是它下面的角色格。
 * 两个入口各管一件事——**点卡头进世界观页**（改名 / 删世界 / 调角色顺序 / 看该世界全部稿件），
 * **点头像进角色页**。角色格全展开不封顶：世界观就那么几个，折叠反而会在卡里留空洞。
 *
 * 「未归入世界观」也是一张卡，排最后、标题降一档色——它是真实状态，不是错误。
 *
 * ## 归档
 *
 * 导出相册时按它建一级目录。它和世界观解耦：世界观是设定归属，归档是「导到哪个文件夹」。
 * 「未分类」（folderId 为空）永远排最后，否则那批稿在任何展柜里都不出现。
 *
 * ## 这里不再有「角色」段
 *
 * 独立的角色段（含长按多选、拖动排序、批量移动、批量删除）在 R5 撤掉了：
 * 排序改到世界观页的「排序」弹窗（空间大、不必和点击抢手势），
 * 单角色的改名 / 换头像 / 移动到 / 删除本来就在角色页。
 * 代价是「批量移动 / 批量删除角色」没有了——覆盖表里按「删除」记。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaxonomyScreen(
    tax: Taxonomy,
    folders: FolderBook,
    arts: List<Art>,
    lib: Library,
    root: File?,
    onChanged: () -> Unit,
    onOpenRole: (String) -> Unit,
    /** 点世界观卡 → 进这个世界观的二级页（角色格 + 管理动作都在那里） */
    onOpenWorld: (WorldTarget) -> Unit,
    /** 点文件夹卡 → 进这个归档文件夹的二级页（稿件网格 + 改名/删除） */
    onOpenFolder: (FolderTarget) -> Unit,
    /** 点画师行 → 画师页（他的稿件网格 + 主页链接） */
    onOpenArtist: (String) -> Unit,
    /** 点标签行 → 回稿库筛选该标签 */
    onFilterTag: (String) -> Unit = {},
    artists: ArtistBook,
    tags: TagBook,
    onBack: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler { onBack() }

    var tab by remember { mutableIntStateOf(0) }
    var addWorld by remember { mutableStateOf(false) }
    var orderingWorlds by remember { mutableStateOf(false) }
    var addFolder by remember { mutableStateOf(false) }
    var orderingFolders by remember { mutableStateOf(false) }
    /** 画师段内部的子段：0 = 画师，1 = 标签 */
    var entityTab by remember { mutableIntStateOf(0) }
    /** 正在改名的画师（null = 没有弹窗） */
    var renamingArtist by remember { mutableStateOf<ArtistEntity?>(null) }
    /** 正在合并的画师（null = 没有弹窗） */
    var mergingArtist by remember { mutableStateOf<ArtistEntity?>(null) }
    /** 正在删除的画师（null = 没有弹窗） */
    var deletingArtist by remember { mutableStateOf<ArtistEntity?>(null) }
    /** 正在改名的标签（null = 没有弹窗） */
    var renamingTag by remember { mutableStateOf<TagEntity?>(null) }
    /** 正在合并的标签（null = 没有弹窗） */
    var mergingTag by remember { mutableStateOf<TagEntity?>(null) }
    /** 正在删除的标签（null = 没有弹窗） */
    var deletingTag by remember { mutableStateOf<TagEntity?>(null) }
    /*
     * 这里原来有一整套「角色多选 + 长按拖动排序」：selection / orderIds / reorderState /
     * exitWithSave / exitWithoutSave / 多选动作条（移动到 · 删除）。
     *
     * R5 把独立的「角色」段并进了世界观卡片，这套东西一并撤掉了：
     * - 角色格只有 78dp 宽，长按拖要和「点进角色页」抢手势，多选又要靠第二段长按起拖，两套叠在一起很难用；
     * - 排序改成世界观页里的「排序」弹窗（RoleOrderSheet），空间大、误触少；
     * - 单个角色的改名 / 换头像 / 移动到 / 删除，角色页本来就有。
     * 覆盖表里记为「删除：角色的批量移动与批量删除」。
     */

    // 文件夹分组：用户文件夹在上，系统文件夹（制品、制图）与「未分类」在下
    val userFolders = remember(folders.all) { folders.all.filter { it.system == null } }
    val sysFolders = remember(folders.all) { folders.all.filter { it.system != null } }

    // 「未分类」（folder_id 为空）的稿件：导出时会落到「未分类」目录，这里必须能看见、能点进去筛，
    // 否则这批稿在任何文件夹展柜里都不出现。放在 LazyColumn 外面算——LazyListScope 里不能调 remember。
    val unsorted = remember(arts) {
        arts.filter { it.folderId == null }.sortedByDescending { Dates.sortKey(it.receivedAt) }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val counted = remember(arts) { arts.countedOnly() }

    // 画师：件数与花费。没填画师的稿件不进统计（那批在「待整理」里算缺口）
    val artistWorks = remember(counted) {
        val n = HashMap<String, Int>()
        val sum = HashMap<String, Long>()
        counted.forEach { a ->
            val id = a.artistId ?: return@forEach
            n[id] = (n[id] ?: 0) + 1
            a.priceTotal?.let { sum[id] = (sum[id] ?: 0L) + it }
        }
        n to sum
    }
    // 标签：被多少件稿用过。与主库默认筛选（kind = Artwork）同一把尺子
    val tagWorks = remember(counted) {
        val m = HashMap<String, Int>()
        counted.forEach { a -> a.tagIds.forEach { m[it] = (m[it] ?: 0) + 1 } }
        m
    }

    // 画师 / 标签的显示顺序：用得多的在前。必须在这儿算 ——
    // LazyColumn 的 scope 是 LazyListScope，不是 @Composable，分支里调不了 remember。
    val artistRows = remember(artists.all, artistWorks) {
        artists.all.sortedByDescending { artistWorks.first[it.id] ?: 0 }
    }
    // 分类 → 画师页按首字母 / 拼音分组展示（与画师选择弹窗同一口径）
    val artistGroups = remember(artists.all) {
        groupByInitial(artists.all) { it.name }
    }
    // 画师花费占比条的分母：算一次，行内不再对全表求 max
    val maxArtistWorks = remember(artistWorks) { artistWorks.first.values.maxOrNull() ?: 0 }
    val tagRows = remember(tags.all, tagWorks) {
        tags.all.sortedByDescending { tagWorks[it.id] ?: 0 }
    }
    // 分类 → 标签页按首字母 / 拼音分组展示（与标签选择弹窗同一口径）
    val tagGroups = remember(tags.all) {
        groupByInitial(tags.all) { it.name }
    }

    // 角色格的占位图（V2）：没有头像的角色，用他最近一张有版本图的稿件缩略图。
    // 数据全是现成的，不新增字段 —— 只为把「8/9 格是琥珀色块 + 一个字」那个满屏噪点消掉。
    val roleThumb = remember(arts, lib) {
        val need = tax.roles.mapTo(HashSet()) { it.id }
        val m = HashMap<String, File>()
        for (a in arts.sortedByDescending { Dates.sortKey(it.receivedAt) }) {
            if (need.isEmpty()) break
            val v = a.primary ?: continue
            val f = lib.thumb(v)
            if (!f.isFile) continue   // 缩略图还没生成，别塞一个空框进去
            for (id in a.roleIds) if (need.remove(id)) m[id] = f
        }
        m
    }

    // 每个角色名下的稿件数（世界观卡片上「N 件」用它，只算普通稿）
    val roleCount = remember(counted) {
        val cnt = HashMap<String, Int>()
        counted.forEach { a -> a.roleIds.forEach { cnt[it] = (cnt[it] ?: 0) + 1 } }
        cnt
    }

    // 头部高度曾经要「锁」一次，因为多选态会换成一条更矮的 SelectionBar，进出时下面整块会跳。
    // 多选态撤掉后就不需要了：这里只剩一种头部，直接按内容量。

    Column(Modifier.fillMaxSize().background(Ink)) {
        run {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 10.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        // 底栏叫「分类」了（R2），页面标题要跟着改，否则一进去像换了个板块
                        Text("分类", style = T.display, color = Paper)
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            "${tax.worlds.size} 个世界观 · ${tax.roles.size} 个角色 · ${folders.all.size} 个文件夹",
                            style = T.label,
                            color = Muted,
                        )
                    }
                    // 画师和标签不在这里新建——它们是在稿件表单里"输入即建"的实体，
                    // 单独给一个"新建画师"入口会造出一堆没有任何作品的空画师。
                    val onPlus: (() -> Unit)? = when (tab) {
                        0 -> { { addWorld = true } }
                        2 -> { { addFolder = true } }
                        else -> null
                    }
                    val onSort: (() -> Unit)? = when {
                        tab == 0 && tax.worlds.size > 1 -> { { orderingWorlds = true } }
                        tab == 2 && userFolders.size > 1 -> { { orderingFolders = true } }
                        else -> null
                    }
                    if (onSort != null) {
                        RoundIconButton(
                            icon = GI.Sort,
                            onClick = onSort,
                            tint = OnMid,
                            background = CardBg,
                        )
                        Spacer(Modifier.width(Space.s))
                    }
                    if (onPlus != null) {
                        RoundIconButton(
                            icon = GI.Plus,
                            onClick = onPlus,
                            tint = Accent,
                            background = AccentC,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(CardBg)
                        .padding(3.dp),
                ) {
                    listOf("世界观·角色", "画师·标签", "文件夹").forEachIndexed { i, label ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (tab == i) AccentC else Color.Transparent)
                                .clickable { tab = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                style = T.bodySm,
                                color = if (tab == i) Accent else Muted,
                                fontWeight = if (tab == i) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.m))
            }
        }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 118.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(Space.m),
                modifier = Modifier.weight(1f),
            ) {
                when (tab) {
                    // ── 世界观·角色：一个世界一张大卡（R5） ────────────────
                    0 -> {
                        if (tax.worlds.isEmpty()) {
                            item {
                                EmptyHint("还没有世界观。\n点右上角 ＋ 建一个（比如「奇幻世界」「星海纪元」），再往里加角色。")
                            }
                        }
                        items(tax.worlds, key = { it.id }) { w ->
                            val roles = tax.rolesOf(w.id)
                            val works = remember(counted, w.id) { counted.count { it.worldId == w.id } }
                            WorldCard(
                                title = w.name,
                                sub = "${roles.size} 个角色 · $works 件",
                                roles = roles,
                                tax = tax,
                                roleWorks = { r -> roleCount[r.id] ?: 0 },
                                roleThumb = roleThumb,
                                onOpenRole = onOpenRole,
                                onClick = { onOpenWorld(WorldTarget(w.id)) },
                            )
                        }

                        // 没归入任何世界观的角色也要有地方看得见，否则「世界观」这一段就漏人
                        val orphans = tax.rolesOf(null)
                        if (orphans.isNotEmpty()) {
                            item {
                                WorldCard(
                                    title = "未归入世界观",
                                    sub = "${orphans.size} 个角色",
                                    roles = orphans,
                                    tax = tax,
                                    roleWorks = { r -> roleCount[r.id] ?: 0 },
                                    roleThumb = roleThumb,
                                    onOpenRole = onOpenRole,
                                    onClick = { onOpenWorld(WorldTarget(null)) },
                                )
                            }
                        }
                    }

                    // ── 画师 / 标签：同一段里两个子段 ──────────────────────
                    1 -> {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = Space.m),
                                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                            ) {
                                listOf("画师", "标签").forEachIndexed { i, label ->
                                    ChipPill(label, selected = entityTab == i) { entityTab = i }
                                }
                            }
                        }

                        if (entityTab == 0) {
                            if (artistGroups.isEmpty()) {
                                item {
                                    EmptyHint("还没有画师。\n在稿件的「画师」里输入名字就会自动建档，不用先来这里建。")
                                }
                            }
                            artistGroups.forEach { (letter, group) ->
                                stickyHeader(key = "artist_hdr_$letter") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(Ink)
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            letter,
                                            style = T.label,
                                            color = Accent,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(Space.xs))
                                        Text(
                                            "${group.size}",
                                            style = T.num,
                                            color = Muted,
                                        )
                                    }
                                }
                                items(group, key = { it.id }) { e ->
                                    ArtistRow(
                                        name = e.name,
                                        aliases = e.aliases,
                                        works = artistWorks.first[e.id] ?: 0,
                                        spend = artistWorks.second[e.id],
                                        writable = artists.writable,
                                        canMerge = artistRows.size > 1,
                                        onClick = { onOpenArtist(e.id) },
                                        onRename = { renamingArtist = e },
                                        onMerge = { mergingArtist = e },
                                        onDelete = { deletingArtist = e },
                                        maxWorks = maxArtistWorks,
                                    )
                                }
                            }
                        } else {
                            if (tagGroups.isEmpty()) {
                                item {
                                    EmptyHint("还没有标签。\n标签在稿件的「标签」里输入就建好了。")
                                }
                            }
                            tagGroups.forEach { (letter, group) ->
                                stickyHeader(key = "tag_hdr_$letter") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(Ink)
                                            .padding(vertical = 4.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            letter,
                                            style = T.label,
                                            color = Accent,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(Space.xs))
                                        Text(
                                            "${group.size}",
                                            style = T.num,
                                            color = Muted,
                                        )
                                    }
                                }
                                items(group, key = { it.id }) { e ->
                                    TagRow(
                                        name = e.name,
                                        works = tagWorks[e.id] ?: 0,
                                        writable = tags.writable,
                                        canMerge = tagRows.size > 1,
                                        onClick = { onFilterTag(e.name) },
                                        onRename = { renamingTag = e },
                                        onMerge = { mergingTag = e },
                                        onDelete = { deletingTag = e },
                                    )
                                }
                            }
                        }
                    }

                    // ── 文件夹：紧凑单列横向行（方案 B） ──
                    else -> {
                        if (userFolders.isEmpty() && sysFolders.isEmpty() && unsorted.isEmpty()) {
                            item {
                                EmptyHint("还没有文件夹。\n点右上角 ＋ 建一个（比如「原创」「同人」），导相册时按它建目录。")
                            }
                        }
                        items(userFolders, key = { it.id }) { f ->
                            val works = remember(arts, f.id) {
                                arts.filter { it.folderId == f.id }
                                    .sortedByDescending { Dates.sortKey(it.receivedAt) }
                            }
                            CollectionCard(
                                title = f.name,
                                sub = "${works.size} 件稿件",
                                thumb = works.firstOrNull()?.primary,
                                lib = lib,
                                onClick = { onOpenFolder(FolderTarget(f.id)) },
                            )
                        }
                        if (sysFolders.isNotEmpty() || unsorted.isNotEmpty()) {
                            if (userFolders.isNotEmpty()) {
                                item(key = "folder_tail_hdr") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp, bottom = 2.dp, start = 4.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("系统与未分类", style = T.micro, color = Muted)
                                        Spacer(Modifier.width(Space.s))
                                        Box(
                                            Modifier
                                                .weight(1f)
                                                .height(1.dp)
                                                .background(OutlineSoft),
                                        )
                                    }
                                }
                            }
                            items(sysFolders, key = { it.id }) { f ->
                                val works = remember(arts, f.id) {
                                    arts.filter { it.folderId == f.id }
                                        .sortedByDescending { Dates.sortKey(it.receivedAt) }
                                }
                                CollectionCard(
                                    title = f.name,
                                    sub = "${works.size} 件稿件",
                                    thumb = works.firstOrNull()?.primary,
                                    lib = lib,
                                    badge = "系统",
                                    onClick = { onOpenFolder(FolderTarget(f.id)) },
                                )
                            }
                            if (unsorted.isNotEmpty()) {
                                item(key = "folder_unsorted") {
                                    CollectionCard(
                                        title = "未分类",
                                        sub = "${unsorted.size} 件稿件",
                                        thumb = unsorted.firstOrNull()?.primary,
                                        lib = lib,
                                        muted = true,
                                        onClick = { onOpenFolder(FolderTarget(null)) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }


    renamingArtist?.let { a ->
        TextPromptDialog(
            title = "重命名画师",
            hint = "画师名",
            initial = a.name,
            onDismiss = { renamingArtist = null },
            onConfirm = { input ->
                renamingArtist = null
                val name = input.trim()
                if (name.isEmpty() || name == a.name) return@TextPromptDialog
                val ok = artists.rename(a.id, name)
                toast(
                    ctx,
                    if (ok) "已改成「$name」" else "改名失败：新名字已被另一个画师占用",
                )
                if (ok) onChanged()
            },
        )
    }

    mergingArtist?.let { a ->
        val targets = remember(artistRows, artistWorks, a.id) {
            artistRows
                .filter { it.id != a.id }
                .map { EntityMergeChoice(it.id, it.name, "${artistWorks.first[it.id] ?: 0} 件", artistWorks.first[it.id] ?: 0) }
        }
        EntityMergeDialog(
            kindLabel = "画师",
            intoName = a.name,
            works = artistWorks.first[a.id] ?: 0,
            targets = targets,
            onDismiss = { mergingArtist = null },
            onConfirm = { from ->
                mergingArtist = null
                val r = root
                if (r == null) {
                    toast(ctx, "演示库不能改")
                    return@EntityMergeDialog
                }
                scope.launch {
                    try {
                        val n = OperationJournal.run(r, "entity", "合并画师") {
                            EntityCommands.mergeArtist(r, artists, from.id, a.id)
                        }
                        toast(ctx, "已把「${from.name}」合并进来（$n 件）")
                        onChanged()
                    } catch (t: Throwable) {
                        toast(ctx, "合并失败：${t.message ?: t.javaClass.simpleName}")
                    }
                }
            },
        )
    }

    deletingArtist?.let { a ->
        val works = artistWorks.first[a.id] ?: 0
        EntityDeleteDialog(
            title = "删除画师「${a.name}」？",
            detail = "$works 件稿的画师会变成未填",
            onDismiss = { deletingArtist = null },
            onConfirm = {
                deletingArtist = null
                val r = root
                if (r == null) {
                    toast(ctx, "演示库不能改")
                    return@EntityDeleteDialog
                }
                scope.launch {
                    try {
                        val n = OperationJournal.run(r, "entity", "删除画师「${a.name}」") {
                            EntityCommands.deleteArtist(r, artists, a.id)
                        }
                        if (artists.byId(a.id) != null) {
                            toast(ctx, "删除失败，检查库目录是否可写")
                            return@launch
                        }
                        toast(ctx, if (n > 0) "已删除，$n 件稿的画师已变成未填" else "已删除")
                        onChanged()
                    } catch (t: Throwable) {
                        toast(ctx, "删除失败：${t.message ?: t.javaClass.simpleName}")
                    }
                }
            },
        )
    }

    renamingTag?.let { t ->
        TextPromptDialog(
            title = "重命名标签",
            hint = "标签名（不带 #）",
            initial = t.name,
            onDismiss = { renamingTag = null },
            onConfirm = { nm ->
                if (nm.isNotBlank()) {
                    // 撞名会被拒：两个标签合成一个身份是有损操作，不能靠改名顺手完成
                    if (tags.rename(t.id, nm)) onChanged() else toast(ctx, "改名失败：这个标签已经存在")
                }
                renamingTag = null
            },
        )
    }

    mergingTag?.let { t ->
        val targets = remember(tagRows, tagWorks, t.id) {
            tagRows
                .filter { it.id != t.id }
                .map { EntityMergeChoice(it.id, "#${it.name}", "${tagWorks[it.id] ?: 0} 件", tagWorks[it.id] ?: 0) }
        }
        EntityMergeDialog(
            kindLabel = "标签",
            intoName = t.name,
            works = tagWorks[t.id] ?: 0,
            targets = targets,
            onDismiss = { mergingTag = null },
            onConfirm = { from ->
                mergingTag = null
                val r = root
                if (r == null) {
                    toast(ctx, "演示库不能改")
                    return@EntityMergeDialog
                }
                scope.launch {
                    try {
                        val n = OperationJournal.run(r, "entity", "合并标签") {
                            EntityCommands.mergeTag(r, tags, from.id, t.id)
                        }
                        toast(ctx, "已把「${from.name}」合并进来（$n 件）")
                        onChanged()
                    } catch (err: Throwable) {
                        toast(ctx, "合并失败：${err.message ?: err.javaClass.simpleName}")
                    }
                }
            },
        )
    }

    deletingTag?.let { t ->
        val works = tagWorks[t.id] ?: 0
        EntityDeleteDialog(
            title = "删除标签「#${t.name}」？",
            detail = "$works 件稿的标签「#${t.name}」会被移除（没有其他标签的会变成未填）",
            onDismiss = { deletingTag = null },
            onConfirm = {
                deletingTag = null
                val r = root
                if (r == null) {
                    toast(ctx, "演示库不能改")
                    return@EntityDeleteDialog
                }
                scope.launch {
                    try {
                        val n = OperationJournal.run(r, "entity", "删除标签「${t.name}」") {
                            EntityCommands.deleteTag(r, tags, t.id)
                        }
                        if (tags.byId(t.id) != null) {
                            toast(ctx, "删除失败，检查库目录是否可写")
                            return@launch
                        }
                        toast(ctx, if (n > 0) "已删除，$n 件稿的标签已移除" else "已删除")
                        onChanged()
                    } catch (err: Throwable) {
                        toast(ctx, "删除失败：${err.message ?: err.javaClass.simpleName}")
                    }
                }
            },
        )
    }

    if (orderingWorlds) {
        val items = remember(tax.worlds, tax.roles, counted) {
            tax.worlds.map { w ->
                val rc = tax.rolesOf(w.id).size
                val wc = counted.count { it.worldId == w.id }
                OrderSheetItem(w.id, w.name, "$rc 个角色 · $wc 件")
            }
        }
        EntityOrderSheet(
            title = "排序 · 世界观",
            subtitle = "按住任意一行拖动。这个顺序会影响分类页与筛选里的世界观先后。",
            items = items,
            showAvatar = false,
            onDismiss = { orderingWorlds = false },
            onSave = { ordered ->
                orderingWorlds = false
                tax.reorderWorlds(ordered)
                onChanged()
            },
        )
    }

    if (orderingFolders) {
        val items = remember(userFolders, arts) {
            userFolders.map { f ->
                val wc = arts.count { it.folderId == f.id }
                OrderSheetItem(f.id, f.name, "$wc 件稿件")
            }
        }
        EntityOrderSheet(
            title = "排序 · 文件夹",
            subtitle = "按住任意一行拖动。这个顺序会影响分类页与筛选里的文件夹先后。",
            items = items,
            showAvatar = false,
            onDismiss = { orderingFolders = false },
            onSave = { ordered ->
                orderingFolders = false
                folders.reorder(ordered)
                onChanged()
            },
        )
    }

    if (addWorld) {
        TextPromptDialog(
            title = "新建世界观",
            hint = "奇幻世界 / 星海纪元 / 现代都市…",
            onDismiss = { addWorld = false },
            onConfirm = { name ->
                tax.addWorld(name)
                addWorld = false
                onChanged()
            },
        )
    }

    if (addFolder) {
        TextPromptDialog(
            title = "新建文件夹",
            hint = "分类名（就是导出时的文件夹名）",
            onDismiss = { addFolder = false },
            onConfirm = { name ->
                addFolder = false
                if (folders.ensure(name) == null) toast(ctx, "新建失败，检查库目录是否可写")
                onChanged()
            },
        )
    }


}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
        Text(text, style = T.body, color = Muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}


/**
 * 世界观卡：外框和展柜一致（标题居中 + 数量），但里面装的是**角色头像**——
 * 「这个世界观下有哪些角色」只有这里能一眼看全，换成稿件缩略图就把这个能力弄丢了。
 * 头像 4 个一排，全部列出（不截断）；点卡片回稿库筛该世界观，点头像进角色页。
 */
/**
 * 世界观大卡（R5，方案 B）：卡头是世界观本身，卡内是这个世界观下的角色。
 *
 * 两个入口各管一件事：
 * - 点卡头那一行 → 世界观页（改名 / 删世界 / 调角色顺序 / 移动角色 / 看该世界全部稿件）
 * - 点头像 → 角色页（这个角色的全部稿件）
 *
 * 为什么是「卡」而不是「细线分隔的连续网格」：世界观是这张卡的主体，
 * 视线先落到「这是哪个世界」，再往下扫角色。细线那版里世界观只剩一行字，压不住。
 */
/**
 * 画师行：圆形首字 + 名字（有别名时跟一行）+ 件数与花费。
 *
 * 不在这里放「打开主页」按钮——点整行进画师页，主页链接在那一页。
 * 行内再塞一个外链按钮，既难点又容易误触（它是往浏览器跳的动作）。
 */
@Composable
private fun ArtistRow(
    name: String,
    aliases: List<String>,
    works: Int,
    spend: Long?,
    writable: Boolean,
    canMerge: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
    /** 全表最大件数：调用方算一次传进来，别在每一行里对全表求 max */
    maxWorks: Int,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, RimBrush, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(AccentC),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), style = T.label, color = Accent)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = T.body,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (aliases.isNotEmpty()) {
                Text(
                    aliases.joinToString(" / "),
                    style = T.micro,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (maxWorks > 0) {
                // 花费占比细条：只在没有别名时出现，别名那一行信息更重
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.5.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(CHigh),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((works.toFloat() / maxWorks).coerceIn(0f, 1f))
                            .height(1.5.dp)
                            .background(Accent.copy(alpha = 0.7f)),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("$works 件", style = T.micro, color = OnMid)
            Text(
                if (spend != null) "¥$spend" else "价格未填",
                style = T.micro,
                color = if (spend != null) Amber else Muted,
            )
        }
        if (writable) {
            Spacer(Modifier.width(Space.xxs))
            RowMoreMenu(
                canMerge = canMerge,
                onRename = onRename,
                onMerge = onMerge,
                onDelete = onDelete,
            )
        } else {
            Text("›", style = T.label, color = Muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
        }
    }
}

/**
 * 标签行：#名字 + 用过多少件 + 「⋯」（重命名 / 合并进… / 删除）。
 */
@Composable
private fun TagRow(
    name: String,
    works: Int,
    writable: Boolean,
    canMerge: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, RimBrush, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "#$name",
            style = T.body,
            color = Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("$works 件", style = T.micro, color = Muted)
        if (writable) {
            Spacer(Modifier.width(Space.xs))
            RowMoreMenu(
                canMerge = canMerge,
                onRename = onRename,
                onMerge = onMerge,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun RowMoreMenu(
    canMerge: Boolean,
    onRename: () -> Unit,
    onMerge: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            GIcon(GI.More, Muted, size = 16.dp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = CHigh,
        ) {
            DropdownMenuItem(
                text = { Text("重命名", style = T.bodySm, color = Paper) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "合并其他",
                        style = T.bodySm,
                        color = if (canMerge) Paper else Muted,
                    )
                },
                enabled = canMerge,
                onClick = {
                    open = false
                    onMerge()
                },
            )
            DropdownMenuItem(
                text = { Text("删除", style = T.bodySm, color = Warn) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun WorldCard(
    title: String,
    sub: String,
    roles: List<Role>,
    tax: Taxonomy,
    roleWorks: (Role) -> Int,
    /** 角色 id → 占位缩略图（没有头像时用） */
    roleThumb: Map<String, File>,
    onOpenRole: (String) -> Unit,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(1.dp, RimBrush, RoundedCornerShape(18.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        // 卡头整行 44dp 可点：比一个小箭头好点，也明确「这里进的是世界观」
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                // 渐到 Transparent 而不是 CardBg：用 CardBg 中段会出现一条色阶
                .background(Brush.horizontalGradient(listOf(CHigh, Color.Transparent)))
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Accent),
            )
            Spacer(Modifier.width(Space.s))
            Text(
                title,
                style = T.titleSm,
                color = Paper,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(sub, style = T.micro, color = Muted, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text("›", style = T.label, color = Muted)
        }

        Spacer(Modifier.height(12.dp))

        if (roles.isEmpty()) {
            Text(
                "还没有角色",
                style = T.micro,
                color = Muted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                textAlign = TextAlign.Center,
            )
        } else {
            // 3 列、全展开不封顶：世界观就那么几个，封顶反而会在卡里留空洞
            val rows = roles.chunked(3)
            rows.forEachIndexed { idx, row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { r ->
                        RoleCell(
                            role = r,
                            avatar = tax.avatarFile(r),
                            thumb = roleThumb[r.id],
                            works = roleWorks(r),
                            onClick = { onOpenRole(r.id) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                if (idx < rows.lastIndex) {
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

/** 卡内的一个角色格：纯方形头像 + 外置名字与件数 */
@Composable
private fun RoleCell(
    role: Role,
    avatar: File?,
    /** 没有头像时的占位：这个角色最近一件稿件的缩略图 */
    thumb: File?,
    works: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                // 底色从 AccentC 换成 CHigh：琥珀只留给「选中 / 价格 / 主操作」，
                // 满屏占位块用琥珀会让整页看起来都在「亮着」
                .background(CHigh),
            contentAlignment = Alignment.Center,
        ) {
            val src = avatar ?: thumb
            if (src != null) {
                AsyncImage(
                    model = src,
                    contentDescription = role.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(role.name.take(1), style = T.title, color = Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            role.name,
            style = T.label,
            color = Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            "$works 件",
            style = T.num,
            color = Muted,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/**
 * 文件夹行卡（方案 B）：左 56dp 方形封面（最近一张稿件，空态显示图标）+ 中标题/系统角标/件数 + 右箭头。
 */
@Composable
private fun CollectionCard(
    title: String,
    sub: String,
    thumb: Ver?,
    lib: Library,
    badge: String? = null,
    muted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .border(1.dp, RimBrush, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(CHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb == null) {
                GIcon(GI.Image, Muted, size = 20.dp, strokeWidth = 1.6f)
            } else {
                AsyncImage(
                    model = lib.thumb(thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = T.body,
                    color = if (muted) OnMid else Paper,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(Space.xs))
                    Text(
                        badge,
                        style = T.micro,
                        color = Muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(CHigh)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(sub, style = T.num, color = Muted, maxLines = 1)
        }
        Spacer(Modifier.width(Space.xs))
        Text("›", style = T.label, color = Muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}


/** 世界观二级页的目标；worldId = null 表示「未归入世界观」这个虚拟分组 */
data class WorldTarget(val worldId: String?)

/** 归档文件夹二级页的目标；folderId = null 表示「未分类」 */
data class FolderTarget(val folderId: String?)

@Composable
fun TextPromptDialog(
    title: String,
    hint: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var v by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = T.titleSm, color = Paper) },
        text = {
            BasicTextField(
                value = v,
                onValueChange = { v = it },
                singleLine = true,
                textStyle = T.body.copy(color = Paper),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(CardBg)
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (v.isBlank()) {
                            Text(hint, style = T.body, color = Muted)
                        }
                        inner()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (v.isNotBlank()) {
                    onConfirm(v.trim())
                    onDismiss()
                }
            }) { Text("确定", style = T.label, color = Accent, fontWeight = FontWeight.Medium) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", style = T.label, color = Muted) }
        },
    )
}
