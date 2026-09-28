package com.moonoverlap.photocalendar

import android.content.Context
import android.location.Geocoder
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val UA = "PhotoCalendarAndroid/1.0 (personal photo calendar; github.com/moonoverlap)"

private fun httpGet(url: String, accept: String): ByteArray? {
    val c = URL(url).openConnection() as HttpURLConnection
    return try {
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Language", "ko")
        if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
    } catch (_: Exception) {
        null
    } finally {
        c.disconnect()
    }
}

/** /tile/{z}/{x}/{y} → OpenStreetMap 지도 타일 (기기에 30일 캐시) */
class TilePathHandler(ctx: Context) : WebViewAssetLoader.PathHandler {
    private val dir = File(ctx.cacheDir, "tiles").apply { mkdirs() }

    override fun handle(path: String): WebResourceResponse? {
        val p = path.trim('/').split('/')
        if (p.size < 3) return null
        val z = p[0].toIntOrNull() ?: return null
        val x = p[1].toIntOrNull() ?: return null
        val y = p[2].substringBefore('.').toIntOrNull() ?: return null
        if (z !in 0..19) return null
        val f = File(dir, "${z}_${x}_${y}.png")
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86400000
        var bytes: ByteArray? = if (fresh) f.readBytes() else null
        if (bytes == null) {
            bytes = httpGet("https://tile.openstreetmap.org/$z/$x/$y.png", "image/png")
            if (bytes != null) {
                try { f.writeBytes(bytes) } catch (_: Exception) {}
            } else if (f.exists()) {
                bytes = f.readBytes()
            }
        }
        if (bytes == null) {
            return WebResourceResponse("image/png", null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse("image/png", null, ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf("Cache-Control" to "max-age=2592000")
        }
    }
}

/** 좌표 → 장소 이름. OpenStreetMap(Nominatim) 우선, 실패하면 휴대폰 기본 지오코더 사용 */
class ReverseGeocoder(private val ctx: Context) {
    private val io = Executors.newSingleThreadExecutor()
    private var last = 0L

    fun request(lat: Double, lng: Double, done: (String?) -> Unit) {
        io.execute {
            val wait = 1100 - (System.currentTimeMillis() - last)
            if (wait > 0) Thread.sleep(wait)
            last = System.currentTimeMillis()
            val osm = httpGet(
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1&accept-language=ko",
                "application/json"
            )?.toString(Charsets.UTF_8)
            val ok = osm != null && try { !JSONObject(osm).has("error") } catch (_: Exception) { false }
            done(if (ok) osm else fallback(lat, lng))
        }
    }

    @Suppress("DEPRECATION")
    private fun fallback(lat: Double, lng: Double): String? = try {
        if (!Geocoder.isPresent()) null else {
            val a = Geocoder(ctx, Locale.KOREA).getFromLocation(lat, lng, 1)?.firstOrNull()
            if (a == null) null else {
                val feature = a.featureName?.takeIf { f -> f.any { it.isLetter() } && f != a.thoroughfare }
                JSONObject().apply {
                    put("name", feature ?: "")
                    put("address", JSONObject().apply {
                        put("city", a.adminArea ?: "")
                        put("borough", a.locality ?: a.subAdminArea ?: "")
                        put("suburb", a.subLocality ?: a.thoroughfare ?: "")
                    })
                }.toString()
            }
        }
    } catch (_: Exception) { null }

    fun shutdown() = io.shutdownNow()
}
