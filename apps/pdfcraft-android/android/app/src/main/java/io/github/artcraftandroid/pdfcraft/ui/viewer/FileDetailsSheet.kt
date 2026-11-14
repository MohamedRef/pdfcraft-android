package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.DocumentProperties
import io.github.artcraftandroid.pdfcraft.core.PageInfo
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SourceProperties(
    val name: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val created: Long? = null,
    val modified: Long? = null,
    val location: String? = null,
)

private data class Detail(val label: String, val value: String)
private data class DetailSection(val title: String, val rows: List<Detail>)

/** The source URI is authoritative. Never present a private working-copy path or its timestamps as the original's. */
private fun sourceProperties(context: Context, uri: Uri?): SourceProperties {
    if (uri == null) return SourceProperties()
    if (uri.scheme == "file") {
        val path = uri.path ?: return SourceProperties(location = uri.toString())
        val file = File(path)
        val attributes = if (Build.VERSION.SDK_INT >= 26) runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() else null
        return SourceProperties(
            name = file.name,
            mimeType = "application/pdf",
            size = file.takeIf { it.isFile }?.length(),
            created = attributes?.creationTime()?.toMillis()?.takeIf { it > 0L },
            modified = attributes?.lastModifiedTime()?.toMillis()?.takeIf { it > 0L }
                ?: file.lastModified().takeIf { it > 0L },
            location = file.absolutePath,
        )
    }
    if (uri.scheme != "content") return SourceProperties(location = uri.toString())

    val resolver = context.contentResolver
    var name: String? = null
    var size: Long? = null
    var created: Long? = null
    var modified: Long? = null
    // OpenableColumns and DocumentsContract have no standard creation timestamp. Read provider-specific
    // creation columns only when supplied; DATE_ADDED is not the file's creation date.
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.optionalString(OpenableColumns.DISPLAY_NAME)
                size = c.optionalLong(OpenableColumns.SIZE)?.takeIf { it >= 0L }
                modified = c.optionalLong(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0L }
                created = listOf("date_created", "creation_time", "created_at")
                    .firstNotNullOfOrNull { c.optionalLong(it)?.takeIf { time -> time > 0L } }
                    ?.let(::providerTimestampMillis)
            }
        }
    }
    return SourceProperties(
        name = name,
        mimeType = runCatching { resolver.getType(uri) }.getOrNull(),
        size = size,
        created = created,
        modified = modified,
        location = uri.toString(),
    )
}

private fun Cursor.optionalString(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else runCatching { getString(index) }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun Cursor.optionalLong(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else runCatching { getLong(index) }.getOrNull()
}

/** Some third-party providers expose timestamps in seconds, others in milliseconds. */
internal fun providerTimestampMillis(value: Long): Long? = when {
    value <= 0L -> null
    value < 100_000_000_000L -> value * 1000L
    else -> value
}

private fun displayDate(value: Long?): String? = value?.takeIf { it > 0L }
    ?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }

/** XMP may declare dates without /Info dates; either XML elements or RDF attributes are common. */
internal fun xmpDate(packet: String?, key: String): String? {
    if (packet.isNullOrEmpty() || key !in setOf("CreateDate", "ModifyDate")) return null
    val name = Regex.escape(key)
    val prefix = "(?:[A-Za-z_][\\w.-]*:)?"
    val element = Regex("<$prefix$name(?:\\s[^>]*)?>\\s*([^<>]{1,96})\\s*</").find(packet)?.groupValues?.get(1)
    val attribute = Regex("$prefix$name\\s*=\\s*[\"']([^\"']{1,96})[\"']").find(packet)?.groupValues?.get(1)
    return (element ?: attribute)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun points(value: Float): String = if (abs(value - value.toInt()) < 0.05f) value.toInt().toString()
    else String.format(Locale.getDefault(), "%.1f", value)

/** The engine's page dimensions are already in display orientation (after /Rotate). */
internal data class PageGeometry(val firstSize: String?, val mixedSizes: Boolean, val orientation: Int?)

internal fun pageGeometry(pages: List<PageInfo>): PageGeometry {
    val first = pages.firstOrNull() ?: return PageGeometry(null, false, null)
    if (!first.width.isFinite() || !first.height.isFinite()) return PageGeometry(null, false, null)
    val firstSize = "${points(first.width)} × ${points(first.height)} pt"
    val firstOrientation = orientation(first.width, first.height)
    var mixedSize = false
    var mixedOrientation = false
    for (page in pages) {
        if (!page.width.isFinite() || !page.height.isFinite()) {
            mixedSize = true
            mixedOrientation = true
            continue
        }
        if (abs(page.width - first.width) > 0.5f || abs(page.height - first.height) > 0.5f) mixedSize = true
        if (orientation(page.width, page.height) != firstOrientation) mixedOrientation = true
    }
    // A null orientation means some pages use a different orientation.
    return PageGeometry(firstSize, mixedSize, if (mixedOrientation) null else firstOrientation)
}

private fun orientation(width: Float, height: Float): Int = when {
    width > height + 0.5f -> 1
    height > width + 0.5f -> 2
    else -> 3
}

@Composable
fun FileDetailsSheet(vm: AppViewModel, doc: OpenDocument) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ctl = doc.controller
    var source by remember(doc) { mutableStateOf<SourceProperties?>(null) }
    var properties by remember(doc.id, doc.info.generation) { mutableStateOf<DocumentProperties?>(null) }
    var propertiesLoaded by remember(doc.id, doc.info.generation) { mutableStateOf(false) }

    LaunchedEffect(doc.source) {
        source = withContext(Dispatchers.IO) { sourceProperties(context, doc.source) }
    }
    LaunchedEffect(doc.id, doc.info.generation) {
        // UniFFI reads the parsed PDF's live properties. This is local, read-only and off the UI thread.
        properties = runCatching { vm.engine?.command { fileProperties(doc.id) } }.getOrNull()
        propertiesLoaded = true
    }

    val sections = remember(doc.info, source, properties, context.resources.configuration) {
        details(context, doc, source, properties)
    }
    val copyText = remember(sections) {
        sections.joinToString("\n\n") { section ->
            section.title + "\n" + section.rows.joinToString("\n") { "${it.label}: ${it.value}" }
        }
    }
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.92f, fill = true) {
        SheetBackHeader(stringResource(R.string.file_details_title), onBack = { ctl.sheet = ctl.fileDetailsBack })
        if (source == null || !propertiesLoaded) {
            Text(stringResource(R.string.file_details_loading), color = pdfColors.textSecondary, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            sections.forEach { section ->
                item {
                    Text(section.title, color = pdfColors.accent, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 7.dp))
                }
                items(section.rows) { row ->
                    DetailValue(row, onCopy = { clipboard.setText(AnnotatedString(row.value)); vm.tell(context.getString(R.string.file_details_copied)) })
                }
            }
        }
        SheetDivider()
        FileDetailsTools(vm, doc, properties)
        SheetRow(LucideIcons.Layers, stringResource(R.string.backdrops_title), { ctl.sheet = ViewerSheet.Backdrops }, chevron = true)
        SheetRow(LucideIcons.SquareDashed, stringResource(R.string.redact_audit_title), { vm.auditRedactions(doc) })
        SheetRow(LucideIcons.Copy, stringResource(R.string.file_details_copy_all), {
            clipboard.setText(AnnotatedString(copyText))
            vm.tell(context.getString(R.string.file_details_copied))
        }, enabled = source != null && propertiesLoaded)
    }
}

@Composable
private fun DetailValue(row: Detail, onCopy: () -> Unit) {
    val colors = pdfColors
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(row.label, color = colors.textSecondary, fontSize = 12.sp)
            SelectionContainer {
                // Very long metadata should not force Compose to lay out megabytes of text.
                val preview = if (row.value.length > 2_048) row.value.take(2_048) + "…" else row.value
                Text(preview, color = colors.text, fontSize = 14.sp, lineHeight = 19.sp,
                    maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onCopy, modifier = Modifier.size(40.dp)) {
            Icon(LucideIcons.Copy, contentDescription = stringResource(R.string.file_details_copy_value),
                tint = colors.icon, modifier = Modifier.size(18.dp))
        }
    }
}

private fun details(context: Context, doc: OpenDocument, source: SourceProperties?, props: DocumentProperties?): List<DetailSection> {
    fun label(id: Int) = context.getString(id)
    val unavailable = label(R.string.file_details_unavailable)
    fun present(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() } ?: unavailable
    fun yesNo(value: Boolean): String = if (doc.info.preparing) label(R.string.document_preparing) else label(if (value) R.string.file_details_allowed else R.string.file_details_not_allowed)
    val info = doc.info
    val geometry = pageGeometry(info.pages)
    val sizes = geometry.firstSize?.let {
        if (geometry.mixedSizes) context.getString(R.string.file_details_mixed_sizes, it) else it
    }
    val facing = if (geometry.firstSize == null) null else when (geometry.orientation) {
        1 -> label(R.string.file_details_landscape)
        2 -> label(R.string.file_details_portrait)
        3 -> label(R.string.file_details_square)
        else -> label(R.string.file_details_mixed)
    }
    val docSection = DetailSection(label(R.string.file_details_file_section), listOf(
        Detail(label(R.string.file_details_name), present(source?.name ?: doc.name)),
        Detail(label(R.string.file_details_type), present(source?.mimeType ?: "application/pdf")),
        Detail(label(R.string.file_details_size), formatSize(source?.size ?: info.fileSize.toLong())),
        Detail(label(R.string.file_details_location), present(source?.location ?: doc.source?.toString())),
        Detail(label(R.string.file_details_fs_created), present(displayDate(source?.created))),
        Detail(label(R.string.file_details_fs_modified), present(displayDate(source?.modified))),
    ))
    val pdfMeta = DetailSection(label(R.string.file_details_metadata_section), listOf(
        Detail(label(R.string.file_details_pdf_title), present(info.title)),
        Detail(label(R.string.file_details_author), present(info.author)),
        Detail(label(R.string.file_details_subject), present(info.subject)),
        Detail(label(R.string.file_details_keywords), present(info.keywords)),
        Detail(label(R.string.file_details_creator), present(info.creator)),
        Detail(label(R.string.file_details_producer), present(info.producer)),
        Detail(label(R.string.file_details_pdf_created), present(pdfDateLabel(props?.creationDate) ?: xmpDate(props?.xmpPacket, "CreateDate"))),
        Detail(label(R.string.file_details_pdf_modified), present(pdfDateLabel(props?.modificationDate) ?: xmpDate(props?.xmpPacket, "ModifyDate"))),
    ))
    val document = DetailSection(label(R.string.file_details_document_section), listOf(
        Detail(label(R.string.file_details_version), present(info.pdfVersion)),
        Detail(label(R.string.file_details_pages), info.pages.size.toString()),
        Detail(label(R.string.file_details_page_size), present(sizes)),
        Detail(label(R.string.file_details_orientation), present(facing)),
        Detail(label(R.string.file_details_encryption), label(if (info.encrypted) R.string.file_details_encrypted else R.string.file_details_not_encrypted)),
        Detail(label(R.string.file_details_printing), yesNo(info.allowsPrinting)),
        Detail(label(R.string.file_details_copying), yesNo(info.allowsCopying)),
        Detail(label(R.string.file_details_editing), props?.allowsModification?.let { yesNo(it) } ?: unavailable),
        Detail(label(R.string.file_details_annotating), yesNo(info.allowsAnnotation)),
        Detail(label(R.string.file_details_form_filling), yesNo(info.allowsFormFilling)),
    ))
    val advanced = buildList {
        add(Detail(label(R.string.file_details_attachments), if (info.preparing) label(R.string.document_preparing) else info.attachments.toString()))
        add(Detail(label(R.string.file_details_signature), label(if (info.preparing) R.string.document_preparing else if (info.signed) R.string.file_details_signature_present else R.string.file_details_signature_none)))
        if (props == null) {
            add(Detail(label(R.string.file_details_custom_fields), unavailable))
        } else if (props.customMetadata.isEmpty()) {
            add(Detail(label(R.string.file_details_custom_fields), label(R.string.file_details_none)))
        } else {
            props.customMetadata.forEach { add(Detail(it.name, present(it.value))) }
        }
        add(Detail(label(R.string.file_details_xmp), present(props?.xmpPacket)))
    }
    return listOf(docSection, pdfMeta, document, DetailSection(label(R.string.file_details_advanced_section), advanced))
}
