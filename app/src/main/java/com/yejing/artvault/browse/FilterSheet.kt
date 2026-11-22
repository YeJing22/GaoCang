package com.yejing.artvault.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yejing.artvault.Space
import com.yejing.artvault.Accent
import com.yejing.artvault.AccentC
import com.yejing.artvault.Art
import com.yejing.artvault.ArtKind
import com.yejing.artvault.CardBg
import com.yejing.artvault.KindFilter
import com.yejing.artvault.Muted
import com.yejing.artvault.Outline
import com.yejing.artvault.Filter
import com.yejing.artvault.FilterPreset
import com.yejing.artvault.FilterPresets
import com.yejing.artvault.Mint
import com.yejing.artvault.MintC
import com.yejing.artvault.Paper
import com.yejing.artvault.Paper70
import com.yejing.artvault.Prefs
import com.yejing.artvault.Amber
import com.yejing.artvault.RoleMode
import com.yejing.artvault.Scrim
import com.yejing.artvault.Sort
import com.yejing.artvault.SortKey
import com.yejing.artvault.TagBook
import com.yejing.artvault.FolderBook
import com.yejing.artvault.UNSORTED_FOLDER
import com.yejing.artvault.VaultNames
import com.yejing.artvault.T
import com.yejing.artvault.Taxonomy
import com.yejing.artvault.tagPool
import com.yejing.artvault.toast
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.PrimaryButton
import com.yejing.artvault.ui.component.SectionLabel
import com.yejing.artvault.ui.component.SheetGrip

/**
 * 筛选面板改成底部抽屉：改条件时背景仍能看见网格，
 * 底部按钮实时显示「查看 N 件」，不用来回跳页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    arts: List<Art>,
    tax: Taxonomy,
    names: VaultNames,
    tags: TagBook,
    folders: FolderBook,
    prefs: Prefs,
    init: Filter,
    /**
     * 归档条件锁死（文件夹二级页进来的）：那页的所有稿件本来就属于同一个文件夹，
     * 再让人改归档就自相矛盾了——改了也筛不出别的东西。
     */
    lockFolder: Boolean = false,
    /** 系统归档页打开时锁定 kind（不能被「重置」清回 Artwork） */
    lockedKind: KindFilter? = null,
    /**
     * 归档页里连「类型」这一组都不显示。
     *
     * 归档本身已经决定了住客：制品/制图只住在系统归档里，普通归档里切到「制品」必然 0 件。
     * 留着这组只会让人以为筛选坏了。
     */
    hideKind: Boolean = false,
    onApply: (Filter) -> Unit,
    onDismiss: () -> Unit,
) {
    // 性能探针：从进组合到首帧的耗时（量「点开筛选为什么停一下」）
    val perfT0 = remember { System.currentTimeMillis() }
    LaunchedEffect(Unit) {
        android.util.Log.d(
            "GaoCangPerf",
            "filterSheet open ${System.currentTimeMillis() - perfT0}ms arts=${arts.size}",
        )
    }

    val ctx = LocalContext.current
    val presets = remember { FilterPresets.load(prefs).toMutableStateList() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var worldId by remember { mutableStateOf(init.worldId) }
    var folderId by remember { mutableStateOf(init.folderId) }
    var roleIds by remember { mutableStateOf(init.roleIds) }
    var roleMode by remember { mutableStateOf(init.roleMode) }
    // 叫 selTags：tags 是 TagBook 参数名，别遮住它
    var selTags by remember { mutableStateOf(init.tags) }
    var query by remember { mutableStateOf(init.query) }
    var gaps by remember { mutableStateOf(init.gaps) }
    var priceMin by remember { mutableStateOf(init.priceMin?.toString() ?: "") }
    var priceMax by remember { mutableStateOf(init.priceMax?.toString() ?: "") }
    var dateFrom by remember { mutableStateOf(init.dateFrom ?: "") }
    var dateTo by remember { mutableStateOf(init.dateTo ?: "") }
    var starredOnly by remember { mutableStateOf(init.starredOnly) }
    var kind by remember { mutableStateOf(lockedKind ?: init.kind) }
    var sort by remember { mutableStateOf(init.sort) }

    val effectiveKind = lockedKind ?: kind
    val kindScoped = remember(arts, effectiveKind) {
        when (effectiveKind) {
            KindFilter.Artwork -> arts.filter { it.kind == ArtKind.Artwork }
            KindFilter.Derived -> arts.filter { it.kind == ArtKind.Derived }
            KindFilter.Fill -> arts.filter { it.kind == ArtKind.Fill }
            KindFilter.All -> arts
        }
    }
    val tags0 = remember(kindScoped, tags) { tagPool(kindScoped, tags) }
    val roleList = remember(worldId, tax) {
        if (worldId == null) tax.roles else tax.rolesOf(worldId)
    }

    fun draft() = Filter(
        worldId = if (lockedKind != null) null else worldId,
        folderId = if (lockFolder) init.folderId else folderId,
        roleIds = if (lockedKind != null) emptySet() else roleIds,
        roleMode = roleMode,
        tags = selTags,
        gaps = gaps,
        query = query.trim(),
        priceMin = priceMin.trim().toLongOrNull(),
        priceMax = priceMax.trim().toLongOrNull(),
        dateFrom = dateFrom.trim().ifBlank { null },
        dateTo = dateTo.trim().ifBlank { null },
        starredOnly = starredOnly,
        kind = effectiveKind,
        sort = sort,
    )

    val scopeTotal = kindScoped.size

    val count = remember(worldId, folderId, roleIds, roleMode, selTags, gaps, query, priceMin, priceMax, dateFrom, dateTo, starredOnly, effectiveKind) {
        val t = System.nanoTime()
        val n = draft().apply(arts, names, tax).size
        android.util.Log.d(
            "GaoCangPerf",
            "filterSheet count=$n ${(System.nanoTime() - t) / 1_000_000.0}ms q='${query.trim()}'",
        )
        n
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CardBg,
        scrimColor = Scrim,
        dragHandle = { SheetGrip() },
    ) {
        Column(Modifier.fillMaxHeight(0.88f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("筛选", style = T.title, color = Paper)
                    Spacer(Modifier.height(3.dp))
                    Text("$count / $scopeTotal 件符合条件", style = T.label, color = Muted)
                }
                val canSavePreset = draft().condCount > 0
                Text(
                    "存为预设",
                    style = T.label,
                    color = if (canSavePreset) Paper70 else Muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(CardBg)
                        .border(1.dp, Outline, RoundedCornerShape(50))
                        .clickable(enabled = canSavePreset) {
                            presetName = FilterPresets.autoName(draft(), tax, tags, folders)
                            showSaveDialog = true
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Spacer(Modifier.width(8.dp))
                // 重置是"清空全部条件并回主页面"，不是面板内的一个开关：
                // 所以给它主色胶囊（和「完成/应用」同一档次），点完直接 onApply 空条件。
                // 用 init.sort 保留排序 —— 排序是浏览习惯，不该被"重置筛选"顺手抹掉。
                Text(
                    "重置",
                    style = T.label,
                    fontWeight = FontWeight.Medium,
                    color = Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AccentC)
                        .clickable { onApply(Filter(kind = lockedKind ?: KindFilter.Artwork, sort = init.sort)) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }

            if (presets.isNotEmpty()) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    presets.forEach { p ->
                        ChipPill(
                            text = p.name,
                            selected = false,
                            outlined = true,
                            onLongClick = {
                                presets.remove(p)
                                FilterPresets.save(prefs, presets)
                                toast(ctx, "已删除预设「${p.name}」")
                            },
                        ) {
                            val clean = FilterPresets.sanitize(p.filter, tax, tags, folders)
                            if (clean.condCount == 0) {
                                toast(ctx, "「${p.name}」的条件已经全部失效")
                            } else {
                                onApply(clean.copy(sort = init.sort))
                            }
                        }
                    }
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                if (lockedKind == null && !hideKind) {
                    SectionLabel("类型")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        KindFilter.entries.forEach { k ->
                            ChipPill(k.label, selected = kind == k, outlined = true) { kind = k }
                        }
                    }
                }

                SectionLabel("文件夹")
                if (lockFolder) {
                    // 归档是这一页的前提条件，不给改；说清楚为什么不给改
                    Text(
                        "已锁定为「${folders.byId(init.folderId ?: "" )?.name ?: "未分类"}」——这是你从文件夹里进来的",
                        style = T.micro,
                        color = Muted,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ChipPill("全部", selected = folderId == null, outlined = true) { folderId = null }
                        ChipPill("未分类", selected = folderId == UNSORTED_FOLDER, outlined = true) {
                            folderId = if (folderId == UNSORTED_FOLDER) null else UNSORTED_FOLDER
                        }
                        folders.all.filter { it.system == null }.forEach { f ->
                            ChipPill(f.name, selected = folderId == f.id, outlined = true) {
                                folderId = if (folderId == f.id) null else f.id
                            }
                        }
                    }
                }

                SectionLabel("待整理")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    com.yejing.artvault.Gap.entries.forEach { g ->
                        ChipPill(g.label, selected = g in gaps, outlined = true) {
                            gaps = if (g in gaps) gaps - g else gaps + g
                        }
                    }
                }

                SectionLabel("星标")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ChipPill("只看星标", selected = starredOnly, outlined = true) { starredOnly = !starredOnly }
                }

                SectionLabel("关键词")
                Row(Modifier.fillMaxWidth()) {
                    NumField("标题 / 画师 / 标签 / 角色 / 平台 / 备注", query) { query = it }
                }

                if (lockedKind == null) {
                    SectionLabel("世界观")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ChipPill("全部", selected = worldId == null, outlined = true) {
                            worldId = null
                            roleIds = emptySet()
                        }
                        tax.worlds.forEach { w ->
                            ChipPill(w.name, selected = worldId == w.id, outlined = true) {
                                worldId = if (worldId == w.id) null else w.id
                                roleIds = emptySet()
                            }
                        }
                    }
                }

                if (lockedKind == null && roleList.isNotEmpty()) {
                    SectionLabel("角色", trailing = {
                        // 单选也有意义（"出现过他就算"），所以不限制 >= 2
                        if (roleIds.isNotEmpty()) {
                            val modeColor = when (roleMode) {
                                RoleMode.And -> Mint
                                RoleMode.Or -> Accent
                            }
                            val modeBg = when (roleMode) {
                                RoleMode.And -> MintC
                                RoleMode.Or -> AccentC
                            }
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(modeBg)
                                    .border(1.dp, modeColor.copy(alpha = 0.45f), RoundedCornerShape(50))
                                    .clickable { roleMode = roleMode.next() }
                                    .padding(horizontal = 11.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Text(
                                    roleMode.short,
                                    style = T.micro,
                                    fontWeight = FontWeight.SemiBold,
                                    color = modeColor,
                                )
                                Text("·", style = T.micro, color = modeColor.copy(alpha = 0.55f))
                                Text(roleMode.hint, style = T.micro, color = Paper70)
                                Text("⇄", style = T.micro, color = modeColor)
                            }
                        }
                    })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        roleList.forEach { r ->
                            ChipPill(r.name, selected = r.id in roleIds, outlined = true) {
                                roleIds = if (r.id in roleIds) roleIds - r.id else roleIds + r.id
                            }
                        }
                    }
                }

                if (tags0.isNotEmpty()) {
                    SectionLabel("标签")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        tags0.forEach { (t, n) ->
                            ChipPill(if (n > 1) "$t $n" else t, selected = t in selTags, outlined = true) {
                                selTags = if (t in selTags) selTags - t else selTags + t
                            }
                        }
                    }
                }

                SectionLabel("价格区间")
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    NumField("最低 ¥", priceMin) { priceMin = it.filter(Char::isDigit) }
                    NumField("最高 ¥", priceMax) { priceMax = it.filter(Char::isDigit) }
                }

                SectionLabel("收稿日期")
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    NumField("2019-01-01", dateFrom) { dateFrom = it }
                    NumField("至今", dateTo) { dateTo = it }
                }

                SectionLabel("排序")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    SortKey.entries.forEach { k ->
                        val active = sort.key == k
                        ChipPill(if (active) sort.label else k.label, selected = active, outlined = true) {
                            // 点同一个字段 = 翻转方向，点别的字段 = 换字段（默认降序）
                            sort = if (active) sort.flip() else Sort(k)
                        }
                    }
                }
                Spacer(Modifier.height(Space.xxl))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .background(CardBg)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                PrimaryButton("查看 $count 件", onClick = { onApply(draft()) }, enabled = true)
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = CardBg,
            title = { Text("存为筛选预设", style = T.title, color = Paper) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "下次点预设胶囊直接生效；长按可删除（最多 ${FilterPresets.MAX} 个）",
                        style = T.micro,
                        color = Muted,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CardBg)
                            .border(1.dp, Outline, RoundedCornerShape(12.dp))
                            .padding(horizontal = 13.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (presetName.isEmpty()) Text("输入预设名称", style = T.bodySm, color = Muted)
                        BasicTextField(
                            value = presetName,
                            onValueChange = { presetName = it },
                            singleLine = true,
                            textStyle = T.bodySm.copy(color = Paper),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cur = draft()
                    val finalName = presetName.trim().ifBlank {
                        FilterPresets.autoName(cur, tax, tags, folders)
                    }
                    val idx = presets.indexOfFirst { it.name == finalName }
                    if (idx >= 0) {
                        presets[idx] = FilterPreset(finalName, cur)
                        FilterPresets.save(prefs, presets)
                        toast(ctx, "已更新预设「$finalName」")
                        showSaveDialog = false
                    } else if (presets.size >= FilterPresets.MAX) {
                        toast(ctx, "预设最多 ${FilterPresets.MAX} 个，长按一条删掉先")
                    } else {
                        presets.add(FilterPreset(finalName, cur))
                        FilterPresets.save(prefs, presets)
                        toast(ctx, "已保存预设「$finalName」")
                        showSaveDialog = false
                    }
                }) { Text("保存", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消", color = Muted) }
            },
        )
    }
}

@Composable
private fun RowScope.NumField(hint: String, value: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .border(1.dp, Outline, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, style = T.bodySm, color = Muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = T.bodySm.copy(color = Paper),
            cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
