package com.yejing.artvault

import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * 屏幕取字的「节点」「识别结果」与「规则」。
 *
 * 刻意做成**纯函数**：输入是一屏的节点列表，输出是一份 [SenseDraft]（全是建议值）。
 * 不碰 Compose、不碰 Context、不碰 IO —— 给一段 JSON 就能在 JVM 上断言，不用连真机。
 *
 * 认两种屏（都是由用户点一次悬浮球抓下来的那一屏）：
 * - **米画师 · 稿件详情**（`com.qixin.mihuas`）：原生控件，每行都有稳定的 `viewIdResourceName`
 *   （`detailMenuPriceNum` 等），主路径按 **id** 取；标签 + 同行取值只作兜底（改 id 不改标签）。
 * - **画加 · 订单快照**（`com.netease.huajia`）：**一个 id 都没有**（抓下来全是空串），
 *   只能靠「标签 → 同行右侧」和「标题紧挨着价格上方」这类几何关系取。
 *
 * 两者都只读当前这一屏，不翻页、不监听；认不出就返回 null，让界面说「没认出」而不是瞎填。
 */
data class SenseNode(
    val text: String,
    val desc: String,
    val id: String,
    val cls: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val click: Boolean,
) {
    /** 认字统一走这里：原生控件文字在 text，WebView / RN 常塞在 contentDescription */
    val label: String get() = text.ifBlank { desc }.trim()

    /** 横向中心，用来判断"谁在标签右边" */
    val cx: Int get() = x + w / 2
}

/** 一屏：包名 + 节点。包名是"这是不是米画师"的第一道判据 */
data class SenseScreen(
    val pkg: String,
    val nodes: List<SenseNode>,
    val w: Int = 0,
    val h: Int = 0,
)

/**
 * 识别结果。**全是建议值**，用户确认后才进表单（`CaptureScreen`），确认后才落库。
 *
 * 价格单位是**元、整数**；`0` 是有效值（免费稿），`null` 才是没填 —— 与 AGENTS 同一条口径。
 */
data class SenseDraft(
    /** 命中的规则，界面上写「来自 米画师 · 稿件详情」 */
    val source: String,
    val platform: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val price: Long? = null,
    /** `yyyy-MM-dd` 或 `yyyy-MM-dd HH:mm`（有分钟就带上，镜像的 mtime 要用它） */
    val receivedAt: String? = null,
) {
    val hitCount: Int get() = listOfNotNull(title, artist, price, receivedAt).size

    /** 没认出来的字段：界面上要明说，别让人以为"没填"是"本来就是空" */
    val missing: List<String>
        get() = buildList {
            if (title == null) add("标题")
            if (artist == null) add("画师")
            if (price == null) add("价格")
            if (receivedAt == null) add("日期")
        }
}

object SenseRules {

    /** `2026-11-18` / `2026年11月18日` / `2026.11.18` 都认，后面可带 `14:30[:00]` */
    private val DATE = Regex(
        "(\\d{4})\\s*[-/年.]\\s*(\\d{1,2})\\s*[-/月.]\\s*(\\d{1,2})\\s*日?" +
            "\\s*(?:(\\d{1,2}):(\\d{2})(?::\\d{2})?)?",
    )

    /** 标题里有没有中文。没有 = 纯数字 / 英文 / 随机串，算不上"名字" */
    private val HAS_CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]")

    /** 米画师企划名/文件名的装饰符号（`✧ 加急 ✧ 主题名`） */
    private val DECOR = Regex("[✧❖◆◇✶✳✱✲·•・]+")

    /** 把服务抓到的 JSON 解回结构；解不出来就当没抓到 */
    fun parse(json: String): SenseScreen = runCatching {
        val o = JSONObject(json)
        val arr = o.optJSONArray("nodes")
        val nodes = ArrayList<SenseNode>(arr?.length() ?: 0)
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val n = arr.optJSONObject(i) ?: continue
                nodes += SenseNode(
                    text = n.strOrNull("t").orEmpty(),
                    desc = n.strOrNull("d").orEmpty(),
                    id = n.strOrNull("id").orEmpty(),
                    cls = n.strOrNull("cls").orEmpty(),
                    x = n.optInt("x"),
                    y = n.optInt("y"),
                    w = n.optInt("w"),
                    h = n.optInt("h"),
                    click = n.optBoolean("click"),
                )
            }
        }
        val sc = o.optJSONObject("screen")
        SenseScreen(
            pkg = o.strOrNull("pkg").orEmpty(),
            nodes = nodes,
            w = sc?.optInt("w") ?: 0,
            h = sc?.optInt("h") ?: 0,
        )
    }.getOrDefault(SenseScreen("", emptyList()))

    /** 识别一屏。命中不了就返回 null（调用方据此提示"没认出"，不要瞎填） */
    fun best(screen: SenseScreen): SenseDraft? =
        MihuashiDetail.extract(screen) ?: HuajiaOrder.extract(screen)

    /** 多空格压成一个（抓下来的文字里常有连续全角空格/缩进） */
    private fun String.sp(): String = trim().replace(Regex("\\s{2,}"), " ")

    /** 两个节点的纵向重叠比例（除以较矮的那个的高度，不受字号影响） */
    private fun overlapY(a: SenseNode, b: SenseNode): Float {
        val top = maxOf(a.y, b.y)
        val bottom = minOf(a.y + a.h, b.y + b.h)
        val small = minOf(a.h, b.h)
        if (small <= 0) return 0f
        return ((bottom - top).toFloat() / small).coerceIn(0f, 1f)
    }

    // ── 米画师 · 稿件详情 ────────────────────────────────────────────

    private object MihuashiDetail {

        const val SOURCE = "米画师 · 稿件详情"

        private const val PKG = "com.qixin.mihuas"

        /** 米画师的资源 id 尾巴。**改 id 是他们的自由，所以下面还有标签兜底** */
        private const val ID_ARTIST = "detailMenuCooperateName"
        private const val ID_PRICE = "detailMenuPriceNum"
        private const val ID_FILE = "detailMenuFileName"
        private const val ID_TIME = "detailMenuUploadTime"
        private const val ID_ENTITY = "detailMenuEntityName"

        /** 标签原文，兜底路径用 */
        private const val L_ARTIST = "合作画师"
        private const val L_PRICE = "合作稿酬"
        private const val L_FILE = "文件名称"
        private const val L_TIME = "上传时间"
        private const val L_ENTITY = "企划/订单"

        private val EXT = Regex(
            "\\.(png|jpe?g|webp|gif|bmp|psd|psb|clip|tif?f|heic|avif)$",
            RegexOption.IGNORE_CASE,
        )

        fun extract(s: SenseScreen): SenseDraft? {
            val byId = s.nodes.any { n -> n.id.endsWith(ID_PRICE) || n.id.endsWith(ID_FILE) }
            // 判据：包名对上，且至少认出 3 个字段的锚点（id 或标签）。只中 1 个的屏多半是别的地方
            val hits = listOf(ID_ARTIST to L_ARTIST, ID_PRICE to L_PRICE, ID_FILE to L_FILE, ID_TIME to L_TIME)
                .count { (id, label) -> s.nodes.any { it.id.endsWith(id) } || s.nodes.any { it.label == label } }
            val pkgOk = s.pkg.equals(PKG, ignoreCase = true)
            if (hits < 3) return null
            if (!pkgOk && !byId) return null

            val title = pickTitle(s)
            val artist = raw(s, ID_ARTIST, L_ARTIST)
                // 画师名前面常有一颗 emoji / 符号图标（米画师用 ImageView 画，偶尔落进文字）
                ?.replace(Regex("^[\\s\\p{So}\\p{Sk}\\p{Pd}·•]+"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            val price = raw(s, ID_PRICE, L_PRICE)?.let { parseYuan(it) }
            val date = raw(s, ID_TIME, L_TIME)?.let { parseDate(it) }

            if (title == null && artist == null && price == null && date == null) return null
            return SenseDraft(
                source = SOURCE,
                platform = "米画师",
                title = title,
                artist = artist,
                price = price,
                receivedAt = date,
            )
        }

        /**
         * 标题：**文件名称优先，但"没意义"的文件名退到「企划/订单」**。
         *
         * 判据是"有没有中文"：`立绘 - 白底` 算名字；`8f3a2b1c`、`123456`、`IMG_20261118`、
         * `final_v2` 这些纯数字/英文/随机串当标题毫无信息量 —— 尤其是从设计软件导出的
         * `未命名-1` 之外那种哈希名。企划名（`✧ 加急 ✧ 主题名`）去掉装饰符号后反而更像名字。
         *
         * 两边都不像名字时仍用文件名（有总比没有强），用户可以在表单里改。
         */
        private fun pickTitle(s: SenseScreen): String? {
            val file = raw(s, ID_FILE, L_FILE)
                ?.replace(EXT, "")
                ?.trim()
                ?.sp()
                ?.takeIf { it.isNotEmpty() }
            val entity = raw(s, ID_ENTITY, L_ENTITY)
                ?.replace(DECOR, " ")
                ?.sp()
                ?.takeIf { it.isNotEmpty() }

            return when {
                file != null && HAS_CJK.containsMatchIn(file) -> file
                entity != null -> entity
                else -> file
            }
        }

        /** 先按 id 取，取不到再按「标签 + 同行右侧」取 */
        private fun raw(s: SenseScreen, id: String, label: String): String? {
            s.nodes.firstOrNull { it.id.endsWith(id) }?.label?.takeIf { it.isNotBlank() }?.let { return it }

            val lab = s.nodes.firstOrNull { it.label.trimEnd('：', ':') == label } ?: return null
            // 同一行：纵向有 50% 以上重叠（不按绝对像素，密度和字号都不影响）
            val row = s.nodes.filter { it !== lab && overlapY(it, lab) >= 0.5f }
            return row.filter { it.cx > lab.cx && it.label.isNotBlank() }
                .sortedBy { it.x }
                .firstOrNull()
                // 上传时间那一行右边还挂着「稿件阶段·确认用」，日期要靠正则挑，不能取第一个
                    ?.let { first ->
                        if (label == L_TIME) row.firstOrNull { DATE.containsMatchIn(it.label) }?.label else first.label
                    }
        }
    }

    // ── 画加 · 订单快照 ────────────────────────────────────────────

    /**
     * 画加（网易，`com.netease.huajia`）的「订单快照」页。
     *
     * 和米画师完全相反：这一屏**一个 `viewIdResourceName` 都没有**（抓下来 `id` 全是空串），
     * 所以只能靠几何关系取：
     * - 一行一值：标签文字 → 同一行**右侧**最近的那个节点（`商品总价` / `付款时间` / `创建时间`）。
     * - 顶部三件套：`卖家昵称` / `标题` / `¥ 价格` 自上而下排，**标题就压在价格正上方**，
     *   所以以「单独的 ¥」为锚点往上找最近一行 = 标题，再往上找最近一行 = 卖家昵称。
     *
     * 只认这一种页：画加的「橱窗」「作品详情」长得像，但**没有** `商品总价 / 实付款 / 订单编号 / 付款时间`
     * 这些订单独有的标签。至少要命中 2 个才动手，认不出就返回 null。
     */
    private object HuajiaOrder {

        const val SOURCE = "画加 · 订单快照"

        /** 包名判据：用 contains 而不是 equals —— 画加有多个渠道包后缀，换了渠道还能用 */
        private const val PKG = "huajia"

        /** 订单快照独有的标签。命中 2 个才认（少了宁可说没认出） */
        private val ANCHORS = listOf("商品总价", "实付款", "订单编号", "付款时间", "订单快照", "服务费")

        /**
         * 界面骨架文字，取标题 / 画师时必须排除。
         * 比较时先 `trimEnd('：', ':')` —— 「预计完成时间:」在抓下来的文字里带冒号。
         */
        private val CHROME = setOf(
            "订单快照", "订单信息", "商品总价", "服务费(5%)", "实付款",
            "预计完成时间", "稿件授权范围", "交付节点", "交易快照",
            "订单编号", "创建时间", "付款时间", "付款渠道",
        )

        private fun SenseNode.isChrome(): Boolean =
            label.trimEnd('：', ':').trim() in CHROME || label.startsWith("¥") || label.startsWith("￥")

        fun extract(s: SenseScreen): SenseDraft? {
            if (!s.pkg.contains(PKG, ignoreCase = true)) return null
            val hits = ANCHORS.count { key -> s.nodes.any { it.label.contains(key) } }
            if (hits < 2) return null

            val title = pickTitle(s)
            val artist = pickArtist(s, title)
            val price = pickPrice(s)
            // 收稿时间：`付款时间` 优先（下单真正生效的时刻），没有才退到 `创建时间`。
            // **不用**「预计完成时间」—— 那是「接单后 1 天内」这种时长，不是日期。
            val date = rowValue(s, "付款时间")?.let { parseDate(it) }
                ?: rowValue(s, "创建时间")?.let { parseDate(it) }

            if (title == null && artist == null && price == null && date == null) return null
            return SenseDraft(
                source = SOURCE,
                platform = "画加",
                title = title,
                artist = artist,
                price = price,
                receivedAt = date,
            )
        }

        /** 上半屏那个单独的「¥」—— 它是「标题在上、价格在下」这段版式的锚点 */
        private fun priceAnchor(s: SenseScreen): SenseNode? =
            s.nodes
                .filter { it.label == "¥" || it.label == "￥" }
                .filter { s.h == 0 || it.y < s.h / 2 }
                .minByOrNull { it.y }

        private fun pickTitle(s: SenseScreen): String? {
            val anchor = priceAnchor(s)
            if (anchor != null) {
                // 价格正上方最近的一行 = 标题（卖家昵称在它上面更远处）
                s.nodes
                    .filter { it !== anchor && it.label.isNotBlank() && !it.isChrome() }
                    .filter { it.y + it.h <= anchor.y + 8 }
                    .filter { it.w >= 120 }
                    .maxByOrNull { it.y }
                    ?.let { return it.label.sp() }
            }
            // 兜底：上半屏最宽的那行。标题通常比「预计完成时间」这类标签长得多
            return s.nodes
                .filter { it.label.isNotBlank() && !it.isChrome() && it.w >= 300 }
                .filter { s.h == 0 || it.y < s.h * 4 / 10 }
                .maxByOrNull { it.w }
                ?.label?.sp()
        }

        /**
         * 画师 = 标题上方那一行昵称。
         *
         * ⚠️ 这是这一屏唯一"靠版式推断"的字段：画加订单快照顶部只画一个昵称，没有「卖家 / 买家」标签。
         * 若有一天这行其实是买家，识别结果会填错人 —— 所以它只是**预填**，表单里可以改。
         */
        private fun pickArtist(s: SenseScreen, title: String?): String? {
            val t = s.nodes.firstOrNull { it.label.sp() == title } ?: return null
            return s.nodes
                .filter { it !== t && it.label.isNotBlank() && !it.isChrome() }
                .filter { it.y + it.h <= t.y + 8 }
                .filter { it.w in 40..500 && it.label.length <= 24 }
                .filter { HAS_CJK.containsMatchIn(it.label) }
                .maxByOrNull { it.y }
                ?.label?.trim()
        }

        private fun pickPrice(s: SenseScreen): Long? {
            // 稿酬口径 = 给画师的钱 → **商品总价**。
            // 绝不能用「实付款」：它含 5% 服务费（本屏 18 + 0.9 = 18.9），每单会多记一点，
            // 和自己的账对不上，而且人眼看不出来差在哪。
            rowValue(s, "商品总价")?.let { parseYuan(it) }?.let { return it }

            // 兜底：标题下面那对是**两个节点**（「¥」和「18」分开），取同一行里 ¥ 右边最近的那个
            val yuan = priceAnchor(s) ?: return null
            return s.nodes
                .filter { it !== yuan && overlapY(it, yuan) >= 0.5f }
                .filter { it.x >= yuan.x + yuan.w / 2 }
                .minByOrNull { it.x }
                ?.let { parseYuan(it.label) }
        }

        /** 标签 → 同行右侧最近的那个值 */
        private fun rowValue(s: SenseScreen, label: String): String? {
            val lab = s.nodes.firstOrNull { it.label.trimEnd('：', ':').trim() == label } ?: return null
            return s.nodes
                .filter { it !== lab && it.label.isNotBlank() && overlapY(it, lab) >= 0.5f }
                .filter { it.cx > lab.cx }
                .minByOrNull { it.x }
                ?.label?.sp()
        }
    }

    /** `¥ 490` / `￥1,280.00` / `490元` → 490 / 1280 / 490。认不出数字就 null */
    internal fun parseYuan(text: String): Long? {
        val m = Regex("[0-9][0-9,]*(?:\\.\\d+)?").find(text) ?: return null
        val v = m.value.replace(",", "").toDoubleOrNull() ?: return null
        if (v < 0 || v > 100_000_000) return null
        return v.roundToLong()
    }

    /** `2026-11-18 06:43` / `2026年11月18日` → `2026-11-18 06:43` / `2026-11-18` */
    internal fun parseDate(text: String): String? {
        val m = DATE.find(text) ?: return null
        val y = m.groupValues[1].toIntOrNull() ?: return null
        val mo = m.groupValues[2].toIntOrNull() ?: return null
        val d = m.groupValues[3].toIntOrNull() ?: return null
        if (mo !in 1..12 || d !in 1..31) return null
        val base = "%04d-%02d-%02d".format(y, mo, d)
        val hh = m.groupValues[4].toIntOrNull()
        val mi = m.groupValues[5].toIntOrNull()
        // 有分钟就带上：镜像设文件 mtime 要用它，只到天的话同一天多张稿在图库里排序会乱（见 Dates 注释）
        return if (hh != null && mi != null && hh in 0..23 && mi in 0..59) {
            "%s %02d:%02d".format(base, hh, mi)
        } else {
            base
        }
    }
}
