package com.streamxhd.tv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Plays one server of an event inside a same-origin wrapper page (iframe), which passes the
 * site's anti-embedding checks. UP/DOWN (or CH+/CH-) switch between the event's servers.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var urls: List<String> = emptyList()
    private var names: List<String> = emptyList()
    private var index = 0

    private val handler = Handler(Looper.getMainLooper())
    private var serverStartedAt = 0L
    private var everPlayed = false
    private var stallNotified = false
    private var toast: Toast? = null

    private val statusPoll = object : Runnable {
        override fun run() {
            checkPlaybackStatus()
            handler.postDelayed(this, STATUS_POLL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        urls = intent.getStringArrayListExtra(EXTRA_URLS)
            ?: intent.getStringExtra(EXTRA_URL)?.let { listOf(it) }
            ?: emptyList()
        if (urls.isEmpty()) {
            finish()
            return
        }
        names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, urls.size - 1)

        progressBar = findViewById(R.id.playerProgressBar)
        webView = findViewById(R.id.playerWebView)

        fullscreenContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        (findViewById<FrameLayout>(android.R.id.content)).addView(fullscreenContainer)

        webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                // Ads in the player pages call window.open() on the first click. Without
                // multiple-window support WebView loads that URL in place of the player.
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
                userAgentString = WebGuards.tvUserAgent(this@PlayerActivity)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    gestureUnlock(view, 400)
                    gestureUnlock(view, 1400)
                    gestureUnlock(view, 2800)
                }

                // The wrapper is loaded with loadDataWithBaseURL and never needs to navigate;
                // any top-level navigation is an ad/referral hijack.
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    Log.d(TAG, "Bloqueada navegacion: ${request?.url}")
                    return true
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    Log.d(TAG, "Bloqueada navegacion: $url")
                    return true
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    Log.d(TAG, "Error: ${error?.description} for ${request?.url}")
                    progressBar.visibility = View.GONE
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val host = request?.url?.host
                    if (WebGuards.isAdHost(host)) return WebGuards.blockedResponse(TAG, host!!)
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

            requestFocus(View.FOCUS_DOWN)
        }

        loadServer(index, announce = urls.size > 1)
    }

    private fun loadServer(newIndex: Int, announce: Boolean) {
        index = newIndex
        val url = urls[index]
        val baseUrl = getBaseUrl(url)
        Log.d(TAG, "Servidor ${index + 1}/${urls.size}: $url | Wrapper base: $baseUrl")
        if (customView != null) exitFullscreen()
        serverStartedAt = SystemClock.elapsedRealtime()
        everPlayed = false
        stallNotified = false
        webView.loadDataWithBaseURL(baseUrl, wrapperHtml(url), "text/html", "UTF-8", baseUrl)
        if (announce) showServerInfo()
    }

    private fun switchServer(delta: Int) {
        if (urls.size < 2) {
            showToast("Este partido tiene un solo servidor")
            return
        }
        val next = ((index + delta) % urls.size + urls.size) % urls.size
        loadServer(next, announce = true)
    }

    private fun showServerInfo() {
        val name = names.getOrNull(index).orEmpty().ifBlank { "Servidor ${index + 1}" }
        val hint = if (urls.size > 1) "  ·  ▲▼ cambiar servidor" else ""
        showToast("${index + 1}/${urls.size}  $name$hint")
    }

    private fun showToast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(this, text, Toast.LENGTH_LONG).also { it.show() }
    }

    /** Tells the user when a server never starts, instead of leaving a black/loading screen. */
    private fun checkPlaybackStatus() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(STATUS_JS) { raw ->
            val state = raw?.trim('"') ?: return@evaluateJavascript
            if (state == "playing") {
                everPlayed = true
                return@evaluateJavascript
            }
            val elapsed = SystemClock.elapsedRealtime() - serverStartedAt
            if (!everPlayed && !stallNotified && (state == "error" || elapsed > STALL_TIMEOUT_MS)) {
                stallNotified = true
                showToast(
                    if (urls.size > 1) "Este servidor no arranca. Probá otro con ▲▼"
                    else "Este servidor no arranca. Volvé y probá otro partido o servidor"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            handler.removeCallbacks(statusPoll)
            handler.postDelayed(statusPoll, STATUS_POLL_MS)
        }
    }

    override fun onPause() {
        // Stop the stream (and its audio) while the app is in the background.
        handler.removeCallbacks(statusPoll)
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (customView != null) {
                exitFullscreen()
                return true
            }
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Player keys are handled here instead of onKeyDown: the WebView consumes DPAD_CENTER and
    // the arrows, so onKeyDown never sees them. Keeping OK away from the page also stops it from
    // "clicking" the player's ad layer.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val action: (() -> Unit)? = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { { runJs(TOGGLE_PLAY_JS) } }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { { runJs(PLAY_JS) } }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { { runJs(PAUSE_JS) } }
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> { { runJs(seekJs(-15)) } }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { { runJs(seekJs(15)) } }
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_NEXT -> { { switchServer(+1) } }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { { switchServer(-1) } }
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU -> { { showServerInfo() } }
            else -> null
        }
        if (action == null) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) action()
        return true
    }

    private fun runJs(js: String) = webView.evaluateJavascript(js, null)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        toast?.cancel()
        // Without this the player WebView (and its timers/stream) outlives the activity.
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun exitFullscreen() {
        customView?.let { fullscreenContainer.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    /**
     * A real key event clears WebView's "tap to unmute" state on some devices. Sent straight
     * to the WebView, so it does not go through [dispatchKeyEvent] (where DOWN switches server).
     */
    private fun gestureUnlock(view: WebView?, delayMillis: Long) {
        view?.postDelayed({
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN))
        }, delayMillis)
    }

    companion object {
        private const val TAG = "PlayerActivity"
        private const val EXTRA_URL = "url"
        private const val EXTRA_URLS = "urls"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_INDEX = "index"
        private const val STATUS_POLL_MS = 2000L
        private const val STALL_TIMEOUT_MS = 20_000L

        fun newIntent(
            context: Context,
            urls: ArrayList<String>,
            names: ArrayList<String>,
            index: Int
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putStringArrayListExtra(EXTRA_URLS, urls)
            .putStringArrayListExtra(EXTRA_NAMES, names)
            .putExtra(EXTRA_INDEX, index)

        /** Same origin as the player, so the wrapper can reach into the iframe. */
        private fun getBaseUrl(url: String): String {
            val uri = Uri.parse(url)
            return "${uri.scheme}://${uri.host}/"
        }

        private fun wrapperHtml(url: String): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<style>
    html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; }
    iframe { position: absolute; top: 0; left: 0; width: 100%; height: 100%; border: none; }
</style>
</head>
<body>
<iframe id="playerFrame"
    src="${TextUtils.htmlEncode(url)}"
    allow="autoplay; fullscreen; encrypted-media; picture-in-picture">
</iframe>
<script>
var frame = document.getElementById('playerFrame');
var playAttempts = 0;

function playerWin() {
    try { return frame.contentWindow; } catch(e) {}
    return null;
}

function playerVideo(win) {
    try { return win.document.querySelector('#player video') || win.document.querySelector('video'); } catch(e) {}
    return null;
}

function setupVideo(v) {
    if (!v) return;
    v.muted = false;
    v.volume = 1.0;
    v.style.outline = 'none';
    v.removeAttribute('controls');
}

// Autoplay with sound. live1/live2 use Clappr (window.player, autoPlay:false) and show a
// "CLICK PARA ACTIVAR SONIDO" button until unlockSound() runs; /channel/ uses Bitmovin.
function tryPlay() {
    var win = playerWin();
    if (!win) { retryTryPlay(); return; }
    try {
        var v = playerVideo(win);
        var p = win.player;
        var hasApi = p && typeof p.play === 'function' && typeof p.isPlaying === 'function';
        if (hasApi && !p.isPlaying()) { try { p.play(); } catch(e2) {} }
        if (v && v.paused) {
            v.muted = false; v.volume = 1.0;
            try { var r = v.play(); if (r && r.catch) r.catch(function(){}); } catch(e3) {}
        }
        if (typeof win.unlockSound === 'function' && !win.soundUnlocked) {
            try { win.unlockSound(); } catch(e4) {}
        }
        setupVideo(v);
        if ((!hasApi || p.isPlaying()) && v && !v.paused) return;
    } catch(e) {}
    retryTryPlay();
}
function retryTryPlay() {
    playAttempts++;
    if (playAttempts < 60) setTimeout(tryPlay, 500);
}

frame.addEventListener('load', function() { playAttempts = 0; setTimeout(tryPlay, 500); });
setInterval(function() {
    var win = playerWin();
    if (win) setupVideo(playerVideo(win));
}, 2000);

// Ads are injected as cross-origin iframes inside the player page.
var SITE_HOST = /(^|\.)streamx-?hd\.[a-z]{2,}${'$'}/i;
function killAds() {
    try {
        var doc = frame.contentDocument;
        if (!doc) return;
        var iframes = doc.querySelectorAll('iframe');
        for (var i = 0; i < iframes.length; i++) {
            var f = iframes[i];
            var host = '';
            try { host = new URL(f.src, doc.baseURI).host; } catch(e) {}
            if (!SITE_HOST.test(host) && f.parentNode) f.parentNode.removeChild(f);
        }
    } catch(e) {}
}
new MutationObserver(killAds).observe(document.body, { childList: true, subtree: true });
setInterval(killAds, 800);
</script>
</body>
</html>
""".trimIndent()

        private const val STATUS_JS = """
(function(){
    try {
        var f = document.getElementById('playerFrame');
        var w = f && f.contentWindow;
        var v = w && (w.document.querySelector('#player video') || w.document.querySelector('video'));
        if (!v) return 'novideo';
        if (v.error) return 'error';
        if (v.paused) return 'paused';
        return v.readyState >= 3 ? 'playing' : 'waiting';
    } catch(e) { return 'unknown'; }
})();
"""

        private const val FIND_VIDEO = """
        var f = document.getElementById('playerFrame');
        if (!f) return;
        var w = f.contentWindow;
        var v = w.document.querySelector('#player video') || w.document.querySelector('video');
        var p = w.player;
"""

        private const val TOGGLE_PLAY_JS = """
(function(){
    try {
$FIND_VIDEO
        if (v) { if (v.paused) v.play(); else v.pause(); }
        else if (p && typeof p.isPlaying === 'function') {
            if (p.isPlaying()) p.pause(); else p.play();
        }
    } catch(e){}
})();
"""
        private const val PLAY_JS = """
(function(){
    try {
$FIND_VIDEO
        if (v) v.play(); else if (p && p.play) p.play();
    } catch(e){}
})();
"""
        private const val PAUSE_JS = """
(function(){
    try {
$FIND_VIDEO
        if (v) v.pause(); else if (p && p.pause) p.pause();
    } catch(e){}
})();
"""

        /** Seeks inside the live DVR window; clamped to what is buffered/seekable. */
        private fun seekJs(seconds: Int): String = """
(function(){
    try {
$FIND_VIDEO
        if (!v) return;
        var t = v.currentTime + ($seconds);
        var s = v.seekable;
        if (s && s.length) {
            t = Math.max(s.start(0), Math.min(s.end(s.length - 1) - 1, t));
        } else if (isFinite(v.duration)) {
            t = Math.max(0, Math.min(v.duration, t));
        }
        v.currentTime = t;
    } catch(e){}
})();
"""
    }
}
