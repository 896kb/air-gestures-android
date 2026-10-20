package dev.kb896.airgestures

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * The Meta sign-in (air-gestures `signin.rs`, kinesis `MetaLoginView.swift`): Meta's own
 * page in a web view that keeps nothing (cookies and storage are cleared before and
 * after). The app never sees the password; it only catches the final redirect to
 * `fb-viewapp://frl_login?token=…&blob=…`, checks it against this sign-in's token and
 * turns it into a session, stored encrypted by [SessionStore]. Only https pages load;
 * the bar on top shows the real host. Finishes with RESULT_OK once signed in, else
 * RESULT_CANCELED with an [EXTRA_ERROR] line when something failed.
 */
class MetaSignIn : Activity() {
    private lateinit var web: WebView
    private lateinit var host: TextView
    private lateinit var status: TextView
    /** The native SSO token of this sign-in: checks and finishes the callback. Never logged. */
    private var native: String? = null
    private var finishing = false

    private fun dpi(value: Float) = Cb.dp(this, value).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Cb.PAGE)
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        forgetWebData()
        host = Cb.text(this, 13f, R.font.geist_mono_medium, Cb.INK_900).apply {
            text = "—"
            isSingleLine = true
        }
        status = Cb.text(this, 12f, color = Cb.INK_500).apply { text = "Asking Meta for a sign-in page…" }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20f), dpi(12f), dpi(20f), dpi(12f))
            setBackgroundColor(Cb.PAGE)
            addView(Cb.label(context, "Signing in at"))
            addView(host, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4f) })
        }
        val hairline = View(this).apply { setBackgroundColor(Cb.HAIRLINE) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setGeolocationEnabled(false)
            settings.setSupportMultipleWindows(true)
            webViewClient = client
            webChromeClient = chrome
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(hairline, LinearLayout.LayoutParams(-1, maxOf(1, dpi(.75f))))
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        thread(name = "meta-signin") {
            val start = runCatching { JSONObject(Bridge.signinStart()) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                start.onSuccess {
                    native = it.getString("native")
                    status.text = "Sign in with your Meta account. Air Gestures only keeps the resulting session."
                    web.loadUrl(it.getString("url"))
                }.onFailure { fail(it.message ?: "couldn't reach Meta") }
            }
        }
    }

    override fun onDestroy() {
        web.stopLoading()
        forgetWebData()
        web.destroy()
        super.onDestroy()
    }

    private fun back() {
        if (web.canGoBack()) web.goBack() else {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    /** Incognito-ish: nothing from an earlier sign-in (or this one) stays in the web view. */
    private fun forgetWebData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        if (::web.isInitialized) {
            web.clearCache(true)
            web.clearFormData()
            web.clearHistory()
        }
    }

    private fun allowed(url: String) = url.startsWith("https://", ignoreCase = true) || url == "about:blank"

    private fun showHost(url: String) {
        host.text = Uri.parse(url).host ?: "—"
    }

    /**
     * What a navigation to `url` does: the callback is caught (and cancelled) here, an https
     * page loads, anything else is blocked. Returns true when the web view must not load it.
     */
    private fun intercept(url: String): Boolean {
        if (Bridge.signinIsCallback(url)) {
            val token = native
            if (token != null && Bridge.signinAccepts(url, token)) finishSignIn(url, token)
            else status.text = "Ignored a sign-in redirect that didn't match; keep going."
            return true
        }
        return !allowed(url)
    }

    private fun finishSignIn(url: String, token: String) {
        if (finishing) return
        finishing = true
        web.stopLoading()
        web.visibility = View.INVISIBLE
        status.text = "Finishing the sign-in…"
        thread(name = "meta-signin") {
            val result = runCatching { SessionStore.save(this, Bridge.signinFinish(url, token)) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { fail(it.message ?: "the sign-in didn't finish") }
            }
        }
    }

    private fun fail(message: String) {
        setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_ERROR, "Meta sign-in: $message"))
        finish()
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
            intercept(request.url.toString())

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // A fallback: a load that skipped the check above is still caught (or stopped) here.
            if (Bridge.signinIsCallback(url) || !allowed(url)) {
                view.stopLoading()
                intercept(url)
                return
            }
            showHost(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // An unknown-scheme error for the callback is the callback all the same.
            val url = request.url.toString()
            if (request.isForMainFrame && Bridge.signinIsCallback(url)) intercept(url)
        }
    }

    /** `window.open` / target=_blank: decided like any navigation, and loaded in this view. */
    private val chrome = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(this@MetaSignIn)
            popup.webViewClient = object : WebViewClient() {
                var taken = false

                /** The popup's first URL goes to the main view; the popup itself is dropped. */
                fun take(popupView: WebView, url: String) {
                    if (taken || url == "about:blank") return
                    taken = true
                    popupView.stopLoading()
                    if (!intercept(url)) web.loadUrl(url)
                    web.post { popupView.destroy() }
                }

                override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                    take(popupView, request.url.toString())
                    return true
                }

                override fun onPageStarted(popupView: WebView, url: String, favicon: Bitmap?) = take(popupView, url)
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    companion object {
        const val EXTRA_ERROR = "error"
    }
}
