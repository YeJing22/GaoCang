package com.yejing.artvault

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoreDataTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleArt(
        id: String = "01TEST00000000000000000001",
        title: String = "标题",
        priceTotal: Long? = 100L,
        artistId: String? = "a1",
        selfMade: Boolean = false,
        worldId: String? = "w1",
        folderId: String? = "f1",
        roleIds: List<String> = listOf("r1"),
        receivedAt: String? = "2026-10-01 12:00:00",
        dateGuessed: Boolean = false,
        kind: ArtKind = ArtKind.Artwork,
        versions: List<Ver> = listOf(Ver("v1", "blob1", "成图", "png", 100, 100, false, true)),
    ) = Art(
        id = id,
        title = title,
        note = "",
        receivedAt = receivedAt,
        dateGuessed = dateGuessed,
        updatedAt = null,
        priceTotal = priceTotal,
        platform = "",
        artistId = artistId,
        selfMade = selfMade,
        worldId = worldId,
        folderId = folderId,
        roleIds = roleIds,
        tagIds = emptyList(),
        versions = versions,
        kind = kind,
    )

    @Test
    fun `shareFor splits evenly, gives remainder to first, excludes pets, falls back on pet-only`() {
        val two = sampleArt(priceTotal = 100, roleIds = listOf("A", "B"))
        assertEquals(50L, two.shareFor("A", emptySet()))
        assertEquals(50L, two.shareFor("B", emptySet()))

        val three = sampleArt(priceTotal = 100, roleIds = listOf("A", "B", "C"))
        assertEquals(34L, three.shareFor("A", emptySet()))
        assertEquals(33L, three.shareFor("B", emptySet()))
        assertEquals(33L, three.shareFor("C", emptySet()))

        // C is pet -> denominator drops from 3 to 2, C gets 0
        val cPet = setOf("C")
        assertEquals(50L, three.shareFor("A", cPet))
        assertEquals(50L, three.shareFor("B", cPet))
        assertEquals(0L, three.shareFor("C", cPet))

        // Pet-only artwork falls back to all roles on the artwork (no divide-by-zero)
        val petOnly = sampleArt(priceTotal = 100, roleIds = listOf("C"))
        assertEquals(100L, petOnly.shareFor("C", cPet))

        // Unassigned or null price -> 0
        assertEquals(0L, sampleArt(priceTotal = null, roleIds = listOf("A")).shareFor("A", emptySet()))
        assertEquals(0L, sampleArt(priceTotal = 100, roleIds = emptyList()).shareFor("A", emptySet()))
    }

    @Test
    fun `matchesGap respects zero price, selfMade, and version label heuristics`() {
        val freeSelfMade = sampleArt(priceTotal = 0L, artistId = null, selfMade = true)
        assertFalse(freeSelfMade.matchesGap(Gap.NoPrice))
        assertFalse(freeSelfMade.matchesGap(Gap.NoArtist))

        val missing = sampleArt(priceTotal = null, artistId = null, selfMade = false, folderId = null, worldId = null, roleIds = emptyList(), title = "")
        assertTrue(missing.matchesGap(Gap.NoPrice))
        assertTrue(missing.matchesGap(Gap.NoArtist))
        assertTrue(missing.matchesGap(Gap.NoFolder))
        assertTrue(missing.matchesGap(Gap.NoWorld))
        assertTrue(missing.matchesGap(Gap.NoRole))
        assertTrue(missing.matchesGap(Gap.NoTitle))

        // Distinct default labels (透明底 + 带背景) are valid; duplicate auto labels trigger NoVerLabel
        val okVers = sampleArt(
            versions = listOf(
                Ver("v1", "b1", "带背景", "png", 10, 10, false, true),
                Ver("v2", "b2", "透明底", "png", 10, 10, true, false),
            )
        )
        assertFalse(okVers.matchesGap(Gap.NoVerLabel))

        val dupVers = sampleArt(
            versions = listOf(
                Ver("v1", "b1", "带背景", "png", 10, 10, false, true),
                Ver("v2", "b2", "带背景 2", "png", 10, 10, false, false),
            )
        )
        assertTrue(dupVers.matchesGap(Gap.NoVerLabel))
    }

    @Test
    fun `taxonomy persists pet flag only when true and reorders worlds safely`() {
        val file = File(tmp.newFolder("meta"), "taxonomy.json")
        val tax = Taxonomy(file)
        val w1 = tax.addWorld("世界一")
        val w2 = tax.addWorld("世界二")
        val r1 = tax.addRole("角色甲", w1.id)

        assertTrue(tax.setPet(r1.id, true))
        val jsonOn = JSONObject(file.readText()).getJSONArray("characters").getJSONObject(0)
        assertTrue(jsonOn.optBoolean("pet", false))

        assertTrue(tax.setPet(r1.id, false))
        val jsonOff = JSONObject(file.readText()).getJSONArray("characters").getJSONObject(0)
        assertFalse(jsonOff.has("pet"))

        tax.reorderWorlds(listOf(w2.id, w1.id))
        val reloaded = Taxonomy(file).load()
        assertEquals(listOf(w2.id, w1.id), reloaded.worlds.map { it.id })
        assertFalse(reloaded.roles.first().pet)
    }

    @Test
    fun `dates parse and sortKey handle date and datetime`() {
        assertFalse(Dates.hasTime("2026-10-01"))
        assertTrue(Dates.hasTime("2026-10-01 09:30:00"))
        assertTrue(Dates.sortKey("2026-10-02") > Dates.sortKey("2026-10-01 23:59:59"))
    }

    @Test
    fun `csvExport writes UTF-8 BOM and escapes commas quotes and newlines`() {
        val vault = tmp.newFolder("vault")
        val tax = Taxonomy(File(vault, "meta/taxonomy.json"))
        val w = tax.addWorld("世界观1")
        val r = tax.addRole("角色1", w.id)
        val artists = ArtistBook.load(vault)
        val tags = TagBook.load(vault)
        val folders = FolderBook.load(vault)
        val f = folders.ensure("文件夹1")!!
        val art = sampleArt(
            title = "含,逗号\"引号\"",
            priceTotal = 0L,
            artistId = null,
            selfMade = true,
            worldId = w.id,
            folderId = f,
            roleIds = listOf(r.id),
        ).copy(note = "第一行\n第二行")
        val csv = CsvExport.exportLedger(vault, listOf(art), tax, VaultNames(artists, tags), folders)
        assertTrue(csv.isFile)
        val content = csv.readText(Charsets.UTF_8)
        assertTrue(content.startsWith("\uFEFF收稿日期,标题,"))
        assertTrue(content.contains("\"含,逗号\"\"引号\"\"\""))
        assertTrue(content.contains("\"第一行\n第二行\""))
        assertTrue(content.contains(",0,自制,是,"))
    }

    @Test
    fun `starred parses from json and filters correctly`() {
        val withStar = parseArt(
            """{"id":"a1","title":"星标稿","versions":[{"id":"v1","blob":"b1","ext":"png","w":10,"h":10,"is_primary":true}],"starred":true}"""
        )!!
        val withoutStar = parseArt(
            """{"id":"a2","title":"普通稿","versions":[{"id":"v2","blob":"b2","ext":"png","w":10,"h":10,"is_primary":true}]}"""
        )!!
        assertTrue(withStar.starred)
        assertFalse(withoutStar.starred)

        val vault = tmp.newFolder("star_vault")
        val tax = Taxonomy(File(vault, "meta/taxonomy.json"))
        val names = VaultNames(ArtistBook.load(vault), TagBook.load(vault))
        val f = Filter(starredOnly = true)
        assertEquals(1, f.condCount)
        val res = f.apply(listOf(withStar, withoutStar), names, tax)
        assertEquals(listOf("a1"), res.map { it.id })
    }

    @Test
    fun `filterPresets round-trips unsorted folder and sanitizes dangling refs`() {
        val vault = tmp.newFolder("preset_vault")
        val tax = Taxonomy(File(vault, "meta/taxonomy.json"))
        val w = tax.addWorld("世界观A")
        val r = tax.addRole("角色A", w.id)
        val tags = TagBook.load(vault)
        tags.ensure("立绘")
        val folders = FolderBook.load(vault)

        val orig = Filter(
            worldId = w.id,
            folderId = UNSORTED_FOLDER,
            roleIds = setOf(r.id, "deleted_role"),
            roleMode = RoleMode.Or,
            tags = setOf("立绘", "deleted_tag"),
            gaps = setOf(Gap.NoPrice),
            query = "关键词",
            priceMin = 50L,
            starredOnly = true,
            kind = KindFilter.All,
        )
        val encoded = FilterPresets.encode(listOf(FilterPreset("测试预设", orig)))
        val decoded = FilterPresets.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(UNSORTED_FOLDER, decoded[0].filter.folderId)
        assertTrue(decoded[0].filter.starredOnly)
        assertEquals(KindFilter.All, decoded[0].filter.kind)

        val clean = FilterPresets.sanitize(decoded[0].filter, tax, tags, folders)
        assertEquals(w.id, clean.worldId)
        assertEquals(UNSORTED_FOLDER, clean.folderId)
        assertEquals(setOf(r.id), clean.roleIds)
        assertEquals(setOf("立绘"), clean.tags)
        assertEquals("世界观A · 未分类", FilterPresets.autoName(clean, tax, tags, folders))
    }

    @Test
    fun `purge protects live and trash refs, isolates and empties only trash`() = runBlocking {
        val vault = tmp.newFolder("purge_vault")
        val artDir = File(vault, "meta/artworks").apply { mkdirs() }
        val trashSlotDir = File(vault, "meta/artworks/.trash").apply { mkdirs() }
        val trashVerDir = File(vault, "meta/artworks/.trash/versions").apply { mkdirs() }
        val blobsDir = File(vault, "blobs").apply { mkdirs() }
        val thumbsDir = File(vault, "thumbs").apply { mkdirs() }

        fun writeBlob(sha: String) {
            val f = File(blobsDir, "${sha.take(2)}/$sha.png")
            f.parentFile?.mkdirs()
            f.writeBytes(byteArrayOf(1, 2, 3, 4))
            File(thumbsDir, "$sha.w640.webp").writeBytes(byteArrayOf(5, 6))
        }

        val liveSha = "aa1111"
        val trashSlotSha = "bb2222"
        val trashVerSha = "cc3333"
        val orphanSha = "dd4444"
        listOf(liveSha, trashSlotSha, trashVerSha, orphanSha).forEach(::writeBlob)

        File(artDir, "a1.json").writeText(
            """{"id":"a1","versions":[{"id":"v1","blob":"$liveSha","ext":"png","w":10,"h":10,"is_primary":true}]}"""
        )
        File(trashSlotDir, "a2.json").writeText(
            """{"id":"a2","versions":[{"id":"v2","blob":"$trashSlotSha","ext":"png","w":10,"h":10,"is_primary":true}]}"""
        )
        File(trashVerDir, "v3.json").writeText(
            """{"type":"version","art_id":"a1","art_title":"t","version":{"id":"v3","blob":"$trashVerSha","ext":"png","w":10,"h":10,"is_primary":false}}"""
        )

        val lib = Library(artDir, thumbsDir, blobsDir, demo = false)
        val r1 = Purge.scan(vault, lib)
        assertEquals(3, r1.referencedCount)
        assertEquals(1, r1.orphanBlobs.size)
        assertEquals("$orphanSha.png", r1.orphanBlobs.first().name)
        assertEquals(1, r1.orphanThumbs.size)

        val iso = Purge.isolate(vault, r1)
        assertEquals(2, iso.succeeded)
        assertEquals(0, iso.failed)
        assertTrue(File(blobsDir, ".trash/dd/$orphanSha.png").isFile)
        assertTrue(File(thumbsDir, ".trash/$orphanSha.w640.webp").isFile)

        val r2 = Purge.scan(vault, lib)
        assertEquals(0, r2.orphanTotalCount)
        assertEquals(1, r2.isolatedBlobs.size)
        assertEquals(1, r2.isolatedThumbs.size)

        val del = Purge.emptyTrash(vault, r2)
        assertEquals(2, del.succeeded)
        assertEquals(0, del.failed)
        assertFalse(File(blobsDir, ".trash/dd/$orphanSha.png").exists())
        // Live & trash-referenced blobs remain untouched
        assertTrue(File(blobsDir, "aa/$liveSha.png").isFile)
        assertTrue(File(blobsDir, "bb/$trashSlotSha.png").isFile)
        assertTrue(File(blobsDir, "cc/$trashVerSha.png").isFile)
    }

    @Test
    fun `folderBook reorder persists user order and preserves system folders`() {
        val vault = tmp.newFolder("folder_order_vault")
        val fb = FolderBook.load(vault)
        fb.ensureSystem()
        val f1 = fb.ensure("甲")!!
        val f2 = fb.ensure("乙")!!
        val f3 = fb.ensure("丙")!!

        assertTrue(fb.reorder(listOf(f3, f1, f2)))
        val reloaded = FolderBook.load(vault)
        val userIds = reloaded.all.filter { it.system == null }.map { it.id }
        val sysKeys = reloaded.all.mapNotNull { it.system }.toSet()
        assertEquals(listOf(f3, f1, f2), userIds)
        assertEquals(setOf("derived", "fill"), sysKeys)
    }

    @Test
    fun `themePreset falls back safely and drives top-level tokens`() {
        assertEquals(ThemePreset.AmberNight, ThemePreset.fromId(null))
        assertEquals(ThemePreset.AmberNight, ThemePreset.fromId("unknown_id"))
        ThemeState.apply(ThemePreset.AmberNight.id)
        assertEquals(ThemePreset.AmberNight.palette.accent, Accent)
        assertEquals(ThemePreset.AmberNight.palette.ink, Ink)
    }

    @Test
    fun `operationJournal lastTouchedArtId falls back to previous art after undo`() = runBlocking {
        val vault = tmp.newFolder("undo_last_vault")
        val f1 = File(vault, "meta/artworks/art1.json").apply {
            parentFile?.mkdirs()
            writeText("""{"id":"art1","title":"旧1","versions":[]}""")
        }
        val f2 = File(vault, "meta/artworks/art2.json").apply {
            writeText("""{"id":"art2","title":"旧2","versions":[]}""")
        }
        OperationJournal.run(vault, "art", "改1") {
            writeJsonAtomic(f1, JSONObject("""{"id":"art1","title":"新1","versions":[]}"""))
        }
        Thread.sleep(5)
        OperationJournal.run(vault, "art", "改2") {
            writeJsonAtomic(f2, JSONObject("""{"id":"art2","title":"新2","versions":[]}"""))
        }
        val valid = setOf("art1", "art2")
        assertEquals("art2", OperationJournal.lastTouchedArtId(vault, valid))

        OperationJournal.undoLatest(vault)
        assertEquals("art1", OperationJournal.lastTouchedArtId(vault, valid))
    }
}
