// Images for a terminal's program, from the phone: picked, pasted or offered
// by the keyboard. What agents read (PNG, JPEG, GIF, WebP) goes as it is;
// anything else (a camera's HEIC), or anything big, as a JPEG small enough
// to send quickly. herdr.core's `put` copies it beside the terminal.

package dev.herdr.mobile.session

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import herdr.core.PUT_LIMIT
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** An image ready to put: its name (its extension saying what it is) and bytes. */
class Image(val name: String, val bytes: ByteArray)

/** Sent as they are, up to [KEEP_BYTES]: what agents read. */
private val KEPT = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp")

/** Bigger than this is made smaller: a phone's link is slow, and agents shrink images anyway. */
private const val KEEP_BYTES = 4 shl 20

/** The long side an image is made smaller to. */
private const val LONG_SIDE = 2048

/** Reads and readies `uri` (blocking: call off the main thread). */
fun readImage(resolver: ContentResolver, uri: Uri, now: Long = System.currentTimeMillis()): Image {
    val type = resolver.getType(uri)
    val given = displayName(resolver, uri)
    val ext = KEPT[type]
    if (ext != null) {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the image")
        if (bytes.size <= KEEP_BYTES) return Image(named(given, ext, now), bytes)
    }
    return Image(named(given, "jpg", now), jpeg(resolver, uri))
}

/** `given` with `ext` as its extension; one made up from the time when there's none. */
internal fun named(given: String?, ext: String, now: Long): String {
    val stem = given?.substringBeforeLast('.')?.replace(Regex("[/\\\\\u0000]"), "_")?.ifBlank { null } ?: "image-$now"
    return "$stem.$ext"
}

private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** The image as a JPEG, its long side at most [LONG_SIDE], under what one put takes. */
private fun jpeg(resolver: ContentResolver, uri: Uri): ByteArray {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
        val long = max(info.size.width, info.size.height)
        if (long > LONG_SIDE) {
            val scale = LONG_SIDE.toFloat() / long
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        // Software: compress() reads its pixels.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    try {
        for (quality in listOf(88, 75, 60)) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= PUT_LIMIT) return out.toByteArray()
        }
        error("The image is too big to send")
    } finally {
        bitmap.recycle()
    }
}
