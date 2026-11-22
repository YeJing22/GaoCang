package com.yejing.artvault.ui.component

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.canhub.cropper.CropImageView
import com.yejing.artvault.Space
import com.yejing.artvault.Accent
import com.yejing.artvault.CHigh
import com.yejing.artvault.CardBg
import com.yejing.artvault.Ink
import com.yejing.artvault.MediaItem
import com.yejing.artvault.Muted
import com.yejing.artvault.Paper
import com.yejing.artvault.T
import com.yejing.artvault.queryMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 头像只导入一张。与建档选图一样支持按系统相册文件夹过滤。 */
@Composable
fun AvatarSinglePicker(
    excludeDirs: List<File>,
    onCancel: () -> Unit,
    onPick: (Uri) -> Unit,
) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<MediaItem>?>(null) }
    var bucket by remember { mutableStateOf<String?>(null) }
    var folderMenu by remember { mutableStateOf(false) }

    LaunchedEffect(excludeDirs) {
        list = withContext(Dispatchers.IO) {
            queryMedia(ctx, excludeDirs.map { it.absolutePath })
        }
    }

    val buckets = remember(list) {
        list.orEmpty().groupingBy { it.bucket }.eachCount().entries.sortedByDescending { it.value }
    }
    val shown = remember(list, bucket) {
        list.orEmpty().let { all ->
            if (bucket == null) all else all.filter { it.bucket == bucket }
        }
    }

    Column(Modifier.fillMaxSize().background(Ink).navigationBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(GI.Close, onClick = onCancel, background = Color.Transparent)
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Text("选一张头像", style = T.titleSm, color = Paper)
                Text(
                    when {
                        list == null -> "正在读取媒体库…"
                        list!!.isEmpty() -> "读不到图片，检查「所有文件访问」权限"
                        else -> "${shown.size} / ${list!!.size} 张 · 点一张进入裁剪"
                    },
                    style = T.micro,
                    color = Muted,
                )
            }
        }

        if (buckets.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(CardBg)
                        .clickable { folderMenu = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        bucket ?: "全部文件夹",
                        style = T.label,
                        color = Paper,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 250.dp),
                    )
                    Spacer(Modifier.width(Space.s))
                    Text("▾", style = T.label, color = Muted)
                }
                DropdownMenu(
                    expanded = folderMenu,
                    onDismissRequest = { folderMenu = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "全部文件夹 (${list?.size ?: 0})",
                                style = T.bodySm,
                                color = if (bucket == null) Accent else Paper,
                            )
                        },
                        onClick = {
                            bucket = null
                            folderMenu = false
                        },
                    )
                    buckets.forEach { entry ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${entry.key} (${entry.value})",
                                    style = T.bodySm,
                                    color = if (bucket == entry.key) Accent else Paper,
                                )
                            },
                            onClick = {
                                bucket = entry.key
                                folderMenu = false
                            },
                        )
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list == null) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(26.dp),
                    color = Accent,
                )
            } else if (shown.isEmpty()) {
                Text(
                    "这个文件夹里没有图片",
                    style = T.bodySm,
                    color = Muted,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                    verticalArrangement = Arrangement.spacedBy(Space.xxs),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.uri.toString() }) { media ->
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(media.uri).size(320).build(),
                            contentDescription = media.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(CardBg)
                                .clickable { onPick(media.uri) },
                        )
                    }
                }
            }
        }
    }
}

private const val AVATAR_SIZE = 256

/**
 * 使用 CanHub CropImageView，不再自己维护手势矩阵。
 * 库负责拖动、以双指中心缩放、旋转、EXIF 与边界；这里只负责 1:1 输出和稿仓界面。
 */
@Composable
fun AvatarCropScreen(
    uri: Uri,
    onCancel: () -> Unit,
    onSaved: (File) -> Unit,
    target: File,
) {
    val scope = rememberCoroutineScope()
    var cropView by remember(uri) { mutableStateOf<CropImageView?>(null) }
    var ready by remember(uri) { mutableStateOf(false) }
    var busy by remember(uri) { mutableStateOf(false) }
    var error by remember(uri) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(GI.Close, onClick = onCancel, background = Color.Transparent)
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Text("裁剪头像", style = T.titleSm, color = Paper)
                Text(
                    "拖动图片 · 拖角调整取景框",
                    style = T.micro,
                    color = Muted,
                )
            }
            Text(
                "重置",
                style = T.label,
                color = Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = ready && !busy) {
                        cropView?.resetCropRect()
                        cropView?.rotation = 0f
                    }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Ink),
        ) {
            AndroidView(
                factory = { context ->
                    CropImageView(context).apply {
                        setBackgroundColor(android.graphics.Color.rgb(12, 12, 14))
                        cropShape = CropImageView.CropShape.RECTANGLE
                        guidelines = CropImageView.Guidelines.ON
                        isAutoZoomEnabled = true
                        // 明确关闭多指：CropImageView 的多指会同时驱动图片缩放与取景框拉拽，
                        // 实机上会把 1:1 框瞬间拉成细长竖框。这里只保留单指拖动图片、拖角改框。
                        setMultiTouchEnabled(false)
                        isShowProgressBar = true
                        setFixedAspectRatio(true)
                        setAspectRatio(1, 1)
                        // 不设 maxCropResultSize：取景必须基于原图自由进行，
                        // 256×256 只是最终头像规格，不能提前限制可选区域。
                        setOnSetImageUriCompleteListener { _, _, loadError ->
                            ready = loadError == null
                            error = loadError?.message
                        }
                        setImageUriAsync(uri)
                        cropView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (!ready && error == null) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(26.dp),
                    color = Accent,
                )
            }
            error?.let {
                Text(
                    it,
                    style = T.body,
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "旋转 90°",
                style = T.body,
                color = Paper,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CHigh)
                    .clickable(enabled = ready && !busy) { cropView?.rotateImage(90) }
                    .padding(vertical = 14.dp),
            )
            PrimaryButton(
                text = if (busy) "处理中…" else "使用这张",
                enabled = ready && !busy,
                modifier = Modifier.weight(1f),
                onClick = {
                    val view = cropView ?: return@PrimaryButton
                    busy = true
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching {
                                // 先取原尺寸裁剪结果，最后再缩：取景大小由原图决定，不被 256 限制
                                val cropped = view.getCroppedImage()
                                    ?: error("没有生成裁剪图")
                                val bitmap = if (cropped.width == AVATAR_SIZE && cropped.height == AVATAR_SIZE) {
                                    cropped
                                } else {
                                    Bitmap.createScaledBitmap(cropped, AVATAR_SIZE, AVATAR_SIZE, true)
                                        .also { if (it !== cropped) cropped.recycle() }
                                }
                                target.parentFile?.mkdirs()
                                // 先写临时文件，成功后覆盖固定文件名；不会积累历史头像。
                                val temp = File(target.parentFile, "${target.name}.tmp")
                                temp.outputStream().use {
                                    bitmap.compress(Bitmap.CompressFormat.WEBP, 90, it)
                                }
                                if (bitmap !== cropped) bitmap.recycle()
                                if (target.exists() && !target.delete()) error("旧头像无法覆盖")
                                if (!temp.renameTo(target)) {
                                    temp.copyTo(target, overwrite = true)
                                    temp.delete()
                                }
                                true
                            }.getOrElse {
                                it.printStackTrace()
                                false
                            }
                        }
                        busy = false
                        if (saved) {
                            onSaved(target)
                        } else {
                            error = "保存失败，检查库目录是否可写"
                        }
                    }
                },
            )
        }
    }
}
