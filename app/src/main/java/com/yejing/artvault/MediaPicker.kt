package com.yejing.artvault

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.PrimaryButton
import com.yejing.artvault.ui.component.RoundIconButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaItem(
    val uri: Uri,
    val name: String,
    val bucket: String,
    val dateMs: Long,
    val w: Int,
    val h: Int,
    val size: Long,
    val mime: String,
    /** 拍摄时间（毫秒）。很多 PNG 没有，为 0 时回落 DATE_ADDED */
    val takenMs: Long = 0,
)

/**
 * 直接读系统媒体库。不调 ACTION_GET_CONTENT —— 那个会交给华为文件管理器，
 * 多选体验和分组都不受控。自己查 MediaStore 就能做文件夹筛选和预览。
 */
suspend fun queryMedia(
    ctx: Context,
    /** 这些目录里的图不展示：库里自己产出的东西，不该被当成「手机里的新图」拿来导 （绝对路径，不带尾斜杠） */
    exclude: List<String> = emptyList(),
): List<MediaItem> = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext emptyList()

    val proj = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.DATE_TAKEN,
    )
    val out = ArrayList<MediaItem>(2048)
    runCatching {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iW = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iMime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val iData = c.getColumnIndex(MediaStore.Images.Media.DATA)
            val iTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                // 先排除库内自产目录：镜像文件是刚扫进来的，DATE_ADDED 最新，
                // 不排掉就会整屏堆在最前面（QQ/微信自己的选择器也都不展示自己的输出目录）。
                if (exclude.isNotEmpty() && iData >= 0) {
                    val path = c.getString(iData)
                    if (path != null && exclude.any { path.startsWith("$it/") }) continue
                }
                val id = c.getLong(iId)
                out += MediaItem(
                    uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    name = c.getString(iName) ?: "image",
                    bucket = c.getString(iBucket) ?: "其他",
                    dateMs = (c.getLong(iDate) ?: 0L) * 1000,
                    w = c.getInt(iW),
                    h = c.getInt(iH),
                    size = c.getLong(iSize),
                    mime = c.getString(iMime) ?: "image/*",
                    takenMs = if (iTaken >= 0) c.getLong(iTaken) else 0L,
                )
            }
        }
    }
    // 按「拍摄时间」排，不按「进媒体库的时间」。
    // 用 DATE_ADDED 的话，任何被重新扫描过的文件都会莫名跳到最前（用户踩过）。
    // 大量 PNG 没有 DATE_TAKEN，那就回落 DATE_ADDED，至少顺序是稳定的。
    out.sortWith(compareByDescending { if (it.takenMs > 0) it.takenMs else it.dateMs })
    out
}

/**
 * 把系统选择器返回的 Uri 变成 [MediaItem]。
 *
 * 系统给的是 content:// Uri，没有名字/尺寸这些，得回查一次。
 * 注意：ACTION_GET_CONTENT 的授权是临时的，只在本次 Activity 会话内有效 ——
 * 所以选完必须立刻进建档流程（读 Uri → 写 blob），不能把它存起来过一会儿再用。
 */
private fun toMediaItem(ctx: android.content.Context, uri: Uri): MediaItem? = runCatching {
    val proj = arrayOf(
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.DATE_ADDED,
    )
    ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
        if (!c.moveToFirst()) null
        else MediaItem(
            uri = uri,
            name = c.getString(0) ?: "image",
            // 系统选择器不告诉我们来自哪个相册，给个固定名，别让它混进真实分组
            bucket = "系统选择",
            dateMs = c.getLong(5) * 1000,
            w = c.getInt(2),
            h = c.getInt(3),
            size = c.getLong(1),
            mime = c.getString(4) ?: "image/*",
        )
    }
}.getOrNull()

@Composable
fun MediaPickerScreen(
    onClose: () -> Unit,
    onDone: (List<MediaItem>) -> Unit,
    /** 库内自产目录，不展示 */
    excludeDirs: List<java.io.File> = emptyList(),
) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<MediaItem>?>(null) }
    var bucket by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<List<String>>(emptyList()) }

    // 系统选择器：交给系统/图库 App 去选，选完直接进建档流程。
    // 用 ACTION_GET_CONTENT（GetMultipleContents 内部就是它）——多选、系统原生界面。
    // 已知风险：华为上它有时会弹「文件管理」而不是图库（见文件头那条注释）。
    // 自用场景先按标准做法接，真弹错了再考虑指定包名。
    val sysPick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            val items = uris.mapNotNull { toMediaItem(ctx, it) }
            if (items.isNotEmpty()) onDone(items)
        }
    }

    LaunchedEffect(excludeDirs) {
        all = queryMedia(ctx, excludeDirs.map { it.absolutePath })
    }

    val list = all
    val buckets = remember(list) {
        list.orEmpty().groupingBy { it.bucket }.eachCount().entries.sortedByDescending { it.value }
    }
    val shown = remember(list, bucket) {
        if (bucket == null) list.orEmpty() else list.orEmpty().filter { it.bucket == bucket }
    }
    val pickedSet = remember(picked) { picked.toSet() }

    Column(Modifier.fillMaxSize().background(Ink)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(GI.Close, onClick = onClose, background = Color.Transparent)
            Spacer(Modifier.width(Space.xxs))
            Column(Modifier.weight(1f)) {
                Text("从手机相册选", style = T.titleSm, color = Paper)
                Text(
                    when {
                        list == null -> "正在读取媒体库…"
                        list.isEmpty() -> "读不到图片，检查「所有文件访问」权限"
                        else -> "${list.size} 张 · 已选 ${picked.size}"
                    },
                    style = T.micro,
                    color = if (list?.isEmpty() == true) Warn else Muted,
                )
            }
            ChipPill("系统图库") { sysPick.launch("image/*") }
            if (picked.isNotEmpty()) {
                Spacer(Modifier.width(Space.xs))
                ChipPill("清空 ${picked.size}", onClick = { picked = emptyList() })
            }
        }

        if (buckets.isNotEmpty()) {
            // 文件夹用下拉而不是一排胶囊：手机上的相册动辄几十个，
            // 横排要一直滑，还看不出选的是哪个（胶囊被滑出屏幕就没了）。
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(CardBg)
                        .clickable { menu = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "📁 " + (bucket ?: "全部文件夹"),
                        style = T.label,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
                    Spacer(Modifier.width(Space.xs))
                    Text("▾", style = T.label, color = Muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "全部 ${list?.size ?: 0}",
                                style = T.bodySm,
                                color = if (bucket == null) Accent else Paper,
                            )
                        },
                        onClick = {
                            bucket = null
                            menu = false
                        },
                    )
                    buckets.forEach { e ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${e.key} (${e.value})",
                                    style = T.bodySm,
                                    color = if (bucket == e.key) Accent else Paper,
                                )
                            },
                            onClick = {
                                bucket = e.key
                                menu = false
                            },
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(28.dp),
                    color = Accent,
                    strokeWidth = 2.dp,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(shown, key = { _, m -> m.uri.toString() }) { _, m ->
                        val key = m.uri.toString()
                        val order = picked.indexOf(key)
                        val on = order >= 0
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBg)
                                .then(
                                    if (on) Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                                .clickable {
                                    picked = if (on) picked - key else picked + key
                                },
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(m.uri).size(320).build(),
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (on) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(Accent),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${order + 1}", fontSize = 9.5.sp, color = OnAccent, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (picked.isNotEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.95f), Ink)))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val ordered = picked.mapNotNull { k -> shown.firstOrNull { it.uri.toString() == k } }
                    .ifEmpty { picked.mapNotNull { k -> list.orEmpty().firstOrNull { it.uri.toString() == k } } }
                PrimaryButton("下一步 · 建档 ${picked.size} 张", onClick = { onDone(ordered) })
            }
        } else {
            Spacer(Modifier.height(14.dp).navigationBarsPadding())
        }
    }
}

