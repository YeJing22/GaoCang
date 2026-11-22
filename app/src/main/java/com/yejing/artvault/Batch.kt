package com.yejing.artvault

import java.io.File

/**
 * 批量操作的写盘层。两条铁律：
 * 1. 只改传入的字段，其它字段（包括别的工具写的不认识的键）原样保留 —— 先读再改再写。
 * 2. 会减少条目数量的操作（合并）不真删文件，一律进 meta/artworks/.trash/。
 */

/** 每个字段的意图。Skip 表示这一项不动 */
enum class FieldMode { Skip, Add, Replace }

/**
 * 批量赋值：null / Skip 一律表示「不改」。
 * 这样多选里的稿件，没勾的字段不会被顺手清空。
 */
data class AssignPatch(
    /** null = 不改 */
    val worldId: String? = null,
    /** 归档文件夹：null = 不改；"" = 清成未分类 */
    val folderId: String? = null,
    val roleMode: FieldMode = FieldMode.Skip,
    val roleIds: List<String> = emptyList(),
    val tagMode: FieldMode = FieldMode.Skip,
    val tags: List<String> = emptyList(),
    val artist: String? = null,
    /**
     * 「自制」标记。null = 不动这个键；false = 明确清掉（选了具体画师 / 手动画回普通稿）。
     *
     * 不写这条的后果：把一批「自制」稿赋成某个画师后，`self_made` 还留在 JSON 里，
     * 变成「有画师又自称自制」的自相矛盾状态（详见 Art.selfMade 的注释）。
     */
    val selfMade: Boolean? = null,
    val platform: String? = null,
    /** 改日期视为人工确认，不再标「推断」 */
    val receivedAt: String? = null,
    val priceTotal: Long? = null,
) {
    val isEmpty: Boolean
        get() = worldId == null && folderId == null && roleMode == FieldMode.Skip &&
            tagMode == FieldMode.Skip && artist == null && selfMade == null &&
            platform == null && receivedAt == null && priceTotal == null
}

data class MergeResult(
    val ok: Boolean,
    val versions: Int = 0,
    val mergedIn: Int = 0,
    val error: String? = null,
)

data class DeleteResult(
    val ok: Boolean,
    val moved: Int = 0,
    val error: String? = null,
)

// ── 入口（实现已收进 ArtworkCommands）──────────────────────────────────
// 保留这些顶层函数只是为了让 UI 不用改签名；真正的读写、归一、校验
// 全部在 ArtworkCommands 里，meta/artworks/*.json 只从那里写。

/** 批量赋值：只改 patch 里给了值的字段，没勾的保持原样 */
suspend fun assignBatch(
    root: File,
    ids: List<String>,
    patch: AssignPatch,
    artists: ArtistBook,
    tags: TagBook,
    tax: Taxonomy? = null,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): Int = ArtworkCommands.assign(root, ids, patch, artists, tags, tax, onProgress)

/** 合并成同一槽；被并掉的槽进回收站并打上「来自合并」标记 */
suspend fun mergeArts(
    root: File,
    ids: List<String>,
    primaryId: String,
    tax: Taxonomy? = null,
): MergeResult = ArtworkCommands.merge(root, ids, primaryId, tax)

/** 删除整槽：JSON 进回收站，图片永不动 */
suspend fun deleteArts(
    root: File,
    ids: List<String>,
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
): DeleteResult = ArtworkCommands.delete(root, ids, onProgress)
