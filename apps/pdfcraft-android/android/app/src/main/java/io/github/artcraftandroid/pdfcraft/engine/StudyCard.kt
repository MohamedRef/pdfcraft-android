package io.github.artcraftandroid.pdfcraft.engine

/**
 * What Home's card shows for a category chip (#560): pure, so it is tested without Android.
 * A chip is null for All, else a [DocCategory] id.
 */
object StudyCard {
    /** A document is listed only after this much counted time, so a file opened by mistake is not. */
    const val MIN_MS = 60_000L

    /** How many documents the card lists. */
    const val ROWS = 3

    /** The documents to list under [chip], newest first ([docs] are newest first). */
    fun rows(docs: List<DocStudy>, categoryOf: (DocStudy) -> String?, chip: String?, limit: Int = ROWS): List<DocStudy> =
        docs.filter { it.activeMs >= MIN_MS && (chip == null || categoryOf(it) == chip) }.take(limit)

    /**
     * The category chips after All, in the person's order: each category that has a listed
     * document. Study is also there while flashcards are due, which only Study shows.
     */
    fun chips(docs: List<DocStudy>, categoryOf: (DocStudy) -> String?, categories: List<DocCategory>, cardsDue: Boolean): List<String> {
        val used = docs.filter { it.activeMs >= MIN_MS }.mapNotNull(categoryOf).toSet()
        return categories.map { it.id }.filter { it in used || (cardsDue && it == DocCategory.STUDY) }
    }

    /** The chip to show: the remembered one while it is offered, else All. */
    fun chosen(remembered: String?, chips: List<String>): String? = remembered?.takeIf { it in chips }

    /** Whether [chip] is the study view (week time, bars, flashcards), else "Continue reading". */
    fun studies(chip: String?): Boolean = chip == DocCategory.STUDY

    /** The URIs whose time counts toward the Study week: Study documents only. */
    fun studyUris(docs: List<DocStudy>, categoryOf: (DocStudy) -> String?): Set<String> =
        docs.filter { categoryOf(it) == DocCategory.STUDY }.map { it.uri }.toSet()

    /** This week's active ms per category id ("" for none), for Settings. */
    fun weekByCategory(docs: List<DocStudy>, categoryOf: (DocStudy) -> String?, weekDays: Set<Long>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (d in docs) {
            val ms = d.days.filterKeys { it in weekDays }.values.sum()
            if (ms > 0) out[categoryOf(d).orEmpty()] = (out[categoryOf(d).orEmpty()] ?: 0) + ms
        }
        return out
    }
}
