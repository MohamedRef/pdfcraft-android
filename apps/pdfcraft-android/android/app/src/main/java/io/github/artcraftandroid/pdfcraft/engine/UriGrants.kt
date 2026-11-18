package io.github.artcraftandroid.pdfcraft.engine

/** A lasting URI grant, as [android.content.UriPermission] describes it. */
data class UriGrant(val uri: String, val tree: Boolean, val persistedAt: Long)

/**
 * How long a document grant stays after it was taken, listed in Recent or not (#705): the file
 * may still be opening. Recent and the tabs list it only once it is open, and Home's clean-up
 * runs meanwhile (the app resumes as the picker closes). The copies' clean-up waits as long.
 */
const val GRANT_SETTLING_MS = 10 * 60 * 1000L

/**
 * The document grants to give back (#108): those of documents no longer in [recent], and the
 * oldest ones beyond [cap]. Folder (tree) grants and the [open] document's grant always stay, and
 * so does one taken less than [GRANT_SETTLING_MS] before [now] (#705), which counts toward the cap.
 */
fun documentGrantsToRelease(
    grants: List<UriGrant>,
    recent: Set<String>,
    open: String?,
    cap: Int,
    now: Long = System.currentTimeMillis(),
): Set<String> {
    val documents = grants.filter { !it.tree && it.uri != open }
    val (kept, unlisted) = documents.partition { it.uri in recent || it.persistedAt > now - GRANT_SETTLING_MS }
    val release = unlisted.mapTo(LinkedHashSet()) { it.uri }
    // The open document counts toward the cap; the newest grants are the ones worth keeping.
    val room = (cap - if (open != null && grants.any { it.uri == open && !it.tree }) 1 else 0).coerceAtLeast(0)
    kept.sortedByDescending { it.persistedAt }.drop(room).mapTo(release) { it.uri }
    return release
}
