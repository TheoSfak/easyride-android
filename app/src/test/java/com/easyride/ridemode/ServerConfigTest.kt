package com.easyride.ridemode

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigTest {

    private class FakeFetcher(private val result: String?) : ConfigFetcher {
        override fun fetchBaseUrl(): String? = result
    }

    private class FakeCache(initial: String? = null) : ConfigCache {
        var stored: String? = initial
        override fun getCachedBaseUrl(): String? = stored
        override fun saveBaseUrl(url: String) {
            stored = url
        }
    }

    @Test
    fun fetchSucceeds_returnsFetchedValueAndUpdatesCache() {
        val cache = FakeCache()
        val result = ServerConfig.resolveBaseUrl(
            FakeFetcher("https://fetched.example"), cache, "https://bootstrap.example"
        )
        assertEquals("https://fetched.example", result)
        assertEquals("https://fetched.example", cache.stored)
    }

    @Test
    fun fetchFailsButCacheHasValue_returnsCachedValue() {
        val cache = FakeCache(initial = "https://cached.example")
        val result = ServerConfig.resolveBaseUrl(
            FakeFetcher(null), cache, "https://bootstrap.example"
        )
        assertEquals("https://cached.example", result)
    }

    @Test
    fun fetchFailsAndNoCache_returnsBootstrapDefault() {
        val cache = FakeCache()
        val result = ServerConfig.resolveBaseUrl(
            FakeFetcher(null), cache, "https://bootstrap.example"
        )
        assertEquals("https://bootstrap.example", result)
    }
}
