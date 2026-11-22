package com.yejing.artvault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 操作日志与撤销（v2-C3）。
 *
 * 解决的第一个问题不是「撤销」，而是**半截状态**：合并 / 批量赋值 / 删除都要改好几个文件，
 * 崩在中间就会留下「主槽已合、原槽还在」这种谁也没法收拾的库。
 *
 * 做法（Write-Ahead）：
 * 1. 操作开始 → 建 `.artvault/journal/<opId>.json`
 * 2. **每改一个文件，先把它的 before/after 追加进 journal**（fsync 级别的 tmp+rename），再继续
 * 3. 整个操作成功 → journal 标记 done 并搬进 `.artvault/undo/`
 * 4. 中途崩 → 下次启动 [recover] 发现 journal 还在 → 用 before 全量回滚
 *
 * 第二个问题是可逆：`undo/` 里每条都是一次操作的完整快照，反向应用即可。
 * 保留最近 [KEEP] 步（一次「批量赋值 20 件」算一步）。
 *
 * **边界（很重要）：只管"改内容"，不管"文件搬家"。**
 * 删槽、合并并掉槽、删版本——这些的可逆性由**回收站**负责，这里不记。
 * 两套机制各管一段，才不会出现「撤销恢复要把它挪回回收站」这种自相矛盾的实现。
 *
 * `.artvault/` 必须进 `.stignore`：操作日志是本机的，同步过去只会在两台设备上互相打架。
 */
data class FileChange(
    /** 相对库根的路径，如 `meta/artworks/xxx.json` */
    val path: String,
    /** null = 这个文件在执行前不存在（新建） */
    val before: String?,
    /**
     * null = 执行后这个文件不存在。
     *
     * **这种情况只出现在「删实体」这类直接删文件的改动上**——删槽 / 合并并掉槽是**移进回收站**，
     * 那些操作不记历史（回收站才是它们的撤销），所以这里不需要为"搬家"建模。
     */
    val after: String?,
)

data class OpRecord(
    val id: String,
    val kind: String,
    /** 人话，直接显示在历史列表里：「批量赋值 20 件」 */
    val label: String,
    val at: Long,
    /** false = 已撤销（可重做） */
    val done: Boolean,
    val changes: List<FileChange>,
)

object OperationJournal {

    /** 保留多少步。一次批量操作算一步，5 步足够覆盖「刚手滑」的窗口 */
    const val KEEP = 5

    /** 一次操作真实落盘完成时的观察者（IO 线程触发，无实际变更不触发） */
    @Volatile
    var onDone: ((OpRecord) -> Unit)? = null

    private val mutex = Mutex()

    /** 正在录制的变更（串行由 [mutex] 保证，单个变量够用） */
    @Volatile
    private var recording: MutableList<FileChange>? = null
    @Volatile
    private var recordingRoot: File? = null
    @Volatile
    private var opId: String? = null
    @Volatile
    private var opKind: String = ""
    @Volatile
    private var opLabel: String = ""
    @Volatile
    private var opAt: Long = 0

    private fun journalDir(root: File) = File(root, ".artvault/journal")
    private fun undoDir(root: File) = File(root, ".artvault/undo")
    private fun journalFile(root: File, id: String) = File(journalDir(root), "$id.json")
    private fun recordFile(root: File, id: String) = File(undoDir(root), "$id.json")

    /**
     * 执行一次可撤销操作。**所有会改库的 UI 动作都应该走这里。**
     *
     * [block] 里正常调用 `ArtworkCommands` / 实体仓库即可——写盘会被 [record] 自动捕获。
     * 抛异常时用已记录的 before 回滚，然后把异常抛给调用方去提示用户。
     */
    suspend fun <T> run(root: File, kind: String, label: String, block: suspend () -> T): T =
        mutex.withLock {
            val id = newId()
            val changes = mutableListOf<FileChange>()
            recording = changes
            recordingRoot = root
            opId = id
            opKind = kind
            opLabel = label
            opAt = System.currentTimeMillis()
            try {
                val result = block()
                stopRecording()
                withContext(Dispatchers.IO) { commitJournal(root, id, kind, label, changes) }
                result
            } catch (t: Throwable) {
                stopRecording()
                withContext(Dispatchers.IO) {
                    // 回滚用内存里的快照（比读 journal 更准：journal 可能少最后一条）
                    applyAll(root, changes, useBefore = true)
                    journalFile(root, id).delete()
                }
                throw t
            }
        }

    /** 有没有正在录制的操作——writeJsonAtomic 用它决定要不要多读一次旧内容 */
    internal fun isRecording(): Boolean = recording != null

    private fun stopRecording() {
        recording = null
        recordingRoot = null
        opId = null
    }

    /**
     * 记录一次文件改动（由 `writeJsonAtomic` 和删除路径调用）。
     *
     * 同一个文件在一次操作里被改多次时合并成一条：保留**最初 before、最后 after**，
     * 否则撤销会变成"退回到中间某个状态"。
     */
    @Synchronized
    internal fun record(abs: File, before: String?, after: String?) {
        val list = recording ?: return
        val root = recordingRoot ?: return
        val rel = runCatching { abs.relativeTo(root).invariantSeparatorsPath }.getOrNull() ?: return
        // 回收站内部的写入不算独立改动：一条「稿件移进 .trash」已经记成 after=null，
        // 撤销时会连副本一起清掉。这里再记一条，撤销反而会把副本写回回收站里。
        if (rel.contains("/.trash/")) return
        val i = list.indexOfFirst { it.path == rel }
        if (i >= 0) {
            list[i] = list[i].copy(after = after)
        } else {
            list += FileChange(rel, before, after)
        }
        // 立刻落盘：崩在下一个文件之前，恢复时也能把这条撤掉
        flush(root)
    }

    private fun flush(root: File) {
        val id = opId ?: return
        val list = recording ?: return
        val body = JSONObject()
            .put("version", 1)
            .put("id", id)
            .put("kind", opKind)
            .put("label", opLabel)
            .put("at", opAt)
            .put("done", false)
            .put("changes", changesToJson(list))
        writeRaw(journalFile(root, id), body.toString())
    }

    private suspend fun commitJournal(
        root: File,
        id: String,
        kind: String,
        label: String,
        changes: List<FileChange>,
    ) = withContext(Dispatchers.IO) {
        val real = changes.filterNot { it.before == it.after }
        if (real.isEmpty()) {
            journalFile(root, id).delete()
            return@withContext
        }
        val body = JSONObject()
            .put("version", 1)
            .put("id", id)
            .put("kind", kind)
            .put("label", label)
            .put("at", opAt)
            .put("done", true)
            .put("changes", changesToJson(real))
        writeRaw(recordFile(root, id), body.toString())
        // 进行中的那份删掉：它已经"完成"，不再是待恢复的日志
        journalFile(root, id).delete()
        trim(root)
        runCatching {
            onDone?.invoke(OpRecord(id, kind, label, opAt, true, real))
        }
    }

    private fun trim(root: File) {
        val files = undoDir(root).listFiles { f -> f.isFile && f.extension == "json" } ?: return
        if (files.size <= KEEP) return
        files.sortedBy { it.lastModified() }
            .take(files.size - KEEP)
            .forEach { it.delete() }
    }

    // ── 查询 / 撤销 / 重做 ────────────────────────────────────────

    /** 历史列表，新的在前 */
    fun history(root: File): List<OpRecord> =
        (undoDir(root).listFiles { f -> f.isFile && f.extension == "json" } ?: emptyArray())
            .mapNotNull { f -> runCatching { parse(f.readText()) }.getOrNull() }
            .sortedByDescending { it.at }

    /**
     * 撤销**最近一条**已完成的操作。
     *
     * 只允许撤最新的一条（标准栈语义）：允许跳着撤会让「重做」失去意义，
     * 也容易把两次操作的中间态当成可回退的状态。
     */
    suspend fun undoLatest(root: File): OpRecord? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val target = history(root).firstOrNull { it.done } ?: return@withContext null
            applyAll(root, target.changes, useBefore = true)
            save(root, target.copy(done = false))
            target
        }
    }

    /** 重做最近一次被撤销的操作 */
    suspend fun redoLatest(root: File): OpRecord? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = history(root)
            val latestDone = all.firstOrNull { it.done }
            val target = all.firstOrNull { !it.done && (latestDone == null || it.at > latestDone.at) }
                ?: return@withContext null
            applyAll(root, target.changes, useBefore = false)
            save(root, target.copy(done = true))
            target
        }
    }

    /**
     * 撤「指定的那一条」，**校验与撤销在同一把锁里**。
     *
     * 为什么需要它：4 秒撤销条记的是「刚才那次操作的 id」，用户点它时那条可能已经被
     * 「操作历史」页手动撤掉了。如果先在外面查一遍 `history` 再调 [undoLatest]，
     * 两步之间能插进别的操作 —— 结果就是「撤了另一条，用户以为在重做」。
     *
     * 返回 null = **没撤**：它已经不 done（撤过了），或者它不是最新那条（被更新的操作压过去了）。
     * 两种情况对调用方是同一件事：这条不能再撤。
     */
    suspend fun undoIfLatest(root: File, id: String): OpRecord? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (id.isBlank()) return@withContext null
            val latestDone = history(root).firstOrNull { it.done } ?: return@withContext null
            // 注意不能写成 firstOrNull { it.done && it.id == id }：那会跳过更新的一条去撤旧的
            if (latestDone.id != id) return@withContext null
            applyAll(root, latestDone.changes, useBefore = true)
            save(root, latestDone.copy(done = false))
            latestDone
        }
    }

    /** 这一条还能不能撤（只有最新的那条能） */
    fun canUndo(r: OpRecord, history: List<OpRecord>): Boolean =
        r.done && history.firstOrNull { it.done }?.id == r.id
    fun canRedo(r: OpRecord, history: List<OpRecord>): Boolean {
        if (r.done) return false
        val latestDone = history.firstOrNull { it.done }
        return latestDone == null || r.at > latestDone.at
    }

    /**
     * 启动时恢复：`journal/` 里还有文件 = 上次操作没走完 → 用 before 全量回滚。
     *
     * **宁可少做，不要半做**：一次合并只合了一半，用户看不出哪里不对，比"这次没生效"糟得多。
     */
    suspend fun recover(root: File): Int = withContext(Dispatchers.IO) {
        val files = journalDir(root).listFiles { f -> f.isFile && f.extension == "json" }
            ?: return@withContext 0
        var n = 0
        files.forEach { f ->
            runCatching {
                val rec = parse(f.readText())
                applyAll(root, rec.changes, useBefore = true)
                f.delete()
                n++
            }
        }
        n
    }

    // ── 内部 ─────────────────────────────────────────────────────

    private fun applyAll(root: File, changes: List<FileChange>, useBefore: Boolean) {
        changes.forEach { c ->
            val text = if (useBefore) c.before else c.after
            if (text == null) {
                // 这一侧没有这个文件：直接删（只有「删实体」会走到，槽删除不记历史）
                File(root, c.path).delete()
            } else {
                writeRaw(File(root, c.path), text)
            }
            if (c.path.startsWith("meta/artworks/") && c.path.endsWith(".json")) {
                val id = c.path.removePrefix("meta/artworks/").removeSuffix(".json")
                if (!id.contains('/')) VaultChanges.mark(id)
            }
        }
    }


    private fun save(root: File, rec: OpRecord) {
        val body = JSONObject()
            .put("version", 1)
            .put("id", rec.id)
            .put("kind", rec.kind)
            .put("label", rec.label)
            .put("at", rec.at)
            .put("done", rec.done)
            .put("changes", changesToJson(rec.changes))
        writeRaw(recordFile(root, rec.id), body.toString())
    }

    private fun changesToJson(list: List<FileChange>): JSONArray = JSONArray().also { arr ->
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("path", c.path)
                    .put("before", c.before ?: JSONObject.NULL)
                    .put("after", c.after ?: JSONObject.NULL)
            )
        }
    }

    private fun parse(text: String): OpRecord {
        val o = JSONObject(text)
        val arr = o.optJSONArray("changes") ?: JSONArray()
        val changes = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val path = c.strOrNull("path") ?: return@mapNotNull null
            FileChange(path, c.strOrNull("before"), c.strOrNull("after"))
        }
        return OpRecord(
            id = o.optString("id").ifBlank { newId() },
            kind = o.optString("kind"),
            label = o.optString("label"),
            at = o.optLong("at"),
            done = o.optBoolean("done", true),
            changes = changes,
        )
    }

    /** 原子写纯文本：undo 的快照是完整 JSON 文本，不需要过 normalize / rev */
    private fun writeRaw(f: File, text: String): Boolean {
        val parent = f.parentFile ?: return false
        parent.mkdirs()
        val tmp = File(parent, "${f.name}.${System.nanoTime()}.tmp")
        return runCatching {
            tmp.writeText(text)
            val ok = tmp.renameTo(f)
            if (!ok) tmp.delete()
            ok
        }.getOrDefault(false)
    }
}
