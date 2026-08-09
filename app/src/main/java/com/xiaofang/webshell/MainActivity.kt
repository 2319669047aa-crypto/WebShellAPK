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
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.*
import android.view.View
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private val executor = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private val requestId = AtomicLong(0)

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_CODE = 1002
        private const val TARGET_URL = "https://ai-virtual-phone-pied.vercel.app/"
        private const val TAG = "WebShell"
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

        webView.addJavascriptInterface(CorsProxyBridge(), "NativeCorsProxy")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCorsProxy(view)
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

    private fun injectCorsProxy(view: WebView?) {
        val js = """
        (function() {
            if (window.__corsProxyInjected) return;
            window.__corsProxyInjected = true;

            const PROXY_HOSTS = ['20170428.cloud', 'www.20170428.cloud'];
            const originalFetch = window.fetch;

            function shouldProxy(url) {
                try {
                    const u = new URL(url, window.location.href);
                    return PROXY_HOSTS.some(h => u.host.includes(h));
                } catch(e) { return false; }
            }

            window.fetch = function(input, init) {
                let url = (typeof input === 'string') ? input : input.url;
                if (!shouldProxy(url)) {
                    return originalFetch.apply(this, arguments);
                }

                let method = (init && init.method) || 'GET';
                let headers = {};
                if (init && init.headers) {
                    if (init.headers instanceof Headers) {
                        init.headers.forEach(function(v, k) { headers[k] = v; });
                    } else {
                        headers = init.headers;
                    }
                }
                let body = (init && init.body) ? init.body : '';

                return new Promise(function(resolve, reject) {
                    let callbackName = '__corsCallback_' + Date.now() + '_' + Math.random().toString(36).substr(2);

                    window[callbackName] = function(statusCode, responseHeaders, responseBody) {
                        delete window[callbackName];
                        let resp = new Response(responseBody, {
                            status: statusCode,
                            headers: JSON.parse(responseHeaders)
                        });
                        resolve(resp);
                    };

                    window[callbackName + '_error'] = function(errorMsg) {
                        delete window[callbackName];
                        delete window[callbackName + '_error'];
                        reject(new Error(errorMsg));
                    };

                    NativeCorsProxy.sendRequest(
                        url,
                        method,
                        JSON.stringify(headers),
                        typeof body === 'string' ? body : JSON.stringify(body),
                        callbackName
                    );
                });
            };

            // Also override XMLHttpRequest for compatibility
            const OrigXHR = window.XMLHttpRequest;
            class ProxiedXHR extends OrigXHR {
                open(method, url, async, user, password) {
                    this._proxyUrl = url;
                    this._proxyMethod = method;
                    this._proxyHeaders = {};
                    this._shouldProxy = shouldProxy(url);
                    if (!this._shouldProxy) {
                        return super.open(method, url, async, user, password);
                    }
                }
                setRequestHeader(key, value) {
                    if (this._shouldProxy) {
                        this._proxyHeaders[key] = value;
                    } else {
                        super.setRequestHeader(key, value);
                    }
                }
                send(body) {
                    if (!this._shouldProxy) {
                        return super.send(body);
                    }
                    const xhr = this;
                    let callbackName = '__xhrCallback_' + Date.now() + '_' + Math.random().toString(36).substr(2);

                    window[callbackName] = function(statusCode, responseHeaders, responseBody) {
                        delete window[callbackName];
                        delete window[callbackName + '_error'];
                        Object.defineProperty(xhr, 'status', { value: statusCode, writable: false });
                        Object.defineProperty(xhr, 'readyState', { value: 4, writable: false });
                        Object.defineProperty(xhr, 'responseText', { value: responseBody, writable: false });
                        Object.defineProperty(xhr, 'response', { value: responseBody, writable: false });
                        if (xhr.onreadystatechange) xhr.onreadystatechange();
                        if (xhr.onload) xhr.onload();
                    };

                    window[callbackName + '_error'] = function(errorMsg) {
                        delete window[callbackName];
                        delete window[callbackName + '_error'];
                        if (xhr.onerror) xhr.onerror(new Error(errorMsg));
                    };

                    NativeCorsProxy.sendRequest(
                        xhr._proxyUrl,
                        xhr._proxyMethod,
                        JSON.stringify(xhr._proxyHeaders),
                        body || '',
                        callbackName
                    );
                }
            }
            window.XMLHttpRequest = ProxiedXHR;

            console.log('[WebShell] CORS proxy injected successfully');
        })();
        """.trimIndent()

        view?.evaluateJavascript(js, null)
    }

    inner class CorsProxyBridge {
        @JavascriptInterface
        fun sendRequest(url: String, method: String, headersJson: String, body: String, callbackName: String) {
            executor.execute {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = method
                    connection.connectTimeout = 60000
                    connection.readTimeout = 60000

                    val headers = JSONObject(headersJson)
                    headers.keys().forEach { key ->
                        connection.setRequestProperty(key, headers.getString(key))
                    }

                    if (method.equals("POST", ignoreCase = true) ||
                        method.equals("PUT", ignoreCase = true) ||
                        method.equals("PATCH", ignoreCase = true)
                    ) {
                        connection.doOutput = true
                        if (body.isNotEmpty()) {
                            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
                            writer.write(body)
                            writer.flush()
                            writer.close()
                        }
                    }

                    val responseCode = connection.responseCode

                    val inputStream = if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream ?: connection.inputStream
                    }

                    val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
                    val responseBody = reader.readText()
                    reader.close()

                    val responseHeaders = JSONObject()
                    connection.headerFields?.forEach { (key, values) ->
                        if (key != null && values != null && values.isNotEmpty()) {
                            responseHeaders.put(key, values.joinToString(", "))
                        }
                    }

                    val escapedBody = responseBody
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t")

                    val escapedHeaders = responseHeaders.toString()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")

                    handler.post {
                        webView.evaluateJavascript(
                            """window["$callbackName"]($responseCode, "$escapedHeaders", "$escapedBody");""",
                            null
                        )
                    }

                    connection.disconnect()

                } catch (e: Exception) {
                    Log.e(TAG, "CORS proxy error: ${e.message}", e)
                    val errorMsg = (e.message ?: "Unknown error")
                        .replace("\"", "\\\"")
                        .replace("\n", " ")
                    handler.post {
                        webView.evaluateJavascript(
                            """window["${callbackName}_error"]("$errorMsg");""",
                            null
                        )
                    }
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "webshell_channel",
                "Float 通知",
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
