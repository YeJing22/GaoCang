package com.yejing.artvault

import android.icu.text.AlphabeticIndex
import android.icu.util.ULocale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yejing.artvault.ui.component.ChipPill
import com.yejing.artvault.ui.component.GI
import com.yejing.artvault.ui.component.GIcon
import java.text.Collator
import java.util.Locale

/* ────────────────────────────────────────────────────────────────
 * 表单里的「已有值」候选。
 *
 * 目的只有一个：**别让同一个画师 / 同一个标签被写成两种字**。
 * 手打「忍 」和「忍」、手打「半身」和「半身像」都会让统计和筛选裂成两半，
 * 而这类错数据事后极难发现，所以一律优先引导从已存在的值里挑。
 * ──────────────────────────────────────────────────────────────── */

/** 标签串 ⇄ 列表。全 App 只有这一份解析口径（保存时也用它） */
fun tagList(s: String): List<String> =
    s.split(',', '，', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/** 点候选标签：已有就删掉，没有就追加，结果仍写回空格分隔的字符串 */
fun toggleTag(s: String, tag: String): String {
    val cur = tagList(s)
    return (if (tag in cur) cur - tag else cur + tag).joinToString(" ")
}

/** 角色分组的小标题（世界观名）：比 Label 轻，不抢视觉 */
@Composable
internal fun GroupHint(text: String) {
    Spacer(Modifier.height(7.dp))
    Text(text, style = T.micro, color = Muted, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(5.dp))
}

private val alphaIndex by lazy {
    AlphabeticIndex<Unit>(ULocale.SIMPLIFIED_CHINESE).buildImmutableIndex()
}

/** 取名称的首字母分组键：英文 A–Z、中文按拼音首字母 A–Z，其余归 `#` */
internal fun initialLetterOf(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "#"
    val c = t[0]
    if (c in 'a'..'z' || c in 'A'..'Z') return c.uppercaseChar().toString()
    val label = alphaIndex.getBucket(alphaIndex.getBucketIndex(t)).label
    return if (label.length == 1 && label[0] in 'A'..'Z') label else "#"
}

/** 按首字母（A–Z 在前，`#` 在末）分组，组内按中英文拼音 / 字母升序排列 */
internal fun <T> groupByInitial(items: List<T>, nameOf: (T) -> String): List<Pair<String, List<T>>> {
    if (items.isEmpty()) return emptyList()
    val collator = Collator.getInstance(Locale.CHINA)
    val sorted = items.sortedWith { a, b -> collator.compare(nameOf(a), nameOf(b)) }
    val buckets = linkedMapOf<String, MutableList<T>>()
    for (item in sorted) {
        val key = initialLetterOf(nameOf(item))
        buckets.getOrPut(key) { ArrayList() }.add(item)
    }
    return buckets.entries
        .sortedWith(compareBy<Map.Entry<String, MutableList<T>>> { it.key == "#" }.thenBy { it.key })
        .map { it.key to it.value }
}

/** 画师字段：点开二级弹窗，搜索框同时就是新建框 */
@Composable
internal fun ArtistField(
    value: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            value.ifBlank { placeholder },
            style = T.bodySm,
            color = if (value.isBlank()) Muted else Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.xxs))
        GIcon(GI.ChevronDown, Muted, size = 14.dp, strokeWidth = 1.8f)
    }
}

/**
 * 画师二级选择弹窗：**一个框干两件事**。
 * - 搜到的：按字母 / 拼音升序排列，顶部搜索过滤（同时匹配别名），点一下直接填入；
 * - 搜不到的：搜索框下方会出现「使用新画师「xxx」」，点它就是新建，
 *   保存稿件时由 `ArtistBook.ensure()` 建档（内联创建，不用先去分类页）。
 *
 * 以前这里还有「选择已有 / 自己填新的」两个页签，同一个框要切两次才能用，2026-11-22 删掉。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArtistPickerDialog(
    pool: List<Pair<String, Int>>,
    artists: ArtistBook,
    current: String,
    clearLabel: String = "清空",
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    /** 选「自制」（自己画的，没有画师）。不传就不显示这一项 */
    onSelfMade: (() -> Unit)? = null,
) {
    val allSorted = remember(pool, artists.all) {
        val counts = pool.toMap()
        val aliasesByName = artists.all.associate { it.name to it.aliases }
        val names = linkedSetOf<String>()
        artists.all.forEach { names.add(it.name) }
        pool.forEach { names.add(it.first) }
        val collator = Collator.getInstance(Locale.CHINA)
        names.map { name -> Triple(name, counts[name] ?: 0, aliasesByName[name].orEmpty()) }
            .sortedWith { a, b -> collator.compare(a.first, b.first) }
    }

    // 只有一个输入框：搜到了就点那一行，没搜到就点「使用新画师「xxx」」——新建和搜索是同一个动作。
    // 以前还有个「自己填新的」模式页签，同一个框要切两次才能用，纯属多余（2026-11-22 删）。
    var query by remember { mutableStateOf("") }

    val q = query.trim()
    val filtered = remember(allSorted, q) {
        if (q.isEmpty()) {
            allSorted
        } else {
            allSorted.filter { (name, _, aliases) ->
                name.contains(q, ignoreCase = true) ||
                    aliases.any { it.contains(q, ignoreCase = true) }
            }
        }
    }
    val grouped = remember(filtered) { groupByInitial(filtered) { it.first } }
    val hasExactInPool = remember(allSorted, q) {
        q.isNotEmpty() && allSorted.any { it.first.equals(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text("画师", style = T.titleSm, color = Paper)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 顶部搜索框
                Row(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CHigh)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "搜索已有画师（共 ${allSorted.size} 位）",
                                    style = T.bodySm,
                                    color = Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = T.bodySm.copy(color = Paper),
                                cursorBrush = SolidColor(Accent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (query.isNotEmpty()) {
                            Spacer(Modifier.width(Space.xs))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { query = "" }
                                    .padding(4.dp),
                            ) {
                                GIcon(GI.Close, Muted, size = 14.dp, strokeWidth = 2f)
                            }
                        }
                    }

                    Spacer(Modifier.height(Space.s))

                    if (q.isNotEmpty() && !hasExactInPool) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentC)
                                .clickable { onPick(q) }
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GIcon(GI.Plus, Accent, size = 14.dp, strokeWidth = 2f)
                            Spacer(Modifier.width(Space.xs))
                            Text(
                                "使用新画师「$q」",
                                style = T.bodySm,
                                color = Accent,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(Space.xs))
                    }

                    if (allSorted.isEmpty()) {
                        Text("库里还没有画师，在上面输入就是新建", style = T.micro, color = Muted)
                    } else if (filtered.isEmpty()) {
                        Text("没有匹配的已有画师，直接点上面那一行就是新建", style = T.micro, color = Muted)
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            grouped.forEach { (letter, group) ->
                                stickyHeader(key = "hdr_$letter") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(CardBg)
                                            .padding(top = 4.dp, bottom = 2.dp, start = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            letter,
                                            style = T.micro,
                                            color = Accent,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(Space.xs))
                                        Text(
                                            "${group.size}",
                                            style = T.num,
                                            color = Muted,
                                        )
                                    }
                                }
                                items(group, key = { it.first }) { (name, count, _) ->
                                    val on = name == current.trim()
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (on) AccentC else CHigh)
                                            .clickable { onPick(name) }
                                            .padding(horizontal = 13.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            name,
                                            style = T.bodySm,
                                            color = if (on) Accent else Paper,
                                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (count > 0) {
                                            Spacer(Modifier.width(Space.s))
                                            Text(
                                                "$count 件",
                                                style = T.micro,
                                                color = if (on) Accent else Muted,
                                            )
                                        }
                                        if (on) {
                                            Spacer(Modifier.width(Space.xs))
                                            GIcon(GI.Check, Accent, size = 14.dp, strokeWidth = 2f)
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = T.label, color = Muted)
            }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSelfMade != null) {
                    TextButton(onClick = onSelfMade) {
                        Text("自制", style = T.label, color = Accent)
                    }
                }
                if (current.isNotBlank()) {
                    TextButton(onClick = { onPick("") }) {
                        Text(clearLabel, style = T.label, color = Muted)
                    }
                }
            }
        },
    )
}

/** 标签字段：点开二级弹窗，搜索框同时就是新建框（多选） */
@Composable
internal fun TagField(
    selected: List<String>,
    placeholder: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (selected.isEmpty()) placeholder else selected.joinToString(" · "),
            style = T.bodySm,
            color = if (selected.isEmpty()) Muted else Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected.isNotEmpty()) {
            Spacer(Modifier.width(Space.xs))
            Text("${selected.size}", style = T.num, color = Accent)
        }
        Spacer(Modifier.width(Space.xxs))
        GIcon(GI.ChevronDown, Muted, size = 14.dp, strokeWidth = 1.8f)
    }
}

/**
 * 标签二级选择弹窗（多选）：**一个框干两件事**。
 * - 搜到的：按首字母 / 拼音升序分组粘性标头，顶部搜索过滤（同时匹配别名），点一下切换选中；
 * - 搜不到的：搜索框下方出现「添加新标签「xxx」」，点一下加入已选，保存稿件时由
 *   `TagBook.ensure()` 建档。要一次建多个，就建完一个再搜下一个（空格逗号批量那个框已删）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TagPickerDialog(
    pool: List<Pair<String, Int>>,
    tags: TagBook,
    current: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var picked by remember(current) { mutableStateOf(current.distinct()) }

    val allSorted = remember(pool, tags.all, picked) {
        val counts = pool.toMap()
        val aliasesByName = tags.all.associate { it.name to it.aliases }
        val names = linkedSetOf<String>()
        tags.all.forEach { names.add(it.name) }
        pool.forEach { names.add(it.first) }
        picked.forEach { names.add(it) }
        val collator = Collator.getInstance(Locale.CHINA)
        names.map { name -> Triple(name, counts[name] ?: 0, aliasesByName[name].orEmpty()) }
            .sortedWith { a, b -> collator.compare(a.first, b.first) }
    }

    // 同 ArtistPickerDialog：搜索框就是新建输入框，搜不到就点「添加新标签「xxx」」
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val q = query.trim()
    val filtered = remember(allSorted, q) {
        if (q.isEmpty()) {
            allSorted
        } else {
            allSorted.filter { (name, _, aliases) ->
                name.contains(q, ignoreCase = true) ||
                    aliases.any { it.contains(q, ignoreCase = true) }
            }
        }
    }
    val grouped = remember(filtered) { groupByInitial(filtered) { it.first } }
    val hasExactInPool = remember(allSorted, q) {
        q.isNotEmpty() && allSorted.any { it.first.equals(q, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text("标签", style = T.titleSm, color = Paper)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CHigh)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text(
                                    "搜索已有标签（共 ${allSorted.size} 个）",
                                    style = T.bodySm,
                                    color = Muted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = T.bodySm.copy(color = Paper),
                                cursorBrush = SolidColor(Accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                            )
                        }
                        if (query.isNotEmpty()) {
                            Spacer(Modifier.width(Space.xs))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { query = "" }
                                    .padding(4.dp),
                            ) {
                                GIcon(GI.Close, Muted, size = 14.dp, strokeWidth = 2f)
                            }
                        }
                    }

                    if (picked.isNotEmpty()) {
                        Spacer(Modifier.height(Space.xs))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "已选 ${picked.size} 个",
                                style = T.micro,
                                color = Accent,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "清空",
                                style = T.micro,
                                color = Muted,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { picked = emptyList() }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(Space.s))

                    if (q.isNotEmpty() && !hasExactInPool) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AccentC)
                                .clickable {
                                    if (q !in picked) picked = picked + q
                                    query = ""
                                    focusRequester.requestFocus()
                                    keyboard?.show()
                                }
                                .padding(horizontal = 13.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GIcon(GI.Plus, Accent, size = 14.dp, strokeWidth = 2f)
                            Spacer(Modifier.width(Space.xs))
                            Text(
                                "添加新标签「$q」",
                                style = T.bodySm,
                                color = Accent,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(Space.xs))
                    }

                    if (allSorted.isEmpty()) {
                        Text("库里还没有标签，在上面输入就是新建", style = T.micro, color = Muted)
                    } else if (filtered.isEmpty()) {
                        Text("没有匹配的已有标签", style = T.micro, color = Muted)
                    } else {
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            grouped.forEach { (letter, group) ->
                                stickyHeader(key = "tag_hdr_$letter") {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(CardBg)
                                            .padding(top = 4.dp, bottom = 2.dp, start = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            letter,
                                            style = T.micro,
                                            color = Accent,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        Spacer(Modifier.width(Space.xs))
                                        Text(
                                            "${group.size}",
                                            style = T.num,
                                            color = Muted,
                                        )
                                    }
                                }
                                items(group, key = { it.first }) { (name, count, _) ->
                                    val on = name in picked
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (on) AccentC else CHigh)
                                            .clickable {
                                                picked = if (on) picked - name else picked + name
                                                if (!on) {
                                                    query = ""
                                                    focusRequester.requestFocus()
                                                    keyboard?.show()
                                                }
                                            }
                                            .padding(horizontal = 13.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            name,
                                            style = T.bodySm,
                                            color = if (on) Accent else Paper,
                                            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (count > 0) {
                                            Spacer(Modifier.width(Space.s))
                                            Text(
                                                "$count 件",
                                                style = T.micro,
                                                color = if (on) Accent else Muted,
                                            )
                                        }
                                        if (on) {
                                            Spacer(Modifier.width(Space.xs))
                                            GIcon(GI.Check, Accent, size = 14.dp, strokeWidth = 2f)
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) {
                Text(
                    if (picked.isEmpty()) "确定" else "确定 (${picked.size})",
                    style = T.label,
                    color = Accent,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", style = T.label, color = Muted)
            }
        },
    )
}
