package com.nexus.videoclient.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import java.security.MessageDigest

object Session {

    private const val PREFS = "nexus_session"
    private const val KEY_COOKIES = "cookies"
    private const val KEY_NAME = "account_name"
    private const val KEY_AVATAR = "account_avatar"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences = prefs
        ?: error("Session.init() wurde nicht aufgerufen")

    val cookieHeader: String
        get() = p().getString(KEY_COOKIES, "").orEmpty()

    var accountName: String?
        get() = p().getString(KEY_NAME, null)
        private set(value) {
            p().edit().putString(KEY_NAME, value).apply()
        }

    var accountAvatar: String?
        get() = p().getString(KEY_AVATAR, null)
        private set(value) {
            p().edit().putString(KEY_AVATAR, value).apply()
        }

    var guestMode: Boolean
        get() = p().getBoolean("guest_mode", false)
        set(value) {
            p().edit().putBoolean("guest_mode", value).apply()
        }

    val isLoggedIn: Boolean
        get() {
            // Google setzt je nach Gerät/Konto unterschiedliche Cookie-Varianten.
            // Vorher wurde nur "SAPISID=" geprüft – auf Pixel/neuen Konten fehlt der
            // Cookie oft, dadurch galt eine gültige Sitzung als "abgemeldet".
            val map = cookieMap()
            val sapisid = map["__Secure-3PAPISID"]
                ?: map["SAPISID"]
                ?: map["__Secure-1PAPISID"]
            val session = map["__Secure-3PSID"]
                ?: map["__Secure-1PSID"]
                ?: map["SID"]
            return !sapisid.isNullOrBlank() && !session.isNullOrBlank()
        }

    fun authorizationHeader(): String? {
        val sapisid = sapisid() ?: return null
        val ts = System.currentTimeMillis() / 1000
        return "SAPISIDHASH ${ts}_${sha1("$ts $sapisid $ORIGIN")}"
    }

    private fun sapisid(): String? {
        val map = cookieMap()
        return map["__Secure-3PAPISID"]
            ?: map["SAPISID"]
            ?: map["__Secure-1PAPISID"]
    }

    private fun cookieMap(): Map<String, String> {
        val raw = cookieHeader
        if (raw.isBlank()) return emptyMap()
        val map = HashMap<String, String>()
        raw.split(";").forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotEmpty()) map[name] = value
            }
        }
        return map
    }

    fun captureFromWebView(): Boolean {
        val manager = CookieManager.getInstance()
        val hosts = listOf(
            "https://www.youtube.com",
            "https://m.youtube.com",
            "https://youtube.com"
        )
        val merged = LinkedHashMap<String, String>()
        hosts.forEach { host ->
            val header = manager.getCookie(host).orEmpty()
            header.split(";").forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val name = part.substring(0, idx).trim()
                    val value = part.substring(idx + 1).trim()
                    if (name.isNotEmpty() && value.isNotEmpty()) merged[name] = value
                }
            }
        }
        if (merged.isEmpty()) return false
        val header = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        p().edit().putString(KEY_COOKIES, header).apply()
        return isLoggedIn
    }

    fun updateAccount(name: String?, avatar: String?) {
        if (!name.isNullOrBlank()) accountName = name
        if (!avatar.isNullOrBlank()) accountAvatar = avatar
    }

    fun clear() {
        p().edit().clear().apply()
    }

    private fun sha1(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    const val ORIGIN = "https://www.youtube.com"
}
