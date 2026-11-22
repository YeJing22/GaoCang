package com.yejing.artvault

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yejing.artvault.ui.component.ExportConfirmDialog
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GIcon
import com.yejing.artvault.ui.component.RoundIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import java.io.File

/** 一行固定高度：拖动时的让位动画按行高算，变量高度会让落点看着跳 */
private val ROW_H = 76.dp

/** 详情页版本模式下，底部操作条发给当前页 [VersionEditor] 的指令 */
enum class VersionCmdKind { Export, Delete }

data class VersionCmd(val artId: String, val kind: VersionCmdKind)

/**
 * 版本管理：改名、设主图、长按拖动排序、删除。
 *
 * 排序只改 JSON 数组顺序（同时写 `sort` 字段），**不动图片文件**。
 * 删除同理：版本没有独立文件，只是从数组里摘掉；blob 是内容寻址、可能被别的槽共享，绝不能删。
 *
 * 拖动排序和角色网格共用 [rememberListReorderState]（见 Reorder.kt）。差别只有落盘时机：
 * 这里松手就写（版本数量少、没有「编辑态」的概念），角色那边要等点「完成」。
 */
@Composable
fun VersionEditor(
    art: Art,
    lib: Library,
    root: File?,
    tax: Taxonomy,
    folders: FolderBook,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 当前正在看的版本 **id**（不是下标）：整行高亮与底部条指令的落点都认它。
     *
     * 为什么不用下标：`items` 是「主图置顶」之后的顺序，而详情页的大图 pager 用的是
     * `art.versions` 的原始顺序，两者只在「迁移写盘 + 重载完成」之后才一致。
     * 拿下标去索引，一旦错位就是「删掉/导出的是另一版」，而且不报错不崩。
     */
    currentId: String? = null,
    /** 整行点击：把大图切到这一版（参数是版本 id）。null = 整行不可点（独立页用不到） */
    onSelect: ((String) -> Unit)? = null,
    /** 详情页版本模式下，底部操作条发来的「导出版本 / 删除版本」指令 */
    command: VersionCmd? = null,
    onCommandHandled: () -> Unit = {},
    /**
     * 解析制品原稿角色名要用（导出文件名里的「角色」字段）。
     * 制品自己不存角色，全靠原稿；不传的话文件名会变成 `未归属`。
     */
    allArts: List<Art> = emptyList(),
    /** 内嵌在详情页的 verticalScroll 里时为 true：列表完全展开由外层滚动，行内导出/删除由底部条接管 */
    embedded: Boolean = onSelect != null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 主图恒为第 0 位：进来就把主图那条排到最前（稳定排序，其余保持原相对顺序）。
    // 这样「主图」不再是一个可能和顺序打架的下标，而就是列表第一位本身。
    var items by remember(art.id, art.versions) {
        // uid 当列表 key：blob 可能重复（同一张图被加成两个版本），拿它当 key 会崩「Key was already used」
        mutableStateOf(
            art.versions.sortedByDescending { it.isPrimary }
                .mapIndexed { i, v ->
                    VerEdit(v.blob, v.label.ifBlank { "未命名" }, uid = "${v.blob}#$i", id = v.id)
                },
        )
    }
    var renaming by remember { mutableStateOf<Int?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Int?>(null) }
    // 多选态：勾出若干版本拆成新槽。不用长按进多选（长按被拖拽排序占了）
    var selecting by remember(art.id) { mutableStateOf(false) }
    var selected by remember(art.id) { mutableStateOf<Set<String>>(emptySet()) }
    // 拆分进行中：挡住连点。第二次点击本来也必然失败（版本已经被第一批摘走了），
    // 但那会额外弹一句「拆分失败」，看着像第一次没成功
    var splitting by remember { mutableStateOf(false) }

    /** 当前版本在 [items] 里的位置；-1 = 不知道 / 已经不在列表里（按 id 查，不按下标猜） */
    val currentIdx = if (currentId == null) -1 else items.indexOfFirst { it.id == currentId }

    LaunchedEffect(items) {
        if (items.size <= 1 && selecting) {
            selecting = false
            selected = emptySet()
        } else if (selected.isNotEmpty()) {
            val valid = items.map { it.uid }.toSet()
            selected = selected.filter { it in valid }.toSet()
        }
    }

    /** 只有两个版本（主图 + 另一个）或处于多选态时，整列禁用拖拽 */
    val canReorder = items.size > 2 && !selecting

    val listState = rememberLazyListState()
    val reorderState = rememberListReorderState(listState, items.map { it.uid }) { ids ->
        // 主图钉死第一位：不管库报什么落点，重排后都把主图那条塞回 0 号位
        val byUid = items.associateBy { it.uid }
        val head = items.firstOrNull()?.let { byUid[it.uid] }
        val rest = ids.filter { it != head?.uid }.mapNotNull { byUid[it] }
        items = if (head != null) listOf(head) + rest else rest
    }

    /**
     * 历史里能认出来的稿件名。没有标题时用收稿日期 —— 只写「未命名」的话，
     * 一堆未命名稿件的版本操作在历史里长得一模一样，根本分不出改的是哪张。
     */
    val artLabel = art.title.ifBlank {
        val d = art.receivedAt?.take(10).orEmpty()
        if (d.length == 10) "未命名（$d）" else "未命名"
    }

    // 单版本导出的确认：verId to label；null = 不弹框
    var askExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    /**
     * 单版本导出：落在「相册」下，和全量镜像同一套命名（见 `Mirror.export`）。
     *
     * 文案走 [exportVersionMessage] —— 详情页那条「文案阶梯」只留一份，
     * 这里以前自己抄了一份，已经和它漂了（少了 skipped 分支）。
     */
    fun exportOne(verId: String) {
        val r = root
        if (r == null) {
            Toast.makeText(ctx, "演示库不能导出", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            // allArts 必须带上原稿，否则制品的导出文件名里角色会变成「未归属」
            val msg = exportVersionMessage(
                ctx, r, lib, art, tax, folders, verId,
                allArts.ifEmpty { listOf(art) },
            )
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 把勾选的版本拆成一个新槽：留在当前页、不跳走、包 OperationJournal 支持撤销 */
    fun splitSelected() {
        if (splitting) return
        val r = root
        if (r == null) {
            Toast.makeText(ctx, "演示库不能拆分", Toast.LENGTH_SHORT).show()
            return
        }
        val pickedItems = items.filter { it.uid in selected }
        // 全选 = 搬空源槽，那是删除的语义，先拦掉（按钮本来就禁用，这里兜一次直接调用）
        if (pickedItems.size >= items.size) {
            Toast.makeText(ctx, "原稿至少保留一个版本", Toast.LENGTH_SHORT).show()
            return
        }
        val verIds = pickedItems.map { it.id }.filter { it.isNotBlank() }
        // 拆分靠版本 id 精确认人。老数据（PC 侧写的、从没过一遍 normalize）可能没有 id，
        // 这时不能拿 blob 或下标顶替 —— blob 会重复、下标会错位，结果是拆错版本还看不出。
        // 进版本模式时那段迁移会顺带补 id（见下面的 LaunchedEffect(art.id)），重进一次即可。
        if (verIds.size != pickedItems.size) {
            Toast.makeText(ctx, "版本标识缺失，退出「版本管理」再进一次后重试", Toast.LENGTH_LONG).show()
            return
        }
        val pickedUids = pickedItems.map { it.uid }.toSet()
        val newTitle = "${art.title.ifBlank { "未命名" }} · 拆出"
        splitting = true
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    runCatching {
                        OperationJournal.run(r, "art", "拆出 ${verIds.size} 个版本") {
                            ArtworkCommands.splitVersions(r, art.id, verIds, tax)
                        }
                    }
                }
                val newId = res.getOrNull()
                if (newId == null) {
                    // 错误信息分两种：「参数不合法」（返回 null）与「写盘失败」（抛出来，已由 journal 回滚）
                    val why = res.exceptionOrNull()?.message
                    Toast.makeText(ctx, why ?: "拆分失败", Toast.LENGTH_SHORT).show()
                } else {
                    items = items.filterNot { it.uid in pickedUids }
                    selecting = false
                    selected = emptySet()
                    Toast.makeText(ctx, "已拆出 ${verIds.size} 个版本，新稿「$newTitle」", Toast.LENGTH_SHORT).show()
                    onChanged()
                }
            } finally {
                splitting = false
            }
        }
    }

    // 详情页版本模式下，底部操作条的「导出版本 / 删除版本」交到这里执行，针对当前高亮的那一版。
    // 落点按**版本 id** 找（不是详情页传下来的下标）：两边顺序不一致时，下标会指到另一版。
    LaunchedEffect(command) {
        val cmd = command ?: return@LaunchedEffect
        if (cmd.artId != art.id) return@LaunchedEffect
        onCommandHandled()
        val idx = currentIdx
        if (idx < 0) {
            Toast.makeText(ctx, "找不到正在看的版本，点一下列表里那一版再试", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        val item = items.getOrNull(idx) ?: return@LaunchedEffect
        when (cmd.kind) {
            VersionCmdKind.Export -> {
                if (root == null) {
                    Toast.makeText(ctx, "演示库不能导出", Toast.LENGTH_SHORT).show()
                } else {
                    val verId = item.id.ifBlank {
                        art.versions.firstOrNull { it.blob == item.blob }?.id.orEmpty()
                    }
                    askExport = verId to item.label
                }
            }
            VersionCmdKind.Delete -> {
                if (items.size <= 1) {
                    Toast.makeText(ctx, "至少保留一个版本", Toast.LENGTH_SHORT).show()
                } else {
                    deleting = idx
                }
            }
        }
    }

    /**
     * [action] 是这次改动做了什么（改名 / 设主图 / 调整顺序 / 删除），写进历史摘要。
     *
     * [journal] 为 false 表示**这次改动不进操作历史**。目前只有「删除版本」走这条：
     * 它已经在回收站里（`meta/artworks/.trash/versions/`），撤销的入口就在「我的 → 回收站」。
     * 这和「删整槽」是同一条口径（`deleteArts` 也从不包 `OperationJournal.run`）。
     *
     * 为什么不能两边都算（2026-11-21 用户发现的问题）：撤销不会去清回收站里那份快照，
     * 结果版本已经回来了、回收站计数还虚高，点「恢复」提示成功却什么都没变（走了去重分支）。
     * 宁可少一个入口，也不要两个入口互相不知道对方。
     */
    fun commit(next: List<VerEdit>, action: String, journal: Boolean = true) {
        val r = root
        if (r == null) {
            Toast.makeText(ctx, "演示库不能改", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                // 一次改动 = 一条历史（改名 / 设主图 / 排序各自算一次），
                // 撤销就退回改动前那一版；主图也在快照里，所以「撤销后主图还是原来那张」
                runCatching {
                    if (journal) {
                        OperationJournal.run(r, "version", "版本 · $artLabel · $action") {
                            // 主图永远在 0 号位，所以主图下标恒为 0
                            applyVersionEdits(r, art.id, next, 0)
                        }
                    } else {
                        applyVersionEdits(r, art.id, next, 0)
                    }
                }.getOrElse { false }
            }
            when {
                !ok -> Toast.makeText(ctx, "写入失败", Toast.LENGTH_SHORT).show()
                // 删除不弹撤销条，至少要告诉人「去哪找」
                !journal -> {
                    Toast.makeText(ctx, "已删除，可在「我的 → 回收站」找回", Toast.LENGTH_SHORT).show()
                    onChanged()
                }
                else -> onChanged()
            }
        }
    }

    // 返回键不在这里处理：内嵌到详情页时由详情页管，独立页由 VersionScreen 管。
    // （以前这里写了一行 `BackHandler { onClose() }`，抽成 VersionEditor 后 onClose 已经不存在了。）

    // 老数据的主图不一定在第 0 位：进来顺一次，把「主图置顶」变成库里真实存在的顺序，
    // 否则面板显示主图在首位、详情页的版本点还是老顺序，两边对不上。
    // 只有真的需要才写盘（写一次之后就永远一致了），演示库 root 为 null 就跳过。
    LaunchedEffect(art.id) {
        val r = root ?: return@LaunchedEffect
        val stored = art.versions
        if (stored.isEmpty()) return@LaunchedEffect
        val ordered = stored.first().isPrimary && stored.count { it.isPrimary } == 1
        // 版本 id 缺失/重复时也要写一次：`setVersions` 给空 id 补新 id，`normalize` 修重复。
        // 「拆分」和「单版本导出」都靠 id 认版本 —— 只在顺序不对时才写的话，
        // 一份顺序恰好正确的老数据会永远没有 id，拆版本时只能报「版本标识缺失」。
        val idsOk = stored.all { v -> v.id.isNotBlank() && stored.count { it.id == v.id } == 1 }
        if (ordered && idsOk) return@LaunchedEffect
        val ok = withContext(Dispatchers.IO) { applyVersionEdits(r, art.id, items, 0) }
        if (ok) onChanged()
    }

    askExport?.let { (verId, label) ->
        ExportConfirmDialog(
            title = "导出「$label」？",
            onCancel = { askExport = null },
            onConfirm = {
                askExport = null
                exportOne(verId)
            },
        )
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    selecting -> "勾选要拆成新稿的版本（原稿至少留 1 个）"
                    canReorder -> "${items.size} 个版本 · 长按拖动排序，点「改名」改名"
                    else -> "${items.size} 个版本 · 主图置顶，暂无可调整的顺序"
                },
                style = T.micro,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            if (items.size > 1) {
                Spacer(Modifier.width(Space.s))
                Text(
                    if (selecting) "退出多选" else "多选",
                    style = T.micro,
                    color = if (selecting) Accent else OnMid,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selecting) AccentC else CardBg)
                        .clickable {
                            selecting = !selecting
                            selected = emptySet()
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        // 内嵌在详情页的 verticalScroll 里时：完全展开为所有行的精确高度，且禁用自身滚动，
        // 让整页像表单一样整体往下滑；独立页（VersionScreen）里则 weight(1f) 自己滚。
        val listModifier = if (embedded) {
            Modifier
                .fillMaxWidth()
                .height((ROW_H + 1.dp) * items.size)
        } else {
            Modifier
                .fillMaxWidth()
                .weight(1f)
        }

        LazyColumn(
            state = listState,
            userScrollEnabled = !embedded,
            modifier = listModifier,
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            itemsIndexed(items, key = { _, v -> v.uid }) { i, v ->
                // enabled = false 的行不会被别的行挤走；主图锁在第 0 位，两版本或开启多选时整列不参与
                ReorderableItem(
                    reorderState,
                    key = v.uid,
                    enabled = canReorder && i != 0,
                ) { isDragging ->
                    val ver = art.versions.firstOrNull { it.blob == v.blob }
                    val picked = v.uid in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(ROW_H)
                            // 被拖的那行跟着手指走（库自己 translate），这里只加抬起来的观感
                            .graphicsLayer {
                                shadowElevation = if (isDragging) 12f else 0f
                                scaleX = if (isDragging) 1.02f else 1f
                                scaleY = if (isDragging) 1.02f else 1f
                            }
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDragging) CHigh else Color.Transparent)
                            // 正在看的那一版：整行底色（主图的琥珀描边在缩略图上，两者可同时出现）
                            .background(
                                if (!selecting && i == currentIdx) AccentC.copy(alpha = 0.35f) else Color.Transparent,
                                RoundedCornerShape(12.dp),
                            )
                            // 多选优先：开启多选后整行点击 = 勾选；否则整行点击 = 切版本（硬切）
                            .then(
                                when {
                                    selecting -> Modifier.clickable {
                                        selected = if (picked) selected - v.uid else selected + v.uid
                                    }
                                    onSelect != null -> Modifier.clickable { onSelect(v.id) }
                                    else -> Modifier
                                },
                            )
                            // 长按起拖；松手即落盘（版本管理没有「完成」这一步）。
                            // 主图行、两版本、多选态都不挂手柄 —— 拖动没有意义或会抢手势。
                            .then(
                                if (canReorder && i != 0) {
                                    Modifier.longPressDraggableHandle(
                                        onDragStopped = { commit(items, "调整顺序") },
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selecting) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (picked) Accent else Ink60)
                                    .then(
                                        if (!picked) Modifier.border(1.5.dp, Paper70, CircleShape)
                                        else Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (picked) GIcon(GI.Check, OnAccent, size = 12.dp, strokeWidth = 2.4f)
                            }
                            Spacer(Modifier.width(10.dp))
                        }

                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CHigh)
                                .border(
                                    width = if (i == 0) 1.5.dp else 0.8.dp,
                                    color = if (i == 0) Accent else Color.White.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(12.dp),
                                ),
                        ) {
                            if (ver?.hasAlpha == true) CheckerBoard(cell = 6.dp)
                            AsyncImage(
                                model = File(lib.thumbsDir, "${v.blob}.w640.webp"),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                v.label,
                                style = T.body,
                                color = Paper,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (ver != null && ver.w > 0) "${ver.w}×${ver.h}" else "尺寸未知",
                                style = T.micro,
                                color = Muted,
                            )
                        }

                        if (!selecting) {
                            Text(
                                "改名",
                                style = T.micro,
                                color = Muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        renaming = i
                                        renameText = v.label
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )

                            if (i == 0) {
                                Text(
                                    "主图",
                                    style = T.micro,
                                    color = Accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(AccentC)
                                        .padding(horizontal = 9.dp, vertical = 4.dp),
                                )
                            } else {
                                ChipPill("设主图") {
                                    // 设主图 = 把它提到第一位，原主图顺位后退
                                    val name = items[i].label
                                    val next = items.toMutableList().apply { add(0, removeAt(i)) }
                                    items = next
                                    commit(next, "设为主图「$name」")
                                }
                            }
                            // 详情页版本模式下，底部操作条接管当前版本的「导出 / 删除」，行内不再重复放
                            if (!embedded) {
                                Spacer(Modifier.width(Space.xxs))
                                Text(
                                    "导出",
                                    style = T.micro,
                                    color = Accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable(enabled = ver != null) {
                                            if (root == null) {
                                                Toast.makeText(ctx, "演示库不能导出", Toast.LENGTH_SHORT).show()
                                            } else {
                                                ver?.let { askExport = it.id to v.label }
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    "删除",
                                    style = T.micro,
                                    color = if (items.size > 1) Warn else Muted,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .clickable(enabled = items.size > 1) { deleting = i }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(0.6.dp).background(OutlineSoft))
                }
            }
        }

        if (selecting) {
            val selCount = selected.size
            val allSelected = selCount >= items.size
            val canSplit = selCount > 0 && !allSelected && !splitting
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .border(1.dp, Outline, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (allSelected) "不能全选，原稿至少留 1 个" else "已选 $selCount 项",
                    style = T.bodySm,
                    color = if (allSelected) Warn else Paper,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "取消",
                    style = T.bodySm,
                    color = Muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            selecting = false
                            selected = emptySet()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (canSplit) Accent else CHigh)
                        .clickable(enabled = canSplit) { splitSelected() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "拆成新稿",
                        style = T.bodySm,
                        color = if (canSplit) OnAccent else Muted,
                    )
                }
            }
        }

        Text(
            "删除的版本会进回收站，可以加回来；图片文件本来也保留（多槽可能共用同一张）。至少留一个版本。",
            style = T.micro,
            color = Muted,
            modifier = Modifier.padding(
                start = 20.dp,
                end = 20.dp,
                top = 10.dp,
                bottom = if (embedded) 16.dp else 24.dp,
            ),
        )
        if (!embedded) {
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    val r = renaming
    if (r != null && r in items.indices) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("版本名称", style = T.titleSm, color = Paper) },
            text = {
                Column {
                    androidx.compose.foundation.text.BasicTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        textStyle = T.bodySm.copy(color = Paper),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardBg)
                            .border(1.dp, Outline, RoundedCornerShape(12.dp))
                            .padding(horizontal = 13.dp, vertical = 12.dp),
                    )
                    Spacer(Modifier.height(Space.s))
                    Text("例：带背景 / 透明底 / 线稿", style = T.micro, color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = renameText.trim()
                    if (name.isNotEmpty() && name != items[r].label) {
                        val oldName = items[r].label
                        val next = items.toMutableList()
                        next[r] = next[r].copy(label = name)
                        items = next
                        commit(next, "改名「$oldName」→「$name」")
                    }
                    renaming = null
                }) { Text("保存", style = T.body, color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消", style = T.body, color = Muted) }
            },
        )
    }

    val d = deleting
    if (d != null && d in items.indices) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("删除「${items[d].label}」？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "删除的版本会进回收站，可以加回来；图片文件保留（多槽可能共用同一张）。",
                    style = T.micro,
                    color = Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // 删掉的若是主图（第 0 位），顺位第一个自动接任
                    val name = items[d].label
                    val next = items.toMutableList()
                    next.removeAt(d)
                    items = next
                    // journal = false：删除走回收站，不进操作历史（与删整槽同一条口径）
                    commit(next, "删除版本「$name」", journal = false)
                    deleting = null
                }) { Text("删除", style = T.body, color = Warn) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消", style = T.body, color = Muted) }
            },
        )
    }
}

/**
 * 独立页形态的版本管理。
 *
 * 详情页已经不用它了：那边走的是「页内版本模式」（把 InfoTab 换成 [VersionEditor]，见卡 13）。
 * 这层外壳 + Root 的 `versionOf` 分支暂时留着当回退，清理时整条链路一起删。
 */
@Composable
fun VersionScreen(
    art: Art,
    lib: Library,
    root: File?,
    tax: Taxonomy,
    folders: FolderBook,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    /** 加版本：走和详情页同一个入口（选图 → 追加到这个槽） */
    onAddVersion: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(GI.Close, onClick = onClose, background = Color.Transparent)
            Spacer(Modifier.width(Space.xxs))
            Text("版本管理", style = T.title, color = Paper)
            Spacer(Modifier.weight(1f))
            // 在这里也能加：整理版本时想补一个是最自然的时机，不该让人退出去再加
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(AccentC)
                    .clickable { onAddVersion(art.id) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("＋", style = T.micro, color = Accent)
                Text("加版本", style = T.micro, color = Accent)
            }
        }
        VersionEditor(
            art = art,
            lib = lib,
            root = root,
            tax = tax,
            folders = folders,
            onChanged = onChanged,
            modifier = Modifier.weight(1f),
        )
    }
}
