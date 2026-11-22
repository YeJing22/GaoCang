package com.yejing.artvault

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 批量写入的「进行中」状态。**状态归 Root 持有**，不放在 sheet 里——
 * sheet 一关就离开组合，进度会跟着消失，而写入还在继续。
 *
 * [total] 为 0 表示总数未知（转圈）；已知就画确定性进度条。
 */
data class Busy(val title: String, val done: Int = 0, val total: Int = 0)

/**
 * 批量写入遮罩：给「点完确认到界面更新」之间那段空白一个明确的交代。
 *
 * 为什么必须做：`ArtworkCommands.assign` 是逐件「读 → 改 → 写盘」的串行循环，
 * 300 件是实打实的一秒级。之前弹窗一点就关、界面毫无反应，用户会以为没生效再点一次；
 * 赋值是幂等的无所谓，**合并 / 追加版本 / 删除都不是**——这是真的误操作风险。
 *
 * 几处刻意的选择：
 * - **延迟 400ms 才露面**：几十件的小批量在 400ms 内就写完了，不该闪一下遮罩。
 * - **遮罩吞掉返回键**：返回键关掉遮罩会让人以为操作中止了，其实还在写。
 * - **不做取消**：取消语义不清（已写的那些回滚还是保留？），真要做得靠撤销栈兜底。
 */
@Composable
fun BusyOverlay(
    state: Busy?,
    /** 非空时显示「取消」：目前只有全量同步给这个（其他写操作是秒级，取消语义不清） */
    onCancel: (() -> Unit)? = null,
) {
    // 忙碌时才注册，而不是常驻注册一个 enabled=false 的。
    //
    // 根因（实测踩过）：`BackHandler` 内部的 `DisposableEffect` **key 不含 enabled**。
    // 常驻注册的话，callback 会一直停在「首次组合」时的位置，之后 `enabled` 只是被 SideEffect
    // 改一下 —— 而 AndroidX 的 OnBackPressedDispatcher 是**后注册的先调用**。遮罩常驻时，
    // 页面自己的 `BackHandler(enabled = selecting)`（进多选时才启用）就把它压过去了，
    // 表现为「遮罩下按返回，直接退出多选」。
    //
    // 放进 if：每次从「不忙」变「忙」都是一个新进入组合的节点，DisposableEffect 重新执行，
    // callback 被**追加到队尾**，这才真正压过页内那些 handler。
    if (state != null) BackHandler { }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(state != null) {
        if (state == null) {
            shown = false
        } else {
            delay(400)
            shown = true
        }
    }

    val cur = state ?: return
    if (!shown) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            // 铺满整屏并吞掉触摸：底下就是网格，点穿会误触到别的稿件
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(268.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(CHigh)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(cur.title, style = T.titleSm, color = Paper)
            Spacer(Modifier.height(14.dp))
            if (cur.total > 0) {
                LinearProgressIndicator(
                    progress = { (cur.done.toFloat() / cur.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = CardBg,
                )
                Spacer(Modifier.height(10.dp))
                Text("${cur.done} / ${cur.total}", style = T.numLg, color = Muted)
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Accent,
                    trackColor = CardBg,
                )
            }
            // 取消放最后：不确定进度（转圈）时也要能取消，所以不能塞在 total > 0 的分支里
            if (onCancel != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "取消",
                    style = T.bodySm,
                    color = Warn,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(CardBg)
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
