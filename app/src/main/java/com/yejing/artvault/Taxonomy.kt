package com.yejing.artvault

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import kotlin.random.Random

data class World(val id: String, val name: String)
data class Role(
    val id: String,
    val name: String,
    val worldId: String?,
    /** 头像相对 meta/ 的路径，例 avatars/<roleId>.webp；一个角色固定一个文件名 */
    val avatar: String? = null,
    /** 宠物（宝可梦之类）：照常算件数，但不计入人均分摊的分母 */
    val pet: Boolean = false,
)

fun newId(): String =
    System.currentTimeMillis().toString(36) + Random.nextInt(46656).toString(36).padStart(3, '0')

/**
 * 世界观 / 角色。落在 <root>/meta/taxonomy.json 一个文件里。
 * PC 端只读，所以单文件不会产生写冲突。file 为 null 时（演示库）只在内存里改。
 */
class Taxonomy(private val file: File?) {
    var worlds: List<World> = emptyList()
        private set
    var roles: List<Role> = emptyList()
        private set

    fun load(): Taxonomy {
        val f = file ?: return this
        if (!f.isFile) return this
        runCatching {
            val o = JSONObject(f.readText())
            worlds = o.optJSONArray("worlds").objects().mapNotNull {
                World(it.optString("id"), it.optString("name"))
                    .takeIf { w -> w.id.isNotBlank() && w.name.isNotBlank() }
            }
            roles = o.optJSONArray("characters").objects().mapNotNull {
                Role(
                    it.optString("id"),
                    it.optString("name"),
                    it.strOrNull("world_id"),
                    it.strOrNull("avatar"),
                    it.optBoolean("pet", false),
                ).takeIf { r -> r.id.isNotBlank() && r.name.isNotBlank() }
            }
        }
        return this
    }

    /**
     * 写盘。**返回值必须被看见**：以前这里丢掉 `writeJsonAtomic` 的结果，
     * 写失败（权限、空间、rename 失败）时内存已经改了、文件没改，表现就是
     * 「界面上删掉了，重进又回来」——而且一声不响。
     */
    private fun persist(): Boolean {
        val f = file ?: run {
            android.util.Log.w("GaoCangTax", "persist skipped: file == null")
            return false
        }
        val o = JSONObject()
        o.put("version", 1)
        o.put("worlds", JSONArray().also { a -> worlds.forEach { w -> a.put(JSONObject().put("id", w.id).put("name", w.name)) } })
        o.put("characters", JSONArray().also { a ->
            roles.forEach { r ->
                val o = JSONObject().put("id", r.id).put("name", r.name).put("world_id", r.worldId ?: "")
                if (!r.avatar.isNullOrBlank()) o.put("avatar", r.avatar)
                if (r.pet) o.put("pet", true)
                a.put(o)
            }
        })
        f.parentFile?.mkdirs()
        val ok = writeJsonAtomic(f, o)
        android.util.Log.d(
            "GaoCangTax",
            "persist ${if (ok) "ok" else "FAILED"} ${f.absolutePath} worlds=${worlds.size} roles=${roles.size}",
        )
        return ok
    }

    fun addWorld(name: String): World {
        val w = World(newId(), name.trim())
        if (w.name.isEmpty()) return w
        worlds = worlds + w
        persist()
        return w
    }

    fun addRole(name: String, worldId: String?): Role {
        val r = Role(newId(), name.trim(), worldId)
        if (r.name.isEmpty()) return r
        roles = roles + r
        persist()
        return r
    }

    fun renameWorld(id: String, name: String) {
        worlds = worlds.map { if (it.id == id) it.copy(name = name.trim()) else it }
        persist()
    }

    fun renameRole(id: String, name: String) {
        roles = roles.map { if (it.id == id) it.copy(name = name.trim()) else it }
        persist()
    }

    fun setPet(id: String, pet: Boolean): Boolean {
        val prev = roles
        if (prev.none { it.id == id }) return false
        roles = prev.map { if (it.id == id) it.copy(pet = pet) else it }
        if (!persist()) {
            roles = prev
            return false
        }
        return true
    }

    /** 把角色移到另一个世界观；worldId 为 null 表示移出世界观 */
    fun moveRole(id: String, worldId: String?) {
        roles = roles.map { if (it.id == id) it.copy(worldId = worldId) else it }
        persist()
    }

    /** 批量移动：一次写盘（逐个调 [moveRole] 会写 N 次文件） */
    fun moveRoles(ids: Collection<String>, worldId: String?) {
        val set = ids.toSet()
        if (set.isEmpty()) return
        val next = roles.map { if (it.id in set) it.copy(worldId = worldId) else it }
        if (next == roles) return
        roles = next
        persist()
    }

    /**
     * 按给定 id 顺序重排角色（拖动排序落盘用）。
     *
     * 名单里没出现过的角色（拖动途中别处新建的）按原相对顺序补在末尾——
     * 宁可排到后面，也不能因为名单不全就把角色弄丢。
     */
    fun reorderRoles(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val byId = roles.associateBy { it.id }
        val head = orderedIds.mapNotNull { byId[it] }
        val taken = head.mapTo(HashSet()) { it.id }
        val next = head + roles.filter { it.id !in taken }
        if (next.map { it.id } == roles.map { it.id }) return
        roles = next
        persist()
    }

    /** 按给定 id 顺序重排世界观（拖动排序落盘用）；未出现在名单里的按原顺序补在末尾 */
    fun reorderWorlds(orderedIds: List<String>) {
        if (orderedIds.isEmpty()) return
        val byId = worlds.associateBy { it.id }
        val head = orderedIds.mapNotNull { byId[it] }
        val taken = head.mapTo(HashSet()) { it.id }
        val next = head + worlds.filter { it.id !in taken }
        if (next.map { it.id } == worlds.map { it.id }) return
        worlds = next
        persist()
    }

    /**
     * 只重排**某个世界观内**的角色，其它世界观的角色在全局列表里的位置一动不动。
     *
     * 为什么不能直接用 [reorderRoles]：角色在数据层是一个扁平列表（`roles`），
     * 界面却按世界观分组显示。在某一组里拖完，拿到的是「这一组的局部顺序」；
     * 直接交给 [reorderRoles] 会把这一组整体提到最前面，别的世界观的角色全被挤到后面。
     * 分组视图上看不出来，但「未归入」这一组和换分组显示时就会露出乱序。
     *
     * 做法：在全局列表里，凡属于该组的槽位按新顺序依次填回去（占位替换）。
     * 传进来的名单必须正好是这一组角色的全集，否则整个忽略——宁可不动，也不做半个重排。
     */
    fun reorderRolesWithin(worldId: String?, orderedIds: List<String>) {
        val local = rolesOf(worldId).mapTo(LinkedHashSet()) { it.id }
        if (orderedIds.size != local.size || !orderedIds.all { it in local }) return
        var i = 0
        val next = roles.map { r -> if (r.id in local) orderedIds[i++] else r.id }
        reorderRoles(next)
    }

    /**
     * 只删实体：世界观从列表消失，名下角色变成「未归入世界观」。
     *
     * **不动稿件引用**——那是 [EntityCommands.deleteWorld] 的活（它改完实体立刻扫稿件）。
     * 别直接调这个方法，否则稿件上会留下一堆悬空 world_id。
     */
    internal fun deleteWorldEntity(id: String) {
        val before = worlds.size
        worlds = worlds.filterNot { it.id == id }
        roles = roles.map { if (it.worldId == id) it.copy(worldId = null) else it }
        android.util.Log.d("GaoCangTax", "deleteWorld id=$id worlds $before -> ${worlds.size}")
        // 写盘失败必须让调用方知道。以前这里丢掉返回值：内存已经删了、文件没删，
        // 事务再一回滚，表现就是「界面上删掉了，退出重进又回来」，而且一声不响。
        if (!persist()) throw java.io.IOException("taxonomy.json 写入失败")
    }

    /**
     * 只删实体：角色连头像一起删。
     *
     * **不动稿件引用**——见 [EntityCommands.deleteRole]。
     */
    internal fun deleteRoleEntity(id: String): Boolean = deleteRoleEntities(setOf(id))

    /** 批量版：先写盘，成功后才删头像文件（失败回报 false，调用方负责别清引用） */
    internal fun deleteRoleEntities(ids: Set<String>): Boolean {
        if (ids.isEmpty()) return true
        val victims = roles.filter { it.id in ids }
        if (victims.isEmpty()) return true
        val before = roles
        roles = roles.filterNot { it.id in ids }
        if (!persist()) {
            roles = before
            return false
        }
        victims.forEach { deleteAvatarFile(it) }
        return true
    }

    /** 设置/清除头像。relPath 为 null 表示移除 */
    fun setAvatar(id: String, relPath: String?) {
        roles = roles.map { if (it.id == id) it.copy(avatar = relPath) else it }
        persist()
    }

    /** 移除头像：删文件 + 清字段，不能只清字段（那会留下孤儿文件） */
    fun clearAvatar(id: String) {
        roles.firstOrNull { it.id == id }?.let { deleteAvatarFile(it) }
        setAvatar(id, null)
    }

    /** 从 meta/taxonomy.json 反推库根，媒体选择器要拿它排除自己产出的目录 */
    fun rootOf(): File? = file?.parentFile?.parentFile

    /** 头像的绝对路径。文件名固定，所以换头像直接覆盖，不会有历史文件堆积 */
    fun avatarFile(r: Role): File? {
        val rel = r.avatar?.takeIf { it.isNotBlank() } ?: return null
        val dir = file?.parentFile ?: return null // file 是 meta/taxonomy.json
        return File(dir, rel).takeIf { it.isFile }
    }

    /** 固定的头像目标路径；目录在这里建好 */
    fun avatarTarget(id: String): File? {
        val dir = file?.parentFile ?: return null
        val avatars = File(dir, "avatars").apply { mkdirs() }
        return File(avatars, "$id.webp")
    }

    private fun deleteAvatarFile(r: Role) {
        val f = avatarFile(r) ?: return
        runCatching { if (!f.delete()) f.deleteOnExit() }
    }

    fun rolesOf(worldId: String?): List<Role> =
        if (worldId == null) roles.filter { it.worldId == null } else roles.filter { it.worldId == worldId }

    fun worldName(id: String?): String? = worlds.firstOrNull { it.id == id }?.name
    fun roleName(id: String?): String? = roles.firstOrNull { it.id == id }?.name
}

private fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

/** 把 string_array 这类字段安全读出来 */
internal fun JSONObject.strList(key: String): List<String> {
    val a = optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
}
