package com.ceyinfo.cerpcashbook.data.remote

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class PersistentCookieJar(context: Context) : CookieJar {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cashbook_cookies", Context.MODE_PRIVATE)

    private val cache = mutableMapOf<String, MutableList<Cookie>>()

    init {
        loadFromDisk()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val existing = cache.getOrPut(host) { mutableListOf() }

        for (cookie in cookies) {
            existing.removeAll { it.name == cookie.name }
            if (cookie.expiresAt > System.currentTimeMillis()) {
                existing.add(cookie)
            }
        }

        saveToDisk()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookies = cache[host] ?: return emptyList()

        val now = System.currentTimeMillis()
        val valid = cookies.filter { it.expiresAt > now }

        if (valid.size != cookies.size) {
            cache[host] = valid.toMutableList()
            saveToDisk()
        }

        return valid
    }

    fun clear() {
        cache.clear()
        prefs.edit().clear().apply()
    }

    private fun saveToDisk() {
        val editor = prefs.edit()
        editor.clear()

        for ((host, cookies) in cache) {
            val serialized = cookies.map { serializeCookie(it) }.toSet()
            editor.putStringSet("cookies_$host", serialized)
        }
        editor.putStringSet("hosts", cache.keys.toSet())
        editor.apply()
    }

    private fun loadFromDisk() {
        val hosts = prefs.getStringSet("hosts", emptySet()) ?: return

        for (host in hosts) {
            val serialized = prefs.getStringSet("cookies_$host", emptySet()) ?: continue
            val cookies = serialized.mapNotNull { deserializeCookie(it) }
                .filter { it.expiresAt > System.currentTimeMillis() }
                .toMutableList()
            if (cookies.isNotEmpty()) {
                cache[host] = cookies
            }
        }
    }

    private fun serializeCookie(cookie: Cookie): String {
        return listOf(
            cookie.name,
            cookie.value,
            cookie.domain,
            cookie.path,
            cookie.expiresAt.toString(),
            if (cookie.secure) "1" else "0",
            if (cookie.httpOnly) "1" else "0"
        ).joinToString("|")
    }

    private fun deserializeCookie(str: String): Cookie? {
        val parts = str.split("|")
        if (parts.size < 7) return null

        val url = "https://${parts[2]}${parts[3]}".toHttpUrlOrNull() ?: return null

        return Cookie.Builder()
            .name(parts[0])
            .value(parts[1])
            .domain(parts[2])
            .path(parts[3])
            .expiresAt(parts[4].toLongOrNull() ?: 0L)
            .apply {
                if (parts[5] == "1") secure()
                if (parts[6] == "1") httpOnly()
            }
            .build()
    }
}
