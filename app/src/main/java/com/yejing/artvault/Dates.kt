package com.yejing.artvault

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 收稿时间有两种形态并存，这个工具负责把它们统一：
 * - 存量 58 件是纯日期 `2026-11-15`（PC 端从文件时间推断的）
 * - 新写入的开始带时间 `2026-11-15 14:30:22`
 *
 * 为什么要时间：镜像的文件 mtime 要用它，只到天的话同一天多张稿在图库里排序会乱。
 *
 * **比较必须走这里，不能用字符串比大小**：`"2026-11-15 14:30" <= "2026-11-15"` 是 false，
 * 直接用会让「截止到 11-15」漏掉 11-15 当天的稿子（真踩过）。
 */
object Dates {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val SECOND: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 现在，精确到秒 */
    fun now(): String = LocalDateTime.now().format(SECOND)

    /** 宽容解析：`yyyy-MM-dd` / `yyyy-MM-dd HH:mm` / `yyyy-MM-dd HH:mm:ss` / 带 T 的 ISO */
    fun parse(s: String?): LocalDateTime? {
        val t = s?.trim().orEmpty()
        if (t.length < 10) return null
        val norm = t.replace('T', ' ')
        return runCatching {
            when {
                norm.length >= 19 -> LocalDateTime.parse(norm.substring(0, 19), SECOND)
                norm.length >= 16 -> LocalDateTime.parse(norm.substring(0, 16), MINUTE)
                else -> LocalDate.parse(norm.substring(0, 10), DATE).atStartOfDay()
            }
        }.getOrNull()
    }

    /** 是否带时刻（决定展示要不要带时分） */
    fun hasTime(s: String?): Boolean = (s?.trim()?.length ?: 0) >= 16

    /** 只取日期部分，给文件名用：`2026-11-15 14:30:22` → `2026-11-15` */
    fun datePart(s: String?): String {
        val t = s?.trim().orEmpty()
        return if (t.length >= 10) t.substring(0, 10) else "未记录"
    }

    /** 展示用：带时刻就显示到分钟（秒不占地方），没有就只显示日期 */
    fun display(s: String?): String {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return "未记录"
        return if (hasTime(t)) t.replace('T', ' ').substring(0, 16) else t.take(10)
    }

    /**
     * 转 epoch 毫秒，给镜像设文件时间用。
     * **只有日期时故意取当天 12:00**：凌晨零点跨时区会被挤到前一天。
     */
    fun epochMillis(s: String?): Long? {
        val dt = parse(s) ?: return null
        val at = if (hasTime(s)) dt else dt.toLocalDate().atTime(12, 0)
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    /**
     * 排序键。**只用来比大小，不是时间戳**（值是把 `yyyyMMddHHmmss` 当十进制数拼起来）。
     *
     * 为什么不再走 [epochMillis]：列表排序要对每件算两次键（`partition` 判空一次、
     * `sortedBy` 一次）。1780 件 = 3560 次 `DateTimeFormatter` 解析 + `ZoneId` 换算，
     * 实测 **370ms** —— 这就是「点开筛选面板停一下」的真正原因。按位取数字后是纯算术。
     *
     * 无效值给 [Long.MIN_VALUE]，与「缺该字段的条目一律沉底」的规则一致。
     * **只有日期时补当天 12:00**，和 [epochMillis] 保持一致，否则同一天的相对顺序会变。
     */
    fun sortKey(s: String?): Long {
        val t = s?.trim() ?: return Long.MIN_VALUE
        if (t.length < 10) return Long.MIN_VALUE
        val y = digitsAt(t, 0, 4)
        val mo = digitsAt(t, 5, 7)
        val d = digitsAt(t, 8, 10)
        if (y < 0 || mo !in 1..12 || d !in 1..31) return Long.MIN_VALUE
        val h: Int
        val mi: Int
        val se: Int
        if (t.length >= 16) {
            h = digitsAt(t, 11, 13)
            mi = digitsAt(t, 14, 16)
            if (h !in 0..23 || mi !in 0..59) return Long.MIN_VALUE
            // 秒可选：`yyyy-MM-dd HH:mm` 也合法
            se = if (t.length >= 19) digitsAt(t, 17, 19).let { if (it in 0..59) it else 0 } else 0
        } else {
            h = 12
            mi = 0
            se = 0
        }
        return ((((y.toLong() * 100 + mo) * 100 + d) * 100 + h) * 100 + mi) * 100 + se
    }

    /** 取 `[from, to)` 的数字；越界或含非数字返回 -1 */
    private fun digitsAt(s: String, from: Int, to: Int): Int {
        if (to > s.length) return -1
        var v = 0
        for (i in from until to) {
            val c = s[i]
            if (c < '0' || c > '9') return -1
            v = v * 10 + (c - '0')
        }
        return v
    }

    /** 筛选下限：当天 00:00:00 */
    fun startOfDay(s: String?): LocalDateTime? = parse(s)?.toLocalDate()?.atStartOfDay()

    /** 筛选上限：当天 23:59:59.999，保证「截止到某天」包含那天带时刻的稿子 */
    fun endOfDay(s: String?): LocalDateTime? =
        parse(s)?.toLocalDate()?.atTime(23, 59, 59, 999_000_000)

    /** 给定日期 + 时分秒，拼成存储格式 */
    fun of(date: LocalDate, hour: Int, minute: Int, second: Int = 0): String =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute, second)).format(SECOND)
}
