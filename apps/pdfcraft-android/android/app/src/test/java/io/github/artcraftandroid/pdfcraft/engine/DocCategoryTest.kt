package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Document categories and Home's card (#560). */
class DocCategoryTest {
    private val day = LocalDate.of(2026, 11, 14)
    private val noon = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000

    private fun <T> withDir(f: (File) -> T): T {
        val dir = Files.createTempDirectory("cat").toFile()
        try { return f(dir) } finally { dir.deleteRecursively() }
    }

    @Test
    fun aLibraryFromBeforeCategoriesStartsWithTheBuiltInOnes() {
        val old = LibraryData.fromJson(JSONObject("""{"collections":[],"items":[{"uri":"content://a","name":"A.pdf"}]}"""))
        assertEquals(listOf("study", "work", "personal"), old.categories.map { it.id })
        assertTrue(old.categories.all { it.name == null && it.builtIn })
        assertNull(old.items.single().category)
    }

    @Test
    fun categoriesRoundTripAndAnUnknownCategoryIsDropped() = withDir { dir ->
        val lib = Library(File(dir, "library.json"))
        lib.addCategory("  مذاكرة   الطب ", id = "c1")
        lib.renameCategory("work", "Job")
        lib.setCategory("content://a", "A.pdf", "/storage/emulated/0/A.pdf", "c1", 5)
        val d = Library(File(dir, "library.json")).load()
        assertEquals(listOf("study", "work", "personal", "c1"), d.categories.map { it.id })
        // a custom name is kept as typed (right-to-left included), spaces tidied
        assertEquals("مذاكرة الطب", d.category("c1")!!.name)
        assertEquals("Job", d.category("work")!!.name)
        assertEquals("custom", d.category("c1")!!.diagWord)
        assertEquals("work", d.category("work")!!.diagWord)
        assertEquals("c1", d.item("content://a")!!.category)
        val broken = LibraryData.fromJson(d.toJson().put("categories", org.json.JSONArray().put(JSONObject().put("id", "study"))))
        assertNull(broken.item("content://a")!!.category)
    }

    @Test
    fun aFileGetsItsCategoryWithoutBeingAddedByHandAndKeepsItByAnyRoute() = withDir { dir ->
        val lib = Library(File(dir, "library.json"))
        val path = "/storage/emulated/0/Download/Anatomy.pdf"
        lib.setCategory("content://picker/1", "Anatomy.pdf", path, DocCategory.STUDY, 7)
        val d = lib.load()
        assertEquals(1, d.items.size)
        assertEquals(DocCategory.STUDY, d.categoryOf("content://picker/1", null))
        // the same file opened from On device: another address, the same path
        assertEquals(DocCategory.STUDY, d.categoryOf("content://media/external/file/9", path))
        assertNull(d.categoryOf("content://media/external/file/10", "/storage/emulated/0/Other.pdf"))
        // set again from the other route: the one item changes, no second one
        lib.setCategory("content://media/external/file/9", "Anatomy.pdf", path, DocCategory.WORK, 8)
        assertEquals(listOf(DocCategory.WORK), lib.load().items.map { it.category })
        // taking it off keeps the item (it may have been added by hand)
        lib.setCategory("content://picker/1", "Anatomy.pdf", path, null, 9)
        assertEquals(1, lib.load().items.size)
        assertNull(lib.load().categoryOf("content://picker/1", path))
        // none for a file not in the library adds nothing
        lib.setCategory("content://x", "X.pdf", null, null, 9)
        assertEquals(1, lib.load().items.size)
    }

    @Test
    fun deletingAndMovingCategories() = withDir { dir ->
        val lib = Library(File(dir, "library.json"))
        lib.setCategory("content://a", "A.pdf", null, DocCategory.WORK, 1)
        lib.moveCategory(DocCategory.PERSONAL, -5)
        assertEquals(listOf("personal", "study", "work"), lib.load().categories.map { it.id })
        lib.moveCategory(DocCategory.PERSONAL, 1)
        assertEquals(listOf("study", "personal", "work"), lib.load().categories.map { it.id })
        lib.deleteCategory(DocCategory.WORK)
        assertEquals(listOf("study", "personal"), lib.load().categories.map { it.id })
        assertNull(lib.load().item("content://a")!!.category)
        // a blank name is refused
        lib.addCategory("   ")
        assertEquals(2, lib.load().categories.size)
    }

    private fun doc(uri: String, ms: Long, at: Long) = DocStudy(uri, "$uri.pdf", activeMs = ms, lastStudied = at, days = mapOf(day.toEpochDay() to ms))

    @Test
    fun threeDocumentsOneInStudyOneInWorkOneWithNone() {
        val docs = listOf(doc("s", 5 * 60_000, 3), doc("w", 4 * 60_000, 2), doc("n", 3 * 60_000, 1))
        val cats = mapOf("s" to DocCategory.STUDY, "w" to DocCategory.WORK)
        val of = { d: DocStudy -> cats[d.uri] }
        assertEquals(listOf("s"), StudyCard.rows(docs, of, DocCategory.STUDY).map { it.uri })
        assertEquals(listOf("s", "w", "n"), StudyCard.rows(docs, of, null).map { it.uri })
        assertEquals(listOf("w"), StudyCard.rows(docs, of, DocCategory.WORK).map { it.uri })
        // the Study week is that document's time alone; no category is not counted in it
        assertEquals(setOf("s"), StudyCard.studyUris(docs, of))
        withDir { dir ->
            val stats = StudyStats(File(dir, "study.json")) { ZoneOffset.UTC }
            for (d in docs) stats.record(d.uri, d.name, 10, setOf(0), 0, d.activeMs, noon)
            assertEquals(5 * 60_000L, stats.week(day, StudyCard.studyUris(docs, of)).sum())
            assertEquals(12 * 60_000L, stats.week(day).sum())
        }
        // chips: the categories in use, in the person's order; Personal has none
        assertEquals(listOf("study", "work"), StudyCard.chips(docs, of, DocCategory.DEFAULTS, cardsDue = false))
        assertTrue(StudyCard.studies(DocCategory.STUDY))
        assertFalse(StudyCard.studies(null))
        assertFalse(StudyCard.studies(DocCategory.WORK))
    }

    @Test
    fun changingACategoryMovesItsTimeWithoutLosingIt() {
        val docs = listOf(doc("a", 10 * 60_000, 2), doc("b", 2 * 60_000, 1))
        var cats = mapOf("a" to DocCategory.STUDY)
        val byCat1 = StudyCard.weekByCategory(docs, { cats[it.uri] }, setOf(day.toEpochDay()))
        assertEquals(mapOf("study" to 600_000L, "" to 120_000L), byCat1)
        cats = mapOf("a" to DocCategory.WORK)
        val byCat2 = StudyCard.weekByCategory(docs, { cats[it.uri] }, setOf(day.toEpochDay()))
        assertEquals(mapOf("work" to 600_000L, "" to 120_000L), byCat2)
        assertEquals(byCat1.values.sum(), byCat2.values.sum())
        assertEquals(emptySet<String>(), StudyCard.studyUris(docs) { cats[it.uri] })
    }

    @Test
    fun aRowNeedsAMinuteAndTheRememberedChipFallsBackToAll() {
        val docs = listOf(doc("quick", 59_000, 3), doc("read", 60_000, 2))
        assertEquals(listOf("read"), StudyCard.rows(docs, { null }, null).map { it.uri })
        assertEquals(emptyList<String>(), StudyCard.chips(listOf(doc("quick", 59_000, 1)), { DocCategory.WORK }, DocCategory.DEFAULTS, false))
        // Study is offered while flashcards are due, as only Study shows them
        assertEquals(listOf("study"), StudyCard.chips(emptyList(), { null }, DocCategory.DEFAULTS, cardsDue = true))
        assertNull(StudyCard.chosen("work", listOf("study")))
        assertEquals("study", StudyCard.chosen("study", listOf("study")))
        assertNull(StudyCard.chosen(null, listOf("study")))
    }

    @Test
    fun theSameFileOpenedByTwoRoutesIsOneRow() = withDir { dir ->
        val stats = StudyStats(File(dir, "study.json")) { ZoneOffset.UTC }
        val path = "/storage/emulated/0/Download/Pharmacology lecture notes.pdf"
        // before #560: two records, one per address (as in the owner's screenshot)
        stats.record("content://picker/1", "Pharmacology lecture notes.pdf", 40, setOf(0), 0, 20_000, noon)
        stats.record("content://media/9", "Pharmacology lecture notes.pdf", 40, setOf(12), 12, 60_000, noon + 1000)
        stats.record("content://other", "Other.pdf", 5, setOf(0), 0, 90_000, noon + 500)
        assertEquals(3, stats.all().size)
        val paths = mapOf("content://picker/1" to path, "content://media/9" to path)
        assertEquals(1, stats.mergeSameFiles { paths[it] })
        val all = stats.all()
        assertEquals(2, all.size)
        val one = all.first { it.path == path }
        // the newer route's place, both routes' time and pages
        assertEquals("content://media/9", one.uri)
        assertEquals(12, one.lastPage)
        assertEquals(80_000L, one.activeMs)
        assertEquals(setOf(0, 12), one.pagesSeen)
        assertEquals(80_000L, one.days[day.toEpochDay()])
        assertEquals("", all.first { it.uri == "content://other" }.path)
        // looked up once: a second pass changes nothing
        assertEquals(0, stats.mergeSameFiles { error("not asked again") })
        // a later open by the first route again joins the same record
        stats.record("content://picker/1", "Pharmacology lecture notes.pdf", 40, setOf(3), 3, 30_000, noon + 5000, path = path)
        val after = stats.all()
        assertEquals(2, after.size)
        assertEquals("content://picker/1", after.first().uri)
        assertEquals(110_000L, after.first().activeMs)
        assertEquals(3, after.first().lastPage)
        // and the path survives a reload
        assertEquals(path, StudyStats(File(dir, "study.json")).get("content://picker/1")!!.path)
    }

    @Test
    fun diagWordsNeverCarryATypedName() {
        assertEquals("none", DocCategory.diagWord(null))
        assertEquals("study", DocCategory.diagWord("study"))
        assertEquals("custom", DocCategory.diagWord("a1b2c3"))
    }
}
