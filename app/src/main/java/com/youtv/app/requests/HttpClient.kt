package com.youtv.app.requests


import android.net.Uri
import android.util.Log
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


object HttpClient {
    const val TAG = "HttpClient"

    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

    @Volatile
    private var configuredProxy: String = ""

    fun configureProxy(value: String) {
        configuredProxy = value
    }

    val okHttpClient: OkHttpClient by lazy {
        getClientWithProxy()
    }

    fun getClientWithProxy(proxy: String = configuredProxy): OkHttpClient {
        val proxySetting = proxy
        clientCache[proxySetting]?.let {
            return it
        }

        val builder = createBuilder()
        if (proxySetting.isNotEmpty()) {
            try {
                val normalized = if ("://" in proxySetting) proxySetting else "http://$proxySetting"
                val proxyUri = Uri.parse(normalized)
                val proxyType = when (proxyUri.scheme) {
                    "http", "https" -> Proxy.Type.HTTP
                    "socks", "socks5" -> Proxy.Type.SOCKS
                    else -> null
                }
                proxyType?.let {
                    val port = proxyUri.port.takeIf { value -> value > 0 }
                        ?: if (proxyUri.scheme == "https") 443 else 80
                    builder.proxy(Proxy(it, InetSocketAddress(proxyUri.host, port)))
                }
                Log.i(TAG, "apply proxy $proxyUri")
            } catch (e: Exception) {
                Log.e(TAG, "getClientWithProxy", e)
            }
        }

        val client = builder.build()
        clientCache[proxySetting] = client
        return client
    }

    private fun createBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .dns(DnsCache())
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
    }
}
