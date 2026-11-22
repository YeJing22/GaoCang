package com.yejing.artvault.browse

import com.yejing.artvault.CardBg
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yejing.artvault.Art
import com.yejing.artvault.ArtKind
import com.yejing.artvault.AssignPatch
import com.yejing.artvault.ArtistBook
import com.yejing.artvault.ArtistField
import com.yejing.artvault.ArtistPickerDialog
import com.yejing.artvault.DateField
import com.yejing.artvault.Muted
import com.yejing.artvault.Field
import com.yejing.artvault.FieldMode
import com.yejing.artvault.FolderBook
import com.yejing.artvault.Ink
import com.yejing.artvault.Paper
import com.yejing.artvault.Space
import com.yejing.artvault.T
import com.yejing.artvault.TagBook
import com.yejing.artvault.TagField
import com.yejing.artvault.TagPickerDialog
import com.yejing.artvault.Taxonomy
import com.yejing.artvault.artistPool
import com.yejing.artvault.tagPool
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.DateTimeDialog
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.Label
import com.yejing.artvault.ui.component.PrimaryButton
import com.yejing.artvault.ui.component.RoundIconButton
import com.yejing.artvault.ui.component.SectionLabel

/**
 * 批量赋值。只有动过的字段才会出现在 [AssignPatch] 里，
 * 没动的保持原值 —— 多选时各稿的差异字段不会被顺手清空。
 *
 * 2026-11-19 从底部弹层改成整页二级界面：弹层是「占满屏幕的内容 + 顶部一根拖拽条」，
 * 内容一长那根条就跟着一起被推着走；而且整块面板都是下拉手势区，滑到一半想停手很容易误关。
 * 现在跟「编辑」页同一套外壳（左上角关闭、底部主按钮），要退出只有两条路：点 X 或按返回键。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssignScreen(
    arts: List<Art>,
    targets: List<Art>,
    tax: Taxonomy,
    artists: ArtistBook,
    tags: TagBook,
    folders: FolderBook,
    onApply: (AssignPatch) -> Unit,
    onBack: () -> Unit,
) {
    var worldId by remember { mutableStateOf<String?>(null) }
    /** null = 不改；"" = 清成未分类 */
    var folderPatch by remember { mutableStateOf<String?>(null) }
    var roleMode by remember { mutableStateOf(FieldMode.Skip) }
    var roleIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tagMode by remember { mutableStateOf(FieldMode.Skip) }
    var chosenTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var showTagPicker by remember { mutableStateOf(false) }
    var artist by remember { mutableStateOf("") }
    /** 勾了「自制」：artist_id 置空 + self_made=true，两者必须成对写（见 AssignPatch.selfMade） */
    var selfMade by remember { mutableStateOf(false) }
    var showArtistPicker by remember { mutableStateOf(false) }
    var platform by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("") }

    val pool = remember(arts, tags) { tagPool(arts, tags) }
    val artistPool0 = remember(arts, artists) { artistPool(arts, artists) }
    val roles = remember(worldId, tax) {
        if (worldId == null) tax.roles else tax.rolesOf(worldId)
    }

    // 字段可用性按**选中范围的 kind** 决定。`applyPatch` 本来就会按 kind 忽略写不进去的字段，
    // 界面上还留着那些输入框只会让人以为改过了（用户 2026-11-22 报「容易误会」）。
    // - 世界观 / 角色：制品不写（继承原稿），制图可以
    // - 归档 / 画师 / 自制 / 平台 / 价格：只有普通稿写
    // - 标签 / 收稿时间：三种都写
    val anyDerived = targets.any { it.kind == ArtKind.Derived }
    val allArtwork = targets.all { it.kind == ArtKind.Artwork }
    val canWorldRole = !anyDerived

    fun patch() = AssignPatch(
        worldId = worldId,
        folderId = folderPatch,
        roleMode = roleMode,
        roleIds = roleIds.toList(),
        tagMode = tagMode,
        tags = chosenTags,
        artist = if (selfMade) "" else artist.trim().ifBlank { null },
        // 「自制」= 明确写 true；选了具体画师 = 明确写 false（清掉可能残留的 self_made）；
        // 两者都没动 = null（不改这个键）
        selfMade = when {
            selfMade -> true
            artist.isNotBlank() -> false
            else -> null
        },
        platform = platform.trim().ifBlank { null },
        receivedAt = date.trim().ifBlank { null },
        priceTotal = price.trim().toLongOrNull(),
    )

    val draft = patch()

    // 自己注册一份返回键：本页是从**多选态**打开的，而 BrowseScreen 的
    // `BackHandler(enabled = selecting)` 注册得比 Root 那个晚、会先吃到返回键，
    // 表现为「按一次只退了多选，页面还在」。页面自己注册的节点更晚进入组合，
    // 才能压过它（同 Busy.kt 记的那条坑；WorldScreen / ArtistScreen 也都是这么写的）。
    // Root 的 when 里仍保留 assignTargets 分支，兜住「屏还没加载好」的那一瞬间。
    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {
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
                    Text("批量赋值", style = T.titleSm, color = Paper)
                    Text("已选 ${targets.size} 件 · 没填的项目不动", style = T.micro, color = Muted)
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                if (!allArtwork) {
                    Text(
                        if (anyDerived) {
                            "选中的里面有制品：世界观 / 角色 / 画师 / 价格都继承或不适用，这里只能改标签和收稿时间。"
                        } else {
                            "选中的里面有制图：不记画师和价格，这里只能改世界观、角色、标签和收稿时间。"
                        },
                        style = T.micro,
                        color = Muted,
                    )
                    Spacer(Modifier.height(Space.m))
                }

                if (canWorldRole) {
                    SectionLabel("世界观")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        ChipPill("不改", selected = worldId == null) { worldId = null }
                        tax.worlds.forEach { w ->
                            ChipPill(w.name, selected = worldId == w.id) {
                                worldId = if (worldId == w.id) null else w.id
                            }
                        }
                    }

                    SectionLabel("角色")
                    ModeRow(roleMode) { roleMode = it; if (it == FieldMode.Skip) roleIds = emptySet() }
                    if (roleMode != FieldMode.Skip) {
                        Spacer(Modifier.height(Space.s))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            roles.forEach { r ->
                                ChipPill(r.name, selected = r.id in roleIds) {
                                    roleIds = if (r.id in roleIds) roleIds - r.id else roleIds + r.id
                                }
                            }
                        }
                        if (roles.isEmpty()) {
                            Spacer(Modifier.height(Space.xs))
                            Text("这个世界观下还没有角色", style = T.micro, color = Muted)
                        }
                    }
                }

                SectionLabel("标签")
                ModeRow(tagMode) { tagMode = it; if (it == FieldMode.Skip) chosenTags = emptyList() }
                if (tagMode != FieldMode.Skip) {
                    Spacer(Modifier.height(Space.s))
                    TagField(chosenTags, "点击选择已有标签或新建") { showTagPicker = true }
                }

                if (allArtwork) {
                    SectionLabel("文件夹")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ChipPill("不改", selected = folderPatch == null) { folderPatch = null }
                        ChipPill("未分类", selected = folderPatch == "") { folderPatch = "" }
                        folders.all.filter { it.system == null }.forEach { f ->
                            ChipPill(f.name, selected = folderPatch == f.id) {
                                folderPatch = if (folderPatch == f.id) null else f.id
                            }
                        }
                    }
                }

                SectionLabel("其它")
                if (allArtwork) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Label("画师")
                            ArtistField(if (selfMade) "自制" else artist, "不改") { showArtistPicker = true }
                        }
                        Column(Modifier.weight(1f)) {
                            Label("平台")
                            Field(platform, { platform = it }, "不改")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label("收稿时间")
                        if (date.isBlank()) {
                            // 批量赋值里「不改」是常态，所以留一个空态入口：
                            // 点开选了时间才会写，否则这些稿子的时间不动
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(com.yejing.artvault.CardBg)
                                    .clickable { showDatePicker = true }
                                    .padding(horizontal = 13.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text("不改", style = com.yejing.artvault.T.bodySm, color = Muted, maxLines = 1)
                            }
                        } else {
                            DateField(date) { showDatePicker = true }
                        }
                    }
                    if (allArtwork) {
                        Column(Modifier.weight(1f)) {
                            Label("价格 ¥")
                            Field(price, { price = it.filter(Char::isDigit) }, "不改", KeyboardType.Number)
                        }
                    }
                }

                Spacer(Modifier.height(Space.xxl))
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
                    text = when {
                        draft.isEmpty -> "选一项要改的内容"
                        else -> "应用到 ${targets.size} 件"
                    },
                    enabled = !draft.isEmpty,
                    onClick = { onApply(draft) },
                )
            }
        }
    }

    if (showArtistPicker) {
        ArtistPickerDialog(
            pool = artistPool0,
            artists = artists,
            current = if (selfMade) "自制" else artist,
            clearLabel = "不改",
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
            pool = pool,
            tags = tags,
            current = chosenTags,
            onDismiss = { showTagPicker = false },
            onConfirm = {
                chosenTags = it
                showTagPicker = false
            },
        )
    }

    if (showDatePicker) {
        DateTimeDialog(
            initial = date.takeIf { it.isNotBlank() } ?: com.yejing.artvault.Dates.now(),
            onDismiss = { showDatePicker = false },
            onConfirm = {
                date = it
                showDatePicker = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeRow(mode: FieldMode, onChange: (FieldMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        ChipPill("不改", selected = mode == FieldMode.Skip) { onChange(FieldMode.Skip) }
        ChipPill("追加", selected = mode == FieldMode.Add) { onChange(FieldMode.Add) }
        ChipPill("覆盖", selected = mode == FieldMode.Replace) { onChange(FieldMode.Replace) }
    }
}
