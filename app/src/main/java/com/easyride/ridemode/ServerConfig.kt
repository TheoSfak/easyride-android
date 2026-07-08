package com.easyride.ridemode

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface ConfigFetcher {
    fun fetchBaseUrl(): String?
}

interface ConfigCache {
    fun getCachedBaseUrl(): String?
    fun saveBaseUrl(url: String)
}

object ServerConfig {
    fun resolveBaseUrl(fetcher: ConfigFetcher, cache: ConfigCache, bootstrapDefault: String): String {
        val fetched = fetcher.fetchBaseUrl()
        if (fetched != null) {
            cache.saveBaseUrl(fetched)
            return fetched
        }
        return cache.getCachedBaseUrl() ?: bootstrapDefault
    }
}

class HttpConfigFetcher(private val configUrl: String) : ConfigFetcher {
    override fun fetchBaseUrl(): String? {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(configUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val url = JSONObject(body).optString("base_url")
                url.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }
}

class PrefsConfigCache(context: Context) : ConfigCache {
    private val prefs = context.getSharedPreferences("easyride_prefs", Context.MODE_PRIVATE)

    override fun getCachedBaseUrl(): String? = prefs.getString(KEY_BASE_URL, null)

    override fun saveBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url).apply()
    }

    companion object {
        private const val KEY_BASE_URL = "cached_base_url"
    }
}
