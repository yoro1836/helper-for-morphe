package dev.rushi.apkdownloadhelper

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Shares the in-app browser's cookies with OkHttp. Once a Cloudflare challenge
 * is solved in the captcha browser, its clearance cookie lets the automated
 * page fetches and downloads through (it is only honoured with the same user
 * agent, see [webViewUserAgent]).
 */
internal object WebViewCookieJar : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
            ?: return emptyList()
        return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        runCatching {
            val manager = CookieManager.getInstance()
            cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        }
    }
}

/** The WebView's own user agent, so OkHttp and the captcha browser look like one client. */
internal fun Context.webViewUserAgent(): String =
    runCatching { WebSettings.getDefaultUserAgent(this) }.getOrNull()?.takeIf(String::isNotBlank)
        ?: ("Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
