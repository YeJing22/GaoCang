package com.yejing.artvault

import java.io.File

/** 版本管理里的一条「编辑后」的版本：blob + 显示名。列表顺序就是最终顺序 */
data class VerEdit(
    val blob: String,
    val label: String,
    /** 列表 key：blob 可能重复（同一张图被加成两个版本），拿 blob 当 key 会崩，所以另发一个 */
    val uid: String,
    /** 盘上的版本 id（`versions[].id`）。拆分要靠它精确定位，不能拿 blob 反查 —— blob 会重复 */
    val id: String = "",
)

/**
 * 把版本列表整体写回：顺序、名字、主图一次性定下来。
 *
 * 为什么整体写而不是逐条改：拖动排序本来就要重排整个数组，分三次写盘
 * （改名 / 排序 / 主图）会在盘上留下中间态，那一刻 Syncthing 正好同步过去，
 * PC 上拿到的就是半截数据。
 *
 * 实现已收进 [ArtworkCommands.setVersions]（唯一写盘点，顺带做归一与被删版本入回收站）。
 */
suspend fun applyVersionEdits(
    root: File,
    artId: String,
    edits: List<VerEdit>,
    primaryIndex: Int,
    tax: Taxonomy? = null,
): Boolean = ArtworkCommands.setVersions(root, artId, edits, primaryIndex, tax)
