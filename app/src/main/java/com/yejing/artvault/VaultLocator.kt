package com.yejing.artvault

import android.os.Environment
import java.io.File

object VaultLocator {

    /** 一个目录算不算库：有 meta/artworks */
    fun isVault(dir: File): Boolean = File(dir, "meta/artworks").isDirectory

    /** 在指定目录初始化一个空稿仓（创建基础目录、空 taxonomy.json 与 .nomedia） */
    fun initVault(dir: File): Boolean = runCatching {
        if (isVault(dir)) return true
        val artworks = File(dir, "meta/artworks")
        val artists = File(dir, "meta/artists")
        val avatars = File(dir, "meta/avatars")
        val blobs = File(dir, "blobs")
        val thumbs = File(dir, "thumbs")
        listOf(artworks, artists, avatars, blobs, thumbs).forEach {
            if (!it.isDirectory && !it.mkdirs()) return false
        }
        val tax = File(dir, "meta/taxonomy.json")
        if (!tax.isFile) {
            tax.writeText("{\n  \"version\": 1,\n  \"worlds\": [],\n  \"characters\": []\n}\n")
        }
        File(blobs, ".nomedia").apply { if (!exists()) createNewFile() }
        File(thumbs, ".nomedia").apply { if (!exists()) createNewFile() }
        isVault(dir)
    }.getOrDefault(false)

    /** 常见位置，用于首次启动时自动识别（Syncthing 可能把它放在任意父目录下） */
    fun candidates(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        val parents = listOf(
            "", "Pictures", "DCIM", "Download", "Documents"
        )
        return parents.map { p ->
            if (p.isEmpty()) File(ext, "ArtVault") else File(ext, "$p/ArtVault")
        }
    }

    fun autoFind(): File? = candidates().firstOrNull { it.isDirectory && isVault(it) }

    /** /sdcard 下一层扫描，找出所有像库的目录（给用户一点提示用） */
    fun scanShallow(): List<File> {
        val ext = Environment.getExternalStorageDirectory()
        val out = ArrayList<File>()
        val tops = ext.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return out
        for (t in tops) {
            if (isVault(t)) out.add(t)
            val subs = t.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: continue
            for (s in subs) if (isVault(s)) out.add(s)
        }
        return out
    }
}
