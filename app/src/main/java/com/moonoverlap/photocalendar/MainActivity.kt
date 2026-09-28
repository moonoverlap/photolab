package com.moonoverlap.photocalendar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var scanner: PhotoScanner
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val scanning = AtomicBoolean(false)
    private var resumed = false
    private var pendingChange = false
    private var pageReady = false

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            // 갤러리에 사진이 추가/삭제되면 잠시 후 다시 확인
            main.removeCallbacks(notifyChange)
            main.postDelayed(notifyChange, 2500)
        }
    }
    private val notifyChange = Runnable {
        if (resumed) js("window.onGalleryChanged && onGalleryChanged()") else pendingChange = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = PhotoScanner(this)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/media/", MediaPathHandler(this))
            .build()

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#F5F7FC"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == APP_HOST) return false
                openExternal(url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadUrl("https://$APP_HOST/assets/www/index.html")

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer
        )
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (pageReady) js("window.onAppResume && onAppResume()")
        if (pendingChange) { pendingChange = false; main.postDelayed(notifyChange, 500) }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        io.shutdownNow()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(window.handleBack && handleBack()) ? 1 : 0") { r ->
            if (r?.trim('"') != "1") finish()
        }
    }

    private fun js(code: String) = main.post { web.evaluateJavascript(code, null) }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) { /* 무시 */ }
    }

    /* ---------- 권한 ---------- */

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun permState(): String {
        val loc = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> if (loc) "full" else "noloc"
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> if (loc) "partial" else "noloc"
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> if (loc) "full" else "noloc"
            else -> "none"
        }
    }

    private fun askPermission() {
        val perms = mutableListOf<String>()
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                perms += Manifest.permission.READ_MEDIA_IMAGES
                perms += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            }
            Build.VERSION.SDK_INT >= 33 -> perms += Manifest.permission.READ_MEDIA_IMAGES
            else -> perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        perms += Manifest.permission.ACCESS_MEDIA_LOCATION
        requestPermissions(perms.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERM) js("window.onPermission && onPermission('${permState()}')")
    }

    /* ---------- JS 브리지 ---------- */

    inner class Bridge {
        @JavascriptInterface
        fun permState(): String = this@MainActivity.permState()

        @JavascriptInterface
        fun requestPermission() = main.post { askPermission() }

        @JavascriptInterface
        fun openSettings() = main.post {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        @JavascriptInterface
        fun openUrl(url: String) = main.post { openExternal(Uri.parse(url)) }

        @JavascriptInterface
        fun scan(): Boolean {
            if (permState() == "none") return false
            if (!scanning.compareAndSet(false, true)) return false
            io.execute {
                try {
                    val result = scanner.scan { done, total ->
                        js("window.onNativeProgress && onNativeProgress($done,$total)")
                    }
                    val payload = JSONObject.quote(result.toString())
                    js("window.onNativeResult && onNativeResult($payload)")
                } catch (e: Exception) {
                    val msg = JSONObject.quote(e.message ?: "error")
                    js("window.onNativeError && onNativeError($msg)")
                } finally {
                    scanning.set(false)
                }
            }
            return true
        }

        @JavascriptInterface
        fun resetCache() = scanner.resetCache()
    }

    companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        private const val REQ_PERM = 41
    }
}
