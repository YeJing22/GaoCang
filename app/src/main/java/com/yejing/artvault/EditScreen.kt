package com.yejing.artvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GuessDateHint
import com.yejing.artvault.ui.component.DateTimeDialog
import com.yejing.artvault.ui.component.Label
import com.yejing.artvault.ui.component.PrimaryButton
import com.yejing.artvault.ui.component.RoundIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** 修改一个已有槽的元数据。图片本身不动。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditScreen(
    art: Art,
    lib: Library,
    root: File,
    tax: Taxonomy,
    /** 只为取「已有的画师 / 标签」做候选，不会被这里改写 */
    arts: List<Art>,
    /** 保存时把表单里的画师 / 标签名换成 id（没有就建实体） */
    artists: ArtistBook,
    tags: TagBook,
    folders: FolderBook,
    /** id → 名字的解析器：回填表单初值用 */
    names: VaultNames,
    /**
     * 屏幕取字的结果（「套用到已有稿件」这条路）。非空时**只覆盖识别到的字段**，
     * 世界观 / 归档 / 角色 / 标签 / 备注保持稿件原值 —— 那些东西截图里没有，
     * 用空值覆盖等于把用户之前分好的类擦掉。
     *
     * 注意它会让表单一进来就是"有未保存改动"（脏值对比的基线仍是稿件原值），
     * 这是对的：用户必须点保存或放弃。
     */
    draft: SenseDraft? = null,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val formScroll = rememberScrollState()

    val isArtwork = art.kind == ArtKind.Artwork
    val isDerived = art.kind == ArtKind.Derived
    val sourceArt = remember(arts, art.sourceArtId) {
        art.sourceArtId?.let { sid -> arts.firstOrNull { it.id == sid } }
    }
    val userFolders = remember(folders.all) { folders.all.filter { it.system == null } }

    var worldId by remember(art.id) { mutableStateOf(art.worldId) }
    var folderId by remember(art.id) { mutableStateOf(art.folderId) }
    var roleIds by remember(art.id) { mutableStateOf(art.roleIds) }
    var title by remember(art.id) { mutableStateOf(draft?.title ?: art.title) }
    var note by remember(art.id) { mutableStateOf(art.note) }
    // 非普通稿吃取字 draft 时屏蔽画师 / 平台 / 价格，避免隐藏控件的值被悄悄写盘
    var artist by remember(art.id) {
        mutableStateOf(
            (if (isArtwork) draft?.artist else null) ?: names.artistName(art.artistId).orEmpty()
        )
    }
    // 自己画的：与「没填画师」分开存（见 Art.selfMade）。识别到画师就一定不是自制
    var selfMade by remember(art.id) {
        mutableStateOf(if (isArtwork && draft?.artist != null) false else art.selfMade)
    }
    var showArtistPicker by remember(art.id) { mutableStateOf(false) }
    var platform by remember(art.id) {
        mutableStateOf((if (isArtwork) draft?.platform else null) ?: art.platform)
    }
    var price by remember(art.id) {
        mutableStateOf(
            (if (isArtwork) draft?.price?.toString() else null) ?: (art.priceTotal?.toString() ?: "")
        )
    }
    // 初值只算一次：快照和 state 必须用同一个字符串，否则 receivedAt 为空时
    // 两次 Dates.now() 差一秒，一进页面就被判成"已改动"
    val date0 = remember(art.id) { art.receivedAt ?: Dates.now() }
    var date by remember(art.id) { mutableStateOf(draft?.receivedAt ?: date0) }
    var showDatePicker by remember(art.id) { mutableStateOf(false) }
    /** 用户有没有手动改过日期。没改过就继承原来的「推断」标记 */
    var dateTouched by remember(art.id) { mutableStateOf(draft?.receivedAt != null) }
    // 叫 tagStr 不叫 tags：tags 已经是 TagBook 参数名，遮住它会把 ensure 传成字符串
    var tagStr by remember(art.id) { mutableStateOf(names.tagsOf(art.tagIds).joinToString(" ")) }
    var showTagPicker by remember(art.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    /** 临时迁移入口：普通稿 → 制图（旧库整理完可直接删掉） */
    var askConvertFill by remember(art.id) { mutableStateOf(false) }

    // ── 未保存改动 ────────────────────────────────────────────────────
    // 逐字段对比，不用"改过就置 dirty"的标记：那样改回原样也算脏，
    // 用户点返回时会被拦下来问一句，反而莫名其妙。
    val snap0 = remember(art.id) {
        EditSnap(
            worldId = art.worldId,
            folderId = art.folderId,
            roleIds = art.roleIds,
            title = art.title,
            note = art.note,
            artist = names.artistName(art.artistId).orEmpty(),
            selfMade = art.selfMade,
            platform = art.platform,
            price = art.priceTotal?.toString() ?: "",
            date = date0,
            tagStr = names.tagsOf(art.tagIds).joinToString(" "),
        )
    }
    // 「就是这个」只清推断标记、值一个字没改 —— 字段对比看不出"动过"，
    // 所以把它单独并进来。否则点完直接返回会静默丢掉这次确认（用户以为已经确认过了）。
    val dirty = !busy && (EditSnap(
        worldId = worldId,
        folderId = folderId,
        roleIds = roleIds,
        title = title,
        note = note,
        artist = artist,
        selfMade = selfMade,
        platform = platform,
        price = price,
        date = date,
        tagStr = tagStr,
    ) != snap0 || (dateTouched && art.dateGuessed))
    var askDiscard by remember(art.id) { mutableStateOf(false) }

    // 有改动才接管返回键。**必须放进 if 重新注册**（AGENTS 铁律）：
    // BackHandler 内部的 DisposableEffect key 不含 enabled，常驻注册 + 改 enabled 的写法
    // 会让回调永远停在首次组合时的位置。
    if (dirty) {
        BackHandler { askDiscard = true }
    }

    if (askDiscard) {
        DiscardEditsDialog(
            onCancel = { askDiscard = false },
            onDiscard = {
                askDiscard = false
                onBack()
            },
        )
    }

    val roles = tax.rolesOf(worldId)
    val v = art.primary
    val artistPool0 = remember(arts, artists) { artistPool(arts, artists) }
    val tagPool0 = remember(arts, tags) { tagPool(arts, tags) }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    GI.Close,
                    onClick = { if (dirty) askDiscard = true else onBack() },
                    background = Color.Transparent,
                )
                Spacer(Modifier.width(Space.xxs))
                Column(Modifier.weight(1f)) {
                    Text("编辑", style = T.titleSm, color = Paper)
                    Text("只改信息，图片不动", style = T.micro, color = Muted)
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(formScroll)
                    .padding(horizontal = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CHigh)
                            .border(0.8.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)),
                    ) {
                        AsyncImage(
                            model = if (v != null) lib.thumb(v) else null,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.width(Space.m))
                    Text(
                        v?.let { "${it.label.ifBlank { "主图" }} · ${it.w}×${it.h}" } ?: "",
                        style = T.label,
                        color = Muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (isDerived) {
                    Spacer(Modifier.height(Space.m))
                    Text(
                        if (sourceArt != null) "继承自：${sourceArt.title.ifBlank { "未命名" }}"
                        else "继承自：原稿已删除",
                        style = T.micro,
                        color = OnMid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardBg)
                            .padding(horizontal = 13.dp, vertical = 10.dp),
                    )
                }

                if (!isDerived) {
                    if (tax.worlds.isEmpty()) {
                        Spacer(Modifier.height(Space.l))
                        Text(
                            "还没有世界观和角色，可以先保存，也能现在去建。",
                            style = T.label,
                            color = Amber,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AmberC)
                                .padding(13.dp),
                        )
                    } else {
                        Label("世界观")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            tax.worlds.forEach { w ->
                                ChipPill(w.name, selected = worldId == w.id) {
                                    // 换世界观不清角色：角色可以跨世界观
                                    worldId = if (worldId == w.id) null else w.id
                                }
                            }
                        }
                    }
                }

                // 角色：普通稿与制图可编辑；制品继承原稿，隐藏
                if (!isDerived && tax.roles.isNotEmpty()) {
                    Label("角色（可多选，可跨世界观）")
                    tax.worlds.forEach { w ->
                        val rs = tax.rolesOf(w.id)
                        if (rs.isNotEmpty()) {
                            GroupHint(w.name)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                rs.forEach { r ->
                                    ChipPill(r.name, selected = r.id in roleIds) {
                                        roleIds = if (r.id in roleIds) roleIds - r.id else roleIds + r.id
                                    }
                                }
                            }
                        }
                    }
                    val loose = tax.roles.filter { it.worldId == null }
                    if (loose.isNotEmpty()) {
                        GroupHint("未归入世界观")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            loose.forEach { r ->
                                ChipPill(r.name, selected = r.id in roleIds) {
                                    roleIds = if (r.id in roleIds) roleIds - r.id else roleIds + r.id
                                }
                            }
                        }
                    }
                }

                if (isArtwork) {
                    // 归档分类：导出时按它建一级文件夹，排除系统归档
                    Label("文件夹")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ChipPill("未分类", selected = folderId == null) { folderId = null }
                        userFolders.forEach { f ->
                            ChipPill(f.name, selected = folderId == f.id) {
                                folderId = if (folderId == f.id) null else f.id
                            }
                        }
                    }
                    if (userFolders.isEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "还没有归档分类：在「我的 → 归档文件夹」里新建",
                            style = T.micro,
                            color = Muted,
                        )
                    }
                }

                Label("标题")
                Field(title, { title = it }, "留空则显示为「未命名」")

                if (isArtwork) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Label("画师")
                            ArtistField(if (selfMade) "自制" else artist, "选填") { showArtistPicker = true }
                        }
                        Column(Modifier.weight(1f)) {
                            Label("平台")
                            Field(platform, { platform = it }, "米画师 / 微博…")
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label(if (isArtwork) "价格 ¥" else "类型")
                        if (isArtwork) {
                            Field(price, { price = it.filter(Char::isDigit) }, "选填", KeyboardType.Number)
                        } else {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CardBg)
                                    .padding(horizontal = 13.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(if (isDerived) "制品" else "制图", style = T.bodySm, color = Muted, maxLines = 1)
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Label("收稿时间")
                        DateField(date) { showDatePicker = true }
                        if (!dateTouched && art.dateGuessed) {
                            Spacer(Modifier.height(5.dp))
                            // 猜对了也要能确认：以前只有"改一下日期"才算确认，
                            // 于是推断正确的稿永远挂在「待矫时」里（2026-11-22 用户报的）。
                            GuessDateHint("推断值") { dateTouched = true }
                        }
                    }
                }

                Label("标签")
                TagField(tagList(tagStr), "选填（点击选择或新建）") { showTagPicker = true }

                Label("备注")
                Field(
                    note,
                    { note = it },
                    "记点什么，关键词搜得到（最多 500 字）",
                    multiline = true,
                    maxLength = 500,
                )

                // 临时迁移入口：将旧普通稿转为「制图」（后续不需要时直接删掉本块即可）
                if (isArtwork) {
                    Spacer(Modifier.height(Space.l))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardBg)
                            .clickable(enabled = !busy) { askConvertFill = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("转为制图", style = T.bodySm, color = OnMid)
                            Text("移入「制图」归档、不计入统计，可撤销", style = T.micro, color = Muted)
                        }
                        Text("›", style = T.titleSm, color = Muted)
                    }
                }

                err?.let {
                    Spacer(Modifier.height(Space.m))
                    Text(
                        it,
                        style = T.label,
                        color = Warn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(WarnC)
                            .padding(13.dp),
                    )
                }
                Spacer(Modifier.height(28.dp))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.95f), Ink))
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                PrimaryButton(
                    text = "保存修改",
                    loading = busy,
                    onClick = {
                        busy = true
                        err = null
                        val form = ImportForm(
                            worldId = worldId,
                            folderId = folderId,
                            roleIds = roleIds,
                            title = title,
                            artist = artist,
                            selfMade = selfMade,
                            platform = platform,
                            note = note,
                            priceTotal = price.trim().toLongOrNull(),
                            receivedAt = date.trim().ifBlank { Dates.now() },
                            dateGuessed = if (dateTouched) false else art.dateGuessed,
                            tags = tagList(tagStr).distinct(),
                        )
                        scope.launch {
                            // 画师/标签名在 updateArtwork 里 ensure 成 id。
                            // 在这里改画师 = 「这一稿换成另一个人」，真改名去画师页
                            //（只改实体，旧名进别名）——两种意图从此分开了。
                            val ok = withContext(Dispatchers.IO) {
                                // 进操作历史：以前改单条撤不回来，只有批量赋值才记。
                                // 命令层不用动 —— writeJsonAtomic 的录制已经挂在 journal 上了。
                                runCatching {
                                    OperationJournal.run(
                                        root,
                                        "edit",
                                        "编辑：${form.title.ifBlank { art.title.ifBlank { "未命名" } }}",
                                    ) {
                                        updateArtwork(root, art.id, form, artists, tags, tax)
                                    }
                                }.getOrElse { false }
                            }
                            busy = false
                            if (ok) onSaved() else err = "写入失败，检查库目录是否可写"
                        }
                    },
                )
            }
        }
    }

    if (showArtistPicker) {
        ArtistPickerDialog(
            pool = artistPool0,
            artists = artists,
            current = artist,
            onDismiss = { showArtistPicker = false },
            onPick = {
                artist = it
                selfMade = false
                showArtistPicker = false
            },
            onSelfMade = {
                artist = ""
                selfMade = true
                showArtistPicker = false
            },
        )
    }

    if (showTagPicker) {
        TagPickerDialog(
            pool = tagPool0,
            tags = tags,
            current = tagList(tagStr),
            onDismiss = { showTagPicker = false },
            onConfirm = {
                tagStr = it.joinToString(" ")
                showTagPicker = false
            },
        )
    }

    if (showDatePicker) {
        DateTimeDialog(
            initial = date,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                date = it
                dateTouched = true
                showDatePicker = false
            },
        )
    }

    if (askConvertFill) {
        val hasChildren = arts.any { it.sourceArtId == art.id }
        AlertDialog(
            onDismissRequest = { askConvertFill = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("转为「制图」？", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    buildString {
                        if (hasChildren) {
                            append("这件稿名下已有制品，不能直接转为制图。请先解除或转移制品关联。")
                        } else {
                            append("会移入系统归档「制图」，保留角色、标签、备注与时间；不再计入件数与账本统计。")
                            art.priceTotal?.let { p -> append("\n\n该稿原记有 ¥$p，转换后价格将清空（可在操作历史中撤销恢复）。") }
                        }
                    },
                    style = T.bodySm,
                    color = OnMid,
                )
            },
            confirmButton = {
                if (!hasChildren) {
                    TextButton(
                        onClick = {
                            askConvertFill = false
                            val fillFolderId = folders.systemFolder(ArtKind.Fill)?.id
                                ?: run { folders.ensureSystem(); folders.systemFolder(ArtKind.Fill)?.id }
                            if (fillFolderId == null) {
                                err = "系统归档「制图」缺失，请重启一次"
                                return@TextButton
                            }
                            busy = true
                            err = null
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        OperationJournal.run(
                                            root,
                                            "convert",
                                            "转为制图：${art.title.ifBlank { "未命名" }}",
                                        ) {
                                            ArtworkCommands.convertToFill(root, art.id, fillFolderId, tax)
                                        }
                                    }.getOrElse { false }
                                }
                                busy = false
                                if (ok) {
                                    toast(ctx, "已转为制图")
                                    onSaved()
                                } else {
                                    err = "转换失败（可能已有制品关联或库不可写）"
                                }
                            }
                        },
                    ) { Text("转为制图", style = T.body, color = Accent) }
                }
            },
            dismissButton = {
                TextButton(onClick = { askConvertFill = false }) {
                    Text(if (hasChildren) "知道了" else "取消", style = T.body, color = Muted)
                }
            },
        )
    }
}

/** 表单快照：只放会写进 JSON 的字段，用来判断"有没有改过"。 */
private data class EditSnap(
    val worldId: String?,
    val folderId: String?,
    val roleIds: List<String>,
    val title: String,
    val note: String,
    val artist: String,
    val selfMade: Boolean,
    val platform: String,
    val price: String,
    val date: String,
    val tagStr: String,
)

/** 有未保存改动时的退出确认。样式与删除确认同一套。 */
@Composable
private fun DiscardEditsDialog(onCancel: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("放弃这次修改？", style = T.titleSm, color = Paper) },
        text = {
            Text(
                "这一页的改动还没保存，退出后会丢失。",
                style = T.bodySm,
                color = OnMid,
            )
        },
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text("放弃", style = T.body, color = Warn)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("继续编辑", style = T.body, color = Muted)
            }
        },
    )
}
