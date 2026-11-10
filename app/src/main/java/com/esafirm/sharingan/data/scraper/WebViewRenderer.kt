package com.esafirm.sharingan.data.scraper

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** The page as the browser engine ended up with it: post-JS DOM and every image it loaded. */
data class RenderedPage(val url: String, val html: String)

/**
 * Loads a chapter in a real [WebView] so pages that are built by JavaScript can be scraped:
 * waits for the load (and any "checking your browser" interstitial), scrolls through the whole
 * page so lazy loaders fill in every image, then hands back the live DOM.
 *
 * One page at a time; main thread only. The [webView] can be shown on screen while it works, so
 * the user can watch, or get past a verification / consent prompt with a tap.
 * Cookies it earns (e.g. a passed challenge) live in [CookieManager] and are reused for downloads.
 */
class WebViewRenderer(private val context: Context) {
    private val lock = Mutex()
    private val _active = MutableStateFlow<String?>(null)
    /** The URL being rendered right now, or null when idle. */
    val active: StateFlow<String?> = _active.asStateFlow()

    private var view: WebView? = null
    private var loaded = false
    private var grab: CompletableDeferred<Unit>? = null
    private var startHost: String? = null

    val userAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }

    /** Created on first use (main thread). Reused for every render so cookies/session stay warm. */
    val webView: WebView
        get() = view ?: create().also { view = it }

    /** Stop waiting/scrolling and take the page as it is now. */
    fun grabNow() {
        grab?.complete(Unit)
    }

    suspend fun render(url: String): RenderedPage = lock.withLock {
        withContext(Dispatchers.Main) {
            val wv = webView
            val done = CompletableDeferred<Unit>().also { grab = it }
            loaded = false
            startHost = Uri.parse(url).host
            _active.value = url
            try {
                wv.loadUrl(url)
                waitUntil(LOAD_TIMEOUT_MS, done) { loaded }
                // Cloudflare & co. show an interstitial first and then reload into the real page.
                waitUntil(CHALLENGE_TIMEOUT_MS, done) { !isInterstitial(wv.title) && loaded }
                if (!done.isCompleted) {
                    eval(wv, SCROLL_JS)
                    waitUntil(SCROLL_TIMEOUT_MS, done) { eval(wv, "!!(window.__sg && window.__sg.done)") == "true" }
                    if (!done.isCompleted) delay(SETTLE_MS) // let the last batch of lazy images get their src
                }
                val raw = eval(wv, GRAB_JS)
                val obj = JSONTokener(raw).nextValue() as? JSONObject ?: error("Couldn't read the page")
                val html = buildString {
                    append(obj.optString("html"))
                    // currentSrc covers srcset choices and images JS set via the property, not the attribute.
                    val imgs = obj.optJSONArray("imgs")
                    if (imgs != null) for (i in 0 until imgs.length()) {
                        append("\n<img src=\"").append(imgs.optString(i).replace("\"", "&quot;")).append("\">")
                    }
                }
                RenderedPage(obj.optString("url").ifEmpty { wv.url ?: url }, html)
            } finally {
                grab = null
                _active.value = null
                wv.stopLoading()
                wv.loadUrl("about:blank") // stop the page's scripts/media; cookies stay
            }
        }
    }

    fun destroy() {
        view?.destroy()
        view = null
    }

    private suspend fun waitUntil(timeoutMs: Long, done: CompletableDeferred<Unit>, cond: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done.isCompleted && System.currentTimeMillis() < end && !cond()) delay(POLL_MS)
    }

    private suspend fun eval(wv: WebView, js: String): String = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { if (cont.isActive) cont.resume(it ?: "null") }
    }

    private fun isInterstitial(title: String?): Boolean {
        val t = title?.lowercase() ?: return false
        return INTERSTITIAL_TITLES.any { t.contains(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.setSupportMultipleWindows(false) // no pop-up ads
        settings.javaScriptCanOpenWindowsAutomatically = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress == 100) loaded = true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loaded = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loaded = true
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url
                if (target.scheme != "http" && target.scheme != "https") return true // intent://, market://…
                if (!request.isForMainFrame || request.hasGesture()) return false
                // Scripted navigation away from the site is almost always an ad redirect.
                return !sameSite(target.host, startHost)
            }
        }
    }

    private fun sameSite(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        fun site(h: String) = h.lowercase().split('.').takeLast(2).joinToString(".")
        return site(a) == site(b)
    }

    private companion object {
        const val POLL_MS = 250L
        const val LOAD_TIMEOUT_MS = 30_000L
        const val CHALLENGE_TIMEOUT_MS = 45_000L
        const val SCROLL_TIMEOUT_MS = 60_000L
        const val SETTLE_MS = 1_200L

        val INTERSTITIAL_TITLES = listOf("just a moment", "checking your browser", "attention required", "ddos-guard", "please wait")

        /** Scrolls to the bottom in viewport steps until the page stops growing, forcing lazy images to load. */
        const val SCROLL_JS = """
(function(){
  if (window.__sg) return;
  window.__sg = {done:false};
  document.querySelectorAll('img[loading="lazy"]').forEach(function(i){ i.loading = 'eager'; });
  var last = -1, still = 0, t0 = Date.now();
  function step(){
    var el = document.scrollingElement || document.documentElement;
    var h = el.scrollHeight;
    window.scrollBy(0, Math.max(200, window.innerHeight * 0.9));
    var atEnd = window.scrollY + window.innerHeight >= h - 4;
    still = (atEnd && h === last) ? still + 1 : 0;
    last = h;
    if (still >= 6 || Date.now() - t0 > 55000) { window.__sg.done = true; return; }
    setTimeout(step, 250);
  }
  step();
})();"""

        const val GRAB_JS = """
(function(){
  var imgs = [].slice.call(document.images)
    .map(function(i){ return i.currentSrc || i.src || ''; })
    .filter(function(s){ return s.indexOf('http') === 0; });
  return { url: location.href, html: document.documentElement.outerHTML, imgs: imgs };
})()"""
    }
}
