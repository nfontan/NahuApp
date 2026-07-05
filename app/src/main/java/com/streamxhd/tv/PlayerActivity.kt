package com.streamxhd.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity

class PlayerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var videoFocused = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val url = intent.getStringExtra("url") ?: run {
            finish()
            return
        }

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
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                mediaPlaybackRequiresUserGesture = false
                userAgentString = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    view?.evaluateJavascript(AUTO_SETUP_VIDEO_JS, null)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    progressBar.visibility = View.GONE
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
            }

            addJavascriptInterface(VideoBridge(), "Android")

            requestFocus(View.FOCUS_DOWN)
            loadUrl(url)
        }
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

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (videoFocused || customView != null) {
                    webView.evaluateJavascript(TOGGLE_PLAY_JS, null)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (videoFocused || customView != null) {
                    webView.evaluateJavascript(SEEK_BACK_JS, null)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (videoFocused || customView != null) {
                    webView.evaluateJavascript(SEEK_FORWARD_JS, null)
                    return true
                }
            }
            KeyEvent.KEYCODE_INFO -> {
                webView.evaluateJavascript(TOGGLE_FULLSCREEN_JS, null)
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun exitFullscreen() {
        customView?.let { fullscreenContainer.removeView(it) }
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        fullscreenContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    inner class VideoBridge {
        @JavascriptInterface
        fun onVideoFocus() {
            videoFocused = true
        }

        @JavascriptInterface
        fun onVideoBlur() {
            videoFocused = false
        }
    }

    companion object {
        fun newIntent(packageContext: android.content.Context, url: String): Intent {
            return Intent(packageContext, PlayerActivity::class.java).putExtra("url", url)
        }

        private const val AUTO_SETUP_VIDEO_JS = """
(function() {
    function setup(v) {
        if (!v) return;
        v.muted = false;
        v.volume = 1.0;
        v.setAttribute('tabindex', '0');
        v.style.outline = 'none';
        v.removeAttribute('controls');
        v.addEventListener('focus', function() { Android.onVideoFocus(); });
        v.addEventListener('blur', function() { Android.onVideoBlur(); });
        if (document.activeElement === document.body) { v.focus(); }
        var tryPlay = function() {
            if (v.paused) {
                v.play().then(function() {
                    if (v.requestFullscreen) v.requestFullscreen();
                    else if (v.webkitRequestFullscreen) v.webkitRequestFullscreen();
                    else if (v.webkitEnterFullscreen) v.webkitEnterFullscreen();
                }).catch(function() {});
            }
        };
        tryPlay();
        v.addEventListener('canplay', tryPlay);
        v.addEventListener('loadedmetadata', tryPlay);
    }
    setup(document.querySelector('video'));
    new MutationObserver(function() {
        var v = document.querySelector('video');
        if (v && !v._setupDone) { v._setupDone = true; setup(v); }
    }).observe(document.body, { childList: true, subtree: true });
    setInterval(function() {
        try {
            var v = document.querySelector('video');
            if (v && v.muted) { v.muted = false; v.volume = 1.0; }
        } catch(e) {}
    }, 2000);
})();
"""
        private const val TOGGLE_PLAY_JS = """
(function(){
    try {
        var v = document.querySelector('video');
        if (v) { if (v.paused) v.play(); else v.pause(); }
    } catch(e){}
})();
"""
        private const val SEEK_BACK_JS = """
(function(){
    try {
        var v = document.querySelector('video');
        if (v) { v.currentTime = Math.max(0, v.currentTime - 15); }
    } catch(e){}
})();
"""
        private const val SEEK_FORWARD_JS = """
(function(){
    try {
        var v = document.querySelector('video');
        if (v) { v.currentTime = Math.min(v.duration, v.currentTime + 15); }
    } catch(e){}
})();
"""
        private const val TOGGLE_FULLSCREEN_JS = """
(function(){
    try {
        var v = document.querySelector('video');
        if (v) {
            if (v.requestFullscreen) { v.requestFullscreen(); }
            else if (v.webkitRequestFullscreen) { v.webkitRequestFullscreen(); }
            else if (v.webkitEnterFullscreen) { v.webkitEnterFullscreen(); }
        }
    } catch(e){}
})();
"""
    }
}
