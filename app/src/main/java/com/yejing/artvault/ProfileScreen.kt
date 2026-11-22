package com.yejing.artvault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.HairLine
import com.yejing.artvault.ui.component.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 我的：库位置、授权、网格密度、关于。原来挤在标题栏右上角的东西都搬到这里。 */
@Composable
fun ProfileScreen(
    root: File?,
    demo: Boolean,
    hasPerm: Boolean,
    count: Int,
    onPickRoot: () -> Unit,
    onGrant: () -> Unit,
    mirrorBusy: Boolean = false,
    mirrorCount: Int = 0,
    mirrorDone: Int = 0,
    mirrorTotal: Int = 0,
    onSyncMirror: () -> Unit = {},
    nameFields: Set<String> = Mirror.ALL_FIELDS.map { it.name }.toSet(),
    onNameFields: (Set<String>) -> Unit = {},
    /** 回收站里的整槽数与被删的单个版本数（R6 起分开显示，比一个总数更有信息量） */
    trashSlots: Int = 0,
    trashVersions: Int = 0,
    onOpenTrash: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenDoctor: () -> Unit = {},
    lib: Library? = null,
    arts: List<Art> = emptyList(),
    tax: Taxonomy? = null,
    folders: FolderBook? = null,
    names: VaultNames? = null,
    petLabel: String = "附属",
    onPetLabel: (String) -> Unit = {},
    themePreset: ThemePreset = ThemePreset.AmberNight,
    onThemePreset: (ThemePreset) -> Unit = {},
) {
    val ctx = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showNameFields by remember { mutableStateOf(false) }
    var showPurge by remember { mutableStateOf(false) }
    var showPurgeDelete by remember { mutableStateOf(false) }
    var showPetLabel by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var crashText by remember { mutableStateOf<String?>(null) }
    var crashTick by remember { mutableIntStateOf(0) }
    val hasCrash = remember(crashTick) { CrashLog.read(ctx) != null }
    var exportingCsv by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val versionName = remember(ctx) {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "—"
    }

    // 无障碍服务开没开。只能在系统设置里改，本页一直活着 —— 不主动刷新就还是旧状态
    var senseTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) senseTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val senseOn = remember(senseTick) { ScreenSenseService.isEnabled(ctx) }

    // 库体积：只算 blobs（原图），thumbs 是可重建的缓存，算进去会让人误以为库很大。
    // 两千张图要 walk 一次，所以放 IO 线程；这个页面每次切进来重新组合，数字自然是新的。
    val libSize by produceState(0L, root) {
        value = withContext(Dispatchers.IO) {
            val dir = root?.let { File(it, "blobs") }
            dir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        }
    }
    // 还能撤几步。读的是 undo/ 目录里已完成的记录，很轻（几条 JSON）
    val undoSteps by produceState(0, root) {
        value = withContext(Dispatchers.IO) {
            root?.let { r -> OperationJournal.history(r).count { it.done } } ?: 0
        }
    }

    fun sizeText(n: Long): String = when {
        n <= 0L -> "—"
        n >= 1024L * 1024 * 1024 -> String.format("%.2f GB", n / 1073741824.0)
        n >= 1024L * 1024 -> String.format("%d MB", n / 1048576L)
        else -> "${n / 1024} KB"
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp, bottom = 14.dp)) {
            Text("我的", style = T.display, color = Paper, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(Space.xs))
            Text(
                "库目录、偏好工具、导出与数据维护",
                style = T.label,
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        HairLine(color = OutlineSoft)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 118.dp + navBottom)
        ) {
            Spacer(Modifier.height(14.dp))

            // ── 库状态 ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (demo) Lilac else Mint)
                    )
                    Spacer(Modifier.width(Space.s))
                    Text(
                        if (demo) "演示数据" else "本地库",
                        style = T.body,
                        color = if (demo) Lilac else Mint,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("$count 件 · ${sizeText(libSize)}", style = T.num, color = Muted)
                }
                Spacer(Modifier.height(10.dp))
                // 路径常常比屏幕宽（/storage/emulated/0/<dir>/ArtVault），两行省略会看不全，
                // 而且这行字是「我到底在改哪个库」的唯一凭据 —— 给它一块底、允许横向拖。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(AppShapes.extraSmall)
                        .background(Ink)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        root?.absolutePath ?: "还没有选择库目录",
                        style = T.num,
                        color = OnMid,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    ChipPill("更换库目录", onClick = onPickRoot)
                    if (!hasPerm) ChipPill("去授权", selected = true, onClick = onGrant)
                }
            }

            SectionLabel("偏好与工具")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SettingRow(
                    "主题配色",
                    trailing = { ValuePill("${themePreset.label} ›") },
                    sub = "2 套深色 + 2 套浅色，切换后立即生效",
                    onClick = { showThemePicker = true },
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "附属角色标签",
                    trailing = { ValuePill("$petLabel ›") },
                    sub = "角色页「不计入多人稿件的价格人均分摊」角色的胶囊文字（如宠物 / 坐骑 / 宝可梦），留空恢复默认「附属」",
                    onClick = { showPetLabel = true },
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "屏幕取字",
                    trailing = { ValuePill(if (senseOn) "已开启" else "去开启 ›") },
                    sub = if (senseOn) {
                        "点击悬浮球抓取信息，目前仅适配米画师 我的稿件->点进某张稿件->更多 弹出的详细信息。长按可收起悬浮球"
                    } else {
                        "通过无障碍服务自动填入稿件信息（点击悬浮球抓取，不会监听或联网）"
                    },
                    onClick = { ScreenSenseService.openSettings(ctx) },
                )
                if (senseOn) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                ScreenSenseService.requestShowBall(ctx)
                                toast(ctx, "已重新显示悬浮球")
                            }
                            .padding(top = 2.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("重新显示悬浮球", style = T.label, color = Accent)
                    }
                }
            }

            SectionLabel("相册与导出")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SettingRow(
                    "同步到相册",
                    value = when {
                        mirrorBusy && mirrorTotal > 0 -> "$mirrorDone / $mirrorTotal"
                        mirrorBusy -> "准备中…"
                        mirrorCount > 0 -> "$mirrorCount 个文件"
                        else -> "未同步"
                    },
                    sub = "按文件夹分目录摊开，一个版本一张；系统图库就能当相册翻",
                    enabled = !mirrorBusy && root != null,
                    onClick = if (!mirrorBusy && root != null) onSyncMirror else null,
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "文件名字段",
                    value = Mirror.NameField.values()
                        .filter { it.name in nameFields }
                        .joinToString("·") { it.label }
                        .ifBlank { "仅日期" },
                    sub = "改完要重新同步一次，文件名才会跟着变",
                    onClick = { showNameFields = true },
                )
                if (root != null && lib != null && tax != null && folders != null) {
                    HairLine(color = OutlineSoft)
                    SettingRow(
                        "校验相册",
                        value = if (verifying) "校验中…" else null,
                        sub = "按内容哈希核对已导出文件，只读不改动文件",
                        enabled = !verifying && !mirrorBusy,
                        onClick = if (!verifying && !mirrorBusy) {
                            {
                                verifying = true
                                scope.launch {
                                    val r = Mirror.verify(
                                        root = root,
                                        lib = lib,
                                        arts = arts,
                                        tax = tax,
                                        folders = folders,
                                        fields = Mirror.fieldsOf(nameFields),
                                    )
                                    verifying = false
                                    toast(
                                        ctx,
                                        "一致 ${r.ok} · 不一致 ${r.mismatch.size} · 缺失 ${r.missing.size}" +
                                            if (r.unchecked > 0) " · 未校验 ${r.unchecked}" else "",
                                    )
                                }
                            }
                        } else null,
                    )
                }
                HairLine(color = OutlineSoft)
                SettingRow(
                    "导出账单 CSV",
                    trailing = { ValuePill(if (exportingCsv) "导出中…" else "导出 ›") },
                    sub = "导出全部稿件与价格明细到库根目录（UTF-8 BOM，Excel 可直接打开）",
                    enabled = root != null && tax != null && folders != null && names != null && !exportingCsv,
                    onClick = if (root != null && tax != null && folders != null && names != null && !exportingCsv) {
                        {
                            exportingCsv = true
                            scope.launch {
                                val res = runCatching {
                                    withContext(Dispatchers.IO) {
                                        CsvExport.exportLedger(root, arts, tax, names, folders)
                                    }
                                }
                                exportingCsv = false
                                res.onSuccess { f -> toast(ctx, "已导出：${f.name}") }
                                    .onFailure { e -> toast(ctx, "导出失败：${e.message ?: "未知错误"}") }
                            }
                        }
                    } else null,
                )
            }
            Text(
                "「同步到相册」会把库内所有图片写到「相册/<文件夹>/」，图按收稿日期设时间；未分文件夹的会进「未分类」。" +
                    "blobs / thumbs 已用 .nomedia 藏起来，不会混进图库。",
                style = T.micro,
                color = Muted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )

            SectionLabel("回退与恢复")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SettingRow(
                    "操作历史",
                    trailing = { ValuePill(if (undoSteps > 0) "可撤 $undoSteps 步 ›" else "暂无 ›") },
                    sub = "只记内容改动（赋值 / 改信息 / 删分类）；删稿、合并请去回收站",
                    enabled = root != null,
                    onClick = if (root != null) onOpenHistory else null,
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "回收站",
                    trailing = {
                        ValuePill(
                            when {
                                trashSlots == 0 && trashVersions == 0 -> "空 ›"
                                trashVersions == 0 -> "$trashSlots 槽 ›"
                                trashSlots == 0 -> "$trashVersions 个版本 ›"
                                else -> "$trashSlots 槽 · $trashVersions 个版本 ›"
                            }
                        )
                    },
                    sub = "删除 / 合并掉的稿件先放这里，可以恢复；图片文件始终保留",
                    enabled = root != null,
                    onClick = if (root != null) onOpenTrash else null,
                )
            }

            SectionLabel("维护与清理")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SettingRow(
                    "一致性检查",
                    trailing = { ValuePill("体检 ›") },
                    sub = "扫一遍：主图、版本 id、引用是否悬空、原图与缩略图是否在位",
                    enabled = root != null,
                    onClick = if (root != null) onOpenDoctor else null,
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "清理孤儿图片",
                    trailing = { ValuePill("清理 ›") },
                    sub = "没有任何稿件引用的原图与缩略图。先移进隔离区，可以反悔",
                    enabled = root != null && lib != null && !demo,
                    onClick = if (root != null && lib != null && !demo) { { showPurge = true } } else null,
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "清空隔离区",
                    trailing = { ValuePill("彻底删除 ›") },
                    sub = "上一项移过去的文件，删了就找不回来",
                    enabled = root != null && lib != null && !demo,
                    onClick = if (root != null && lib != null && !demo) { { showPurgeDelete = true } } else null,
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "崩溃日志",
                    trailing = { ValuePill(if (hasCrash) "查看 ›" else "无记录", warn = hasCrash) },
                    sub = "仅在本地保存最近一次异常堆栈，便于离线排查或反馈问题",
                    enabled = hasCrash,
                    onClick = if (hasCrash) {
                        { crashText = CrashLog.read(ctx) }
                    } else null,
                )
            }

            SectionLabel("关于")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                SettingRow("应用", value = "稿仓 v$versionName")
                HairLine(color = OutlineSoft)
                SettingRow("开源协议", value = "MIT License")
                HairLine(color = OutlineSoft)
                SettingRow(
                    "隐私与权限说明",
                    trailing = { ValuePill("查看 ›") },
                    sub = "纯本地离线，无联网权限；数据保存在用户自选目录",
                    onClick = { showPrivacy = true },
                )
                HairLine(color = OutlineSoft)
                SettingRow(
                    "第三方开源许可",
                    trailing = { ValuePill("查看 ›") },
                    sub = "Coil / Image Cropper / Reorderable / Drag Select",
                    onClick = { showLicenses = true },
                )
            }

        }
    }

    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("主题配色", style = T.titleSm, color = Paper) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    ThemePreset.entries.forEach { preset ->
                        val active = preset == themePreset
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) AccentC else CHigh)
                                .clickable {
                                    onThemePreset(preset)
                                    showThemePicker = false
                                }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(preset.palette.ink),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(preset.palette.accent),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    preset.label,
                                    style = T.bodySm,
                                    color = if (active) Accent else Paper,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                                )
                                Text(preset.sub, style = T.micro, color = Muted)
                            }
                            if (active) {
                                Text("当前", style = T.micro, color = Accent, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) {
                    Text("关闭", style = T.body, color = Muted)
                }
            },
        )
    }

    if (showPetLabel) {
        var input by remember(petLabel) { mutableStateOf(petLabel) }
        AlertDialog(
            onDismissRequest = { showPetLabel = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("附属角色标签文字", style = T.titleSm, color = Paper) },
            text = {
                Column {
                    Text(
                        "在角色页用于标识「不计入多人稿件的价格人均分摊」的角色（例如：宠物、坐骑、宝可梦）。留空将恢复默认「附属」。",
                        style = T.micro,
                        color = Muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CHigh)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        if (input.isBlank()) {
                            Text("附属", style = T.bodySm, color = Muted)
                        }
                        androidx.compose.foundation.text.BasicTextField(
                            value = input,
                            onValueChange = { input = it.take(12) },
                            singleLine = true,
                            textStyle = T.bodySm.copy(color = Paper),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPetLabel = false
                    onPetLabel(input.trim().ifBlank { "附属" })
                }) {
                    Text("确定", style = T.body, color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPetLabel = false }) {
                    Text("取消", style = T.body, color = Muted)
                }
            },
        )
    }

    crashText?.let { log ->
        AlertDialog(
            onDismissRequest = { crashText = null },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("最近一次崩溃日志", style = T.titleSm, color = Paper) },
            text = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Ink)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(log, style = T.micro, color = OnMid)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clip.setText(androidx.compose.ui.text.AnnotatedString(log))
                    toast(ctx, "崩溃日志已复制")
                }) {
                    Text("复制", style = T.body, color = Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    CrashLog.clear(ctx)
                    crashText = null
                    crashTick++
                    toast(ctx, "已清空崩溃日志")
                }) {
                    Text("清空", style = T.body, color = Warn)
                }
            },
        )
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("隐私与权限说明", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "1. 零联网：本应用未申请 INTERNET 权限，不会上传任何图片、账目或使用记录。\n\n" +
                        "2. 所有文件访问权限（MANAGE_EXTERNAL_STORAGE）：仅用于读写你指定的本地稿仓目录（meta/、blobs/、thumbs/）以及导出相册镜像，方便跨设备使用 Syncthing 等工具同步。\n\n" +
                        "3. 无障碍服务（可选）：仅在你手动点击「取」悬浮球时读取一次当前屏幕文本以辅助填入约稿信息，不监听键盘输入、不后台采集。",
                    style = T.bodySm,
                    color = OnMid,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacy = false }) {
                    Text("知道了", style = T.body, color = Accent)
                }
            },
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            containerColor = CardBg,
            shape = RoundedCornerShape(24.dp),
            title = { Text("第三方开源许可", style = T.titleSm, color = Paper) },
            text = {
                Text(
                    "本应用基于 MIT 协议开源，使用以下开源组件：\n\n" +
                        "• Jetpack Compose & AndroidX（Apache-2.0）\n" +
                        "• Coil（Apache-2.0）\n" +
                        "• Android-Image-Cropper（Apache-2.0）\n" +
                        "• Reorderable（Apache-2.0）\n" +
                        "• Drag-Select-Compose（MIT，Copyright (c) 2023 Jordon de Hoog）",
                    style = T.bodySm,
                    color = OnMid,
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicenses = false }) {
                    Text("关闭", style = T.body, color = Accent)
                }
            },
        )
    }

    if (showNameFields) {
        NameFieldsDialog(
            selected = nameFields,
            onToggle = { key ->
                val next = if (key in nameFields) nameFields - key else nameFields + key
                // 至少留一个：全关掉文件名就只剩扩展名了
                if (next.isNotEmpty()) onNameFields(next)
            },
            onDismiss = { showNameFields = false },
        )
    }

    if (showPurge && root != null && lib != null) {
        PurgeIsolateDialog(root = root, lib = lib, onDismiss = { showPurge = false })
    }

    if (showPurgeDelete && root != null && lib != null) {
        PurgeDeleteDialog(root = root, lib = lib, onDismiss = { showPurgeDelete = false })
    }
}

/** 文件名包含哪些字段：四枚胶囊 + 实时预览 */
@Composable
private fun NameFieldsDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = Mirror.NameField.values()
        .filter { it.name in selected }
        .joinToString("_") { it.sample }
        .let { if (it.isBlank()) "（空）" else "$it.png" }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = { Text("文件名包含哪些字段", style = T.titleSm, color = Paper) },
        text = {
            Column {
                Text(
                    "顺序固定为「日期 / 角色 / 标题 / 版本」，世界观是文件夹名，不在文件名里。",
                    style = T.micro,
                    color = Muted,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Mirror.NameField.values().forEach { f ->
                        ChipPill(
                            f.label,
                            selected = f.name in selected,
                            onClick = { onToggle(f.name) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.l))
                Text("预览", style = T.micro, color = Muted)
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(CardBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(preview, style = T.bodySm, color = Paper)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "至少要保留一个字段；改动后重新同步，旧文件会被自动清理。",
                    style = T.micro,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("好", style = T.body, color = Accent)
            }
        },
    )
}

/** 设置行右侧的值胶囊：`可撤 5 步 ›`。带底色，和裸文字比更像「可以点进去的东西」 */
@Composable
private fun ValuePill(text: String, warn: Boolean = false) {
    Text(
        text,
        style = T.micro,
        color = if (warn) Warn else OnMid,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (warn) WarnC else CHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 设置行：左标题右内容，[onClick] 为空时不可点 */
@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    sub: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val base = Modifier.fillMaxWidth()
    val rowMod = if (onClick != null) base.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick) else base
    Row(
        rowMod.padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body, color = if (enabled) Paper else Muted)
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(sub, style = T.micro, color = Muted)
            }
        }
        if (value != null) {
            Spacer(Modifier.width(10.dp))
            Text(value, style = T.label, color = if (enabled) OnMid else Muted, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            trailing()
        }
    }
}
