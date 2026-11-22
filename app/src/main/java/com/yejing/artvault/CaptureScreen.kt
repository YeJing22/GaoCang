package com.yejing.artvault

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(
    items: List<MediaItem>,
    root: File?,
    tax: Taxonomy,
    /** 只为取「已有的画师 / 标签」做候选，不会被这里改写 */
    arts: List<Art>,
    /** 保存时把表单里的画师 / 标签名换成 id（没有就建实体） */
    artists: ArtistBook,
    tags: TagBook,
    folders: FolderBook,
    /**
     * 屏幕取字的结果（来自无障碍服务）。非空时预填标题 / 画师 / 价格 / 日期 / 平台。
     *
     * **只预填，不自动保存**：截图里认错一位金额比手动输入更糟。
     * 认得准不准由用户在表单里核对，落库仍然只走 [importMedia] 这一条路。
     */
    draft: SenseDraft? = null,
    kind: ArtKind = ArtKind.Artwork,
    sourceArt: Art? = null,
    allowKindSwitch: Boolean = true,
    onBack: () -> Unit,
    onSaved: (ImportResult) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val formScroll = rememberScrollState()

    var curKind by remember(kind) { mutableStateOf(kind) }
    val isArtwork = curKind == ArtKind.Artwork
    val isDerived = curKind == ArtKind.Derived
    val isFill = curKind == ArtKind.Fill

    // 非普通稿的归档钉死在系统归档；取不到先自愈一次
    val sysFolderId = remember(curKind, folders) {
        if (curKind == ArtKind.Artwork) null
        else folders.systemFolder(curKind)?.id
            ?: run { folders.ensureSystem(); folders.systemFolder(curKind)?.id }
    }
    val sysFolderMissing = curKind != ArtKind.Artwork && sysFolderId == null
    val derivedNoSource = isDerived && sourceArt == null
    val userFolders = remember(folders.all) { folders.all.filter { it.system == null } }

    var worldId by remember {
        mutableStateOf(if (tax.worlds.size == 1) tax.worlds.first().id else null)
    }
    /** 归档分类（导出文件夹），与世界观无关 */
    var folderId by remember { mutableStateOf<String?>(null) }
    var roleIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var title by remember { mutableStateOf(draft?.title.orEmpty()) }
    // 制品只取 draft 的标题与时间，忽略画师 / 价格 / 平台
    var artist by remember { mutableStateOf(if (kind == ArtKind.Artwork) draft?.artist.orEmpty() else "") }
    var selfMade by remember { mutableStateOf(false) }
    var showArtistPicker by remember { mutableStateOf(false) }
    var platform by remember { mutableStateOf(if (kind == ArtKind.Artwork) draft?.platform.orEmpty() else "") }
    var price by remember { mutableStateOf(if (kind == ArtKind.Artwork) (draft?.price?.toString() ?: "") else "") }
    val guessedDate = remember(items) { guessDate(items) }
    // 截图里的「上传时间」是平台记下的真实收稿时间，不是按文件时间猜的 ——
    // 所以它算「已确认」（dateGuessed = false），下面 dateTouched 的初值也跟着改
    var date by remember { mutableStateOf(draft?.receivedAt ?: guessedDate ?: Dates.now()) }
    /** 用户有没有手动改过时间。没改过且来自文件时间，才是「推断」 */
    var dateTouched by remember { mutableStateOf(draft?.receivedAt != null) }
    var showDatePicker by remember { mutableStateOf(false) }
    // 叫 tagStr 不叫 tags：tags 已经是 TagBook 参数名，遮住它会把 ensure 传成字符串
    var tagStr by remember { mutableStateOf("") }
    var showTagPicker by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val n = items.size
    val priceVal = price.trim().toLongOrNull()
    val roles = tax.rolesOf(worldId)
    val artistPool0 = remember(arts, artists) { artistPool(arts, artists) }
    val tagPool0 = remember(arts, tags) { tagPool(arts, tags) }

    /**
     * 保存。两种形态只差一个参数：
     * - [oneSlot] = false：一张一录，每张各建一件，总价按张均摊（老行为）
     * - [oneSlot] = true ：全部建成**同一件**的多个版本，价格整笔记一件
     *
     * 抽成一个函数而不是两个按钮各写一遍：两条路的表单快照、判重口径、错误文案
     * 必须完全一样，抄第二份迟早漂。
     */
    fun save(oneSlot: Boolean) {
        val r0 = root ?: return
        if (derivedNoSource || sysFolderMissing) return
        busy = true
        err = null
        val form = ImportForm(
            worldId = if (isDerived) null else worldId,
            folderId = if (isArtwork) folderId else sysFolderId,
            roleIds = if (isDerived) emptyList() else roleIds,
            title = title,
            artist = if (isArtwork) artist else "",
            selfMade = if (isArtwork) selfMade else false,
            platform = if (isArtwork) platform else "",
            note = note,
            // 制品/制图绝不写 price_total（保持 null，0 是有效价格）
            priceTotal = if (isArtwork) priceVal else null,
            receivedAt = date.trim().ifBlank { Dates.now() },
            dateGuessed = !dateTouched,
            tags = tagList(tagStr).distinct(),
            kind = curKind,
            sourceArtId = if (isDerived) sourceArt?.id else null,
        )
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                // 判重口径：全库 version.blob（不是 blobs/ 文件存在）——删稿件不删 blob
                val known = arts.flatMap { a -> a.versions.map { it.blob } }.toSet()
                if (oneSlot) importVersions(ctx, r0, items, form, artists, tags, known, tax)
                else importMedia(ctx, r0, items, form, artists, tags, known, tax)
            }
            busy = false
            if (r.done > 0) {
                val skipTxt = if (r.skipped > 0) "，${r.skipped} 张已在库" else ""
                val failTxt = if (r.failed > 0) "，${r.failed} 张失败：${r.firstError ?: "未知原因"}" else ""
                toast(
                    ctx,
                    if (oneSlot) "已建成 1 件，含 ${r.versions} 个版本$failTxt$skipTxt"
                    else "已建档 ${r.done} 件$failTxt$skipTxt",
                )
                onSaved(r)
            } else if (r.skipped > 0 && r.failed == 0) {
                // 全跳过：什么都没建，留在表单让用户换图，不走 onSaved
                toast(ctx, "这 ${r.skipped} 张都已经在库里了")
            } else {
                err = "没有成功导入：${r.firstError ?: "未知原因"}"
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // ── 顶栏 ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(GI.Close, onClick = onBack, background = Color.Transparent)
                Spacer(Modifier.width(Space.xxs))
                Column(Modifier.weight(1f)) {
                    Text("建档 · $n 张", style = T.titleSm, color = Paper)
                    Text("这些信息会套用到每张", style = T.micro, color = Muted)
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(formScroll)
                    .padding(horizontal = 20.dp),
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                ) {
                    items(items, key = { it.uri.toString() }) { m ->
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(m.uri).size(220).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(62.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CHigh),
                        )
                    }
                }
                Text(
                    if (n > 1) {
                        "「建档 $n 件」= 每张各建一件，总价按张均摊；" +
                            "「$n 版合成 1 件」= 同一件的多个版本，价格整笔记一件。"
                    } else {
                        "这一张会建成一件稿件。"
                    },
                    style = T.micro,
                    color = Muted,
                )

                if (draft != null) {
                    Spacer(Modifier.height(Space.s))
                    Text(
                        buildString {
                            append("已从「${draft.source}」读入 ${draft.hitCount} 项")
                            if (draft.missing.isNotEmpty()) {
                                append("，没认出：${draft.missing.joinToString("、")}")
                            }
                            append("。核对一下再保存。")
                        },
                        style = T.micro,
                        color = Accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AccentC)
                            .padding(horizontal = 13.dp, vertical = 9.dp),
                    )
                }

                if (allowKindSwitch) {
                    Label("类型")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        ChipPill("普通稿", selected = isArtwork) { curKind = ArtKind.Artwork }
                        ChipPill("制图", selected = isFill) { curKind = ArtKind.Fill }
                        // 制品置灰：只能从原稿详情页「＋制品」进（不变式）
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(CardBg.copy(alpha = 0.55f))
                                .clickable { toast(ctx, "制品请从原稿详情页的「＋制品」进入") }
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                        ) {
                            Text("制品", style = T.label, color = Muted.copy(alpha = 0.55f))
                        }
                    }
                }

                if (sysFolderMissing) {
                    Spacer(Modifier.height(Space.s))
                    Text(
                        "系统归档缺失，请重启一次",
                        style = T.label,
                        color = Warn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(WarnC)
                            .padding(13.dp),
                    )
                }

                if (isDerived) {
                    Spacer(Modifier.height(Space.s))
                    if (sourceArt == null) {
                        Text(
                            "制品必须关联原稿",
                            style = T.label,
                            color = Warn,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(WarnC)
                                .padding(13.dp),
                        )
                    } else {
                        val srcWorld = sourceArt.worldId?.let { tax.worldName(it) }
                        val srcRoles = sourceArt.roleIds.mapNotNull { tax.roleName(it) }.joinToString("/")
                        val srcArtist = when {
                            sourceArt.selfMade -> "自制"
                            sourceArt.artistId != null -> artists.byId(sourceArt.artistId)?.name
                            else -> null
                        }
                        val metaSummary = listOfNotNull(
                            srcWorld,
                            srcRoles.takeIf { it.isNotBlank() },
                            srcArtist,
                        ).joinToString(" · ")
                        val srcTitle = sourceArt.title.ifBlank { "未命名" }
                        Text(
                            if (metaSummary.isNotBlank()) "继承自：$srcTitle（$metaSummary）"
                            else "继承自：$srcTitle",
                            style = T.micro,
                            color = OnMid,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBg)
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                        )
                    }
                }

                if (!isDerived) {
                    if (tax.worlds.isEmpty()) {
                        Spacer(Modifier.height(Space.l))
                        Text(
                            "还没有世界观和角色。可以先保存，也能现在去建。",
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
                                    // 换世界观**不清角色**：角色可以跨世界观，清掉反而毁掉联动图
                                    worldId = if (worldId == w.id) null else w.id
                                }
                            }
                        }
                    }
                }

                // 角色：普通稿与制图可选；制品继承原稿，不渲染
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
                    // 归档分类：导出时按它建一级文件夹。和世界观无关，排除系统归档
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
                        Label(if (isArtwork) (if (n > 1) "本批总价 ¥" else "价格 ¥") else "类型")
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
                        if (guessedDate != null && !dateTouched) {
                            Spacer(Modifier.height(5.dp))
                            GuessDateHint("取自图片文件时间") { dateTouched = true }
                        }
                    }
                }
                if (isArtwork && n > 1 && priceVal != null) {
                    val per = priceVal / n
                    Text(
                        "共 $n 张，按张均摊 ≈ ¥$per，合计仍为 ¥$priceVal",
                        style = T.micro,
                        color = Mint,
                        modifier = Modifier.padding(top = 8.dp),
                    )
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

            // ── 底部主按钮 ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.95f), Ink))
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                if (root == null) {
                    Text(
                        "当前是演示库，不能写入。先在「我的」里选一个库目录。",
                        style = T.label,
                        color = Warn,
                    )
                } else {
                    // 多选时给两条路：几张各建一件（价格摊），还是建成同一件的几个版本（整笔记）。
                    // 后者是给"带背景 / 透明底 / 线稿"这种同一次约稿的多个形态准备的。
                    val canSave = !busy && !derivedNoSource && !sysFolderMissing
                    if (n > 1) {
                        // 两等分并排：竖排两行会把底部条撑到 108dp，好在这个页面本来就要滚，
                        // 但按钮区是常驻的，占掉的高度等于图缩短了，不划算
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryButton(
                                text = "建档 $n 件",
                                onClick = { save(oneSlot = false) },
                                modifier = Modifier.weight(1f),
                                enabled = canSave,
                                loading = busy,
                            )
                            PrimaryButton(
                                text = "$n 版合成 1 件",
                                onClick = { save(oneSlot = true) },
                                modifier = Modifier.weight(1f),
                                enabled = canSave,
                                container = CardBg,
                                content = Paper,
                            )
                        }
                    } else {
                        PrimaryButton(
                            text = "保存",
                            enabled = canSave,
                            loading = busy,
                            onClick = { save(oneSlot = false) },
                        )
                    }
                }
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
}

/** 日期字段：点开日历 + 时钟，不做手输 */
@Composable
internal fun DateField(value: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(Dates.display(value), style = T.bodySm, color = Paper, maxLines = 1)
    }
}

/** 多行输入框的高度区间：最少 3 行（60dp）、最多 6 行（136dp）—— 超过 6 行就在框内滚。 */
private val MultilineMinH = 60.dp
private val MultilineMaxH = 136.dp

/**
 * 表单输入框。整个 App 的表单都用这一个，避免样式漂移。
 *
 * [multiline] 打开后变多行，高度在 3–6 行之间：内容少时不会占一大块，超过 6 行由
 * `BasicTextField` 的 `maxLines` 在框内滚动（跟随光标）。给备注用。
 * 两个都不能省：写死 `height(44.dp)` 多行框根本不会长；只给 `heightIn(min)` 又会一直长到
 * 和内容一样高、永远产生不了内部滚动。
 *
 * [maxLength] 是**输入时**拦截：满了就不再接受新字符，但**不截断已有的值** ——
 * 截断会在用户不知情的情况下改掉他写的东西（尤其是旧数据带进来的长文本）。
 * 粘贴整段超长时值原样不动 + toast 说明原因：以前是静默丢弃，用户以为粘贴坏了。
 *
 * [modifier] 放最后，因为已有调用点用**位置参数**传 `KeyboardType`
 * （`Field(price, {...}, "选填", KeyboardType.Number)`），插在中间会让那些调用错位。
 */
@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    kb: KeyboardType = KeyboardType.Text,
    multiline: Boolean = false,
    maxLength: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val showCount = multiline && maxLength != Int.MAX_VALUE
    // 输入法会连续发 onValueChange：超限提示要节流，否则一次长粘贴能刷出好几条 toast
    var warnedAt by remember { mutableLongStateOf(0L) }

    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (multiline) Modifier.heightIn(min = MultilineMinH, max = MultilineMaxH)
                else Modifier.height(44.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .padding(
                start = 13.dp,
                end = 13.dp,
                top = if (multiline) 11.dp else 0.dp,
                bottom = if (multiline) 8.dp else 0.dp,
            ),
        contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(hint, style = T.bodySm, color = Muted, maxLines = if (multiline) 2 else 1)
        }
        BasicTextField(
            value = value,
            onValueChange = {
                if (it.length <= maxLength) {
                    onChange(it)
                } else {
                    val now = System.currentTimeMillis()
                    if (now - warnedAt > 1200) {
                        warnedAt = now
                        toast(ctx, "文本过长，粘贴失败（最多 $maxLength 字）")
                    }
                }
            },
            singleLine = !multiline,
            maxLines = if (multiline) 6 else 1,
            textStyle = T.bodySm.copy(color = Paper),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(keyboardType = kb),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (showCount) Modifier.padding(bottom = 12.dp) else Modifier),
        )
        if (showCount) {
            Text(
                "${value.length} / $maxLength",
                style = T.micro,
                // 快满了才变主色，平时别拿它抢注意力
                color = if (value.length >= maxLength) Accent else Muted,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/** 表单里的强调按钮（分类管理等次要动作） */
@Composable
internal fun GhostButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = T.label,
        color = Accent,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AccentC)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
