package com.streamxhd.tv

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import java.io.ByteArrayInputStream

/** Shared WebView rules for MainActivity and PlayerActivity. */
object WebGuards {

    /** Ad/tracker hosts injected by the site and the player pages. Subdomains are blocked too. */
    private val AD_BLOCKED_HOSTS = setOf(
        "skygg.lat",
        "llvpn.com",
        "llvpn.one",
        "bz.cubbiesexcheat.com",
        "histats.com"
    )

    /** Any of the site's domains: streamxhd.sbs, streamxhd.st, streamx-hd.com, ... */
    private val SITE_HOST = Regex("^(www\\.)?streamx-?hd\\.[a-z]{2,}$")

    fun isSiteHost(host: String?): Boolean = host != null && SITE_HOST.matches(host.lowercase())

    /** A server page of the site (live1.php / live2.php / channel/?id=...), i.e. what the player plays. */
    fun isPlayerUrl(uri: Uri?): Boolean {
        if (uri == null || !isSiteHost(uri.host)) return false
        val path = uri.path?.lowercase() ?: return false
        return Regex("/live\\d*\\.php$").containsMatchIn(path) || path.startsWith("/channel")
    }

    fun isAdHost(host: String?): Boolean =
        host != null && AD_BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }

    /** Empty response used to drop ad requests. */
    fun blockedResponse(tag: String, host: String): WebResourceResponse {
        Log.d(tag, "Bloqueada request de ad: $host")
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    /**
     * Android TV user agent with the real Chrome/WebView version of the device, so the site
     * does not get a fixed (and possibly older or newer) version than what actually runs.
     */
    fun tvUserAgent(context: Context): String {
        val chrome = try {
            Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(context))?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        } ?: "120.0.6099.230"
        val release = Build.VERSION.RELEASE ?: "12"
        return "Mozilla/5.0 (Linux; Android $release; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    /**
     * Resolves a server URL from the site against the domain currently loaded and forces the
     * adaptive-quality player (live1.php -> live2.php). Returns null for anything that is not
     * http(s), so ad frames calling the JS bridge cannot open arbitrary schemes.
     */
    fun normalizePlayerUrl(raw: String, currentHost: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Any explicit scheme other than http(s) (javascript:, intent:, data:, ...) is rejected.
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(trimmed)?.groupValues?.get(1)?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") return null
        val absolute = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("/") -> "https://$currentHost$trimmed"
            else -> "https://$currentHost/$trimmed"
        }
        val uri = Uri.parse(absolute)
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (uri.host.isNullOrEmpty()) return null
        return absolute.replace(Regex("live1\\.php", RegexOption.IGNORE_CASE), "live2.php")
    }
}
