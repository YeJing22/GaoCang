package com.yejing.artvault.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yejing.artvault.Art
import com.yejing.artvault.CardBg
import com.yejing.artvault.Ink
import com.yejing.artvault.Library
import com.yejing.artvault.Muted
import com.yejing.artvault.Paper
import com.yejing.artvault.Space
import com.yejing.artvault.T
import com.yejing.artvault.Warn
import com.yejing.artvault.Accent
import com.yejing.artvault.ui.component.ArtCard
import com.yejing.artvault.ui.component.EmptyState

/**
 * 全屏搜索态（R3c2）。
 *
 * 为什么独立成一页，而不是在筛选面板里放个输入框：
 * 点搜索框时人是**想打字**的——键盘要立刻出来、光标要已经在闪。
 * 而筛选面板是个「勾条件」的地方，从它那儿进搜索，等于每次都先看一堆和自己无关的选项。
 *
 * 只负责关键词；价格、日期、世界观这些仍然在筛选面板里（[onOpenFilter] 进）。
 * 这样这一页就能做到「点开即输入」。
 */
@Composable
fun SearchScreen(
    query: String,
    onQuery: (String) -> Unit,
    results: List<Art>,
    lib: Library,
    onOpen: (Int) -> Unit,
    onOpenFilter: () -> Unit,
    onClose: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    // 进来就聚焦：键盘弹起 + 光标闪烁。这一下决定了它「像不像搜索」。
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Column(Modifier.fillMaxSize().background(Ink).imePadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fieldModifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(CardBg)
            Row(
                fieldModifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索 标题 / 画师 / 角色 / 标签", style = T.bodySm, color = Muted, maxLines = 1)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = onQuery,
                        singleLine = true,
                        textStyle = T.bodySm.copy(color = Paper),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(Space.xs))
                    Text(
                        "×",
                        style = T.titleSm,
                        color = Muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onQuery("") }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(Space.s))
            Text(
                "筛选",
                style = T.label,
                color = Muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onOpenFilter)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(Space.xxs))
            Text(
                "取消",
                style = T.label,
                color = Paper,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    query.isBlank() -> "输入关键词开始搜索"
                    results.isEmpty() -> "没有匹配的稿件"
                    else -> "${results.size} 件符合条件"
                },
                style = T.micro,
                color = if (query.isNotBlank() && results.isEmpty()) Warn else Muted,
            )
        }

        if (results.isEmpty()) {
            EmptyState(
                title = if (query.isBlank()) "搜点什么" else "没找到",
                sub = if (query.isBlank()) "标题、画师、角色、标签、平台、备注都能搜"
                else "换个词试试，或者去筛选里放宽条件",
                action = null,
                onAction = {},
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(results, key = { it.id }) { a ->
                    ArtCard(
                        art = a,
                        lib = lib,
                        onClick = { onOpen(results.indexOfFirst { it.id == a.id }) },
                        onLongClick = null,
                    )
                }
            }
        }
    }
}
