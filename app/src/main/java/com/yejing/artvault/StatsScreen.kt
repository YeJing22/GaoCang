package com.yejing.artvault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yejing.artvault.ui.component.EmptyState
import com.yejing.artvault.ui.component.SectionLabel
import java.time.YearMonth
import java.util.Locale
import kotlin.math.roundToInt

/** 画师聚合结果：一件稿只算一次（多版本、多角色都不影响计数） */
data class ArtistStat(
    /** 画师实体 id——点进画师页靠它，改名也不受影响 */
    val id: String,
    val name: String,
    val count: Int,
    /** 已记价格的合计（元） */
    val spend: Long,
    val pricedCount: Int,
    /** 最近一件的收稿时间原串，展示时再用 Dates 格式化 */
    val latest: String?,
    /** 列表左侧最多三张缩略图，按收稿时间新→旧 */
    val preview: List<Art>,
)

/**
 * 画师反查的数据层：按 `artist_id` 分组（实体化后名字只是显示）。
 * 档案解析不出来的组显示「档案缺失」——那是数据坏了，不能装成没填。
 */
fun artistStats(arts: List<Art>, book: ArtistBook): List<ArtistStat> =
    arts.asSequence()
        .mapNotNull { a -> a.artistId?.let { it to a } }
        .groupBy({ it.first }, { it.second })
        .map { (id, list) ->
            val priced = list.mapNotNull { it.priceTotal }
            ArtistStat(
                id = id,
                name = book.byId(id)?.name ?: VaultNames.MISSING,
                count = list.size,
                spend = priced.sum(),
                pricedCount = priced.size,
                latest = list.filter { Dates.parse(it.receivedAt) != null }
                    .maxByOrNull { Dates.sortKey(it.receivedAt) }
                    ?.receivedAt,
                preview = list.sortedByDescending { Dates.sortKey(it.receivedAt) }.take(3),
            )
        }
        .sortedWith(
            compareByDescending<ArtistStat> { it.count }
                .thenByDescending { it.spend }
                .thenBy { it.name }
        )

/** 近 6 个自然月的约稿支出汇总（右端对齐库里最新一条稿的月份，而非系统今天） */
private data class SixMonthSpend(
    val months: List<Pair<String, Long>>,
    val undated: Int,
)

private fun recentSixMonthsSpend(arts: List<Art>): SixMonthSpend {
    val latest = arts.mapNotNull { Dates.parse(it.receivedAt) }.maxOrNull()
    val undated = arts.count { Dates.parse(it.receivedAt) == null }
    if (latest == null) {
        return SixMonthSpend(months = List(6) { "" to 0L }, undated = undated)
    }
    val endMonth = YearMonth.of(latest.year, latest.monthValue)
    val keys = (5 downTo 0).map { offset ->
        val ym = endMonth.minusMonths(offset.toLong())
        "%04d-%02d".format(Locale.US, ym.year, ym.monthValue)
    }
    val byMonth = HashMap<String, Long>(6)
    for (a in arts) {
        val price = a.priceTotal ?: continue
        val dt = Dates.parse(a.receivedAt) ?: continue
        val k = "%04d-%02d".format(Locale.US, dt.year, dt.monthValue)
        if (k in keys) {
            byMonth[k] = (byMonth[k] ?: 0L) + price
        }
    }
    return SixMonthSpend(
        months = keys.map { it to (byMonth[it] ?: 0L) },
        undated = undated,
    )
}

/**
 * 账本：只做「现有数据的汇总」，不引入新数据源。
 *
 * 顶部两个板块：
 * - 概览：资产主卡（含近 6 月迷你柱）+ 世界观分布
 * - 画师：画师反查入口（点进 [ArtistScreen] 看这个人画过的稿），
 *   放这里而不是塞进筛选菜单——按画师找人是「浏览」而不是「筛子」，筛菜单里一长串画师很难用
 */
@Composable
fun StatsScreen(
    arts: List<Art>,
    tax: Taxonomy,
    lib: Library,
    artists: ArtistBook,
    names: VaultNames,
    onOpenArtist: (String) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val counted = remember(arts) { arts.countedOnly() }
    val stats = remember(counted, artists) { artistStats(counted, artists) }
    // 「自制」也是填过画师字段的，不算没填（口径同 Filter.matchesGap）
    val noArtist = remember(counted) { counted.count { it.artistId == null && !it.selfMade } }
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp)) {
            Text("账本", style = T.display, color = Paper, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(Space.xs))
            Text(
                "共 ${counted.size} 件 · ${tax.roles.size} 个角色 · ${stats.size} 位画师",
                style = T.label,
                color = Muted,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(CardBg)
                    .padding(3.dp),
            ) {
                listOf("概览", "画师").forEachIndexed { i, label ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (tab == i) AccentC else Color.Transparent)
                            .clickable { tab = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = T.bodySm,
                            color = if (tab == i) Accent else Muted,
                            fontWeight = if (tab == i) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.m))
        }

        if (tab == 0) {
            OverviewTab(arts = counted, tax = tax, navBottom = navBottom)
        } else {
            ArtistTab(
                stats = stats,
                noArtist = noArtist,
                lib = lib,
                navBottom = navBottom,
                onOpen = onOpenArtist,
            )
        }
    }
}

@Composable
private fun ColumnScope.OverviewTab(arts: List<Art>, tax: Taxonomy, navBottom: Dp) {
    val priced = remember(arts) { arts.mapNotNull { it.priceTotal } }
    val total = priced.sum()
    val avg = if (priced.isEmpty()) 0L else total / priced.size
    val max = priced.maxOrNull()
    val sixMonths = remember(arts) { recentSixMonthsSpend(arts) }

    val dist = remember(arts, tax) {
        tax.worlds
            .map { w -> w.name to arts.count { a -> a.worldId == w.id } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(6)
    }
    val maxCount = dist.maxOfOrNull { it.second } ?: 1
    val distTotal = dist.sumOf { it.second }.coerceAtLeast(1)

    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 118.dp + navBottom)
    ) {
        AssetHeroCard(
            artCount = arts.size,
            pricedCount = priced.size,
            total = total,
            avg = avg,
            max = max,
            sixMonths = sixMonths,
        )

        if (dist.isNotEmpty()) {
            SectionLabel("世界观分布")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CardBg)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Ink),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    dist.forEachIndexed { i, (_, n) ->
                        val color = ChartColors.getOrElse(i) { ChartColors.last() }
                        Box(
                            Modifier
                                .weight(n.toFloat())
                                .fillMaxHeight()
                                .background(color)
                        )
                    }
                }
                dist.forEachIndexed { i, (name, n) ->
                    val color = ChartColors.getOrElse(i) { ChartColors.last() }
                    val pct = ((n * 100.0) / distTotal).roundToInt()
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = T.label, color = OnMid, modifier = Modifier.weight(1f))
                            Text("$n 件 · $pct%", style = T.num, color = Muted)
                        }
                        Spacer(Modifier.height(Space.xs))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(CHigh)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(n.toFloat() / maxCount)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(color)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(Space.xl))
        Text(
            "月份支出曲线、单价 Top 这些图，等库整理完再补；按画师浏览已经放到「画师」板块。",
            style = T.micro,
            color = Muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun AssetHeroCard(
    artCount: Int,
    pricedCount: Int,
    total: Long,
    avg: Long,
    max: Long?,
    sixMonths: SixMonthSpend,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CardBg)
            .border(1.dp, Accent.copy(alpha = 0.3f), shape)
            .padding(16.dp),
    ) {
        if (artCount == 0) {
            Text("还没有稿件", style = T.bodySm, color = Muted)
            return@Column
        }

        val pct = pricedCount * 1000 / artCount / 10.0
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "累计约稿总支出",
                style = T.micro,
                color = Muted,
                modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(AccentC)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "记价率 %.1f%%".format(Locale.US, pct),
                        style = T.micro.copy(fontFeatureSettings = "tnum"),
                        color = Accent,
                    )
                }
                Text(
                    "已记价 $pricedCount 件",
                    style = T.micro.copy(fontFeatureSettings = "tnum"),
                    color = Muted,
                )
            }
        }

        Spacer(Modifier.height(Space.s))

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (pricedCount == 0) "—" else "¥%,d".format(Locale.US, total),
                style = T.display.copy(fontFeatureSettings = "tnum"),
                color = Accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(Space.m))
            SixMonthBars(sixMonths.months)
        }

        Spacer(Modifier.height(Space.l))
        Box(Modifier.fillMaxWidth().height(1.dp).background(OutlineSoft))
        Spacer(Modifier.height(Space.m))

        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroMetric(
                label = "馆藏总量",
                value = "%,d 件".format(Locale.US, artCount),
                valueColor = Paper,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.fillMaxHeight().padding(vertical = 2.dp).width(1.dp).background(OutlineSoft))
            HeroMetric(
                label = "平均单价",
                value = if (pricedCount == 0) "—" else "¥%,d".format(Locale.US, avg),
                valueColor = Paper,
                modifier = Modifier.weight(1f).padding(start = Space.m),
            )
            Box(Modifier.fillMaxHeight().padding(vertical = 2.dp).width(1.dp).background(OutlineSoft))
            HeroMetric(
                label = "单件最高",
                value = max?.let { "¥%,d".format(Locale.US, it) } ?: "—",
                valueColor = Accent,
                modifier = Modifier.weight(1f).padding(start = Space.m),
            )
        }

        if (sixMonths.undated > 0) {
            Spacer(Modifier.height(Space.m))
            Text(
                "另有 ${sixMonths.undated} 件无日期未计入",
                style = T.micro,
                color = Muted,
            )
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = T.micro, color = Muted, maxLines = 1)
        Spacer(Modifier.height(Space.xxs))
        Text(
            value,
            style = T.numLg,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SixMonthBars(months: List<Pair<String, Long>>) {
    val maxSpend = months.maxOfOrNull { it.second } ?: 0L
    Row(
        Modifier.height(36.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEachIndexed { i, (_, spend) ->
            val h = if (maxSpend <= 0L || spend <= 0L) {
                2.dp
            } else {
                (36f * spend.toFloat() / maxSpend.toFloat()).dp.coerceAtLeast(2.dp)
            }
            val color = if (i == months.lastIndex) Accent else Accent.copy(alpha = 0.35f)
            Box(
                Modifier
                    .width(10.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun ColumnScope.ArtistTab(
    stats: List<ArtistStat>,
    noArtist: Int,
    lib: Library,
    navBottom: Dp,
    onOpen: (String) -> Unit,
) {
    if (stats.isEmpty()) {
        Column(Modifier.fillMaxWidth().weight(1f)) {
            EmptyState(
                title = "还没有记录画师",
                sub = if (noArtist > 0) {
                    "现在 $noArtist 件稿都还没填画师。\n在稿件信息里填上名字（或长按多选批量赋值），这里就会自动汇总。"
                } else {
                    "在稿件信息里填上画师名，这里就会自动汇总，点进去能看这个人画过的全部稿件。"
                },
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxWidth().weight(1f),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 0.dp,
            bottom = 118.dp + navBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionLabel("${stats.size} 位画师 · 按件数排")
        }
        itemsIndexed(stats, key = { _, s -> s.name }) { idx, s ->
            ArtistRow(rank = idx + 1, stat = s, lib = lib, onClick = { onOpen(s.id) })
        }
        if (noArtist > 0) {
            item {
                Text(
                    "还有 $noArtist 件没填画师。在稿库长按多选 →「赋值」可以批量填，填好就会出现在上面的列表里。",
                    style = T.micro,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ArtistRow(rank: Int, stat: ArtistStat, lib: Library, onClick: () -> Unit) {
    val sub = buildList {
        add("${stat.count} 件")
        if (stat.pricedCount > 0) add("¥${stat.spend}")
        stat.latest?.let { add("最近 ${Dates.datePart(it)}") }
    }.joinToString("  ·  ")

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CardBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "%02d".format(Locale.US, rank),
            style = T.num,
            color = if (rank <= 3) Accent else Muted,
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(Space.xs))
        // 三张缩略图；不足三张补占位，否则每行左边宽度不一、名字会参差不齐
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
            stat.preview.forEach { art ->
                ThumbBox(art, lib)
            }
            repeat(3 - stat.preview.size) {
                Box(
                    Modifier
                        .width(36.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(0.8.dp, OutlineSoft, RoundedCornerShape(8.dp))
                )
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                stat.name,
                style = T.titleSm,
                color = Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                sub,
                style = T.micro,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Space.s))
        // 件数用价格色：一眼能看出谁是「主力画师」，和概览里的金额是同一套语义
        Text(
            "${stat.count}",
            style = T.titleSm.copy(fontFeatureSettings = "tnum"),
            color = Amber,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ThumbBox(art: Art, lib: Library) {
    Box(
        Modifier
            .width(36.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
    ) {
        art.primary?.let { v ->
            AsyncImage(
                model = lib.thumb(v),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
