package com.streamxhd.tv

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var doubleBackToExit = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        progressBar = findViewById(R.id.progressBar)
        webView = findViewById(R.id.webView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

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
                userAgentString = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = View.GONE
                    view?.evaluateJavascript(INJECTED_JS, null)
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
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }

                override fun onHideCustomView() {
                    exitFullscreen()
                }
            }

            addJavascriptInterface(PlayBridge(), "Android")

            requestFocus(View.FOCUS_DOWN)
            loadUrl("https://stream-xhd.com/")
        }
    }

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
            webView.postDelayed({ doubleBackToExit = false }, 2000)
            return true
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
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    inner class PlayBridge {
        @JavascriptInterface
        fun playUrl(url: String) {
            if (url.isBlank()) return
            runOnUiThread {
                startActivity(PlayerActivity.newIntent(this@MainActivity, url))
            }
        }
    }

    companion object {
        private const val INJECTED_JS = """
(function() {
    document.addEventListener('click', function(e) {
        var btn = e.target.closest('[data-action="play"]');
        if (btn) {
            e.preventDefault();
            e.stopPropagation();
            var url = btn.getAttribute('data-url');
            if (url && url.trim()) {
                Android.playUrl(url.trim());
            }
        }
    }, true);
    document.querySelectorAll('[data-action="play"]').forEach(function(btn) {
        btn.setAttribute('tabindex', '0');
    });
    new MutationObserver(function() {
        document.querySelectorAll('[data-action="play"]:not([tabindex])').forEach(function(btn) {
            btn.setAttribute('tabindex', '0');
        });
    }).observe(document.body, { childList: true, subtree: true });
})();
"""
    }
}
