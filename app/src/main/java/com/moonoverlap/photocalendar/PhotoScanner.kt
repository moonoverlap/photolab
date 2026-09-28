package com.moonoverlap.photocalendar

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 갤러리(MediaStore) 전체를 훑어서 GPS 정보가 있는 사진만 돌려준다.
 * 한 번 읽은 사진은 (id, 수정시각) 기준으로 캐시해 다음부터는 새 사진만 EXIF를 읽는다.
 */
class PhotoScanner(private val ctx: Context) {

    private data class Entry(val mod: Long, val lat: Double?, val lng: Double?, val ts: Long)

    private val cacheFile = File(ctx.filesDir, "scan_cache_v1.tsv")

    fun resetCache() { cacheFile.delete() }

    private fun loadCache(): HashMap<Long, Entry> {
        val map = HashMap<Long, Entry>()
        if (!cacheFile.exists()) return map
        try {
            cacheFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 5) {
                    val id = p[0].toLongOrNull() ?: return@forEachLine
                    map[id] = Entry(
                        p[1].toLongOrNull() ?: 0L,
                        p[2].toDoubleOrNull(),
                        p[3].toDoubleOrNull(),
                        p[4].toLongOrNull() ?: 0L
                    )
                }
            }
        } catch (_: Exception) { map.clear() }
        return map
    }

    private fun saveCache(map: Map<Long, Entry>) {
        val tmp = File(ctx.filesDir, "scan_cache_v1.tmp")
        tmp.bufferedWriter().use { w ->
            for ((id, e) in map) {
                w.append(id.toString()).append('\t').append(e.mod.toString()).append('\t')
                    .append(e.lat?.toString() ?: "").append('\t').append(e.lng?.toString() ?: "").append('\t')
                    .append(e.ts.toString()).append('\n')
            }
        }
        tmp.renameTo(cacheFile)
    }

    private fun readExif(uri: Uri): Triple<Double?, Double?, Long?> {
        val target = try { MediaStore.setRequireOriginal(uri) } catch (_: Exception) { uri }
        return try {
            ctx.contentResolver.openFileDescriptor(target, "r")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                val ll = exif.latLong
                val t = exif.dateTimeOriginal ?: exif.dateTime
                if (ll != null && !(ll[0] == 0.0 && ll[1] == 0.0) && !ll[0].isNaN() && !ll[1].isNaN())
                    Triple(ll[0], ll[1], t) else Triple(null, null, t)
            } ?: Triple(null, null, null)
        } catch (_: Exception) {
            Triple(null, null, null)
        }
    }

    fun scan(progress: (Int, Int) -> Unit): JSONObject {
        val old = loadCache()
        val fresh = HashMap<Long, Entry>(old.size + 64)
        val out = JSONArray()
        val base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        var total = 0
        var newlyRead = 0
        var noGps = 0
        ctx.contentResolver.query(base, proj, null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { c ->
            total = c.count
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iMod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            var i = 0
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val mod = c.getLong(iMod)
                var e = old[id]
                if (e == null || e.mod != mod) {
                    val (lat, lng, exifTs) = readExif(ContentUris.withAppendedId(base, id))
                    val taken = if (c.isNull(iTaken)) 0L else c.getLong(iTaken)
                    val ts = when {
                        taken > 0 -> taken
                        exifTs != null && exifTs > 0 -> exifTs
                        else -> mod * 1000
                    }
                    e = Entry(mod, lat, lng, ts)
                    newlyRead++
                }
                fresh[id] = e
                if (e.lat != null && e.lng != null) {
                    out.put(JSONObject().apply {
                        put("id", id.toString())
                        put("name", c.getString(iName) ?: "")
                        put("bucket", c.getString(iBucket) ?: "")
                        put("ts", e.ts)
                        put("lat", e.lat)
                        put("lng", e.lng)
                    })
                } else noGps++
                i++
                if (i % 40 == 0) progress(i, total)
            }
        }
        saveCache(fresh)
        progress(total, total)
        return JSONObject().apply {
            put("photos", out)
            put("total", total)
            put("noGps", noGps)
            put("newlyRead", newlyRead)
        }
    }
}
