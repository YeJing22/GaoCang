package com.yejing.artvault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import coil.compose.LocalImageLoader
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.yejing.artvault.browse.AssignScreen
import com.yejing.artvault.browse.BrowseScreen
import com.yejing.artvault.browse.SearchScreen
import com.yejing.artvault.browse.FilterSheet
import com.yejing.artvault.browse.MergeSheet
import com.yejing.artvault.ui.component.DeleteConfirmDialog
import com.yejing.artvault.ui.component.ExportConfirmDialog
import com.yejing.artvault.ui.component.MirrorCancelDialog
import com.yejing.artvault.ui.component.GaoBottomBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class UndoState(val opId: String, val label: String)

/** 打开媒体选择器的意图：合为单一状态，避免新旧状态漏复位 */
private sealed interface MediaMode {
    data object NewArtwork : MediaMode
    data class AddVersion(val artId: String) : MediaMode
    data class NewDerived(val sourceId: String) : MediaMode
}

/** 通用「点一张稿」选择态（屏幕取字套用 / 制品关联原稿 / 选库内已有稿转为制品 / 拆出版本合并至其他稿件） */
private data class PickArt(
    val mode: Mode,
    val artId: String = "",
    val verIds: List<String> = emptyList(),
) {
    enum class Mode { Apply, Link, AdoptDerived, MoveVersions }
}

/**
 * 撤销条存活时间。**这个数字只有一份**：既决定 `LaunchedEffect` 什么时候清掉撤销条，
 * 也决定底部那条进度线走多久。以前 4000 是写在 delay 里的裸数字，加进度线时若再写一个 4000，
 * 两边早晚会漂（改了一个忘了另一个 = 线走完了条还在，或反过来）。
 */
private const val UndoWindowMs = 4000L

data class Screen(
    val arts: List<Art>,
    val lib: Library,
    val demo: Boolean,
    val hasPerm: Boolean,
    val root: File?,
    val tax: Taxonomy,
    /** 画师实体仓库（含主页链接） */
    val artists: ArtistBook,
    /** 标签实体仓库 */
    val tags: TagBook,
    /** 归档分类仓库（导出目录用，和世界观无关） */
    val folders: FolderBook,
)

private fun hasAllFiles(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

private suspend fun load(ctx: Context, prefs: Prefs): Screen {
    val perm = hasAllFiles()

    val saved = prefs.vaultPath?.let { File(it) }?.takeIf { perm && VaultLocator.isVault(it) }
    val root = saved ?: (if (perm) VaultLocator.autoFind() else null)

    // **有库就是真库——哪怕是空的。** 以前用 arts.isNotEmpty() 判，空库会回落演示数据：
    // 路径错了 / 库坏了 / 只是真的还没建档，三种情况全被「演示库」盖住，还出现
    // 「演示数据 + 真实 root」的混合态（演示模式里 root 必须是 null，见下面）。
    if (root != null) {
        // 先把上次没走完的操作回滚掉，再读库——否则会把「合并到一半」的状态读进内存
        OperationJournal.recover(root)
        val lib = Library(File(root, "meta/artworks"), File(root, "thumbs"), File(root, "blobs"), demo = false)
        val arts = lib.load()
        prefs.vaultPath = root.absolutePath
        val tax = Taxonomy(File(root, "meta/taxonomy.json")).load()
        return Screen(
            arts, lib, demo = false, hasPerm = perm, root = root, tax = tax,
            artists = ArtistBook.load(root),
            tags = TagBook.load(root),
            folders = FolderBook.load(root).also { runCatching { it.ensureSystem() } },
        )
    }

    // 没有可用库 → 演示库。root 强制 null：演示态里出现真实路径，后面的写操作就会悄悄落进真库。
    // 目录结构与真库完全一致（meta/ + thumbs/），只是按只读打开——演示态和真库走同一套代码
    val d = Demo.ensure(ctx)
    val lib = Library(File(d, "meta/artworks"), File(d, "thumbs"), null, demo = true)
    return Screen(
        lib.load(), lib, demo = true, hasPerm = perm, root = null,
        tax = Taxonomy(File(d, "meta/taxonomy.json")).load(),
        artists = ArtistBook.load(d, readOnly = true),
        tags = TagBook.load(d, readOnly = true),
        folders = FolderBook.load(d, readOnly = true),
    )
}

/**
 * 只刷新登记过的那几条稿，其余原样复用。
 *
 * 实体文件（taxonomy / artists / tags / folders）很小，顺手全量重读 —— 批量赋值会内联新建
 * 画师和标签，删实体也会改 taxonomy，与其逐个判断不如整份读一遍（几毫秒）。
 */
private fun loadPatched(prev: Screen, dirty: Set<String>): Screen {
    val root = prev.root ?: return prev
    val dir = File(root, "meta/artworks")
    val changed = ArrayList<Art>(dirty.size)
    dirty.forEach { id ->
        val f = File(dir, "$id.json")
        // 文件还在 → 替换；不在了（删除 / 合并掉）→ 下面 filterNot 会把它摘掉
        if (f.isFile) runCatching { parseArt(f.readText()) }.getOrNull()?.let { changed += it }
    }
    val arts = (prev.arts.filterNot { it.id in dirty } + changed)
        .sortedWith(compareByDescending { it.receivedAt ?: "" })
    return prev.copy(
        arts = arts,
        tax = Taxonomy(File(root, "meta/taxonomy.json")).load(),
        artists = ArtistBook.load(root),
        tags = TagBook.load(root),
        folders = FolderBook.load(root).also { runCatching { it.ensureSystem() } },
    )
}

private fun openAllFilesSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    runCatching {
        ctx.startActivity(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            )
        )
    }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        ThemeState.apply(Prefs(applicationContext).themePreset)
        // 全面屏：内容画到状态栏 / 导航栏下面，底栏自己消化 inset
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            // 随浅色/深色主题同步切换状态栏与导航栏图标明暗，避免浅色背景下状态栏白字看不清
            val isDark = ThemeState.palette.isDark
            LaunchedEffect(isDark) {
                val style = if (isDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    )
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            // 全Compose 树共用一个注册了 GifDecoder 的 loader：网格仍吃缩略图（静态首帧），
            // 只有详情页取原图时才会走动图解码 —— 别把网格也切到原图，1813 件同时解动图会卡死。
            // Coil 2.x 没有 setSingletonImageLoaderFactory（那是 3.x 的 API），用 CompositionLocal 最省事。
            val loader = remember { appImageLoader(applicationContext) }
            CompositionLocalProvider(LocalImageLoader provides loader) {
                GaoCangTheme { Root() }
            }
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    /** 当前打开的角色独立页；非空时盖住底栏与角色库 */
    var roleViewId by remember { mutableStateOf<String?>(null) }
    /**
     * 世界观二级页（角色库 → 点世界观卡）。
     * 和 [roleViewId] 是上下层关系：世界观页点角色进角色页，返回先回世界观页。
     * worldId 为 null = 「未归入世界观」这个虚拟分组。
     */
    var worldView by remember { mutableStateOf<WorldTarget?>(null) }
    /** 归档文件夹二级页；folderId = null 表示「未分类」 */
    var folderView by remember { mutableStateOf<FolderTarget?>(null) }
    /** 文件夹页里正在看哪一件（按 id，理由同 [roleOpenId]） */
    var folderOpenId by remember { mutableStateOf<String?>(null) }
    /**
     * 文件夹二级页自己的筛选/排序。**和稿库的 [filter] 分开存**——
     * 否则在文件夹里筛完再回稿库，稿库的列表也被悄悄改了。
     */
    var folderFilter by remember { mutableStateOf<Filter?>(null) }
    /** 当前打开的画师页（统计 → 画师反查）；画师只是个字符串，直接存名字 */
    var artistView by remember { mutableStateOf<String?>(null) }
    /**
     * 角色页 / 画师页里正在看哪一件（按稿件 id）。
     * 放在 Root 而不是各自页面里：点「编辑 / 管理版本」时 Root 会换成另一个分支，
     * 那两个页面整个离开组合，本地 remember（含标签筛选）被丢掉，返回时会从详情页掉回网格。
     */
    var roleOpenId by remember { mutableStateOf<String?>(null) }
    var artistOpenId by remember { mutableStateOf<String?>(null) }
    /** 详情页各稿件的滚动进度（按 art.id 记）：跳去角色/画师/编辑页再返回时恢复 */
    val detailScrolls = remember { HashMap<String, Int>() }
    var showDirPicker by remember { mutableStateOf(false) }
    var detailId by remember { mutableStateOf<String?>(null) }
    /** 详情页点「关联制品 / 衍生自」打开的关联详情：压在各二级页与主详情之上，按返回先回到原稿详情 */
    var linkedArtId by remember { mutableStateOf<String?>(null) }
    var showMedia by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<MediaItem>?>(null) }
    /** 屏幕取字识别完，等用户选「新建一稿 / 套用到已有」的那一份 */
    var senseAsk by remember { mutableStateOf<SenseDraft?>(null) }
    /**
     * 「套用到已有稿件」的取字结果：在 EditScreen 打开期间保持非空，关闭编辑页时清掉。
     */
    var senseApply by remember { mutableStateOf<SenseDraft?>(null) }
    /** 通用「点一张稿」模式（取字套用 / 制品关联原稿 / 选库内稿转为制品共用） */
    var pickArt by remember { mutableStateOf<PickArt?>(null) }
    /** 点「＋制品」后的两选一弹窗（非空 = 原稿 id） */
    var addDerivedAsk by remember { mutableStateOf<String?>(null) }
    /** 选库内已有稿转为制品的确认弹窗（pickedArtId to sourceArtId） */
    var adoptConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** 媒体选择器的目的（建普通稿 / 加版本 / 建制品）；null = 选择器未激活 */
    var mediaMode by remember { mutableStateOf<MediaMode?>(null) }
    var filter by remember { mutableStateOf(Filter()) }
    var showFilter by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<String?>(null) }
    /** 多选模式（由稿库页自己维护选择集，这里只负责藏底栏和 FAB）*/
    var selecting by remember { mutableStateOf(false) }
    var assignTargets by remember { mutableStateOf<List<Art>?>(null) }
    var mergeTargets by remember { mutableStateOf<List<Art>?>(null) }
    /** 待确认删除的稿件：删除不可撤（只进 .trash），所以一定要走这一步确认 */
    var deleteTargets by remember { mutableStateOf<List<Art>?>(null) }
    /** 版本管理页对应的稿件 id；非空即打开该页 */
    var versionOf by remember { mutableStateOf<String?>(null) }
    /** 全屏搜索态。打开时关键词一并清掉，避免上次的搜索条件莫名其妙带进来 */
    var searchOpen by remember { mutableStateOf(false) }
    fun closeSearch() {
        searchOpen = false
        filter = filter.copy(query = "")
    }
    /** 回收站（「我的」里进）；非空即打开该页 */
    var showTrash by remember { mutableStateOf(false) }
    /** 操作历史（撤销 / 重做）；和回收站同形态 */
    var showHistory by remember { mutableStateOf(false) }
    /** 一致性检查弹窗（「我的 → 数据」里进） */
    var showDoctor by remember { mutableStateOf(false) }
    var trashSlots by remember { mutableIntStateOf(0) }
    var trashVersions by remember { mutableIntStateOf(0) }
    // 列数固定 3（R3 的决定：删掉 2/3 切换）。prefs.gridColumns 留着只为兼容旧配置文件。
    val columns = 3
    var tick by remember { mutableIntStateOf(0) }
    var mirrorBusy by remember { mutableStateOf(false) }
    /** 全量同步前的确认 */
    var mirrorConfirm by remember { mutableStateOf(false) }
    var mirrorIncludeDerived by remember { mutableStateOf(false) }
    var mirrorIncludeFill by remember { mutableStateOf(false) }
    /** 取消后已经写出去的文件：非空即询问「保留 / 删除」 */
    var mirrorOrphans by remember { mutableStateOf<List<String>?>(null) }
    // 取消标志用 AtomicBoolean：它在 IO 线程被循环读取，Compose 的 state 跨线程读不安全
    val mirrorStop = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    /**
     * 批量写入的进行中状态。放 Root 而不是放到各个 sheet 里：
     * sheet 一关就离开组合，进度会跟着丢，而写入还在继续。
     */
    var busy by remember { mutableStateOf<Busy?>(null) }
    var undoBar by remember { mutableStateOf<UndoState?>(null) }
    var mirrorCount by remember { mutableIntStateOf(0) }
    /** 镜像进度：2230 个版本要复制几分钟，得让用户看到在动 */
    var mirrorDone by remember { mutableIntStateOf(0) }
    var mirrorTotal by remember { mutableIntStateOf(0) }
    var nameFields by remember { mutableStateOf(prefs.mirrorNameFields) }
    var lastCleanedId by remember { mutableStateOf(prefs.lastCleanedId) }
    var pendingUndoSync by remember { mutableStateOf(false) }
    var petLabel by remember { mutableStateOf(prefs.petLabel) }
    var seenOnboarding by remember { mutableStateOf(prefs.seenPicker) }

    /**
     * 多选导出：整批交给 [Mirror.export] 一次 plan，不是逐件循环调用。
     *
     * 逐件调会各自从空的「已用名字」集合开始算重名，同名稿件（同一天同一角色同一标题）
     * 的 `(2)(3)` 编号会跨件重复，最终和全量镜像对不上。
     *
     * 只往「相册」目录复制副本，不动 `meta/`，所以不走操作日志、也不需要撤销。
     */
    fun exportBatch(list: List<Art>, s: Screen) {
        val r = s.root
        if (r == null) {
            toast(ctx, "演示库不能导出")
            return
        }
        if (list.isEmpty()) return
        scope.launch {
            busy = Busy("正在导出", 0, 0)
            val res = try {
                withContext(Dispatchers.IO) {
                    Mirror.export(
                        ctx, r, s.lib, list, s.tax, s.folders,
                        fields = Mirror.fieldsOf(nameFields),
                        allArts = s.arts,
                    ) { done, total ->
                        // 回调在 IO 线程：Compose 状态只能回主线程改
                        scope.launch {
                            if (done % 5 == 0 || done == total) busy = Busy("正在导出", done, total)
                        }
                    }
                }
            } catch (t: Throwable) {
                Mirror.Result(error = t.message ?: "未知原因")
            } finally {
                busy = null
            }
            toast(
                ctx,
                run {
                    val base = when {
                        res.error != null -> "导出失败：${res.error}"
                        res.written == 0 && res.failed == 0 -> "${res.skipped} 个已是最新，无需重复导出"
                        res.written == 0 -> "已导出 0 个"
                        res.skipped == 0 -> "已导出 ${res.written} 个"
                        else -> "导出 ${res.written} 个，${res.skipped} 个已是最新"
                    }
                    if (res.error == null && res.failed > 0) "$base，${res.failed} 个写入失败" else base
                },
            )
        }
    }

    LaunchedEffect(screen?.root) {
        val r = screen?.root
        mirrorCount = if (r == null) 0 else withContext(Dispatchers.IO) { Mirror.lastCount(r) }
        // 补 .nomedia 并把已经混进图库的 blobs/thumbs 索引清掉（用户实际踩过）
        if (r != null) withContext(Dispatchers.IO) { runCatching { Gallery.tidy(ctx, r) } }
    }

    LaunchedEffect(screen?.root, tick) {
        val r = screen?.root
        // 分开统计：整槽和单版本是两种东西，「3 条」看不出里面有几个槽
        val (slots, versions) = if (r == null) 0 to 0 else withContext(Dispatchers.IO) {
            val entries = Trash(r).list()
            entries.count { it is TrashEntry.Slot } to entries.count { it is TrashEntry.Version }
        }
        trashSlots = slots
        trashVersions = versions
    }

    LaunchedEffect(tick) {
        // 写入方通过 VaultChanges 登记「改了哪几条」：有登记就只补那几条，没有才整库重读。
        // 量出来的差距：整库 380ms（并行解析）vs 只补一条 ~5ms。
        val t0 = System.currentTimeMillis()
        val dirty = VaultChanges.drain()
        val prev = screen
        val loaded = if (dirty.isNotEmpty() && prev != null) {
            withContext(Dispatchers.IO) { loadPatched(prev, dirty) }
        } else {
            withContext(Dispatchers.IO) { load(ctx, prefs) }
        }
        screen = loaded
        if (pendingUndoSync) {
            pendingUndoSync = false
            val validIds = loaded.arts.mapTo(HashSet()) { it.id }
            val prevId = loaded.root?.let { r ->
                withContext(Dispatchers.IO) { OperationJournal.lastTouchedArtId(r, validIds) }
            } ?: loaded.arts
                .filter { it.counted && Gap.entries.none { g -> it.matchesGap(g) } && !it.updatedAt.isNullOrBlank() }
                .maxByOrNull { it.updatedAt!! }
                ?.id
            lastCleanedId = prevId
            prefs.lastCleanedId = prevId
        } else if (dirty.isNotEmpty()) {
            val cleaned = loaded.arts
                .filter { it.id in dirty && it.counted && Gap.entries.none { g -> it.matchesGap(g) } }
                .maxByOrNull { it.updatedAt ?: "" }
            if (cleaned != null) {
                lastCleanedId = cleaned.id
                prefs.lastCleanedId = cleaned.id
            }
        }
        if (lastCleanedId == null || loaded.arts.none { it.id == lastCleanedId }) {
            val fallback = loaded.arts
                .filter { it.counted && Gap.entries.none { g -> it.matchesGap(g) } && !it.updatedAt.isNullOrBlank() }
                .maxByOrNull { it.updatedAt!! }
            lastCleanedId = fallback?.id
            if (fallback != null) prefs.lastCleanedId = fallback.id
        }
        android.util.Log.d(
            "GaoCangPerf",
            "reload ${System.currentTimeMillis() - t0}ms arts=${screen?.arts?.size} dirty=${dirty.size}",
        )
    }

    // 从系统授权页返回时重新加载
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> {
                    tick++
                    // 无障碍服务据此判断"点球"是记目标（在稿仓里）还是抓屏（在米画师里）
                    SenseHandoff.appVisible = true
                }
                Lifecycle.Event.ON_PAUSE -> SenseHandoff.appVisible = false
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        SenseHandoff.appVisible = true
        onDispose {
            owner.lifecycle.removeObserver(obs)
            SenseHandoff.appVisible = false
        }
    }

    // 详情页开着哪张稿：在稿仓里点悬浮球 = 记住这一张（先指目标，再去米画师取）
    // 打开编辑页时清空 uiArtId，避免抓完回编辑页时悬浮球错回「记」
    LaunchedEffect(editId, linkedArtId, roleOpenId, artistOpenId, folderOpenId, detailId) {
        SenseHandoff.uiArtId = if (editId != null) null
        else (linkedArtId ?: roleOpenId ?: artistOpenId ?: folderOpenId ?: detailId)
    }

    // 操作完成后 4 秒底部撤销条：统一挂在 OperationJournal.onDone，不漏调用点，无实际变更不触发
    DisposableEffect(Unit) {
        OperationJournal.onDone = { rec ->
            Handler(Looper.getMainLooper()).post {
                if (screen?.root != null && screen?.demo == false) {
                    undoBar = UndoState(rec.id, rec.label)
                }
            }
        }
        onDispose { OperationJournal.onDone = null }
    }

    LaunchedEffect(undoBar?.opId) {
        if (undoBar != null) {
            delay(UndoWindowMs)
            undoBar = null
        }
    }

    val s0 = screen
    // 读侧 join：稿件上只有 id，展示名字（画师/标签）都要从这里过
    val names = remember(s0) { s0?.let { VaultNames(it.artists, it.tags) } }
    val shown = remember(s0?.arts, filter) {
        val n = names ?: return@remember emptyList()
        s0?.let { filter.apply(it.arts, n, it.tax) } ?: emptyList()
    }

    // 任何覆盖层打开时都由这里接管返回键。
    // 注意：不能加 `detailId == null` 条件——从详情页点「＋版本」进媒体选择器时 detailId 非空，
    // 而 DetailScreen 已被 showMedia 分支遮蔽、它自己的 BackHandler 已注销，
    // 一旦这里也不接管，就没有任何东西处理返回键 → 系统直接把 Activity 结束掉。
    // 自带遮罩的浮层（bottom sheet / dialog）：它们把底边整片盖住就够了。
    // 以前底栏跟着一起收起 → 页面高度变化 → 网格重排，观感上是「底栏先闪没了，面板才出来」，
    // 比实际耗时更显慢（用户 2026-11-18 观察到的正是这个）。整页覆盖层才收底栏。
    val floatOpen = s0 != null &&
        (showFilter || mergeTargets != null || deleteTargets != null)

    val fullOpen = s0 != null &&
        (editId != null || assignTargets != null || picked != null || showMedia || showDirPicker ||
            searchOpen || versionOf != null || linkedArtId != null || roleViewId != null || worldView != null ||
            folderView != null || artistView != null || showTrash || showHistory)

    val overlayOpen = floatOpen || fullOpen

    // 选稿模式也算一层覆盖：以前它不在这个判断里，在稿库主界面按返回会直接把 App 退掉
    // （用户 2026-11-22 报的「选已有稿转制品时按返回退出应用」）。
    BackHandler(enabled = overlayOpen || tab != 0 || pickArt != null) {
        when {
            searchOpen -> closeSearch()
            versionOf != null -> {
                android.util.Log.d("GaoCangBack", "main versionOf")
                versionOf = null
            }
            showTrash -> showTrash = false
            showHistory -> showHistory = false
            deleteTargets != null -> deleteTargets = null
            showFilter -> showFilter = false
            // 批量赋值已经是整页二级界面（2026-11-19），返回键要留在这一页，不能落到 else -> tab = 0
            assignTargets != null -> assignTargets = null
            editId != null -> editId = null
            // 建档页退回媒体选择器，保留已选意图（mediaMode 不清）
            picked != null -> {
                picked = null
                showMedia = true
            }
            showMedia -> {
                showMedia = false
                mediaMode = null
            }
            showDirPicker -> showDirPicker = false
            linkedArtId != null -> {
                val prev = linkedArtId
                linkedArtId = null
                if (prev != null && prev != detailId && prev != roleOpenId && prev != artistOpenId && prev != folderOpenId) {
                    detailScrolls.remove(prev)
                }
            }
            // 这几个二级页内部还会再开一层「详情」。返回键在两层都注册了 BackHandler，
            // 谁先响应取决于注册顺序；所以这里必须做成「有详情先关详情」——
            // 否则一旦这一层抢先，就会一次把详情和整页一起关掉，观感上是「返回键多按了一次」。
            roleViewId != null -> {
                android.util.Log.d("GaoCangBack", "main role openId=${roleOpenId}")
                if (roleOpenId != null) roleOpenId = null else roleViewId = null
            }
            worldView != null -> worldView = null
            folderView != null -> {
                android.util.Log.d("GaoCangBack", "main folder openId=${folderOpenId}")
                if (folderOpenId != null) folderOpenId = null else folderView = null
            }
            artistView != null -> {
                android.util.Log.d("GaoCangBack", "main artist openId=${artistOpenId}")
                if (artistOpenId != null) artistOpenId = null else artistView = null
            }
            // 选稿模式：先退出选稿，别把整个 App 退掉（这和「取消」按钮是同一件事）。
            // 限定 tab == 0：选稿是在稿库里点的，人在别的 tab 时返回应该先回稿库，而不是悄悄取消选稿。
            pickArt != null && tab == 0 -> {
                val p = pickArt
                pickArt = null
                if (p?.mode == PickArt.Mode.Apply) senseApply = null
            }
            else -> tab = 0
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        val s = screen
        val sel = picked
        val di = if (detailId == null || s == null) -1 else shown.indexOfFirst { it.id == detailId }
        // 制品默认不在 shown 里：库里确实有这条稿就用单条列表打开（关联查看），只有真被删了才清空 detailId
        val soloArt = if (detailId != null && di < 0 && s != null) {
            s.arts.firstOrNull { it.id == detailId }
        } else null
        val detailOpen = di >= 0 || soloArt != null
        LaunchedEffect(di, soloArt, detailId) {
            if (detailId != null && di < 0 && soloArt == null) detailId = null
        }

        fun enterPickMode(mode: PickArt) {
            // 关闭所有二级覆盖层（含独立叠加层 FolderScreen），露出稿库与顶部横幅
            linkedArtId = null
            detailId = null
            folderView = null
            folderOpenId = null
            roleViewId = null
            roleOpenId = null
            artistView = null
            artistOpenId = null
            worldView = null
            searchOpen = false
            mediaMode = null
            picked = null
            showMedia = false
            tab = 0
            pickArt = mode
        }

        fun enterPickSource(artId: String) = enterPickMode(PickArt(PickArt.Mode.Link, artId))
        fun enterAdoptDerived(sourceId: String) = enterPickMode(PickArt(PickArt.Mode.AdoptDerived, sourceId))
        fun enterMoveVersions(srcArtId: String, verIds: List<String>) =
            enterPickMode(PickArt(PickArt.Mode.MoveVersions, srcArtId, verIds))

        fun openRoleFromDetail(roleId: String) {
            linkedArtId = null
            roleOpenId = null
            roleViewId = roleId
        }

        fun openArtistFromDetail(artistId: String) {
            // linkedArtId / roleViewId / worldView 在 when 里排在 artistView 之前，不清空会遮住画师页
            linkedArtId = null
            roleViewId = null
            roleOpenId = null
            worldView = null
            artistOpenId = null
            artistView = artistId
        }

        fun clearScrollIfClosed(id: String?) {
            if (id != null && id != linkedArtId && id != detailId && id != roleOpenId && id != artistOpenId && id != folderOpenId) {
                detailScrolls.remove(id)
            }
        }

        fun openArtFromDetail(artId: String) {
            // 如果点的正是底下正开着的原稿，直接关掉关联层回到原稿，不重复叠一层
            val underId = when {
                roleViewId != null -> roleOpenId
                artistView != null -> artistOpenId
                detailOpen -> detailId
                folderView != null -> folderOpenId
                else -> null
            }
            if (linkedArtId != null && artId == underId) {
                val prev = linkedArtId
                linkedArtId = null
                clearScrollIfClosed(prev)
            } else {
                linkedArtId = artId
            }
        }

        fun filterFromDetail(nextFilter: Filter) {
            linkedArtId = null
            detailId = null
            folderView = null
            folderOpenId = null
            folderFilter = null
            roleViewId = null
            roleOpenId = null
            artistView = null
            artistOpenId = null
            worldView = null
            searchOpen = false
            showFilter = false
            tab = 0
            filter = nextFilter
        }

        /** 退出选稿模式。取字那条路还要把识别结果一起丢掉，否则下次「＋」建档会带上它 */
        fun cancelPick() {
            val p = pickArt ?: return
            pickArt = null
            if (p.mode == PickArt.Mode.Apply) senseApply = null
        }

        fun handlePickArt(pickedId: String, sc: Screen) {
            val p = pickArt ?: return
            when (p.mode) {
                PickArt.Mode.Apply -> {
                    pickArt = null
                    searchOpen = false
                    editId = pickedId
                }
                PickArt.Mode.Link -> {
                    val r = sc.root
                    if (r == null) {
                        toast(ctx, "演示库不能改")
                        return
                    }
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                OperationJournal.run(r, "link", "关联原稿") {
                                    ArtworkCommands.linkSource(r, p.artId, pickedId, sc.tax)
                                }
                            }.getOrElse { false }
                        }
                        if (ok) {
                            pickArt = null
                            searchOpen = false
                            toast(ctx, "已关联原稿")
                            tick++
                        } else {
                            toast(ctx, "不能关联到它（会形成环）")
                        }
                    }
                }
                PickArt.Mode.AdoptDerived -> {
                    if (pickedId == p.artId) {
                        toast(ctx, "不能选原稿自己")
                        return
                    }
                    val target = sc.arts.firstOrNull { it.id == pickedId } ?: return
                    if (target.kind != ArtKind.Artwork) {
                        toast(ctx, "它已经是制品或制图了")
                        return
                    }
                    if (sc.arts.any { it.sourceArtId == pickedId }) {
                        toast(ctx, "这件稿下面已有制品，不能转为制品")
                        return
                    }
                    adoptConfirm = pickedId to p.artId
                }
                PickArt.Mode.MoveVersions -> {
                    if (pickedId == p.artId) {
                        toast(ctx, "不能合并到当前同一件稿件")
                        return
                    }
                    val r = sc.root
                    if (r == null) {
                        toast(ctx, "演示库不能改")
                        return
                    }
                    val dstTitle = sc.arts.firstOrNull { it.id == pickedId }?.title?.ifBlank { "未命名" } ?: "未命名"
                    val srcId = p.artId
                    val verIds = p.verIds
                    scope.launch {
                        val moved = withContext(Dispatchers.IO) {
                            runCatching {
                                OperationJournal.run(r, "art", "将 ${verIds.size} 个版本并入「$dstTitle」") {
                                    ArtworkCommands.moveVersionsToArt(r, srcId, pickedId, verIds, sc.tax)
                                }
                            }.getOrElse { 0 }
                        }
                        if (moved > 0) {
                            pickArt = null
                            searchOpen = false
                            toast(ctx, "已将 $moved 个版本并入「$dstTitle」")
                            tick++
                        } else {
                            toast(ctx, "合并失败，检查源稿是否仍存在")
                        }
                    }
                }
            }
        }

        fun unlinkArt(artId: String, sc: Screen) {
            val r = sc.root
            if (r == null) {
                toast(ctx, "演示库不能改")
                return
            }
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        OperationJournal.run(r, "unlink", "解除原稿关联") {
                            ArtworkCommands.unlinkSource(r, artId, sc.tax)
                        }
                    }.getOrElse { false }
                }
                if (ok) {
                    toast(ctx, "已解除关联")
                    tick++
                } else {
                    toast(ctx, "解除失败")
                }
            }
        }

        // 屏幕取字：无障碍服务识别成功后把 App 拉到前台，结果放在 SenseHandoff。
        //
        // 两条路：
        // 1. **先指过目标**（在详情页点过球）→ 直接进那张稿的「编辑信息」，预填识别结果。
        //    这是主路径：图多半早就导入过了，不用再选一次。
        // 2. 没指过目标 → 弹框问「新建一稿 / 套用到已有」（忘了先点「取」时的兜底）。
        val senseDraft = SenseHandoff.draft
        LaunchedEffect(senseDraft) {
            if (senseDraft != null) {
                val target = SenseHandoff.takeTarget()
                if (target != null) {
                    senseAsk = null
                    SenseHandoff.draft = null
                    senseApply = senseDraft
                    tab = 0
                    editId = target
                } else {
                    senseAsk = senseDraft
                }
            }
        }

        // 「点一张稿」横幅（取字套用 / 关联原稿 / 转为制品共用）。
        // 文案在这里算，画由 BrowseScreen 负责 —— 以前是 `align(TopCenter)` 的浮层，
        // 和「稿库」大标题叠在一起；现在排在标题下方、随内容下移。
        val curPick = pickArt
        LaunchedEffect(curPick, senseApply) {
            if (curPick?.mode == PickArt.Mode.Apply && senseApply == null) {
                pickArt = null
                senseApply = null
            }
        }
        val pickHint = if (curPick != null && !detailOpen && !selecting && !showMedia && picked == null && editId == null) {
            when (curPick.mode) {
                PickArt.Mode.Apply -> if (senseApply != null) "点击想要录入的稿件" else null
                PickArt.Mode.Link -> "点一张稿作为它的原稿"
                PickArt.Mode.AdoptDerived -> {
                    val srcTitle = s?.arts?.firstOrNull { it.id == curPick.artId }?.title?.ifBlank { "未命名" } ?: "原稿"
                    "点一张已有稿件，转为「$srcTitle」的制品"
                }
                PickArt.Mode.MoveVersions ->
                    "点一张目标稿件，将 ${curPick.verIds.size} 个版本并入其中"
            }
        } else null

        if (s == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(28.dp),
                color = Accent,
            )
        } else {
            val barsVisible = !fullOpen && !detailOpen && !selecting

            when (tab) {
                0 -> BrowseScreen(
                    arts = s.arts,
                    shown = shown,
                    lib = s.lib,
                    tax = s.tax,
                    demo = s.demo,
                    hasPerm = s.hasPerm,
                    filter = filter,
                    columns = columns,
                    onFilter = { filter = it },
                    onOpenFilter = { showFilter = true },
            onOpenSearch = { searchOpen = true },
                    onGrant = { openAllFilesSettings(ctx) },
                    onPickRoot = { showDirPicker = true },
                    onOpen = {
                        val id = shown[it].id
                        if (pickArt != null) handlePickArt(id, s) else detailId = id
                    },
                    onAssign = { assignTargets = it },
                    onMerge = { mergeTargets = it },
                    onDelete = { deleteTargets = it },
                    onExport = { exportBatch(it, s) },
                    onSelectingChange = { selecting = it },
                    pickHint = pickHint,
                    onCancelPick = { cancelPick() },
                    lastCleanedId = lastCleanedId,
                )

                1 -> TaxonomyScreen(
                    tax = s.tax,
                    folders = s.folders,
                    arts = s.arts,
                    lib = s.lib,
                    root = s.root,
                    onChanged = { tick++ },
                    onBack = { tab = 0 },
                    onOpenRole = {
                        roleOpenId = null
                        roleViewId = it
                    },
                    onOpenWorld = { worldView = it },
                    onOpenFolder = { f ->
                        folderOpenId = null
                        val isSys = f.folderId != null && s.folders.byId(f.folderId)?.system != null
                        folderFilter = Filter(
                            folderId = f.folderId ?: UNSORTED_FOLDER,
                            kind = if (isSys) KindFilter.All else KindFilter.Artwork,
                        )
                        folderView = f
                    },
                    onOpenArtist = { id ->
                        artistOpenId = null
                        artistView = id
                    },
                    onFilterTag = { tag -> filterFromDetail(Filter(tags = setOf(tag), sort = filter.sort)) },
                    artists = s.artists,
                    tags = s.tags,
                )

                2 -> StatsScreen(
                    arts = s.arts,
                    tax = s.tax,
                    lib = s.lib,
                    artists = s.artists,
                    names = names!!,
                    onOpenArtist = {
                        artistOpenId = null
                        artistView = it
                    },
                )

                else -> ProfileScreen(
                    root = s.root,
                    demo = s.demo,
                    hasPerm = s.hasPerm,
                    count = s.arts.countedOnly().size,
                    onPickRoot = { showDirPicker = true },
                    onGrant = { openAllFilesSettings(ctx) },
                    mirrorBusy = mirrorBusy,
                    mirrorCount = mirrorCount,
                    mirrorDone = mirrorDone,
                    mirrorTotal = mirrorTotal,
                    nameFields = nameFields,
                    onNameFields = {
                        nameFields = it
                        prefs.mirrorNameFields = it
                    },
                    trashSlots = trashSlots,
                    trashVersions = trashVersions,
                    onOpenTrash = { showTrash = true },
                    onOpenHistory = { showHistory = true },
                    onOpenDoctor = { showDoctor = true },
                    lib = s.lib,
                    arts = s.arts,
                    tax = s.tax,
                    folders = s.folders,
                    names = names,
                    petLabel = petLabel,
                    onPetLabel = {
                        petLabel = it
                        prefs.petLabel = it
                    },
                    themePreset = ThemeState.preset,
                    onThemePreset = {
                        ThemeState.preset = it
                        prefs.themePreset = it.id
                    },
                    onSyncMirror = {
                        if (s.root != null && !mirrorBusy) {
                            mirrorIncludeDerived = false
                            mirrorIncludeFill = false
                            mirrorConfirm = true
                        }
                    },
                )
            }

            // 不加淡变了：底栏和动作条的渐变遮罩带现在等高，硬切时遮罩是连续的，
            // 反而是淡变会让遮罩变弱、露出底下网格（用户实测能看到下一行图闪一下）。
            if (barsVisible) {
                // 录入键从右下角的 FAB 挪到最中间（R2）：它以前一直盖着第三列卡片，
                // 回顶按钮也被迫抬到 164dp 去躲它。现在底栏 5 格：稿库 / 分类 / ＋ / 账本 / 我的。
                GaoBottomBar(
                    selected = tab,
                    onSelect = { tab = it },
                    onCapture = {
                        if (s.demo) showDirPicker = true else {
                            mediaMode = MediaMode.NewArtwork
                            showMedia = true
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // 覆盖层的「垫子」：Compose 里 background 不拦截指针事件，覆盖层的空白处会一路
        // 穿透到底下的 tab 页（真踩过：回收站空态能点到「我的」里的「同步到相册」）。
        // 垫在覆盖层下面、tab 页上面：覆盖层自己的可点元素在更上层不受影响。
        if (overlayOpen || detailOpen) {
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures { } }
            )
        }

        // 文件夹二级页放在「垫子之上、覆盖层之下」：
        // 以前它和赋值/合并/删除那些卡片是同一个 when 的兄弟分支，一开卡片整个页面就离开组合，
        // 滚动位置和多选态全丢（表现为「跳回顶部」，且卡片背后的背景闪回一级页）。
        // 放成独立的一层之后，卡片盖在它上面，它原地不动。
    if (folderView != null && s != null) folderView.let { fv ->
            val lockedFolder = fv!!.folderId ?: UNSORTED_FOLDER
            val lockedKind = if (fv.folderId != null && s.folders.byId(fv.folderId)?.system != null) KindFilter.All else null
            FolderScreen(
            folderId = fv.folderId,
            root = s.root,
            folders = s.folders,
            arts = s.arts,
            lib = s.lib,
            tax = s.tax,
            names = names!!,
            columns = columns,
            filter = folderFilter ?: Filter(folderId = lockedFolder, kind = lockedKind ?: KindFilter.Artwork),
            onFilter = { folderFilter = it.copy(folderId = lockedFolder, kind = lockedKind ?: it.kind) },
            onOpenFilter = { showFilter = true },
            openId = folderOpenId,
            onOpenId = {
                val prev = folderOpenId
                folderOpenId = it
                if (it == null) clearScrollIfClosed(prev)
            },
            onClose = {
                val prev = folderOpenId
                folderOpenId = null
                folderView = null
                folderFilter = null
                clearScrollIfClosed(prev)
            },
            onChanged = { tick++ },
            onAssign = { assignTargets = it },
            onMerge = { mergeTargets = it },
            onDelete = { deleteTargets = it },
            onExport = { exportBatch(it, s) },
            onAddVersion = { id ->
                mediaMode = MediaMode.AddVersion(id)
                showMedia = true
            },
            onManageVersions = { id -> versionOf = id },
            onEdit = { a -> editId = a.id },
            onOpenArt = ::openArtFromDetail,
            onAddDerived = { id -> addDerivedAsk = id },
            onUnlink = { id -> unlinkArt(id, s) },
            onPickSource = { id -> enterPickSource(id) },
            onOpenRole = ::openRoleFromDetail,
            onOpenArtist = ::openArtistFromDetail,
            onFilterTag = { tag -> filterFromDetail(Filter(tags = setOf(tag), sort = filter.sort)) },
            onFilterWorld = { wid -> filterFromDetail(Filter(worldId = wid, sort = filter.sort)) },
            savedScrolls = detailScrolls,
            onMoveVersionsToArt = ::enterMoveVersions,
            )
        }


        when {
            versionOf != null && s != null -> {
                val target = s.arts.firstOrNull { it.id == versionOf }
                if (target == null) {
                    // 稿件被删/合并掉了：不能在这里直接写 state（组合期间赋值会抖），走副作用
                    LaunchedEffect(versionOf) { versionOf = null }
                } else {
                    VersionScreen(
                        art = target,
                        lib = s.lib,
                        root = s.root,
                        tax = s.tax,
                        folders = s.folders,
                        onClose = { versionOf = null },
                        // 改完立刻重载：详情页还在下面，回来要看得到新名字/新顺序
                        onChanged = { tick++ },
                        onAddVersion = { id ->
                            mediaMode = MediaMode.AddVersion(id)
                            showMedia = true
                        },
                    )
                    // 版本页自己接管返回，必须在 VersionScreen 之后再注册一次（后注册者优先）。
                    //
                    // 原因：归档页（FolderScreen）是独立叠加层，不在 when 里——它被版本页盖住时
                    // 仍然留在组合中，里面详情页的 BackHandler 也还注册着。那个更晚注册、优先级更高，
                    // 于是前两次返回都被它吃掉（先关看不见的详情，再关版本页），要按三次才退干净。
                    // 角色页/画师页在 when 里与版本页互斥，所以没这个毛病。
                    BackHandler {
                        android.util.Log.d("GaoCangBack", "versionScreen")
                        versionOf = null
                    }
                }
            }

            showHistory && s?.root != null -> HistoryScreen(
                root = s.root,
                onClose = { showHistory = false },
                onChanged = { tick++ },
                onUndo = {
                    pendingUndoSync = true
                    tick++
                },
            )

            showTrash && s?.root != null -> TrashScreen(
                root = s.root,
                lib = s.lib,
                names = VaultNames(s.artists, s.tags),
                onClose = { showTrash = false },
                onChanged = { tick++ },
            )

            mirrorConfirm && s != null -> {
                val r = s.root
                val incDerived = mirrorIncludeDerived
                val incFill = mirrorIncludeFill
                val syncCount = s.arts.count {
                    when (it.kind) {
                        ArtKind.Artwork -> true
                        ArtKind.Derived -> incDerived
                        ArtKind.Fill -> incFill
                    }
                }
                ExportConfirmDialog(
                    title = "同步 $syncCount 件到相册？",
                    detail = "按归档分文件夹、每个版本导一张到「相册」。首次会复制几 GB，手机上要留出空间；过程中可以取消。",
                    extra = {
                        Column {
                            MirrorToggleRow("同时导出制品（相册/制品）", incDerived) {
                                mirrorIncludeDerived = !mirrorIncludeDerived
                            }
                            Spacer(Modifier.height(6.dp))
                            MirrorToggleRow("同时导出制图（相册/制图）", incFill) {
                                mirrorIncludeFill = !mirrorIncludeFill
                            }
                        }
                    },
                    onCancel = { mirrorConfirm = false },
                    onConfirm = {
                        mirrorConfirm = false
                        if (r == null) {
                            toast(ctx, "演示库不能同步")
                        } else {
                            mirrorStop.set(false)
                            mirrorBusy = true
                            mirrorDone = 0
                            mirrorTotal = 0
                            busy = Busy("正在同步到相册", 0, 0)
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    Mirror.sync(
                                        ctx = ctx,
                                        root = r,
                                        lib = s.lib,
                                        arts = s.arts,
                                        tax = s.tax,
                                        folders = s.folders,
                                        fields = Mirror.fieldsOf(nameFields),
                                        includeDerived = incDerived,
                                        includeFill = incFill,
                                        onProgress = { d, t ->
                                            mirrorDone = d
                                            mirrorTotal = t
                                            scope.launch { busy = Busy("正在同步到相册", d, t) }
                                        },
                                        shouldStop = { mirrorStop.get() },
                                    )
                                }
                                mirrorBusy = false
                                busy = null
                                mirrorCount = withContext(Dispatchers.IO) { Mirror.lastCount(r) }
                                when {
                                    res.cancelled -> {
                                        if (res.writtenPaths.isEmpty()) toast(ctx, "已取消")
                                        else mirrorOrphans = res.writtenPaths
                                    }
                                    !res.ok -> toast(ctx, "同步失败：${res.error ?: "未知原因"}")
                                    else -> toast(
                                        ctx,
                                        "已同步 ${res.written} 件 · 跳过 ${res.skipped} · 清理 ${res.removed}" +
                                            if (res.failed > 0) " · ${res.failed} 个写入失败" else "",
                                    )
                                }
                            }
                        }
                    },
                )
            }

            mirrorOrphans != null -> {
                val paths = mirrorOrphans!!
                MirrorCancelDialog(
                    count = paths.size,
                    onKeep = {
                        mirrorOrphans = null
                        toast(ctx, "已保留 ${paths.size} 个文件，下次同步会跳过")
                    },
                    onDelete = {
                        mirrorOrphans = null
                        val r = screen?.root
                        scope.launch {
                            val n = withContext(Dispatchers.IO) {
                                val ok = paths.count { p -> runCatching { File(p).delete() }.getOrDefault(false) }
                                // 文件删了，manifest 里的记录也摘掉，否则「已同步 N 件」会虚高
                                if (r != null) runCatching { Mirror.forget(r, paths) }
                                ok
                            }
                            mirrorCount = if (r == null) 0 else withContext(Dispatchers.IO) { Mirror.lastCount(r) }
                            toast(ctx, "已删除 $n 个文件")
                        }
                    },
                )
            }

            showFilter && s != null -> {
                val fv = folderView
                val lockedKind = if (fv?.folderId != null && s.folders.byId(fv.folderId)?.system != null) {
                    KindFilter.All
                } else null
                FilterSheet(
                    arts = s.arts,
                    tax = s.tax,
                    names = names!!,
                    tags = s.tags,
                    folders = s.folders,
                    prefs = prefs,
                    // 从文件夹页打开时，归档维度锁死（那页的稿件本来就同属一个文件夹）
                    lockFolder = fv != null,
                    lockedKind = lockedKind,
                    // 归档页里「类型」没有意义：制品/制图只住在系统归档，普通归档切过去必然 0 件
                    hideKind = fv != null,
                    init = if (fv != null) {
                        val locked = fv.folderId ?: UNSORTED_FOLDER
                        folderFilter ?: Filter(folderId = locked, kind = lockedKind ?: KindFilter.Artwork)
                    } else {
                        filter
                    },
                    onApply = { f ->
                        if (fv != null) {
                            val locked = fv.folderId ?: UNSORTED_FOLDER
                            folderFilter = f.copy(folderId = locked, kind = lockedKind ?: f.kind)
                        } else {
                            filter = f
                        }
                        showFilter = false
                    },
                    onDismiss = { showFilter = false },
                )
            }

            assignTargets != null && s != null -> {
                val tg = assignTargets!!
                val root = s.root
                AssignScreen(
                    arts = s.arts,
                    targets = tg,
                    tax = s.tax,
                    artists = s.artists,
                    tags = s.tags,
                    folders = s.folders,
                    onApply = { patch ->
                        assignTargets = null
                        if (root == null) {
                            toast(ctx, "演示库不能改")
                        } else {
                            // 画师/标签的名字在 applyPatch 里 ensure 成 id（没有就建实体），
                            // 实体化之后不再需要「改名跟档案」那套补偿逻辑
                            scope.launch {
                                val total = tg.size
                                busy = Busy("正在赋值", 0, total)
                                val n = try {
                                    OperationJournal.run(root, "assign", "批量赋值 $total 件") {
                                        assignBatch(
                                            root,
                                            tg.map { it.id },
                                            patch,
                                            s.artists,
                                            s.tags,
                                            s.tax,
                                        ) { done, t ->
                                            // 每 10 件刷一次（含最后一件）：300 件不必每件都写一次状态
                                            if (done % 10 == 0 || done == t) {
                                                busy = Busy("正在赋值", done, t)
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    toast(ctx, "赋值失败，已回滚：${t.message ?: "未知原因"}")
                                    tick++
                                    return@launch
                                } finally {
                                    busy = null
                                }
                                // 差额要说清：N 小于选中数时用户得知道差在哪
                                toast(
                                    ctx,
                                    when {
                                        n == 0 -> "没有改动"
                                        n < total -> "已更新 $n 件，${total - n} 件失败"
                                        else -> "已更新 $n 件"
                                    },
                                )
                                tick++
                            }
                        }
                    },
                    onBack = { assignTargets = null },
                )
            }

            mergeTargets != null && s != null -> {
                val tg = mergeTargets!!
                val root = s.root
                MergeSheet(
                    targets = tg,
                    lib = s.lib,
                    onConfirm = { pid ->
                        mergeTargets = null
                        if (root == null) {
                            toast(ctx, "演示库不能改")
                        } else {
                            scope.launch {
                                // 合并也不记历史：被并掉的槽在回收站里（带「合并」标签），
                                // 想把它们放回来就去那儿；历史只负责"内容改错了"
                                val r = withContext(Dispatchers.IO) {
                                    mergeArts(root, tg.map { it.id }, pid, s.tax)
                                }
                                toast(
                                    ctx,
                                    if (r.ok) "已合并成 ${r.versions} 个版本"
                                    else "合并失败：${r.error ?: "未知原因"}"
                                )
                                tick++
                            }
                        }
                    },
                    onDismiss = { mergeTargets = null },
                )
            }

            editId != null && s?.root != null -> {
                val target = s.arts.firstOrNull { it.id == editId }
                if (target == null) {
                    editId = null
                } else {
                    EditScreen(
                        art = target,
                        lib = s.lib,
                        root = s.root,
                        tax = s.tax,
                        arts = s.arts,
                        artists = s.artists,
                        tags = s.tags,
                        folders = s.folders,
                        names = names!!,
                        draft = senseApply,
                        onBack = {
                            editId = null
                            senseApply = null
                        },
                        onSaved = {
                            editId = null
                            senseApply = null
                            tick++
                        },
                    )
                    // 编辑页自己接管返回，必须在 EditScreen 之后再注册一次（后注册者优先）。
                    //
                    // 与版本管理页同一个坑：归档页（FolderScreen）是独立叠加层、不在 when 里，
                    // 它被编辑页盖住时仍然留在组合中，里面详情页的 BackHandler 也还注册着。
                    // 那个更晚注册、优先级更高，于是这一次返回被它吃掉——编辑页没关、详情没了，
                    // 看起来就是「返回键失灵，要按两次」。
                    BackHandler {
                        android.util.Log.d("GaoCangBack", "editScreen")
                        editId = null
                    }
                }
            }

            sel != null && s != null -> {
                val derivedSource = (mediaMode as? MediaMode.NewDerived)?.let { m ->
                    s.arts.firstOrNull { it.id == m.sourceId }
                }
                val isNewDerived = mediaMode is MediaMode.NewDerived
                CaptureScreen(
                    items = sel,
                    root = if (s.demo) null else s.root,
                    tax = s.tax,
                    arts = s.arts,
                    artists = s.artists,
                    tags = s.tags,
                    folders = s.folders,
                    draft = senseDraft,
                    kind = if (isNewDerived) ArtKind.Derived else ArtKind.Artwork,
                    sourceArt = derivedSource,
                    allowKindSwitch = !isNewDerived,
                    onBack = {
                        // 退回选图时不置空 mediaMode，保留制品意图
                        picked = null
                        showMedia = true
                    },
                    onSaved = {
                        picked = null
                        showMedia = false
                        mediaMode = null
                        // 用掉了就清掉：否则下次「＋」建档会莫名其妙带上一次的价格/画师
                        SenseHandoff.draft = null
                        tick++
                    },
                )
            }

            showMedia && s != null -> {
            MediaPickerScreen(
                // 库内自产目录不进选择器：镜像刚写完就是最新的 DATE_ADDED，
                // 不排除会整屏堆在最前面（用户实际踩到）
                excludeDirs = s.root?.let { Mirror.generatedDirs(it) } ?: emptyList(),
                onClose = {
                    showMedia = false
                    mediaMode = null
                    // 没选图就退出 = 放弃这次识别结果，别留着让下一次建档吃进去
                    SenseHandoff.draft = null
                },
                onDone = { list ->
                    val mode = mediaMode
                    if (mode is MediaMode.AddVersion && s.root != null) {
                        val target = mode.artId
                        showMedia = false
                        mediaMode = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                // 全库 version.blob（含本槽自己的）；查引用不查 blob 文件，删稿件不删 blob
                                val known = s.arts.flatMap { a -> a.versions.map { it.blob } }.toSet()
                                appendVersions(ctx, s.root, target, list, known)
                            }
                            toast(
                                ctx,
                                when {
                                    r.done > 0 && r.skipped > 0 -> "已加入 ${r.done} 个版本，${r.skipped} 张已在库"
                                    r.done > 0 -> "已加入 ${r.done} 个版本"
                                    r.skipped > 0 && r.failed == 0 -> "这 ${r.skipped} 张都已经在这个槽里了"
                                    else -> "没加进去：${r.firstError ?: "未知原因"}"
                                }
                            )
                            tick++
                        }
                    } else {
                        picked = list
                        showMedia = false
                    }
                },
            )
            // 与编辑页/版本页同一个坑：从归档页的详情点「＋加版本」时，归档页（独立叠加层）
            // 仍在组合中，它里面详情页的 BackHandler 更晚注册，会先被这次返回触发。
            // 在选择器之后接管一次，保证一次返回就回到详情。
            BackHandler {
                android.util.Log.d("GaoCangBack", "mediaPicker")
                showMedia = false
                mediaMode = null
            }
            }

            linkedArtId != null && s != null -> {
                val target = s.arts.firstOrNull { it.id == linkedArtId }
                if (target == null) {
                    LaunchedEffect(linkedArtId) { linkedArtId = null }
                } else {
                    DetailScreen(
                        arts = listOf(target),
                        lib = s.lib,
                        tax = s.tax,
                        names = names!!,
                        root = s.root,
                        folders = s.folders,
                        rangeLabel = "关联查看",
                        startIndex = 0,
                        onAddVersion = { id ->
                            mediaMode = MediaMode.AddVersion(id)
                            showMedia = true
                        },
                        onEdit = { a -> editId = a.id },
                        onDelete = { a -> deleteTargets = listOf(a) },
                        onChanged = { tick++ },
                        onManageVersions = { id -> versionOf = id },
                        onPageChanged = {},
                        onClose = {
                            val prev = linkedArtId
                            linkedArtId = null
                            clearScrollIfClosed(prev)
                        },
                        allArts = s.arts,
                        onOpenArt = ::openArtFromDetail,
                        onAddDerived = { id -> addDerivedAsk = id },
                        onUnlink = { id -> unlinkArt(id, s) },
                        onPickSource = { id -> enterPickSource(id) },
                        onOpenRole = ::openRoleFromDetail,
                        onOpenArtist = ::openArtistFromDetail,
                        onFilterTag = { tag -> filterFromDetail(Filter(tags = setOf(tag), sort = filter.sort)) },
                        onFilterWorld = { wid -> filterFromDetail(Filter(worldId = wid, sort = filter.sort)) },
                        savedScrolls = detailScrolls,
            onMoveVersionsToArt = ::enterMoveVersions,
                    )
                }
            }

            roleViewId != null && s != null -> RoleScreen(
                roleId = roleViewId!!,
                root = s.root,
                folders = s.folders,
                arts = s.arts,
                lib = s.lib,
                tax = s.tax,
                columns = columns,
                names = names!!,
                openId = roleOpenId,
                onOpenId = {
                    val prev = roleOpenId
                    roleOpenId = it
                    if (it == null) clearScrollIfClosed(prev)
                },
                onClose = {
                    val prev = roleOpenId
                    roleOpenId = null
                    roleViewId = null
                    clearScrollIfClosed(prev)
                },
                onChanged = { tick++ },
                onAddVersion = { id ->
                    mediaMode = MediaMode.AddVersion(id)
                    showMedia = true
                },
                onManageVersions = { id -> versionOf = id },
                onEdit = { a -> editId = a.id },
                onAssign = { assignTargets = it },
                onMerge = { mergeTargets = it },
                onDelete = { list -> deleteTargets = list },
                onExport = { exportBatch(it, s) },
                onOpenArt = ::openArtFromDetail,
                onAddDerived = { id -> addDerivedAsk = id },
                onUnlink = { id -> unlinkArt(id, s) },
                onPickSource = { id -> enterPickSource(id) },
                onOpenRole = ::openRoleFromDetail,
                onOpenArtist = ::openArtistFromDetail,
                onFilterTag = { tag -> filterFromDetail(Filter(tags = setOf(tag), sort = filter.sort)) },
                onFilterWorld = { wid -> filterFromDetail(Filter(worldId = wid, sort = filter.sort)) },
                savedScrolls = detailScrolls,
            onMoveVersionsToArt = ::enterMoveVersions,
                petLabel = petLabel,
            )

            worldView != null && s != null -> WorldScreen(
                worldId = worldView!!.worldId,
                root = s.root,
                arts = s.arts,
                tax = s.tax,
                onOpenRole = {
                    roleOpenId = null
                    roleViewId = it
                },
                onClose = { worldView = null },
                onChanged = { tick++ },
            )

            artistView != null && s != null -> ArtistScreen(
                // artistView 存的是画师实体 id：改名只动实体，这个 id 永远不变
                artistId = artistView!!,
                root = s.root,
                folders = s.folders,
                arts = s.arts,
                lib = s.lib,
                tax = s.tax,
                columns = columns,
                artists = s.artists,
                names = names!!,
                openId = artistOpenId,
                onOpenId = {
                    val prev = artistOpenId
                    artistOpenId = it
                    if (it == null) clearScrollIfClosed(prev)
                },
                onClose = {
                    val prev = artistOpenId
                    artistOpenId = null
                    artistView = null
                    clearScrollIfClosed(prev)
                },
                onChanged = { tick++ },
                onAddVersion = { id ->
                    mediaMode = MediaMode.AddVersion(id)
                    showMedia = true
                },
                onManageVersions = { id -> versionOf = id },
                onEdit = { a -> editId = a.id },
                onDelete = { list -> deleteTargets = list },
                onOpenArt = ::openArtFromDetail,
                onAddDerived = { id -> addDerivedAsk = id },
                onUnlink = { id -> unlinkArt(id, s) },
                onPickSource = { id -> enterPickSource(id) },
                onOpenRole = ::openRoleFromDetail,
                onOpenArtist = ::openArtistFromDetail,
                onFilterTag = { tag -> filterFromDetail(Filter(tags = setOf(tag), sort = filter.sort)) },
                onFilterWorld = { wid -> filterFromDetail(Filter(worldId = wid, sort = filter.sort)) },
                savedScrolls = detailScrolls,
            onMoveVersionsToArt = ::enterMoveVersions,
            )

            detailOpen && s != null -> DetailScreen(
                arts = if (di >= 0) shown else listOf(soloArt!!),
                lib = s.lib,
                tax = s.tax,
                names = names!!,
                root = s.root,
                folders = s.folders,
                filter = filter,
                rangeLabel = if (di >= 0) null else "关联查看",
                startIndex = if (di >= 0) di else 0,
                onAddVersion = { id ->
                    mediaMode = MediaMode.AddVersion(id)
                    showMedia = true
                },
                onEdit = { a -> editId = a.id },
                // 详情页的删除也走同一套：先二次确认，再进 .trash
                onDelete = { a -> deleteTargets = listOf(a) },
                onChanged = { tick++ },
                // 详情页已经改用「页内版本模式」（卡 13），不再走这条独立页入口。
                // 接线保留是为了 `versionOf` 分支还能当回退；等版本模式验过一轮，
                // 本行 + 上面四个 `onManageVersions` 转发 + DetailScreen 的同名参数 + VersionScreen 一起删。
                onManageVersions = { id -> versionOf = id },
                // 翻页要回写到 detailId：否则从版本管理/编辑页回来会跳回最初那张
                onPageChanged = { detailId = it },
                onClose = {
                    val prev = detailId
                    detailId = null
                    clearScrollIfClosed(prev)
                },
                allArts = s.arts,
                onOpenArt = ::openArtFromDetail,
                onAddDerived = { id -> addDerivedAsk = id },
                onUnlink = { id -> unlinkArt(id, s) },
                onPickSource = { id -> enterPickSource(id) },
                onOpenRole = ::openRoleFromDetail,
                onOpenArtist = ::openArtistFromDetail,
                onFilterTag = { tag -> filterFromDetail(Filter(tags = setOf(tag), sort = filter.sort)) },
                onFilterWorld = { wid -> filterFromDetail(Filter(worldId = wid, sort = filter.sort)) },
                savedScrolls = detailScrolls,
            onMoveVersionsToArt = ::enterMoveVersions,
            )

            // 搜索页放在详情之后：从搜索结果进详情时，详情盖在上面；
            // 从详情返回，searchOpen 仍为 true，自然回到搜索结果。
            searchOpen && s != null -> {
                SearchScreen(
                    query = filter.query,
                    onQuery = { filter = filter.copy(query = it) },
                    results = shown,
                    lib = s.lib,
                    onOpen = { i ->
                        shown.getOrNull(i)?.let { a ->
                            if (pickArt != null) handlePickArt(a.id, s) else detailId = a.id
                        }
                    },
                    onOpenFilter = { showFilter = true },
                    onClose = { closeSearch() },
                )
                BackHandler { closeSearch() }
            }
        }

        // 屏幕取字：识别完先问一句。很多图早就导入过了，默认走"新建"会让人卡在选图那一步
        // （已在库的图会被判成"跳过"），所以「套用到已有」是并列的一等选项。
        val ask = senseAsk
        if (ask != null) {
            AlertDialog(
                onDismissRequest = {
                    senseAsk = null
                    SenseHandoff.draft = null
                },
                containerColor = CardBg,
                shape = RoundedCornerShape(24.dp),
                title = { Text("识别到米画师稿件信息", style = T.titleSm, color = Paper) },
                text = {
                    Text(
                        buildString {
                            append("标题  ${ask.title ?: "—"}\n")
                            append("画师  ${ask.artist ?: "—"}\n")
                            append("价格  ${ask.price?.let { "¥$it" } ?: "—"}\n")
                            append("时间  ${ask.receivedAt ?: "—"}")
                        },
                        style = T.bodySm,
                        color = Paper70,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            senseAsk = null
                            SenseHandoff.draft = null
                            // 套用模式：把别的覆盖层都关掉，回稿库点一张稿
                            detailId = null
                            folderView = null
                            folderOpenId = null
                            mediaMode = null
                            picked = null
                            showMedia = false
                            tab = 0
                            senseApply = ask
                            pickArt = PickArt(PickArt.Mode.Apply)
                        },
                    ) { Text("套用到已有稿件", style = T.body, color = Accent) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            senseAsk = null
                            mediaMode = MediaMode.NewArtwork
                            showMedia = true
                        },
                    ) { Text("新建一稿", style = T.body, color = Accent) }
                },
            )
        }

        if (showDoctor && s != null) {
            val r = s.root
            if (r == null) {
                showDoctor = false
            } else {
                DoctorDialog(
                    root = r,
                    arts = s.arts,
                    artists = s.artists,
                    tags = s.tags,
                    folders = s.folders,
                    tax = s.tax,
                    onClose = { showDoctor = false },
                    onChanged = { tick++ },
                )
            }
        }

        if (s != null && s.demo && !seenOnboarding && !showDirPicker) {
            AlertDialog(
                onDismissRequest = {
                    seenOnboarding = true
                    prefs.seenPicker = true
                },
                containerColor = CardBg,
                shape = RoundedCornerShape(24.dp),
                title = { Text("欢迎使用稿仓", style = T.titleSm, color = Paper) },
                text = {
                    Text(
                        "所有图片与账目都以开放的本地文件格式保存在你指定的目录，零联网、无账号，方便备份与多端同步。\n\n" +
                            "你可以先逛逛内置演示库，或直接选择 / 新建自己的本地稿仓。",
                        style = T.bodySm,
                        color = OnMid,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        seenOnboarding = true
                        prefs.seenPicker = true
                        if (!s.hasPerm) {
                            toast(ctx, "请先授予所有文件访问权限，返回后再选目录")
                            openAllFilesSettings(ctx)
                        } else {
                            showDirPicker = true
                        }
                    }) {
                        Text("新建或选择稿仓", style = T.body, color = Accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        seenOnboarding = true
                        prefs.seenPicker = true
                    }) {
                        Text("先逛演示库", style = T.body, color = Muted)
                    }
                },
            )
        }

        if (showDirPicker && s != null) {
            val start = s.root?.parentFile ?: Environment.getExternalStorageDirectory()
            DirPickerDialog(
                start = start,
                onDismiss = { showDirPicker = false },
                onPick = { p ->
                    prefs.vaultPath = p.absolutePath
                    showDirPicker = false
                    tick++
                },
            )
        }

        // 「＋制品」两选一：从相册选新图 / 选库内已有稿件转为制品
        addDerivedAsk?.let { srcId ->
            AlertDialog(
                onDismissRequest = { addDerivedAsk = null },
                containerColor = CardBg,
                shape = RoundedCornerShape(24.dp),
                title = { Text("添加制品", style = T.titleSm, color = Paper) },
                text = {
                    Text(
                        "从相册导入新的制品照片，或把库里已有的一张普通稿转为这件原稿的制品。",
                        style = T.bodySm,
                        color = OnMid,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            addDerivedAsk = null
                            mediaMode = MediaMode.NewDerived(srcId)
                            showMedia = true
                        },
                    ) { Text("从相册选新图", style = T.body, color = Accent) }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            addDerivedAsk = null
                            enterAdoptDerived(srcId)
                        },
                    ) { Text("选库内已有稿", style = T.body, color = Accent) }
                },
            )
        }

        // 选库内已有稿转为制品的确认弹窗
        adoptConfirm?.let { (pickedId, srcId) ->
            val sc = screen
            val target = sc?.arts?.firstOrNull { it.id == pickedId }
            val src = sc?.arts?.firstOrNull { it.id == srcId }
            if (sc == null || target == null || src == null) {
                adoptConfirm = null
            } else {
                AlertDialog(
                    onDismissRequest = { adoptConfirm = null },
                    containerColor = CardBg,
                    shape = RoundedCornerShape(24.dp),
                    title = { Text("转为制品？", style = T.titleSm, color = Paper) },
                    text = {
                        Text(
                            buildString {
                                append("将「${target.title.ifBlank { "未命名" }}」转为「${src.title.ifBlank { "未命名" }}」的制品。\n")
                                append("会移入系统归档「制品」，世界观/角色/画师改为继承原稿，不再计入统计。")
                                target.priceTotal?.let { p ->
                                    append("\n\n该稿原记有 ¥$p，转换后价格将清空（可在操作历史中撤销恢复）。")
                                }
                            },
                            style = T.bodySm,
                            color = OnMid,
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                adoptConfirm = null
                                val r = sc.root
                                if (r == null) {
                                    toast(ctx, "演示库不能改")
                                    return@TextButton
                                }
                                val derivedFolderId = sc.folders.systemFolder(ArtKind.Derived)?.id
                                    ?: run { sc.folders.ensureSystem(); sc.folders.systemFolder(ArtKind.Derived)?.id }
                                if (derivedFolderId == null) {
                                    toast(ctx, "系统归档「制品」缺失，请重启一次")
                                    return@TextButton
                                }
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) {
                                        runCatching {
                                            OperationJournal.run(
                                                r,
                                                "convert",
                                                "转为制品：${target.title.ifBlank { "未命名" }}",
                                            ) {
                                                ArtworkCommands.convertToDerived(r, pickedId, srcId, derivedFolderId, sc.tax)
                                            }
                                        }.getOrElse { false }
                                    }
                                    if (ok) {
                                        pickArt = null
                                        searchOpen = false
                                        toast(ctx, "已转为制品")
                                        tick++
                                    } else {
                                        toast(ctx, "转换失败")
                                    }
                                }
                            },
                        ) { Text("转为制品", style = T.body, color = Accent) }
                    },
                    dismissButton = {
                        TextButton(onClick = { adoptConfirm = null }) {
                            Text("取消", style = T.body, color = Muted)
                        }
                    },
                )
            }
        }

        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // 118 是「列表底部留白」= 底栏顶(96) + 22 间隙。多选时**不用再加 96**：
        // 动作条和底栏共用同一块 BarShell（同位置同高），底栏在 selecting 时本就不画。
        val undoBottom = 118.dp + navBottom
        // 退场动画期间 undoBar 已是 null，直接读它 exit 就是硬切 —— 留住最后一次的值（提到外面才活得够久）
        var undoShown by remember { mutableStateOf<UndoState?>(null) }
        val curUndo = undoBar
        SideEffect { if (curUndo != null) undoShown = curUndo }
        AnimatedVisibility(
            visible = undoBar != null && s?.root != null && !s.demo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = undoBottom),
        ) {
            val u = undoBar ?: undoShown
            if (u != null) {
                // 倒计时（R6）：4 秒得看得见。条走完 = 来不及了，比凭空消失好理解。
                // key 用 opId：连做两次操作时进度会从头开始，而不是接着上一条走。
                //
                // ⚠ **不能用 Animatable / animateTo**（2026-11-21 真机踩过）：
                // Animatable 挂在 Compose 的动画时钟上，而系统的「动画时长缩放」被设成 0 时
                // （开发者选项关掉动画 / 部分机型的流畅模式），动画会被压成**立即完成** ——
                // 表现就是这条进度线一帧都不存在，只剩底下那条暗槽。真机上就是这么"没有线"的。
                // 换成帧时钟自己算：`withFrameMillis` 只读帧时间，与 durationScale 无关，
                // 而且它本身就是"每帧一次"的节奏，跑满 UndoWindowMs 就退出。
                var left by remember(u.opId) { mutableFloatStateOf(1f) }
                LaunchedEffect(u.opId) {
                    left = 1f
                    var t0 = 0L
                    while (true) {
                        val now = withFrameMillis { it }
                        if (t0 == 0L) t0 = now
                        val p = 1f - (now - t0).toFloat() / UndoWindowMs
                        left = if (p > 0f) p else 0f
                        if (p <= 0f) break
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(CHigh),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            u.label,
                            style = T.label,
                            color = Paper,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(Space.m))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentC)
                                .clickable {
                                    val r = s?.root ?: return@clickable
                                    val targetId = u.opId
                                    scope.launch {
                                        // 校验 + 撤销在同一把锁里（undoIfLatest）：分两步做的话，
                                        // 中间那几毫秒插进别的操作，就会「撤了另一条，用户以为在重做」
                                        val rec = withContext(Dispatchers.IO) {
                                            OperationJournal.undoIfLatest(r, targetId)
                                        }
                                        undoBar = null
                                        if (rec == null) {
                                            toast(ctx, "这条操作已被撤销，不能再撤")
                                        } else {
                                            toast(ctx, "已撤销：${rec.label}")
                                            pendingUndoSync = true
                                            tick++
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(
                                "撤销",
                                style = T.label,
                                color = Accent,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    // 进度条读 left.value 的位置在 draw 阶段（drawBehind 的 lambda 里），
                    // 所以每帧只重绘这一条 2dp，不会带着整块 Row 重组。
                    //
                    // 槽 + 实心（2026-11-21 修）：一开始只画了「琥珀→透明」的渐变条，
                    // 用户真机上没看出这是计时器 —— 渐变右端本来就淡，整条又只有 2dp，
                    // 读起来像卡片底边的一道反光，不像『还剩多少时间』。
                    // 现在先铺一条暗槽、再画实心琥珀，长度本身就是读数，不再依赖观感。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .drawBehind {
                                drawRect(Ink.copy(alpha = 0.45f))
                                val w = size.width * left.coerceIn(0f, 1f)
                                if (w > 0f) drawRect(Accent, size = Size(w, size.height))
                            },
                    )
                }
            }
        }

        // 批量写入的遮罩压在最上层：它出现时下面的网格、动作条、sheet 都不该能被点到
        BusyOverlay(
            busy,
            // 只有全量同步能取消：其他写入是秒级、取消语义不清
            onCancel = if (mirrorBusy) ({ mirrorStop.set(true) }) else null,
        )
    }
    // 删除确认框**不能**放在上面那个 when 里（2026-11-21 用户报）。
    //
    // when 只渲染一个分支：`deleteTargets != null` 一旦命中，底下的详情页 / 归档页就被整个
    // 替换成「空 tab」—— 表现是「删除弹窗一出来，背景已经退回稿库主界面」。
    // DeleteConfirmDialog 本身是 AlertDialog（独立窗口），放在 when 外面就能浮在当前页面之上，
    // 背景保持不动；删除确认后 tick++ 重载，该稿不存在了，detailId 才会被清掉、详情才关闭。
    deleteTargets?.let { tg ->
        val root = screen?.root
        DeleteConfirmDialog(
            count = tg.size,
            sample = tg.take(2).map { it.title.ifBlank { "未命名" } },
            onCancel = { deleteTargets = null },
            onConfirm = {
                deleteTargets = null
                if (root == null) {
                    toast(ctx, "演示库不能改")
                } else {
                    scope.launch {
                        // 删除不记操作历史：它进回收站，撤销的入口就在「我的 → 回收站」
                        val total = tg.size
                        busy = Busy("正在删除", 0, total)
                        val res = try {
                            deleteArts(root, tg.map { it.id }) { done, t ->
                                if (done % 10 == 0 || done == t) {
                                    busy = Busy("正在删除", done, t)
                                }
                            }
                        } finally {
                            busy = null
                        }
                        toast(
                            ctx,
                            if (res.ok) "已删除 ${res.moved} 件（进 .trash，可找回）"
                            else "删除失败：${res.error ?: "未知原因"}",
                        )
                        tick++
                    }
                }
            },
        )
    }

}

@Composable
private fun MirrorToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) AccentC else CHigh)
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = T.micro,
            color = if (checked) Accent else OnMid,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (checked) "已勾选" else "不导",
            style = T.micro,
            color = if (checked) Accent else Muted,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
        )
    }
}
