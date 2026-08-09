package com.xiaofang.webshell

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.*
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_CODE = 1002
        private const val TARGET_URL = "https://ai-virtual-phone-pied.vercel.app/"
        private val CORS_PROXY_HOSTS = listOf(
            "www.20170428.cloud",
            "20170428.cloud"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        webView = WebView(this)
        setContentView(webView)

        setupWebView()
        createNotificationChannel()
        requestNotificationPermission()

        webView.loadUrl(TARGET_URL)
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString + " WebShellAPK/1.0"
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url ?: return super.shouldInterceptRequest(view, request)
                val host = url.host ?: return super.shouldInterceptRequest(view, request)

                if (CORS_PROXY_HOSTS.any { host.contains(it) }) {
                    return proxyCorsRequest(request)
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                val acceptTypes = fileChooserParams?.acceptTypes
                if (acceptTypes != null && acceptTypes.isNotEmpty() && acceptTypes[0].isNotEmpty()) {
                    intent.type = acceptTypes[0]
                }

                startActivityForResult(
                    Intent.createChooser(intent, "选择文件"),
                    FILE_CHOOSER_REQUEST_CODE
                )
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
    }

    private fun proxyCorsRequest(request: WebResourceRequest): WebResourceResponse? {
        return try {
            val url = URL(request.url.toString())
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = request.method ?: "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            request.requestHeaders?.forEach { (key, value) ->
                if (!key.equals("Host", ignoreCase = true) &&
                    !key.equals("Origin", ignoreCase = true) &&
                    !key.equals("Referer", ignoreCase = true)
                ) {
                    connection.setRequestProperty(key, value)
                }
            }

            if (request.method.equals("POST", ignoreCase = true) ||
                request.method.equals("PUT", ignoreCase = true)
            ) {
                connection.doOutput = true
            }

            val responseCode = connection.responseCode
            val contentType = connection.contentType ?: "application/json"
            val encoding = connection.contentEncoding ?: "UTF-8"

            val mimeType = if (contentType.contains(";")) {
                contentType.split(";")[0].trim()
            } else {
                contentType
            }

            val inputStream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: ByteArrayInputStream("Error".toByteArray())
            }

            val responseHeaders = mutableMapOf<String, String>()
            responseHeaders["Access-Control-Allow-Origin"] = "*"
            responseHeaders["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
            responseHeaders["Access-Control-Allow-Headers"] = "*"

            connection.headerFields?.forEach { (key, values) ->
                if (key != null && values != null && values.isNotEmpty()) {
                    responseHeaders[key] = values.joinToString(", ")
                }
            }

            WebResourceResponse(mimeType, encoding, responseCode, "OK", responseHeaders, inputStream)
        } catch (e: Exception) {
            val errorBody = """{"error": "${e.message}"}""".toByteArray()
            WebResourceResponse(
                "application/json",
                "UTF-8",
                500,
                "Internal Error",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, POST, PUT, DELETE, OPTIONS",
                    "Access-Control-Allow-Headers" to "*"
                ),
                ByteArrayInputStream(errorBody)
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "webshell_channel",
                "WebShell 通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "来自网页的通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                val results = mutableListOf<Uri>()
                data.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        results.add(clipData.getItemAt(i).uri)
                    }
                } ?: data.data?.let { uri ->
                    results.add(uri)
                }
                fileUploadCallback?.onReceiveValue(results.toTypedArray())
            } else {
                fileUploadCallback?.onReceiveValue(null)
            }
            fileUploadCallback = null
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
}
