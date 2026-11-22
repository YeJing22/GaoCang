package com.yejing.artvault

/**
 * 多角色筛选的匹配方式。
 * - [And] 同框：选中的角色都要出现（双人图 / 群像）
 * - [Or] 任一：出现其中一个就算
 *
 * 曾经还有一个 [Only]「只含」（角色集合恰好相等），2026-11-18 按用户要求删掉：
 * 要看"某个角色一个人出场的稿"直接进角色二级页就行，而在多角色筛选里它和「同框」几乎等价。
 */
/**
 * 「待整理」的缺口类型。批量分类时用来清尾：先筛出没填画师的，一次赋完；
 * 再筛没选角色的，依此类推。**多个缺口之间是「或」**——目的是"找出所有有缺的"。
 */
enum class Gap(val label: String) {
    NoArtist("待填画师"),
    NoRole("待选角色"),
    NoWorld("待分世界观"),
    NoFolder("待归档"),
    NoPrice("待记价格"),
    NoTitle("待填标题"),

    /**
     * 版本名还是导入时自动填的（`透明底` / `带背景` / `版本 2`），没人工命名过。
     * 版本多起来以后「透明底 / 带背景」这种名字在版本条上完全没法区分，所以单独列一个缺口。
     */
    NoVerLabel("待改版名"),

    /**
     * 日期是推断的、而且只有日期没有时分秒 —— 这类稿在图库里排不出先后（镜像 mtime 只能落到当天 12:00）。
     * 和其他缺口不同，它缺的不是"用户还没填"，而是"源文件里就没有"。
     */
    NoTime("待矫时"),
}

enum class RoleMode(val short: String, val hint: String) {
    And("同框", "都要出现"),
    Or("任一", "出现其一"),
    ;

    /** 点一下切到下一种（两态循环） */
    fun next(): RoleMode = if (this == And) Or else And
}

/** 排序字段。方向由 [Sort.dir] 决定，同一个字段点第二次就翻转。 */
enum class SortKey(val label: String, val short: String) {
    Received("收稿时间", "收稿"),
    Updated("修改时间", "修改"),
    Price("价格", "价格"),
}

enum class SortDir {
    Desc, Asc;

    fun flip(): SortDir = if (this == Desc) Asc else Desc
}

data class Sort(val key: SortKey = SortKey.Received, val dir: SortDir = SortDir.Desc) {
    val short: String
        get() = key.short + if (dir == SortDir.Desc) "↓" else "↑"

    val label: String
        get() {
            val d = when (key) {
                SortKey.Price -> if (dir == SortDir.Desc) "高→低" else "低→高"
                else -> if (dir == SortDir.Desc) "新→旧" else "旧→新"
            }
            return "${key.label} $d"
        }

    fun flip(): Sort = copy(dir = dir.flip())
}

/**
 * 筛选条件。可叠加，任何一项为空即不生效。
 * 排序不参与「已启用条件」计数，但会一直生效。
 *
 * [tags] 存的是**标签名**（用户看到的口径），匹配时把稿件的 tag_ids 解析回名字再比——
 * 这样标签改名后筛选项自动跟着走，不会出现「筛一个已经不存在的名字」。
 * [query] 是关键词，匹配标题 / 画师（含别名）/ 标签 / 角色 / 平台 / 备注。
 */
/**
 * 「未分类」的筛选标记：`Filter.folderId` 用**空字符串**表示"只筛没分归档的稿"。
 * 不能用 null——那是"这一维不生效"，两者是相反的意思。
 */
const val UNSORTED_FOLDER = ""

/** 稿件类型筛选维度。默认只看普通稿，避免制品/制图混进日常浏览 */
enum class KindFilter(val label: String) {
    Artwork("普通稿"),
    Derived("制品"),
    Fill("制图"),
    All("全部"),
}

/** 只有普通稿算「一件」；制品 / 制图 不计数、不进待整理、不参与花费/画师/世界观统计 */
val Art.counted: Boolean get() = kind == ArtKind.Artwork

fun List<Art>.countedOnly(): List<Art> = filter { it.counted }

/**
 * 这一件是否命中某个缺口。
 *
 * 抽成函数是为了让「筛选结果」和首屏「待整理 N」胶囊上的数字用**同一把尺子**：
 * 数字写在胶囊上，点进去却筛出另一个数，是最难解释的那类 bug。
 */
fun Art.matchesGap(g: Gap): Boolean {
    if (kind != ArtKind.Artwork) return false
    return when (g) {
        // **自制 不算"没填画师"**：`artistId = null` 有两种意思，
        // 只有 selfMade = false 才是没填（见 Art.selfMade 的注释）。
        // 漏了这半边会让"自制"的稿永远挂在「待整理」里，怎么填都消不掉。
        Gap.NoArtist -> artistId == null && !selfMade
        Gap.NoRole -> roleIds.isEmpty()
        Gap.NoWorld -> worldId == null
        Gap.NoFolder -> folderId == null
        Gap.NoPrice -> priceTotal == null
        Gap.NoTitle -> title.isBlank()
        // 推断出来的日期且没有时分秒：显示上就是"只有年月日"，排序时挤在当天 12:00
        Gap.NoTime -> dateGuessed && !Dates.hasTime(receivedAt)
        Gap.NoVerLabel -> verLabelsNeedFix()
    }
}

/**
 * 多版本稿的版本名要不要改。
 *
 * - **单版本一律不算**：只有一个版本时名字根本不显示，全库 1700+ 件会被无差别命中。
 * - **「透明底」+「带背景」各一个不算**：交付图总有一个透明版，这两个名字本身是有信息的
 *   （2026-11-22 用户拍板）。只有**重名**才真的分不出谁是谁（`带背景` / `带背景`）。
 * - 该改的三种：空名、「版本 N」（合并去重时补的）、自动名重名。
 *
 * 合并去重会加序号（`透明底 2`），所以先剥掉尾部数字再判。
 * **刻意用启发式、不加 schema 字段**：两千件老数据一次就能扫出来，不用迁移、不改写盘契约。
 */
fun Art.verLabelsNeedFix(): Boolean {
    if (versions.size < 2) return false
    val base = versions.map { it.label.trim().replace(AUTO_VER_INDEX, "").trim() }
    if (base.any { it.isEmpty() || it == "版本" }) return true
    val auto = base.filter { it == "透明底" || it == "带背景" }
    return auto.size != auto.toSet().size
}

private val AUTO_VER_INDEX = Regex("\\s\\d+$")

/** 有多少件「待整理」（命中 [gaps] 里任意一项）。默认全部缺口。 */
fun List<Art>.gapCount(gaps: Set<Gap> = Gap.entries.toSet()): Int =
    count { a -> gaps.any { a.matchesGap(it) } }

data class Filter(
    val worldId: String? = null,
    /** 归档分类（导出文件夹）筛选；和世界观互不影响的独立维度。空串见 [UNSORTED_FOLDER] */
    val folderId: String? = null,
    val roleIds: Set<String> = emptySet(),
    /** 多角色时：同框（都要出现）还是任一（出现其一） */
    val roleMode: RoleMode = RoleMode.And,
    val tags: Set<String> = emptySet(),
    /** 待整理：勾中的缺口任意一项命中即保留 */
    val gaps: Set<Gap> = emptySet(),
    val query: String = "",
    val priceMin: Long? = null,
    val priceMax: Long? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    /** 只看星标 */
    val starredOnly: Boolean = false,
    val kind: KindFilter = KindFilter.Artwork,
    val sort: Sort = Sort(),
) {
    val condCount: Int
        get() = listOf(
            worldId != null,
            folderId != null,
            roleIds.isNotEmpty(),
            tags.isNotEmpty(),
            gaps.isNotEmpty(),
            query.isNotBlank(),
            priceMin != null || priceMax != null,
            dateFrom != null || dateTo != null,
            starredOnly,
            kind != KindFilter.Artwork,
        ).count { it }

    val isEmpty: Boolean get() = condCount == 0

    fun withSort(s: Sort): Filter = copy(sort = s)

    fun apply(arts: List<Art>, names: VaultNames, tax: Taxonomy): List<Art> {
        var out = when (kind) {
            KindFilter.Artwork -> arts.filter { it.kind == ArtKind.Artwork }
            KindFilter.Derived -> arts.filter { it.kind == ArtKind.Derived }
            KindFilter.Fill -> arts.filter { it.kind == ArtKind.Fill }
            KindFilter.All -> arts
        }

        if (starredOnly) out = out.filter { it.starred }
        worldId?.let { w -> out = out.filter { it.worldId == w } }
        folderId?.let { f ->
            out = if (f == UNSORTED_FOLDER) out.filter { it.folderId == null }
            else out.filter { it.folderId == f }
        }

        if (roleIds.isNotEmpty()) {
            out = when (roleMode) {
                // 同框：这几个角色必须同时出现在这个槽里（双人图 / 群像）
                RoleMode.And -> out.filter { a -> a.roleIds.toSet().containsAll(roleIds) }
                RoleMode.Or -> out.filter { a -> a.roleIds.any { it in roleIds } }
            }
        }

        if (tags.isNotEmpty()) {
            out = out.filter { a -> names.tagsOf(a.tagIds).any { it in tags } }
        }

        if (gaps.isNotEmpty()) {
            out = out.filter { a -> gaps.any { a.matchesGap(it) } }
        }

        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            val t0 = System.nanoTime()
            out = out.filter { names.searchText(it, tax).contains(q) }
            // 量化验收用：每敲一个字符这一段的耗时（搜索串已缓存，正常应 < 5ms）
            android.util.Log.d(
                "GaoCangPerf",
                "search '${q}' hit=${out.size} ${(System.nanoTime() - t0) / 1_000_000.0}ms",
            )
        }

        if (priceMin != null || priceMax != null) {
            out = out.filter { a ->
                val p = a.priceTotal ?: return@filter false
                (priceMin == null || p >= priceMin) && (priceMax == null || p <= priceMax)
            }
        }

        if (dateFrom != null || dateTo != null) {
            // 不能拿字符串比大小：`"2026-11-15 14:30" <= "2026-11-15"` 是 false，
            // 会把「截止 11-15」那天带时刻的稿子漏掉。上限统一按当天 23:59:59 算。
            val from = Dates.startOfDay(dateFrom)
            val to = Dates.endOfDay(dateTo)
            out = out.filter { a ->
                val d = Dates.parse(a.receivedAt) ?: return@filter false
                (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))
            }
        }

        // 缺该字段的条目一律沉底，升序降序都不影响这条规则
        // 时间用解析后的 epoch 排，不直接比字符串（日期串和日期+时刻串混在一起时字典序会错）
        return when (sort.key) {
            SortKey.Received -> {
                // 判空也走 sortKey：它和 sortedBy 必须用同一把尺子，
                // 否则一边"解析得出"一边"排不上"，条目会既不在 missing 也不进有序段
                val (missing, has) = out.partition { Dates.sortKey(it.receivedAt) == Long.MIN_VALUE }
                val s = has.sortedBy { Dates.sortKey(it.receivedAt) }
                (if (sort.dir == SortDir.Desc) s.reversed() else s) + missing
            }
            SortKey.Updated -> {
                val (missing, has) = out.partition {
                    Dates.sortKey(it.updatedAt ?: it.receivedAt) == Long.MIN_VALUE
                }
                val s = has.sortedBy { Dates.sortKey(it.updatedAt ?: it.receivedAt) }
                (if (sort.dir == SortDir.Desc) s.reversed() else s) + missing
            }
            SortKey.Price -> {
                val (missing, has) = out.partition { it.priceTotal == null }
                val s = has.sortedBy { it.priceTotal!! }
                (if (sort.dir == SortDir.Desc) s.reversed() else s) + missing
            }
        }
    }
}

/** 从现有数据里聚合出可选标签（名字 + 次数，只列被用到的），按出现次数排序 */
fun tagPool(arts: List<Art>, book: TagBook): List<Pair<String, Int>> =
    arts.asSequence()
        .flatMap { a -> a.tagIds.asSequence().mapNotNull { book.byId(it)?.name } }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

/** 画师候选（名字 + 件数，只列被用到的），顺序和统计页的「画师」板块一致 */
fun artistPool(arts: List<Art>, book: ArtistBook): List<Pair<String, Int>> =
    arts.asSequence()
        .mapNotNull { it.artistId }
        .mapNotNull { book.byId(it)?.name }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }
