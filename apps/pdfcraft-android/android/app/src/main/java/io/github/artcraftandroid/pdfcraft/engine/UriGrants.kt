package io.github.artcraftandroid.pdfcraft.engine

/** A lasting URI grant, as [android.content.UriPermission] describes it. */
data class UriGrant(val uri: String, val tree: Boolean, val persistedAt: Long)

/**
 * The document grants to give back (#108): those of documents no longer in [recent], and the
 * oldest ones beyond [cap]. Folder (tree) grants and the [open] document's grant always stay.
 */
fun documentGrantsToRelease(grants: List<UriGrant>, recent: Set<String>, open: String?, cap: Int): Set<String> {
    val documents = grants.filter { !it.tree && it.uri != open }
    val release = documents.filter { it.uri !in recent }.mapTo(LinkedHashSet()) { it.uri }
    // The open document counts toward the cap; the newest grants are the ones worth keeping.
    val room = (cap - if (open != null && grants.any { it.uri == open && !it.tree }) 1 else 0).coerceAtLeast(0)
    documents.filter { it.uri in recent }.sortedByDescending { it.persistedAt }.drop(room).mapTo(release) { it.uri }
    return release
}
