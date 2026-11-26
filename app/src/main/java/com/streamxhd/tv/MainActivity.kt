package com.streamxhd.tv

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var doubleBackToExit = false

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    /** Candidate domains, in the order they are tried. */
    private val domains = mutableListOf<String>()
    private var domainIndex = 0
    /** Hosts that failed in the current cycle; late callbacks for them are ignored. */
    private val failedHosts = mutableSetOf<String>()
    private var connectionDialog: AlertDialog? = null
    /** Domain that actually serves the site right now (after redirects), used by the JS bridge. */
    @Volatile private var currentHost: String = DEFAULT_DOMAINS[0]

    private val loadTimeout = Runnable { tryNextDomain("timeout") }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        val pkgInfo = packageManager.getPackageInfo(packageName, 0)
        Toast.makeText(this, "v${pkgInfo.versionName}", Toast.LENGTH_LONG).show()

        checkForUpdates(PackageInfoCompat.getLongVersionCode(pkgInfo))

        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Pop-under ads use window.open(); without this WebView opens them in place.
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                userAgentString = WebGuards.tvUserAgent(this@MainActivity)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    // The domain answered; the slow part (ads, scripts) no longer counts
                    // against the timeout. onPageFinished still validates the content.
                    val host = url?.let { Uri.parse(it).host }
                    // failedHosts: a late callback for an error page must not cancel the
                    // timeout of the domain being tried now.
                    if (WebGuards.isSiteHost(host) && host !in failedHosts) {
                        handler.removeCallbacks(loadTimeout)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    onMainPageFinished(view, url)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        onMainFrameFailed(request.url?.host, "${error?.description}")
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?
                ) {
                    // Only called on API < 23, and only for the main frame.
                    if (android.os.Build.VERSION.SDK_INT < 23) {
                        onMainFrameFailed(failingUrl?.let { Uri.parse(it).host }, "$description")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    val code = errorResponse?.statusCode ?: return
                    if (request?.isForMainFrame == true && code >= 400) {
                        onMainFrameFailed(request.url?.host, "HTTP $code")
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = blockOffSite(request?.url)

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    blockOffSite(url?.let { Uri.parse(it) })

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val host = request?.url?.host
                    if (WebGuards.isAdHost(host)) return WebGuards.blockedResponse(TAG, host!!)
                    // Safety net: the site's own "Ver aquí" handler plays the server in a small
                    // muted iframe inside the agenda. INJECTED_JS normally stops it, but if that
                    // click got through anyway, cancel the iframe and open the real player.
                    if (request != null && !request.isForMainFrame && WebGuards.isPlayerUrl(request.url)) {
                        val url = request.url.toString()
                        handler.post { rescueInlinePlayer(url) }
                        return WebResourceResponse(
                            "text/html", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                }

                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback
                    fullscreenContainer.visibility = View.VISIBLE
                    fullscreenContainer.addView(
                        view,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fullscreenContainer.bringToFront()
                    webView.visibility = View.GONE
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    Log.d(TAG, "Bloqueado popup (window.open)")
                    return false
                }
            }

            addJavascriptInterface(PlayBridge(), "Android")

            requestFocus(View.FOCUS_DOWN)
        }

        startDomainCycle()
        discoverDomains()
    }

    // ---------------------------------------------------------------- domains

    /** Builds the candidate list: last domain that worked, domains from the directory, defaults. */
    private fun startDomainCycle() {
        val ordered = LinkedHashSet<String>()
        prefs.getString(KEY_LAST_DOMAIN, null)?.let { ordered.add(it) }
        prefs.getString(KEY_DISCOVERED, "")!!.split(',').filter { it.isNotBlank() }.forEach { ordered.add(it) }
        ordered.addAll(DEFAULT_DOMAINS)
        domains.clear()
        domains.addAll(ordered)
        failedHosts.clear()
        loadDomain(0)
    }

    private fun loadDomain(index: Int) {
        domainIndex = index
        val domain = domains[index]
        Log.d(TAG, "Cargando dominio ${index + 1}/${domains.size}: $domain")
        handler.removeCallbacks(loadTimeout)
        handler.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
        webView.loadUrl("https://$domain/")
    }

    private fun onMainFrameFailed(host: String?, reason: String) {
        if (host != null && host in failedHosts) return
        tryNextDomain(reason)
    }

    private fun tryNextDomain(reason: String) {
        handler.removeCallbacks(loadTimeout)
        failedHosts.add(domains[domainIndex])
        Log.w(TAG, "Dominio ${domains[domainIndex]} falló ($reason)")
        if (domainIndex < domains.size - 1) {
            loadDomain(domainIndex + 1)
        } else {
            showConnectionError()
        }
    }

    private fun onMainPageFinished(view: WebView?, url: String?) {
        if (url == null || url == "about:blank") return
        val host = Uri.parse(url).host ?: return
        if (!WebGuards.isSiteHost(host)) {
            // e.g. an expired domain that now redirects to a parking page
            if (host !in failedHosts) tryNextDomain("redirigido a $host")
            return
        }
        view?.evaluateJavascript(SITE_CHECK_JS) { result ->
            val isShown = host == Uri.parse(webView.url ?: "").host
            if (result == "true") {
                // Always inject into an agenda page, whatever path led here: without it the
                // site's own "Ver aquí" plays in a small muted iframe.
                view.evaluateJavascript(INJECTED_JS, null)
                if (isShown) {
                    // Also when this host had been given up on (slow TV hit the timeout) but
                    // ended up loading: it works, so it becomes the current domain.
                    failedHosts.remove(host)
                    handler.removeCallbacks(loadTimeout)
                    connectionDialog?.dismiss()
                    currentHost = host
                    prefs.edit().putString(KEY_LAST_DOMAIN, host).apply()
                }
            } else if (isShown && host !in failedHosts) {
                tryNextDomain("la página no es la agenda")
            }
        }
    }

    private fun showConnectionError() {
        if (isFinishing) return
        connectionDialog?.dismiss()
        connectionDialog = AlertDialog.Builder(this)
            .setTitle("No se pudo conectar")
            .setMessage("Ningún dominio respondió (${domains.joinToString()}). Revisá la conexión a internet.")
            .setPositiveButton("Reintentar") { _, _ -> startDomainCycle() }
            .setNegativeButton("Salir") { _, _ -> finishAffinity() }
            .setCancelable(false)
            .show()
    }

    /** Reads the official directory (streamveri.xyz) so new domains are tried without an update. */
    private fun discoverDomains() {
        Thread {
            try {
                val conn = URL(DOMAIN_DIRECTORY_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.setRequestProperty("User-Agent", WebGuards.tvUserAgent(this))
                val html = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val found = Regex("href=\"https://([a-z0-9.-]+)/?\"", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .map { it.groupValues[1].lowercase() }
                    .filter { WebGuards.isSiteHost(it) }
                    .distinct()
                    .toList()
                if (found.isEmpty()) return@Thread
                Log.d(TAG, "Dominios del directorio: $found")
                prefs.edit().putString(KEY_DISCOVERED, found.joinToString(",")).apply()
                runOnUiThread {
                    found.filter { it !in domains }.forEach { domains.add(it) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "No se pudo leer el directorio de dominios: ${e.message}")
            }
        }.start()
    }

    /** Keeps the main WebView on the site: ads and off-site links are not followed. */
    private fun blockOffSite(uri: Uri?): Boolean {
        val host = uri?.host ?: return false
        if (WebGuards.isSiteHost(host)) return false
        Log.d(TAG, "Bloqueada navegacion fuera del sitio: $uri")
        return true
    }

    // ---------------------------------------------------------------- keys / lifecycle

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                exitFullscreen()
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
            if (doubleBackToExit) {
                finishAffinity()
                return true
            }
            doubleBackToExit = true
            Toast.makeText(this, "Presiona Atrás nuevamente para salir", Toast.LENGTH_SHORT).show()
            handler.postDelayed({ doubleBackToExit = false }, 2000)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        connectionDialog?.dismiss()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private fun exitFullscreen() {
        customView?.let { fullscreenContainer.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    // ---------------------------------------------------------------- JS bridge

    inner class PlayBridge {
        /** Single URL (kept for compatibility). */
        @JavascriptInterface
        fun playUrl(url: String) {
            openPlayer(listOf(url), listOf(""), 0)
        }

        /** {"urls":[...], "names":[...], "index":n}: every server of the event, so the player can switch. */
        @JavascriptInterface
        fun playServers(json: String) {
            try {
                val obj = JSONObject(json)
                val urls = obj.getJSONArray("urls")
                val names = obj.optJSONArray("names")
                val u = (0 until urls.length()).map { urls.getString(it) }
                val n = (0 until u.size).map { names?.optString(it, "") ?: "" }
                openPlayer(u, n, obj.optInt("index", 0))
            } catch (e: Exception) {
                Log.e(TAG, "playServers: JSON inválido: ${e.message}")
            }
        }
    }

    private var lastRescueUrl: String? = null
    private var lastRescueAt = 0L

    /** Called (UI thread) when the agenda tried to load a server in its own inline iframe. */
    private fun rescueInlinePlayer(url: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (url == lastRescueUrl && now - lastRescueAt < 3000) return
        lastRescueUrl = url
        lastRescueAt = now
        Log.w(TAG, "Reproductor embebido de la página interceptado: $url")
        webView.evaluateJavascript(rescueInlineJs(url)) { result ->
            val parsed = try {
                // evaluateJavascript returns the JSON string itself JSON-encoded
                if (result == null || result == "null") null
                else JSONObject(org.json.JSONTokener(result).nextValue() as String)
            } catch (e: Exception) {
                null
            }
            if (parsed == null) {
                openPlayer(listOf(url), listOf(""), 0)
                return@evaluateJavascript
            }
            val urls = parsed.getJSONArray("urls")
            val names = parsed.optJSONArray("names")
            val u = (0 until urls.length()).map { urls.getString(it) }
            val n = u.indices.map { names?.optString(it, "") ?: "" }
            openPlayer(u, n, parsed.optInt("index", 0))
        }
    }

    private fun openPlayer(rawUrls: List<String>, rawNames: List<String>, rawIndex: Int) {
        val host = currentHost
        val urls = ArrayList<String>()
        val names = ArrayList<String>()
        var index = 0
        rawUrls.forEachIndexed { i, raw ->
            val url = WebGuards.normalizePlayerUrl(raw, host) ?: return@forEachIndexed
            if (url in urls) {
                if (i == rawIndex) index = urls.indexOf(url)
                return@forEachIndexed
            }
            if (i == rawIndex) index = urls.size
            urls.add(url)
            names.add(rawNames.getOrNull(i).orEmpty())
        }
        if (urls.isEmpty()) return
        runOnUiThread {
            startActivity(PlayerActivity.newIntent(this, urls, names, index))
        }
    }

    // ---------------------------------------------------------------- OTA update

    private fun checkForUpdates(currentVersionCode: Long) {
        Thread {
            try {
                val conn = URL(UPDATE_VERSION_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.useCaches = false
                val latest = conn.inputStream.bufferedReader().use { it.readText().trim() }.toLongOrNull()
                conn.disconnect()
                if (latest != null && latest > currentVersionCode) {
                    runOnUiThread {
                        if (!isFinishing) promptUpdate(latest, currentVersionCode)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Update check failed: ${e.message}")
            }
        }.start()
    }

    private fun promptUpdate(latest: Long, current: Long) {
        AlertDialog.Builder(this)
            .setTitle("Actualización disponible")
            .setMessage("Hay una nueva versión disponible ($latest). ¿Descargar e instalar?")
            .setPositiveButton("Sí") { _, _ -> downloadAndInstall(current) }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun downloadAndInstall(currentVersionCode: Long) {
        Toast.makeText(this, "Descargando...", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val tmpFile = File(dir, "NahuApp.apk.part")
                val apkFile = File(dir, "NahuApp.apk")
                val conn = URL(UPDATE_APK_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 30000
                conn.readTimeout = 60000
                conn.useCaches = false
                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                apkFile.delete()
                if (!tmpFile.renameTo(apkFile)) throw IllegalStateException("rename failed")

                // Reject truncated downloads or a file that is not a newer NahuApp before
                // handing it to the installer.
                val info = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                    ?: throw IllegalStateException("APK inválido")
                if (info.packageName != packageName) throw IllegalStateException("paquete ${info.packageName}")
                val newCode = PackageInfoCompat.getLongVersionCode(info)
                if (newCode <= currentVersionCode) throw IllegalStateException("versión $newCode no es más nueva")

                runOnUiThread { installApk(apkFile) }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Error al descargar la actualización", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun installApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install intent failed: ${e.message}")
            Toast.makeText(this, "No se pudo abrir el instalador", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS = "nahuapp"
        private const val KEY_LAST_DOMAIN = "last_domain"
        private const val KEY_DISCOVERED = "discovered_domains"
        /** Time for a domain to start showing the site (onPageCommitVisible) before trying the next. */
        private val LOAD_TIMEOUT_MS = if (android.os.Build.VERSION.SDK_INT >= 23) 12_000L else 25_000L

        /** Fallbacks if neither the last working domain nor the directory are available. */
        private val DEFAULT_DOMAINS = listOf(
            "streamxhd.sbs",
            "streamxhd.st",
            "streamxhd.click",
            "streamxhd.com"
        )

        private const val DOMAIN_DIRECTORY_URL = "https://streamveri.xyz/"

        private const val UPDATE_VERSION_URL =
            "https://raw.githubusercontent.com/nfontan/NahuApp/main/releases/version.txt"
        private const val UPDATE_APK_URL =
            "https://raw.githubusercontent.com/nfontan/NahuApp/main/releases/NahuApp.apk"

        /** True when the loaded page is the event agenda (and not a parking/error page). */
        private const val SITE_CHECK_JS =
            "(function(){return !!(document.getElementById('content') && document.getElementById('tabs'));})()"

        /**
         * __nahuCollectServers(btn) -> {urls, names, index}: every server of the event the
         * "Ver aquí" button belongs to (same data-target), so the player can switch with UP/DOWN.
         */
        private const val COLLECT_SERVERS_FN = """
function __nahuCollectServers(btn) {
    function serverName(b) {
        var row = b.closest('.compact-server-row');
        var el = row && row.querySelector('.compact-server-name span:not(.compact-server-number)');
        return el ? el.textContent.trim() : '';
    }
    var url = (btn.getAttribute('data-url') || '').trim();
    if (!url) return null;
    var target = btn.getAttribute('data-target');
    var all = target
        ? Array.prototype.slice.call(document.querySelectorAll('[data-action="play"]')).filter(function(b) {
              return b.getAttribute('data-target') === target;
          })
        : [btn];
    var urls = [], names = [], index = 0;
    all.forEach(function(b) {
        var u = (b.getAttribute('data-url') || '').trim();
        if (!u) return;
        var name = serverName(b);
        var pos = urls.indexOf(u);
        if (pos === -1) { urls.push(u); names.push(name); pos = urls.length - 1; }
        else if (!names[pos] && name) { names[pos] = name; }
        if (u === url) index = pos;
    });
    if (urls.length === 0) { urls = [url]; names = ['']; }
    return { urls: urls, names: names, index: index };
}
"""

        /**
         * Undoes the site's inline player for [url] (stops the iframe, hides the panel) and
         * returns the event's servers as JSON, or null if no button with that URL is found.
         */
        private fun rescueInlineJs(url: String): String = """
(function() {
$COLLECT_SERVERS_FN
    var url = ${JSONObject.quote(url)};
    Array.prototype.slice.call(document.querySelectorAll('iframe')).forEach(function(f) {
        if (f.src === url) {
            f.removeAttribute('src');
            var panel = f.closest('.show');
            if (panel) panel.classList.remove('show');
        }
    });
    var btn = Array.prototype.slice.call(document.querySelectorAll('[data-action="play"]')).find(function(b) {
        var u = (b.getAttribute('data-url') || '').trim();
        return u === url || (u && new URL(u, location.href).href === url);
    });
    return btn ? JSON.stringify(__nahuCollectServers(btn)) : null;
})();
"""

        private const val INJECTED_JS = """
(function() {
    if (window.__nahuInjected) return;
    window.__nahuInjected = true;
    localStorage.setItem('playbackMode', 'live2');
    // The page reads playbackMode before this runs, so on a fresh install it still shows
    // live1; selecting the button updates the page state and re-renders the server URLs.
    var live2Btn = document.querySelector('[data-mode="live2"]');
    if (live2Btn && !live2Btn.classList.contains('active')) live2Btn.click();

$COLLECT_SERVERS_FN

    document.addEventListener('click', function(e) {
        var btn = e.target.closest('[data-action="play"]');
        if (!btn) return;
        e.preventDefault();
        e.stopPropagation();
        var data = __nahuCollectServers(btn);
        if (data) Android.playServers(JSON.stringify(data));
    }, true);

    var refreshBtn = document.createElement('div');
    refreshBtn.id = '__refresh_btn';
    refreshBtn.textContent = '↻';
    refreshBtn.style.cssText = 'position:fixed;top:8px;right:8px;z-index:9999;background:#CC000000;color:white;font-size:22px;padding:12px 16px;border-radius:4px;cursor:pointer;min-width:48px;min-height:48px;display:flex;align-items:center;justify-content:center;';
    refreshBtn.setAttribute('tabindex', '0');
    refreshBtn.addEventListener('click', function() { window.location.reload(); });
    refreshBtn.addEventListener('keydown', function(e) {
        if (e.key === 'Enter' || e.key === ' ') { window.location.reload(); }
    });
    document.body.appendChild(refreshBtn);
    document.querySelectorAll('[data-action="play"], .tab').forEach(function(el) {
        el.setAttribute('tabindex', '0');
    });
    var firstTab = document.querySelector('.tab');
    if (firstTab) { firstTab.focus(); }
    new MutationObserver(function() {
        document.querySelectorAll('[data-action="play"]:not([tabindex]), .tab:not([tabindex])').forEach(function(el) {
            el.setAttribute('tabindex', '0');
        });
        if (!document.getElementById('__refresh_btn')) {
            document.body.appendChild(refreshBtn);
        }
    }).observe(document.body, { childList: true, subtree: true });
})();
"""
    }
}
