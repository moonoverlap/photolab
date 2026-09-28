package com.moonoverlap.photocalendar

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * /media/thumb/{id}  → 달력 칸용 썸네일 (512px)
 * /media/full/{id}   → 크게 보기용 (긴 변 2048px). HEIC도 JPEG로 변환해서 전달.
 */
class MediaPathHandler(private val ctx: Context) : WebViewAssetLoader.PathHandler {

    private val base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val thumbCache = object : LruCache<Long, ByteArray>(40 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: ByteArray) = value.size
    }

    override fun handle(path: String): WebResourceResponse? {
        val parts = path.trim('/').split('/')
        if (parts.size < 2) return null
        val id = parts[1].toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(base, id)
        return try {
            val bytes = when (parts[0]) {
                "thumb" -> thumbCache.get(id) ?: run {
                    val bmp = ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                    jpeg(bmp, 84).also { thumbCache.put(id, it) }
                }
                "full" -> {
                    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                    val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                        val w = info.size.width
                        val h = info.size.height
                        val s = minOf(1f, 2048f / max(w, h))
                        if (s < 1f) decoder.setTargetSize((w * s).roundToInt(), (h * s).roundToInt())
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    jpeg(bmp, 90)
                }
                else -> return null
            }
            WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes)).apply {
                responseHeaders = mapOf("Cache-Control" to "max-age=604800")
            }
        } catch (e: Exception) {
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun jpeg(bmp: Bitmap, q: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
        bmp.recycle()
        return bos.toByteArray()
    }
}
