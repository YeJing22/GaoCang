package com.yejing.artvault.ui.component

import com.yejing.artvault.CHigh
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yejing.artvault.Space
import com.yejing.artvault.Accent
import com.yejing.artvault.Dates
import com.yejing.artvault.Muted
import com.yejing.artvault.OnAccent
import com.yejing.artvault.Paper
import com.yejing.artvault.CardBg
import com.yejing.artvault.T
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 收稿时间的日期 + 时钟选择。两步：先日历选日期，再时钟选时刻。
 *
 * 为什么不直接用 DatePickerDialog：两个 Material 对话框串起来，
 * 中间那一步（选完日期后弹时钟）在很多 ROM 上会闪一下、且样式跟我们自己的面板不搭。
 * 这里一个 Dialog 内部切步骤，返回键走系统的正常关闭。
 *
 * 存储格式见 [Dates]：`yyyy-MM-dd HH:mm:ss`；也可以选「只记日期」，存 `yyyy-MM-dd`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(
    initial: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val parsed = remember(initial) { Dates.parse(initial) }
    var step by remember { mutableIntStateOf(0) }

    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = (parsed?.toLocalDate() ?: LocalDate.now())
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val nowTime = remember { LocalTime.now() }
    val timeState = rememberTimePickerState(
        initialHour = parsed?.hour ?: nowTime.hour,
        initialMinute = parsed?.minute ?: nowTime.minute,
        is24Hour = true,
    )
    // 已有秒就沿用，避免每次打开都归零（同一天里两件稿能靠秒区分先后）
    val seconds = remember(initial) {
        val t = initial?.trim().orEmpty()
        if (t.length >= 19) t.substring(17, 19).toIntOrNull() ?: 0 else 0
    }

    val pickedDate: LocalDate = dateState.selectedDateMillis
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        ?: LocalDate.now()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(CardBg)
                    .padding(top = 16.dp, bottom = 8.dp),
            ) {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text("收稿时间", style = T.titleSm, color = Paper)
                    Spacer(Modifier.height(Space.xxs))
                    Text(
                        if (step == 0) "先选日期" else "再选时刻 · ${pickedDate}",
                        style = T.micro,
                        color = Muted,
                    )
                }
                Spacer(Modifier.height(Space.s))

                if (step == 0) {
                    DatePicker(
                        state = dateState,
                        showModeToggle = false,
                        title = null,
                        headline = null,
                        colors = DatePickerDefaults.colors(
                            containerColor = CardBg,
                            selectedDayContainerColor = Accent,
                            selectedDayContentColor = OnAccent,
                            todayDateBorderColor = Accent,
                            dayContentColor = Paper,
                            weekdayContentColor = Muted,
                            subheadContentColor = Muted,
                            yearContentColor = Paper,
                            currentYearContentColor = Paper,
                            selectedYearContentColor = OnAccent,
                            selectedYearContainerColor = Accent,
                        ),
                        modifier = Modifier.height(400.dp),
                    )
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TimePicker(
                            state = timeState,
                            colors = TimePickerDefaults.colors(
                                clockDialColor = com.yejing.artvault.CHigh,
                                clockDialSelectedContentColor = OnAccent,
                                clockDialUnselectedContentColor = Paper,
                                selectorColor = Accent,
                                containerColor = CardBg,
                                timeSelectorSelectedContainerColor = Accent,
                                timeSelectorSelectedContentColor = OnAccent,
                                timeSelectorUnselectedContainerColor = com.yejing.artvault.CHigh,
                                timeSelectorUnselectedContentColor = Paper,
                            ),
                            modifier = Modifier.height(320.dp),
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", style = T.body, color = Muted)
                    }
                    if (step == 1) {
                        TextButton(onClick = {
                            // 只记日期：有些老稿子确实只有日子，硬塞一个时刻是假精度
                            onConfirm(pickedDate.toString())
                        }) {
                            Text("只记日期", style = T.label, color = Muted)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (step == 0) {
                        TextButton(onClick = { step = 1 }) {
                            Text("下一步", style = T.body, color = Accent, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        TextButton(onClick = { step = 0 }) {
                            Text("上一步", style = T.label, color = Muted)
                        }
                        TextButton(onClick = {
                            onConfirm(
                                Dates.of(pickedDate, timeState.hour, timeState.minute, seconds)
                            )
                        }) {
                            Text("确定", style = T.body, color = Accent, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}
