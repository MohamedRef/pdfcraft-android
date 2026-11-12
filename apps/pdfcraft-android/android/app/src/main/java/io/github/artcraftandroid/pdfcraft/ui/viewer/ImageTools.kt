package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.copyIntoCache
import io.github.artcraftandroid.pdfcraft.ui.copyOut
import io.github.artcraftandroid.pdfcraft.ui.mimeFor
import java.io.File
import kotlinx.coroutines.launch

/**
 * Edit PDF's picture tools beyond move, resize, quarter turns and delete (#161): crop, flip, turn
 * by any angle, stacking order, replace from a photo, save as a file, ungroup Form artwork and
 * remove a crop. Each change is one engine `image_edit`, so one undo step.
 */
class ImageEditState {
    /** The crop being framed (the part to keep, page points), or null when not cropping. */
    var crop by mutableStateOf<FloatArray?>(null)

    /** The crop frame keeps this width:height, or null for a free crop. */
    var cropRatio by mutableStateOf<Float?>(null)

    /** The picture whose Rotate dialog is open. */
    var rotating by mutableStateOf<EditImage?>(null)
}

/** The engine's `image_edit` actions for the stacking-order menu, in menu order. */
enum class Arrange(val action: String, val label: Int) {
    Front("bring_to_front", R.string.edit_image_bring_to_front),
    Forward("bring_forward", R.string.edit_image_bring_forward),
    Backward("send_backward", R.string.edit_image_send_backward),
    Back("send_to_back", R.string.edit_image_send_to_back),
}

/**
 * The number [number] (1-based, of [count] on the page) takes after [arrange]: page_images numbers
 * pictures in drawing order, so a picture brought to the front is drawn, and numbered, last.
 */
fun numberAfterArrange(arrange: Arrange, number: Int, count: Int): Int = when (arrange) {
    Arrange.Front -> count
    Arrange.Back -> 1
    Arrange.Forward -> (number + 1).coerceAtMost(count)
    Arrange.Backward -> (number - 1).coerceAtLeast(1)
}.coerceAtLeast(1)

/** The degrees the Rotate slider stops at: every 15°, from -180 to 180. */
fun snapDegrees(raw: Float): Int = ((raw / 15f).let { kotlin.math.round(it) } * 15f).toInt().coerceIn(-180, 180)

/**
 * The largest [ratio] (width:height) box inside [box] (x0, y0, x1, y1), centred on it: an aspect
 * preset applied to the crop frame.
 */
fun fitRatio(box: FloatArray, ratio: Float): FloatArray {
    val w = box[2] - box[0]
    val h = box[3] - box[1]
    if (w <= 0f || h <= 0f || ratio <= 0f) return box.copyOf()
    val (nw, nh) = if (w / h > ratio) h * ratio to h else w to w / ratio
    val cx = (box[0] + box[2]) / 2f
    val cy = (box[1] + box[3]) / 2f
    return floatArrayOf(cx - nw / 2f, cy - nh / 2f, cx + nw / 2f, cy + nh / 2f)
}

/**
 * Move the crop frame's edges by ([dx], [dy]) points: [edges] says which (left, top, right,
 * bottom) follow the drag. The frame stays inside [limit] (the picture's full box) and at least
 * [min] points each way; with [ratio] it keeps that shape, the width leading.
 */
fun dragCrop(crop: FloatArray, edges: BooleanArray, dx: Float, dy: Float, limit: FloatArray, ratio: Float?, min: Float = 8f): FloatArray {
    val r = crop.copyOf()
    if (edges[0]) r[0] = (r[0] + dx).coerceIn(limit[0], r[2] - min)
    if (edges[2]) r[2] = (r[2] + dx).coerceIn(r[0] + min, limit[2])
    if (edges[1]) r[1] = (r[1] + dy).coerceIn(limit[1], r[3] - min)
    if (edges[3]) r[3] = (r[3] + dy).coerceIn(r[1] + min, limit[3])
    if (ratio != null && ratio > 0f) {
        val h = (r[2] - r[0]) / ratio
        if (edges[1] && !edges[3]) r[1] = (r[3] - h).coerceAtLeast(limit[1]) else r[3] = (r[1] + h).coerceAtMost(limit[3])
        // the height ran into the picture's edge: take the width back to match it
        val w = (r[3] - r[1]) * ratio
        if (edges[0] && !edges[2]) r[0] = r[2] - w else r[2] = r[0] + w
    }
    return r
}

/** Read picture [number] on [page] again (its box, crop and kind change with an edit). */
suspend fun AppViewModel.readImage(doc: OpenDocument, page: Int, number: Int): EditImage? {
    val eng = engine ?: return null
    val r = runCatching { eng.tool("page_images", mapOf("doc" to doc.id, "page" to page + 1)) }.getOrNull() ?: return null
    val arr = r.optJSONArray("images") ?: return null
    val o = arr.optJSONObject(number - 1) ?: return null
    return editImageFrom(page, o)
}

/** An [EditImage] from one entry of page_images. */
fun editImageFrom(page: Int, o: org.json.JSONObject): EditImage? {
    val full = o.optJSONArray("rect") ?: return null
    val visible = o.optJSONArray("visible_rect")
    return EditImage(
        page = page, number = o.optInt("image"), rect = FloatArray(4) { k -> full.optDouble(k).toFloat() },
        kind = o.optString("kind", "image"), hasCrop = o.optBoolean("has_editor_crop"),
        visible = visible?.let { v -> FloatArray(4) { k -> v.optDouble(k).toFloat() } },
    )
}

/** Run `image_edit` [action] on [img], then select it again as [number] (null: leave the selection). */
fun AppViewModel.imageAction(doc: OpenDocument, img: EditImage, action: String, extra: Map<String, Any?> = emptyMap(), number: Int? = img.number) {
    runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to action) + extra) {
        if (number == null) {
            editor.editImage = null
        } else {
            viewModelScope.launch { editor.editImage = readImage(doc, img.page, number) }
        }
    }
}

/** Start framing a crop of [img], from what it shows now. */
fun AppViewModel.startCrop(img: EditImage) {
    editor.imageEdit.cropRatio = null
    editor.imageEdit.crop = (img.visible ?: img.rect).copyOf()
}

fun AppViewModel.applyCrop(doc: OpenDocument, img: EditImage) {
    val c = editor.imageEdit.crop ?: return
    editor.imageEdit.crop = null
    imageAction(doc, img, "crop", mapOf("rect" to c.map { it.toDouble() }))
}

fun AppViewModel.arrangeImage(doc: OpenDocument, img: EditImage, arrange: Arrange) {
    viewModelScope.launch {
        val count = engine?.let { eng ->
            runCatching { eng.tool("page_images", mapOf("doc" to doc.id, "page" to img.page + 1)).optInt("count") }.getOrNull()
        } ?: return@launch
        imageAction(doc, img, arrange.action, number = numberAfterArrange(arrange, img.number, count))
    }
}

/** Replace: pick a photo, then draw it where [img] is. Grouped artwork has no picture to replace. */
fun AppViewModel.replaceImage(doc: OpenDocument, img: EditImage) {
    if (img.kind == "form") {
        tell(text(R.string.edit_image_form_no_replace))
        return
    }
    requests.image { uri ->
        if (uri == null) return@image
        viewModelScope.launch {
            val f = try {
                copyIntoCache(getApplication<Application>(), uri, "replace", "-" + files.displayName(uri))
            } catch (e: Exception) {
                tell(text(R.string.msg_picture_unreadable))
                return@launch
            }
            if (f.name.lowercase().endsWith(".gif")) {
                f.delete()
                tell(text(R.string.msg_gif_not_supported))
                return@launch
            }
            imageAction(doc, img, "replace", mapOf("path" to f.absolutePath))
            // the engine has read the file once the edit is in; it isn't needed after
            kotlinx.coroutines.delay(2_000)
            f.delete()
        }
    }
}

/**
 * Save image: the engine writes the picture (JPEG as it is, others as PNG) into the cache, then
 * the user picks where it goes. Grouped artwork is refused by the engine, whose reason is shown.
 */
fun AppViewModel.saveImage(doc: OpenDocument, img: EditImage) {
    val eng = engine ?: return
    val ctx = getApplication<Application>()
    viewModelScope.launch {
        val base = File(ctx.cacheDir, "out-image-${System.nanoTime()}")
        val written = try {
            File(eng.tool("image_save", mapOf("doc" to doc.id, "page" to img.page + 1, "image" to img.number, "path" to base.absolutePath)).getString("path"))
        } catch (e: Exception) {
            tell(e.userMessage)
            return@launch
        }
        val name = "${doc.name.removeSuffix(".pdf")}-p${img.page + 1}-${img.number}.${written.extension}"
        requests.create(name, mimeFor(written)) { uri ->
            viewModelScope.launch {
                try {
                    if (uri != null) {
                        copyOut(ctx, written, uri)
                        tell(text(R.string.edit_image_saved))
                    }
                } catch (e: Exception) {
                    tell(e.userMessage)
                } finally {
                    written.delete()
                }
            }
        }
    }
}

/** Ungroup Form artwork: its text becomes editable paragraphs, so the page's paragraphs are read again. */
fun AppViewModel.ungroupImage(doc: OpenDocument, img: EditImage) {
    runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "ungroup")) {
        editor.editImage = null
        editor.paragraphs.remove(img.page)
        viewModelScope.launch { editor.loadParagraphs(doc, img.page) }
        tell(text(R.string.edit_image_ungrouped))
    }
}
