package com.yejing.artvault

import java.io.File

/**
 * 实体命令层（v2-C2）：**「改实体 + 清/搬稿件引用」必须成对发生。**
 *
 * 以前这两步靠页面自己记：删世界观调 `removeWorld`，再（如果想起来）扫一遍稿件；
 * 删文件夹调 `folders.delete`，再调 `stripFolderRefs`。漏第二步不会报错，只会留下一堆
 * 指向不存在 id 的引用——界面上只表现为「少显示一个标签」，人眼发现不了。
 *
 * 这个 object 的每个方法都是一次完整操作：实体改完立刻收拾引用，并返回受影响件数。
 * **UI 只该调这里**，不要再去直接调 `Taxonomy.deleteWorldEntity` 这类裸操作。
 *
 * 线程约定：实体对象（taxonomy / books）在组合期会被读，所以它们的改动留在**调用方线程**
 * （UI 里就是 Main，量只有几十字节）；扫稿件那半才是 IO。
 */
object EntityCommands {

    /** 删世界观：角色退回「未归入世界观」，稿件上的 world_id 置空 */
    suspend fun deleteWorld(root: File, tax: Taxonomy, id: String): Int {
        tax.deleteWorldEntity(id)
        return ArtworkCommands.stripRef(root, "world_id", id)
    }

    /** 删角色：头像文件一并删掉，稿件上的 character_ids 摘掉这一项 */
    suspend fun deleteRole(root: File, tax: Taxonomy, id: String): Int {
        if (!tax.deleteRoleEntity(id)) return 0
        return ArtworkCommands.stripRef(root, "character_ids", id)
    }

    /**
     * 批量删角色：实体一次改完、库只扫一遍。
     *
     * 不要用循环调 [deleteRole] —— 那样 N 个角色会全库扫 N 遍，而且中间每次都会写一次
     * taxonomy.json，中途失败会留下「删了一半」的状态。
     */
    suspend fun deleteRoles(root: File, tax: Taxonomy, ids: Collection<String>): Int {
        val set = ids.toSet()
        if (set.isEmpty()) return 0
        if (!tax.deleteRoleEntities(set)) return 0
        return ArtworkCommands.stripRefs(root, "character_ids", set)
    }

    /** 删归档文件夹：那批稿件退回「未分类」。系统归档拒绝删除，删实体失败时绝不清引用。 */
    suspend fun deleteFolder(root: File, folders: FolderBook, id: String): Int {
        if (!folders.delete(id)) return 0
        return ArtworkCommands.stripRef(root, "folder_id", id)
    }

    /** 删画师：稿件上的 artist_id 置空（反查里这批稿会变成「没填画师」） */
    suspend fun deleteArtist(root: File, artists: ArtistBook, id: String): Int {
        if (!artists.delete(id)) return 0
        return ArtworkCommands.stripRef(root, "artist_id", id)
    }

    /** 删标签：稿件上的 tag_ids 摘掉这一项 */
    suspend fun deleteTag(root: File, tags: TagBook, id: String): Int {
        if (!tags.delete(id)) return 0
        return ArtworkCommands.stripRef(root, "tag_ids", id)
    }

    /**
     * 合并画师：[fromId] 名下的稿件改挂到 [toId]，再删掉 [fromId]。
     * 主页链接保留 [toId] 的——它才是「主身份」，[fromId] 的档案随实体一起消失。
     */
    suspend fun mergeArtist(root: File, artists: ArtistBook, fromId: String, toId: String): Int {
        val n = ArtworkCommands.remapRef(root, "artist_id", fromId, toId)
        artists.delete(fromId)
        return n
    }

    /** 合并标签：引用先去重改挂，再删实体（合并后同一件稿不会出现两个同名标签） */
    suspend fun mergeTag(root: File, tags: TagBook, fromId: String, toId: String): Int {
        val n = ArtworkCommands.remapRef(root, "tag_ids", fromId, toId)
        tags.delete(fromId)
        return n
    }
}
