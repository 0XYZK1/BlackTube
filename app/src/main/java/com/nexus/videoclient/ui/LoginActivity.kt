package com.nexus.videoclient.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.nexus.videoclient.R
import com.nexus.videoclient.data.Session

class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var hint: TextView
    private var popupDialog: Dialog? = null
    private var blockedDialogShown = false
    private var visitedAccounts = false
    private var lastUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sitzung ist noch gültig: gar nicht erst nach dem Passwort fragen.
        if (Session.isLoggedIn) {
            setResult(RESULT_OK)
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        webView = findViewById(R.id.login_webview)
        hint = findViewById(R.id.login_hint)

        findViewById<View>(R.id.btn_cancel).setOnClickListener { finish() }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        seedConsentCookies()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            userAgentString = cleanedUserAgent()
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                lastUrl = url
                if (url.contains("accounts.google.com")) visitedAccounts = true
                hint.text = if (url.contains("accounts.google.com")) {
                    "Tip: Google's password manager fills your saved account with a single tap. " +
                        "BlackTube never sees your password."
                } else {
                    "Checking sign-in…"
                }
                checkLogin(view, url)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean = openPopup(resultMsg)
        }

        webView.loadUrl(START_URL)
    }

    private fun checkLogin(view: WebView, url: String) {
        if (!url.contains("youtube.com") || url.contains("accounts.google.com")) {
            if (url.contains("accounts.google.com")) detectBlocked(view)
            return
        }
        if (!visitedAccounts) return
        view.postDelayed({
            Session.captureFromWebView()
            CookieManager.getInstance().flush()
            if (Session.isLoggedIn) {
                setResult(RESULT_OK)
                finish()
            }
        }, 1200)
    }

    private fun detectBlocked(view: WebView) {
        if (blockedDialogShown) return
        view.evaluateJavascript(
            "(function(){var b=document.body;return b?b.innerText.slice(0,3000):'';})()"
        ) { raw ->
            val text = (raw ?: "").lowercase()
            val blocked = text.contains("nicht sicher") ||
                text.contains("not secure") ||
                text.contains("sicherheitshalber") ||
                text.contains("couldn't sign you in")
            if (blocked) {
                blockedDialogShown = true
                AlertDialog.Builder(this)
                    .setTitle("Google blocked sign-in in the in-app browser")
                    .setMessage(
                        "Please update “Android System WebView” in the Play Store or try again."
                    )
                    .setPositiveButton("Retry") { _, _ -> webView.loadUrl(START_URL) }
                    .setNeutralButton("Open in browser") { _, _ ->
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(START_URL)))
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun openPopup(resultMsg: Message): Boolean {
        if (popupDialog != null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        val popup = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                userAgentString = cleanedUserAgent()
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        dialog.setContentView(
            popup,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        dialog.setOnDismissListener {
            popupDialog = null
            runCatching {
                (popup.parent as? ViewGroup)?.removeView(popup)
                popup.destroy()
            }
        }

        popup.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (url.contains("youtube.com") && !url.contains("accounts.google.com")) {
                    view.postDelayed({
                        Session.captureFromWebView()
                        CookieManager.getInstance().flush()
                        if (Session.isLoggedIn) {
                            popupDialog?.dismiss()
                            setResult(RESULT_OK)
                            finish()
                        }
                    }, 1200)
                }
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) {
                popupDialog?.dismiss()
            }
        }

        popupDialog = dialog
        dialog.show()
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun seedConsentCookies() {
        val manager = CookieManager.getInstance()
        val existing = manager.getCookie("https://www.youtube.com").orEmpty()
        if (!existing.contains("CONSENT=")) {
            manager.setCookie(
                "https://www.youtube.com",
                "CONSENT=YES+cb.20240101-07-p0.de+FX+999; Domain=.youtube.com; Path=/"
            )
        }
        if (!existing.contains("SOCS=")) {
            manager.setCookie("https://www.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/")
        }
        manager.flush()
    }

    private fun cleanedUserAgent(): String =
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv)", ")")
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
            .trim()

    override fun onDestroy() {
        popupDialog?.dismiss()
        popupDialog = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        // Shows saved Google accounts as a 1-tap list
        const val START_URL =
            "https://accounts.google.com/AccountChooser?service=youtube&hl=en" +
                "&continue=https%3A%2F%2Fm.youtube.com%2F"
    }
}
