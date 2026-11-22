package com.yejing.artvault

/**
 * 写盘方登记「这条稿变了」，刷新时只重读这几条。
 *
 * 为什么不让调用方传：`ArtworkCommands.commit()` 是稿件 JSON 的唯一写入口，
 * 在那一处 mark 就不会漏（13 个 onChanged 调用点谁也记不住）。
 * 空集合 = 没人登记 → 调用方退回全量重读（冷启动、外部改动、演示库都走这条）。
 */
object VaultChanges {

    private val ids = LinkedHashSet<String>()

    @Synchronized
    fun mark(id: String) {
        if (id.isNotBlank()) ids += id
    }

    @Synchronized
    fun drain(): Set<String> =
        if (ids.isEmpty()) emptySet() else HashSet(ids).also { ids.clear() }
}
