package com.yejing.artvault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GIcon
import com.yejing.artvault.ui.component.HairLine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InfoTab(
    art: Art,
    tax: Taxonomy,
    names: VaultNames,
    lib: Library,
    folders: FolderBook,
    root: File?,
    verId: String,
    refreshTick: Int,
    sourceArt: Art? = null,
    derived: List<Art> = emptyList(),
    onOpenArt: (String) -> Unit = {},
    onAddDerived: (String) -> Unit = {},
    onUnlink: (String) -> Unit = {},
    onPickSource: (String) -> Unit = {},
    onOpenRole: (String) -> Unit = {},
    onOpenArtist: (String) -> Unit = {},
    onFilterTag: (String) -> Unit = {},
    onFilterWorld: (String) -> Unit = {},
    roleCounts: Map<String, Int> = emptyMap(),
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val exportedFile by produceState<File?>(null, root, art.id, verId, refreshTick) {
        value = withContext(Dispatchers.IO) {
            root?.let { Mirror.exportedFile(it, art.id, verId) }
        }
    }
    val p = art.priceTotal
    // 0 元是有效价格（无偿稿），只有 null 才算「没填」
    val hasPrice = art.kind == ArtKind.Artwork && p != null
    var showMeta by remember(art.id) { mutableStateOf(false) }
    var demoLinkTip by remember(art.id) { mutableStateOf(false) }
    // 制品的世界观 / 角色 / 画师从原稿继承、只读，不写进子件 JSON
    val isDerived = art.kind == ArtKind.Derived
    val meta = if (isDerived) (sourceArt ?: art) else art
    // 稿件上只有 artist_id；解析不出说明档案缺了，如实标出来，别装成「没填」
    val artistName = names.artist(meta.artistId)
    val artistHome = names.artistHome(meta.artistId)
    val world = meta.worldId?.let { tax.worldName(it) }
    val roleItems = remember(meta.roleIds, tax.roles) {
        meta.roleIds.mapNotNull { id -> tax.roles.firstOrNull { it.id == id } }
    }
    val tagNames = names.tagsOf(art.tagIds)
    val folderName = folders.byId(art.folderId)?.name ?: "未分类"

    Column(Modifier.padding(horizontal = 20.dp)) {
        // 标签放哪，按**真实字宽**决定（2026-11-21 用户要求）：
        // 少的时候和标题同一行（省一行高度），放不下才让标题独占一行、标签往下换行。
        // 不能按"标签个数"拍脑袋 —— 「#资深训练家服」这种长标签两个就顶满了。
        val titleText = art.title.ifBlank { "未命名" }
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val density = androidx.compose.ui.platform.LocalDensity.current
        val availW = with(density) {
            // 页面左右各 20dp，卡片里再各 16dp
            (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 40.dp - 32.dp).toPx()
        }
        val tagsInline = if (tagNames.isEmpty()) {
            true
        } else {
            val titleW = measurer.measure(AnnotatedString(titleText), style = T.title, maxLines = 1).size.width
            val chipsW = tagNames.sumOf { n ->
                measurer.measure(AnnotatedString("#$n"), style = T.label, maxLines = 1).size.width
            } + with(density) {
                // 每个 chip 左右各 10dp 内边距 + chip 之间 6dp
                (tagNames.size * 20 + (tagNames.size - 1) * 6).dp.toPx()
            }
            titleW + with(density) { Space.m.toPx() } + chipsW <= availW
        }

        if (tagsInline) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    titleText,
                    style = T.title,
                    color = Paper,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (tagNames.isNotEmpty()) {
                    Spacer(Modifier.width(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        tagNames.forEach { name ->
                            MetaChip("#$name", color = Mint, bg = MintC, onClick = { onFilterTag(name) })
                        }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    titleText,
                    style = T.title,
                    color = Paper,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Space.s))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    tagNames.forEach { name ->
                        MetaChip("#$name", color = Mint, bg = MintC, onClick = { onFilterTag(name) })
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.l))
        // 藏品票据卡（R6 卡 2）：票头 / 名片 / 双栏 / 铭牌。替代原来的六格。
        // 空值边界：价格 null →「未填」（0 是有效价格，显示 ¥0）；画师解析不出 →「档案缺失」，
        // 没填 →「未填」（两件事）；没主页不渲染「主页↗」；roles 空 →「未填」；dateGuessed 保留「推断」。
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(CardBg),
        ) {
            // 票头：渐变只铺在这一块
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(AccentC.copy(alpha = 0.5f), Color.Transparent)))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        // 不写「N 版本均摊」：多版本是同一次约稿的不同形态（透明底 / 线稿 / 拆分），
                        // 这一槽的 price_total 就是那次约稿的价，按版本除会让人以为每版单独计费。
                        // 真正需要均摊的只有「一次导入多张图、各建一槽」那一层（见 Import.kt）。
                        if (art.kind == ArtKind.Artwork) "约稿总价" else "二次创作",
                        style = T.micro,
                        color = Muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        when (art.kind) {
                            ArtKind.Derived -> "制品"
                            ArtKind.Fill -> "制图"
                            ArtKind.Artwork -> when (p) {
                                null -> "未填"
                                0L -> "无偿"
                                else -> "¥$p"
                            }
                        },
                        style = T.title,
                        color = if (hasPrice) Accent else Muted,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(Space.s))
                Column(horizontalAlignment = Alignment.End) {
                    val showKindChip = art.kind != ArtKind.Artwork
                    if (showKindChip || art.platform.isNotBlank() || (art.dateGuessed && art.receivedAt != null)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                            if (showKindChip) {
                                MetaChip(
                                    if (art.kind == ArtKind.Derived) "制品" else "制图",
                                    color = Accent,
                                    bg = AccentC,
                                )
                            }
                            if (art.kind == ArtKind.Artwork && art.platform.isNotBlank()) MetaChip(art.platform, color = OnMid)
                            if (art.dateGuessed && art.receivedAt != null) MetaChip("推断", color = Amber, bg = AmberC)
                        }
                        Spacer(Modifier.height(Space.xs))
                    }
                    Text(
                        Dates.display(art.receivedAt),
                        style = T.micro,
                        color = if (art.receivedAt == null) Muted else OnMid,
                        maxLines = 1,
                    )
                }
            }
            // 登场角色行（方案 2）：单人横向名片行 + 右侧件数提示；多人横向圆头像胶囊
            HairLine(color = OutlineSoft)
            when (roleItems.size) {
                0 -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (isDerived) "角色 · 继承" else "登场角色",
                        style = T.micro,
                        color = Muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(Space.xs))
                    Text("未填", style = T.bodySm, color = Muted)
                }

                1 -> {
                    val r = roleItems[0]
                    val avatar = tax.avatarFile(r)
                    val cnt = roleCounts[r.id]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenRole(r.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(CHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (avatar != null) {
                                AsyncImage(
                                    model = avatar,
                                    contentDescription = r.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(r.name.take(1), style = T.bodySm, color = Accent)
                            }
                        }
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (isDerived) "角色 · 继承" else "登场角色",
                                style = T.micro,
                                color = Muted,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                r.name,
                                style = T.body,
                                color = Paper,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(Space.s))
                        Text(
                            if (cnt != null) "$cnt 件 ›" else "›",
                            style = T.label,
                            color = Muted,
                            maxLines = 1,
                        )
                    }
                }

                else -> Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (isDerived) "角色 · 继承" else "登场角色",
                        style = T.micro,
                        color = Muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .nestedScroll(
                                remember {
                                    object : NestedScrollConnection {
                                        override fun onPostScroll(
                                            consumed: Offset,
                                            available: Offset,
                                            source: NestedScrollSource,
                                        ): Offset =
                                            if (available.x != 0f) Offset(available.x, 0f) else Offset.Zero
                                    }
                                },
                            )
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        roleItems.forEach { r ->
                            val avatar = tax.avatarFile(r)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(CHigh)
                                    .clickable { onOpenRole(r.id) }
                                    .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(CardBg),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (avatar != null) {
                                        AsyncImage(
                                            model = avatar,
                                            contentDescription = r.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    } else {
                                        Text(r.name.take(1), style = T.micro, color = Accent)
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    r.name,
                                    style = T.label,
                                    color = Paper,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            HairLine(color = OutlineSoft)
            // 创作者 + 世界观双栏：画师去掉头像，保留主页按钮；两者均可点击跳转
            val artistId = meta.artistId
            val artistOk = !meta.selfMade && artistId != null && artistName.isNotBlank() && artistName != VaultNames.MISSING
            val artistLabel = when {
                meta.selfMade -> "自制"
                artistName.isBlank() -> "未填"
                else -> artistName
            }
            val worldId = meta.worldId
            val worldClickable = worldId != null && world != null
            if (art.kind != ArtKind.Fill) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .heightIn(min = 72.dp),
                ) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (artistOk) Modifier.clickable { onOpenArtist(artistId) }
                                else Modifier,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (isDerived) "创作者 · 继承" else "创作者",
                                style = T.micro,
                                color = Muted,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            if (artistHome != null && !meta.selfMade) {
                                Spacer(Modifier.width(Space.xs))
                                Text(
                                    "主页↗",
                                    style = T.micro,
                                    color = OnMid,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(CHigh)
                                        .clickable {
                                            if (root == null) {
                                                demoLinkTip = true
                                            } else if (!ArtistLink.open(ctx, artistHome)) {
                                                toast(ctx, "打不开这个链接，检查一下地址")
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                artistLabel,
                                style = T.bodySm,
                                color = if (artistName.isBlank() && !meta.selfMade) Muted else Paper,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (artistOk) {
                                Text(" ›", style = T.bodySm, color = Muted)
                            }
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(OutlineSoft))
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (worldClickable) Modifier.clickable { onFilterWorld(worldId) }
                                else Modifier,
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            if (isDerived) "世界观 · 继承" else "世界观设定",
                            style = T.micro,
                            color = Muted,
                            maxLines = 1,
                        )
                        Spacer(Modifier.height(Space.xs))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                world ?: "未归属",
                                style = T.bodySm,
                                color = if (world == null) Muted else Paper,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (worldClickable) {
                                Text(" ›", style = T.bodySm, color = Muted)
                            }
                        }
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (worldClickable) Modifier.clickable { onFilterWorld(worldId) }
                            else Modifier,
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("世界观设定", style = T.micro, color = Muted, maxLines = 1)
                    Spacer(Modifier.height(Space.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            world ?: "未归属",
                            style = T.bodySm,
                            color = if (world == null) Muted else Paper,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (worldClickable) {
                            Text(" ›", style = T.bodySm, color = Muted)
                        }
                    }
                }
            }
            HairLine(color = OutlineSoft)
            // 铭牌：文件夹单独占一行，已导出版本右侧提供「在相册中打开」，最右箭头可展开查看 ULID。
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showMeta = !showMeta }
                    // 上下 14dp：这行是票据卡的收尾，贴着下一个区块太紧会显得"没写完"（2026-11-21）
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "文件夹：$folderName",
                    style = T.micro,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val targetFile = exportedFile
                if (targetFile != null) {
                    Spacer(Modifier.width(Space.s))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(CardBg)
                            .border(0.8.dp, Outline, RoundedCornerShape(50))
                            .clickable {
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) { GalleryOpen.uriOf(ctx, targetFile) }
                                    when {
                                        uri == null -> toast(ctx, "系统图库还没索引到这个文件，稍后再试")
                                        !GalleryOpen.open(ctx, uri) -> toast(ctx, "没有能打开图片的应用")
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("在相册中打开", style = T.micro, color = OnMid, maxLines = 1)
                        GIcon(GI.External, OnMid, size = 13.dp, strokeWidth = 1.7f)
                    }
                    Spacer(Modifier.width(Space.xs))
                } else {
                    Spacer(Modifier.width(Space.s))
                }
                GIcon(
                    GI.ChevronDown,
                    Muted,
                    size = 14.dp,
                    strokeWidth = 1.8f,
                    modifier = Modifier.rotate(if (showMeta) 180f else 0f),
                )
            }
            if (showMeta) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            clip.setText(AnnotatedString(art.id))
                            toast(ctx, "完整 ULID 已复制")
                        }
                        .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ULID: ${art.id}",
                        style = T.num,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Space.s))
                    Text(
                        "复制",
                        style = T.micro,
                        color = OnMid,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(CHigh)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }

        // 备注：填了就要看得见，**不截行**（编辑页能写 500 字，这里就得能全显示）。
        // 只有「一键复制」那颗按钮可点 —— 整块可点会和"想滚动页面"抢手势，
        // 而且随手一滑就复制成功，反而莫名其妙（2026-11-21 用户要求）。
        if (art.note.isNotBlank()) {
            Spacer(Modifier.height(Space.l))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("备注", style = T.micro, color = Muted)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "一键复制",
                        style = T.micro,
                        color = OnMid,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(CHigh)
                            .clickable {
                                clip.setText(AnnotatedString(art.note))
                                toast(ctx, "备注已复制")
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Spacer(Modifier.height(Space.s))
                Text(
                    art.note,
                    style = T.bodySm,
                    color = OnMid,
                )
            }
        }

        // ── 关联区（备注块下方） ──
        when (art.kind) {
            ArtKind.Derived -> {
                Spacer(Modifier.height(Space.l))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .then(
                            if (sourceArt != null) Modifier.clickable { onOpenArt(sourceArt.id) }
                            else Modifier
                        )
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sourceArt != null) {
                        Text("衍生自", style = T.micro, color = Muted)
                        Spacer(Modifier.width(Space.m))
                        Text(
                            sourceArt.title.ifBlank { "未命名" },
                            style = T.bodySm,
                            color = Paper,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Space.s))
                        Text(
                            "换",
                            style = T.micro,
                            color = OnMid,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(CHigh)
                                .clickable { onPickSource(art.id) }
                                .padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.width(Space.xs))
                        Text("›", style = T.titleSm, color = Muted)
                    } else {
                        Text(
                            if (art.sourceArtId != null) "原稿已删除" else "未关联原稿",
                            style = T.bodySm,
                            color = if (art.sourceArtId != null) Warn else Muted,
                            modifier = Modifier.weight(1f),
                        )
                        if (art.sourceArtId != null) {
                            Text(
                                "解除",
                                style = T.micro,
                                color = Warn,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(WarnC)
                                    .clickable { onUnlink(art.id) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                            Spacer(Modifier.width(Space.xs))
                        }
                        Text(
                            "关联原稿 ›",
                            style = T.micro,
                            color = Accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AccentC)
                                .clickable { onPickSource(art.id) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            ArtKind.Artwork -> {
                Spacer(Modifier.height(Space.l))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(CardBg)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (derived.isEmpty()) "制品" else "制品 ${derived.size} 件",
                            style = T.micro,
                            color = Muted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "＋制品",
                            style = T.micro,
                            color = Accent,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(AccentC)
                                .clickable { onAddDerived(art.id) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                    if (derived.isNotEmpty()) {
                        Spacer(Modifier.height(Space.s))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            items(derived, key = { it.id }) { d ->
                                val pv = d.primary
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(CHigh)
                                        .clickable { onOpenArt(d.id) },
                                ) {
                                    if (pv != null) {
                                        AsyncImage(
                                            model = lib.thumb(pv),
                                            contentDescription = d.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ArtKind.Fill -> Unit
        }

    }

    if (demoLinkTip) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { demoLinkTip = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("演示功能说明", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "此为演示功能，在真实图库中可以填入画师主页并按下此按钮直接跳转",
                    style = T.bodySm,
                    color = OnMid,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { demoLinkTip = false }) {
                    Text("知道了", style = T.body, color = Accent)
                }
            },
        )
    }
}

/**
 * 底部信息面板，可收起。
 * - 收起：把手 + 标题 + 一行摘要（日期 · 价格 · 版本数），屏幕几乎全留给图。
 * - 展开：表单字段 + 标签 + 版本条 + 操作。
 * 手势：在面板上向上拖 = 展开，向下拖 = 收起；点把手 / 标题 / 箭头也能切。
 * 面板本身刻意不做 scrollable，避免和 VerticalPager（切换下一张）抢手势。
 */
@Composable
private fun MetaChip(
    text: String,
    color: Color,
    bg: Color = CHigh,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text,
        style = T.label,
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
